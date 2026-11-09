package org.jetbrains.kotlin.test.helper.teamcity

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.io.HttpRequests
import com.intellij.util.messages.Topic
import kotlinx.coroutines.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.jetbrains.kotlin.test.helper.git.kotlinMonorepoRepository

internal fun interface BuildStatusesListener {
    fun buildStatusesChanged()
}

internal fun interface TeamCityFetchListener {
    fun fetchStateChanged()
}

/**
 * The progress of the branch-builds fetch: in flight, finished, finished without anything to show,
 * or failed to reach TeamCity at all.
 */
internal enum class TeamCityFetchState { IN_PROGRESS, COMPLETED, NO_BUILDS_FOUND, FAILED }

/**
 * Caches the live status of every TeamCity build the tool window shows; the tool window reads only this cache.
 *
 * That covers the remembered builds launched from the IDE and the latest aggregate builds of the local
 * branches, which are fetched in the same rounds and kept in memory only (never persisted).
 * Everything is fetched via guest REST while [setPollingActive] is on (the Remote Run tool window is
 * visible), plus once per [requestRefresh] (the refresh button, or right after launching a build).
 * Finished builds are final, so their statuses are fetched only until their finished status is known.
 * Guest REST needs no authentication, just network access to the server.
 */
@Service(Service.Level.PROJECT)
internal class TeamCityBuildStatusService(private val project: Project, private val scope: CoroutineScope) {
    companion object {
        private val logger = Logger.getInstance(TeamCityBuildStatusService::class.java)

        private val pollingInterval = 1.minutes
        private val schedulingPollingInterval = 15.seconds
        private val connectTimeout = 5.seconds
        private val requestTimeout = 10.seconds

        val STATUSES_CHANGED_TOPIC: Topic<BuildStatusesListener> =
            Topic("TeamCity build statuses changed", BuildStatusesListener::class.java)

        val FETCH_STATE_TOPIC: Topic<TeamCityFetchListener> =
            Topic("TeamCity fetch state changed", TeamCityFetchListener::class.java)

        fun getInstance(project: Project): TeamCityBuildStatusService = project.service()
    }

    @Volatile
    private var statuses: Map<String, TeamCityBuildStatus> = emptyMap()

    @Volatile
    private var failedTestOccurrences: Map<String, FailedTestsResult> = emptyMap()

    private var failedTestFetchCounts: Map<String, FailedTestFetchCounts> = emptyMap()

    /**
     * The latest aggregate builds fetched for the local branches; in memory only, replaced by every successful round.
     */
    @Volatile
    private var fetchedBuildsCache: List<FetchedTeamCityBuild> = emptyList()

    @Volatile
    private var fetchStateValue: TeamCityFetchState = TeamCityFetchState.COMPLETED

    /**
     * Guarded by `this`, like every write to [statuses]; null while polling is inactive.
     */
    private var pollingJob: Job? = null

    /**
     * Replaced in tests to avoid network access.
     */
    internal var fetchStatuses: suspend () -> Map<String, TeamCityBuildStatus> = ::fetchAllStatusesOrEmpty

    /**
     * Replaced in tests to avoid network access.
     */
    internal var fetchFailedTests: suspend (String) -> FailedTestsResult? = ::fetchFailedTestsFromTeamCity

    /**
     * Replaced in tests to avoid network access.
     */
    internal var fetchBranchBuilds: suspend (List<String>) -> FetchedBranchBuildsResult = ::fetchBranchBuildsFromTeamCity

    /**
     * Replaced in tests to avoid depending on a git repository.
     */
    internal var associatedBranchNames: () -> List<String> = ::monorepoLocalBranchNames

    fun status(buildId: String): TeamCityBuildStatus? = statuses[buildId]

    fun failedTests(buildId: String): FailedTestsResult? = failedTestOccurrences[buildId]

    /**
     * The aggregate builds fetched for the local branches, without their statuses (read those with [status]).
     */
    fun fetchedBranchBuilds(): List<RememberedTeamCityBuild> =
        fetchedBuildsCache.map { fetchedBuild -> fetchedBuild.build }

