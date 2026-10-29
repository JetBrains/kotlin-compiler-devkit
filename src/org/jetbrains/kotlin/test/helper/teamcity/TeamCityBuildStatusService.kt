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

internal fun interface BuildStatusesListener {
    fun buildStatusesChanged()
}

/**
 * Caches the live status of every remembered TeamCity build; the tool window reads only this cache.
 *
 * Builds are polled via guest REST while [setPollingActive] is on (the Remote Run tool window is visible),
 * plus once per [requestRefresh] (e.g. right after launching a build).
 * Finished builds are final, so they are fetched only until their finished status is known.
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

        fun getInstance(project: Project): TeamCityBuildStatusService = project.service()
    }

    @Volatile
    private var statuses: Map<String, TeamCityBuildStatus> = emptyMap()

    @Volatile
    private var failedTestOccurrences: Map<String, FailedTestsResult> = emptyMap()

    private var failedTestFetchCounts: Map<String, FailedTestFetchCounts> = emptyMap()

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

    fun status(buildId: String): TeamCityBuildStatus? = statuses[buildId]

    fun failedTests(buildId: String): FailedTestsResult? = failedTestOccurrences[buildId]

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
        val fetched = fetchStatuses()
        val store = RememberedTeamCityBuildStore.getInstance(project)
        val scheduledBuilds = resolveScheduledBuilds(store.builds(), fetched)

        if (scheduledBuilds.isEmpty()) {
            updateStatuses(fetched)
            refreshFailedTestOccurrences()
            return
        }

        scheduledBuilds.forEach { (triggerBuildId, aggregateBuild) ->
            store.replace(triggerBuildId, aggregateBuild)
        }
        updateStatuses(fetchStatuses())
        refreshFailedTestOccurrences()
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
        val rememberedBuildIds = RememberedTeamCityBuildStore.getInstance(project).builds()
            .map { it.buildId }
            .toSet()

        val changed = synchronized(this) {
            val mergedStatuses = mergeStatuses(statuses, rememberedBuildIds, newStatuses)
            val retainedFailedTests = failedTestOccurrences.filter { (buildId, _) ->
                val counts = mergedStatuses[buildId]?.testCounts
                buildId in rememberedBuildIds && counts.hasFailedTests()
            }
            val retainedFetchCounts = failedTestFetchCounts.filter { (buildId, _) ->
                val counts = mergedStatuses[buildId]?.testCounts
                buildId in rememberedBuildIds && counts.hasFailedTests()
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

    private suspend fun refreshFailedTestOccurrences() {
        val buildIds = RememberedTeamCityBuildStore.getInstance(project).builds()
            .map { build -> build.buildId }
        val currentStatuses = statuses

        for (buildId in buildIds) {
            val counts = currentStatuses[buildId]?.testCounts ?: continue
            if (!counts.hasFailedTests()) continue

            val fetchCounts = FailedTestFetchCounts(counts.failed, counts.muted)
            if (synchronized(this) { failedTestFetchCounts[buildId] == fetchCounts }) continue

            val result = fetchFailedTests(buildId) ?: continue
            val stillRemembered = RememberedTeamCityBuildStore.getInstance(project).builds()
                .any { rememberedBuild -> rememberedBuild.buildId == buildId }
            if (!stillRemembered) continue

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
 * Drops statuses of builds that are no longer remembered and keeps the last known status
 * of builds whose fetch failed this round (missing from [fetched]).
 * A finished status is final: a slower, overlapping fetch round can't turn it back into queued or running.
 */
internal fun mergeStatuses(
    previous: Map<String, TeamCityBuildStatus>,
    rememberedBuildIds: Set<String>,
    fetched: Map<String, TeamCityBuildStatus>,
): Map<String, TeamCityBuildStatus> {
    val rememberedPrevious = previous.filterKeys { buildId -> buildId in rememberedBuildIds }
    val rememberedFetched = fetched.filterKeys { buildId -> buildId in rememberedBuildIds }

    val newerFetched = rememberedFetched.filter { (buildId, fetchedStatus) ->
        rememberedPrevious[buildId]?.state != BuildState.FINISHED || fetchedStatus.state == BuildState.FINISHED
    }

    return rememberedPrevious + newerFetched
}