    /**
     * Drops the fetched branch builds and their statuses, so tests of the shared project start from scratch.
     */
    internal fun clearFetchedBranchBuilds() {
        fetchedBuildsCache = emptyList()
        updateStatuses(emptyMap())
    }

    val fetchState: TeamCityFetchState
        get() = fetchStateValue

    internal val isPolling: Boolean
        get() = synchronized(this) { pollingJob?.isActive == true }

    /**
     * Activating fetches right away and then every [pollingInterval], or more often while an Aggregate Trigger is scheduling.
     * Deactivating cancels the timer and any in-flight request. Repeated calls with the same value do nothing.
     * A loop that ended unexpectedly counts as inactive, so the next activation starts a new one.
     */
    @Synchronized
    fun setPollingActive(active: Boolean) {
        if (pollingJob?.isActive == active) return

        if (!active) {
            pollingJob?.cancel()
            return
        }

        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                refreshOnce()
                delay(nextPollingInterval())
            }
        }
    }

    /**
     * Runs one refresh round immediately, regardless of the polling loop.
     */
    fun requestRefresh() {
        scope.launch {
            refreshOnce()
        }
    }

    private suspend fun refreshOnce() {
        setFetchState(TeamCityFetchState.IN_PROGRESS)

        var branchBuildsResult: FetchedBranchBuildsResult? = null
        try {
            branchBuildsResult = try {
                fetchBranchBuilds(associatedBranchNames())
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.debug("Unable to fetch the aggregate TeamCity builds of the local branches", exception)
                null
            }

            val fetched = fetchStatuses()
            val store = RememberedTeamCityBuildStore.getInstance(project)
            val scheduledBuilds = resolveScheduledBuilds(store.builds(), fetched)

            if (scheduledBuilds.isNotEmpty()) {
                scheduledBuilds.forEach { (triggerBuildId, aggregateBuild) ->
                    store.replace(triggerBuildId, aggregateBuild)
                }
            }

            /* A failed round keeps the previously fetched builds, like statuses keep the last known values. */
            if (branchBuildsResult?.isFailed == false) {
                val fetchedBuildsChanged = fetchedBuildsCache != branchBuildsResult.builds
                fetchedBuildsCache = branchBuildsResult.builds

                /* Build metadata can change without a status change, e.g. when a branch was renamed locally. */
                if (fetchedBuildsChanged) publishStatusesChanged()
            }

            val fetchedStatuses = if (scheduledBuilds.isEmpty()) fetched else fetchStatuses()
            val branchBuildStatuses = branchBuildsResult?.builds.orEmpty()
                .associate { fetchedBuild -> fetchedBuild.build.buildId to fetchedBuild.status }
            updateStatuses(fetchedStatuses + branchBuildStatuses)
            refreshFailedTestOccurrences()
        } finally {
            setFetchState(fetchEndState(branchBuildsResult))
        }
    }

    private fun fetchEndState(branchBuildsResult: FetchedBranchBuildsResult?): TeamCityFetchState {
        if (branchBuildsResult == null || branchBuildsResult.isFailed) return TeamCityFetchState.FAILED

        val nothingToShow = branchBuildsResult.builds.isEmpty() &&
            RememberedTeamCityBuildStore.getInstance(project).builds().isEmpty()
        return if (nothingToShow) TeamCityFetchState.NO_BUILDS_FOUND else TeamCityFetchState.COMPLETED
    }

    private fun setFetchState(state: TeamCityFetchState) {
        if (fetchStateValue == state) return

        fetchStateValue = state
        if (!project.isDisposed) {
            project.messageBus.syncPublisher(FETCH_STATE_TOPIC).fetchStateChanged()
        }
    }

    private fun nextPollingInterval() =
        if (RememberedTeamCityBuildStore.getInstance(project).builds().any { build ->
                isSchedulingInProgress(build, status(build.buildId))
            }
        ) {
            schedulingPollingInterval
        } else {
            pollingInterval
        }

    /**
     * Listeners run synchronously, so they are notified outside the lock.
     */
    internal fun updateStatuses(newStatuses: Map<String, TeamCityBuildStatus>) {
        val shownBuildIds = shownBuildIds()

        val changed = synchronized(this) {
            val mergedStatuses = mergeStatuses(statuses, shownBuildIds, newStatuses)
            val retainedFailedTests = failedTestOccurrences.filter { (buildId, _) ->
                val counts = mergedStatuses[buildId]?.testCounts
                buildId in shownBuildIds && counts.hasFailedTests()
            }
            val retainedFetchCounts = failedTestFetchCounts.filter { (buildId, _) ->
                val counts = mergedStatuses[buildId]?.testCounts
                buildId in shownBuildIds && counts.hasFailedTests()
            }
            val statusesChanged = statuses != mergedStatuses
            val failedTestsChanged = failedTestOccurrences != retainedFailedTests
            statuses = mergedStatuses
            failedTestOccurrences = retainedFailedTests
            failedTestFetchCounts = retainedFetchCounts
            statusesChanged || failedTestsChanged
        }

        if (changed) publishStatusesChanged()
    }

    private fun publishStatusesChanged() {
        if (project.isDisposed) return
        project.messageBus.syncPublisher(STATUSES_CHANGED_TOPIC).buildStatusesChanged()
    }

    /**
     * The real fetch; internal so tests can restore it after replacing [fetchStatuses].
     */
    internal suspend fun fetchAllStatusesOrEmpty(): Map<String, TeamCityBuildStatus> {
        val builds = RememberedTeamCityBuildStore.getInstance(project).builds()
            .filter { build -> statuses[build.buildId]?.state != BuildState.FINISHED }
        if (builds.isEmpty()) return emptyMap()

        return builds.mapNotNull { build ->
            fetchBuildStatus(build)?.let { status -> build.buildId to status }
        }.toMap()
    }

    internal suspend fun fetchBuildStatus(build: RememberedTeamCityBuild): TeamCityBuildStatus? {
        val body = try {
            fetchJson(buildStatusUrl(build.buildId))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.debug("Unable to fetch TeamCity build status of build ${build.buildId}", exception)
            return null
        }

        return parseBuildStatus(body, build.webUrl)
    }

    /**
     * The real fetch; internal so tests can restore it after replacing [fetchBranchBuilds].
     *
     * Every branch is queried in parallel, plus the build queue once; a branch whose request fails
     * is skipped, and the round only counts as failed when no request reached TeamCity at all.
     */
    internal suspend fun fetchBranchBuildsFromTeamCity(branchNames: List<String>): FetchedBranchBuildsResult {
        if (branchNames.isEmpty()) return FetchedBranchBuildsResult(emptyList(), isFailed = false)

        return coroutineScope {
            val localBranchNames = branchNames.toSet()
            val queuedBuildsDeferred = async { fetchQueuedAggregateBuilds(localBranchNames) }
            val branchBuildsDeferreds = branchNames.map { branchName ->
                async { fetchBranchBuildsForBranch(branchName) }
            }

            val queuedBuilds = queuedBuildsDeferred.await()
            val branchBuilds = branchBuildsDeferreds.awaitAll()

            val builds = branchBuilds.filterNotNull().flatten() + queuedBuilds.orEmpty()
            val reachedTeamCity = queuedBuilds != null || branchBuilds.any { it != null }

            FetchedBranchBuildsResult(
                builds = builds.distinctBy { fetchedBuild -> fetchedBuild.build.buildId },
                isFailed = !reachedTeamCity,
            )
        }
    }

    private suspend fun fetchBranchBuildsForBranch(branchName: String): List<FetchedTeamCityBuild>? {
        val body = try {
            fetchJson(aggregateBuildsUrl(branchName))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.debug("Unable to fetch the aggregate TeamCity builds of branch $branchName", exception)
            return null
        }

        return parseFetchedBranchBuilds(body, branchName)
    }

    private suspend fun fetchQueuedAggregateBuilds(localBranchNames: Set<String>): List<FetchedTeamCityBuild>? {
        val body = try {
            fetchJson(aggregateBuildQueueUrl())
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.debug("Unable to fetch the TeamCity aggregate build queue", exception)
            return null
        }

        return parseQueuedBranchBuilds(body, localBranchNames)
    }

    /**
     * The local branches whose aggregate runs the tool window fetches.
     */
    internal fun monorepoLocalBranchNames(): List<String> =
        project.kotlinMonorepoRepository()?.branches?.localBranches?.map { branch -> branch.name }.orEmpty()

    /**
     * The ids of every build the tool window shows: remembered launches and fetched branch builds.
     */
    private fun shownBuildIds(): Set<String> =
        RememberedTeamCityBuildStore.getInstance(project).builds().map { it.buildId }.toSet() +
            fetchedBuildsCache.map { fetchedBuild -> fetchedBuild.build.buildId }

    private suspend fun refreshFailedTestOccurrences() {
        val buildIds = shownBuildIds()
        val currentStatuses = statuses

        for (buildId in buildIds) {
            val counts = currentStatuses[buildId]?.testCounts ?: continue
            if (!counts.hasFailedTests()) continue

            val fetchCounts = FailedTestFetchCounts(counts.failed, counts.muted)
            if (synchronized(this) { failedTestFetchCounts[buildId] == fetchCounts }) continue

            val result = fetchFailedTests(buildId) ?: continue
            if (buildId !in shownBuildIds()) continue

            val changed = synchronized(this) {
                val currentCounts = statuses[buildId]?.testCounts
                if (FailedTestFetchCounts(currentCounts?.failed ?: 0, currentCounts?.muted ?: 0) != fetchCounts) {
                    return@synchronized false
                }

                val updatedResults = failedTestOccurrences + (buildId to result)
                failedTestFetchCounts = failedTestFetchCounts + (buildId to fetchCounts)
                val resultsChanged = failedTestOccurrences != updatedResults
                failedTestOccurrences = updatedResults
                resultsChanged
            }

            if (changed) publishStatusesChanged()
        }
    }

    internal suspend fun fetchFailedTestsFromTeamCity(buildId: String): FailedTestsResult? {
        val body = try {
            fetchJson(failedTestsUrl(buildId))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.debug("Unable to fetch failed TeamCity tests of build $buildId", exception)
            return null
        }

        return parseFailedTests(body)
    }

    /**
     * Uses the IDE proxy and certificate settings; throws on network errors and non-2xx responses.
     */
    private suspend fun fetchJson(url: String): String = runInterruptible(Dispatchers.IO) {
        HttpRequests.request(url)
            .accept("application/json")
            .connectTimeout(connectTimeout.inWholeMilliseconds.toInt())
            .readTimeout(requestTimeout.inWholeMilliseconds.toInt())
            .readString()
    }
}

private data class FailedTestFetchCounts(val failed: Int, val muted: Int)

private fun TestCounts?.hasFailedTests(): Boolean =
    this != null && (failed > 0 || muted > 0)

internal fun resolveScheduledBuilds(
    builds: List<RememberedTeamCityBuild>,
    fetched: Map<String, TeamCityBuildStatus>,
): List<Pair<String, RememberedTeamCityBuild>> = builds.mapNotNull { build ->
    val status = fetched[build.buildId] ?: return@mapNotNull null
    if (!build.isAggregateTrigger && status.buildTypeId != AGGREGATE_TRIGGER_BUILD_TYPE_ID) return@mapNotNull null

    val aggregateBuild = scheduledBuildFromTrigger(status, build.isAggregateTrigger) ?: return@mapNotNull null
    build.buildId to build.copy(
        buildId = aggregateBuild.buildId,
        webUrl = aggregateBuild.webUrl,
        isAggregateTrigger = false,
    )
}

/**
 * Drops statuses of builds that are no longer shown and keeps the last known status
 * of builds whose fetch failed this round (missing from [fetched]).
 * A finished status is final: a slower, overlapping fetch round can't turn it back into queued or running.
 */
internal fun mergeStatuses(
    previous: Map<String, TeamCityBuildStatus>,
    shownBuildIds: Set<String>,
    fetched: Map<String, TeamCityBuildStatus>,
): Map<String, TeamCityBuildStatus> {
    val shownPrevious = previous.filterKeys { buildId -> buildId in shownBuildIds }
    val shownFetched = fetched.filterKeys { buildId -> buildId in shownBuildIds }

    val newerFetched = shownFetched.filter { (buildId, fetchedStatus) ->
        shownPrevious[buildId]?.state != BuildState.FINISHED || fetchedStatus.state == BuildState.FINISHED
    }

    return shownPrevious + newerFetched
}
