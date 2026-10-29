package org.jetbrains.kotlin.test.helper.teamcity

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Polling tests replace the fetcher, so no network access happens.
 * The light project is shared between tests, so the remembered-build store is cleaned in tearDown.
 */
class TeamCityBuildStatusServiceTest : BasePlatformTestCase() {
    private val firstBuildId = "1111002160"
    private val secondBuildId = "1111032123"
    private val firstBuild = rememberedBuild(firstBuildId)

    fun testMergeStatuses() {
        val runningFirst = runningStatus(firstBuildId)

        /* Forgotten builds lose their cached status. */
        assertEquals(
            mapOf(firstBuildId to runningFirst),
            mergeStatuses(
                previous = mapOf(firstBuildId to runningFirst, secondBuildId to runningStatus(secondBuildId)),
                rememberedBuildIds = setOf(firstBuildId),
                fetched = emptyMap(),
            ),
        )

        /* Last known statuses survive failed fetch rounds. */
        assertEquals(
            mapOf(firstBuildId to runningFirst),
            mergeStatuses(
                previous = mapOf(firstBuildId to runningFirst),
                rememberedBuildIds = setOf(firstBuildId),
                fetched = emptyMap(),
            ),
        )

        /* Fetched statuses win over previous ones. */
        val finishedFirst = finishedSuccessfulStatus()
        assertEquals(
            mapOf(firstBuildId to finishedFirst),
            mergeStatuses(
                previous = mapOf(firstBuildId to runningFirst),
                rememberedBuildIds = setOf(firstBuildId),
                fetched = mapOf(firstBuildId to finishedFirst),
            ),
        )
    }

    fun testMergeKeepsFinishedStatusesAndIgnoresForgottenBuilds() {
        val finishedFirst = finishedSuccessfulStatus()

        /* A slower, overlapping fetch round must not turn a finished build back into a running one. */
        assertEquals(
            mapOf(firstBuildId to finishedFirst),
            mergeStatuses(
                previous = mapOf(firstBuildId to finishedFirst),
                rememberedBuildIds = setOf(firstBuildId),
                fetched = mapOf(firstBuildId to runningStatus(firstBuildId)),
            ),
        )

        /* Statuses of builds forgotten while their fetch was in flight are dropped. */
        assertEquals(
            emptyMap<String, TeamCityBuildStatus>(),
            mergeStatuses(
                previous = emptyMap(),
                rememberedBuildIds = emptySet(),
                fetched = mapOf(firstBuildId to runningStatus(firstBuildId)),
            ),
        )
    }

    fun testResolvesFinishedAggregateTriggerToScheduledBuild() {
        val triggerBuild = firstBuild.copy(isAggregateTrigger = true)
        val triggerStatus = finishedTriggerStatus()

        assertEquals(
            listOf(
                "1111002160" to triggerBuild.copy(
                    buildId = "1111003000",
                    webUrl = "https://buildserver.labs.intellij.net/build/1111003000",
                    isAggregateTrigger = false,
                ),
            ),
            resolveScheduledBuilds(
                builds = listOf(triggerBuild),
                fetched = mapOf("1111002160" to triggerStatus),
            ),
        )
    }

    fun testStoreReplacementPreservesLaunchMetadata() {
        val store = RememberedTeamCityBuildStore.getInstance(project)
        val triggerBuild = firstBuild.copy(isAggregateTrigger = true)
        val aggregateBuild = triggerBuild.copy(
            buildId = "1111003000",
            webUrl = "https://buildserver.labs.intellij.net/build/1111003000",
            isAggregateTrigger = false,
        )
        store.add(triggerBuild)

        store.replace(triggerBuild.buildId, aggregateBuild)

        assertEquals(listOf(aggregateBuild), store.builds())
    }

    fun testStoreReplacementDoesNotDuplicateAlreadyRememberedAggregate() {
        val store = RememberedTeamCityBuildStore.getInstance(project)
        val triggerBuild = firstBuild.copy(isAggregateTrigger = true)
        val aggregateBuild = rememberedBuild("1111003000", launchTimestamp = 1)
        store.add(triggerBuild)
        store.add(aggregateBuild)

        store.replace(triggerBuild.buildId, triggerBuild.copy(buildId = aggregateBuild.buildId))

        assertEquals(listOf(aggregateBuild), store.builds())
    }

    fun testRefreshImmediatelyFetchesResolvedAggregateBuild() = runBlocking {
        val triggerBuild = firstBuild.copy(isAggregateTrigger = true)
        val store = RememberedTeamCityBuildStore.getInstance(project)
        val service = TeamCityBuildStatusService.getInstance(project)
        val aggregateFetched = Channel<Unit>(Channel.UNLIMITED)
        var fetchCount = 0
        store.add(triggerBuild)
        service.fetchStatuses = {
            if (fetchCount++ == 0) {
                mapOf(firstBuildId to finishedTriggerStatus())
            } else {
                aggregateFetched.send(Unit)
                mapOf("1111003000" to runningStatus("1111003000"))
            }
        }

        service.requestRefresh()

        withTimeout(5.seconds) { aggregateFetched.receive() }
        assertEquals("1111003000", store.builds().single().buildId)
        assertEquals(runningStatus("1111003000"), service.status("1111003000"))
    }

    fun testFinishedBuildsAreNotFetchedAgain() = runBlocking {
        val store = RememberedTeamCityBuildStore.getInstance(project)
        val service = TeamCityBuildStatusService.getInstance(project)
        store.add(firstBuild)
        service.updateStatuses(mapOf(firstBuildId to finishedSuccessfulStatus()))

        /* No unfinished builds: returns without any network access. */
        assertEquals(emptyMap<String, TeamCityBuildStatus>(), service.fetchAllStatusesOrEmpty())
    }

    fun testFetchesFailedTestsOnlyWhenFailuresExistAndPrunesForgottenResults() = runBlocking {
        val store = RememberedTeamCityBuildStore.getInstance(project)
        val service = TeamCityBuildStatusService.getInstance(project)
        val statusFetches = Channel<Unit>(Channel.UNLIMITED)
        val failedTestFetches = Channel<String>(Channel.UNLIMITED)
        var currentStatus = runningStatus(firstBuildId).copy(
            testCounts = TestCounts(passed = 3, failed = 0, ignored = 0, muted = 0),
        )
        val failedTests = FailedTestsResult(
            tests = listOf(
                FailedTest(
                    name = "testFails",
                    packageName = "org.example",
                    className = "ExampleTest",
                    methodName = "testFails",
                    displayName = "testFails",
                    isMuted = false,
                    buildTypeName = "Compiler Tests",
                ),
            ),
            totalCount = 1,
        )

        store.add(firstBuild)
        service.fetchStatuses = {
            statusFetches.send(Unit)
            mapOf(firstBuildId to currentStatus)
        }
        service.fetchFailedTests = { buildId ->
            failedTestFetches.send(buildId)
            failedTests
        }

        service.requestRefresh()
        withTimeout(5.seconds) { statusFetches.receive() }
        assertNoFurtherFetches(failedTestFetches)

        currentStatus = currentStatus.copy(testCounts = TestCounts(passed = 3, failed = 1, ignored = 0, muted = 0))
        service.requestRefresh()
        withTimeout(5.seconds) { statusFetches.receive() }
        assertEquals(firstBuildId, withTimeout(5.seconds) { failedTestFetches.receive() })
        withTimeout(5.seconds) {
            while (service.failedTests(firstBuildId) != failedTests) delay(10.milliseconds)
        }

        service.requestRefresh()
        withTimeout(5.seconds) { statusFetches.receive() }
        assertNoFurtherFetches(failedTestFetches)

        store.remove(firstBuildId)
        service.updateStatuses(emptyMap())
        assertEquals(null, service.failedTests(firstBuildId))
    }

    fun testStoreKeepsOnlyTheNewestBuilds() {
        val store = RememberedTeamCityBuildStore.getInstance(project)
        val buildCount = RememberedTeamCityBuildStore.MAX_REMEMBERED_BUILDS + 5

        for (index in 1..buildCount) {
            store.add(rememberedBuild(buildId = index.toString(), launchTimestamp = index.toLong()))
        }

        val rememberedBuildIds = store.builds().map { build -> build.buildId }.toSet()
        assertEquals(RememberedTeamCityBuildStore.MAX_REMEMBERED_BUILDS, rememberedBuildIds.size)
        assertFalse("1" in rememberedBuildIds)
        assertTrue(buildCount.toString() in rememberedBuildIds)
    }

    fun testStoreStateIsDetachedFromLaterChanges() {
        val store = RememberedTeamCityBuildStore.getInstance(project)
        store.add(firstBuild)

        val savedState = store.state
        store.add(rememberedBuild(secondBuildId))
        store.remove(firstBuildId)

        assertEquals(listOf(firstBuildId), savedState.builds.map { bean -> bean.buildId })
    }

    fun testStatusListenersRunOutsideTheServiceLock() {
        val store = RememberedTeamCityBuildStore.getInstance(project)
        val service = TeamCityBuildStatusService.getInstance(project)
        store.add(firstBuild)

        val lockHeldInListener = mutableListOf<Boolean>()
        val connection = project.messageBus.connect(testRootDisposable)
        connection.subscribe(
            TeamCityBuildStatusService.STATUSES_CHANGED_TOPIC,
            BuildStatusesListener { lockHeldInListener += Thread.holdsLock(service) },
        )

        try {
            service.updateStatuses(mapOf(firstBuildId to runningStatus(firstBuildId)))
        } finally {
            connection.disconnect()
        }

        assertEquals(listOf(false), lockHeldInListener)
    }

    fun testUpdatePublishesOnlyOnChangedStatuses() {
        val store = RememberedTeamCityBuildStore.getInstance(project)
        val service = TeamCityBuildStatusService.getInstance(project)
        store.add(firstBuild)

        assertEquals(1, countPublishedEvents { service.updateStatuses(mapOf(firstBuildId to runningStatus(firstBuildId))) })
        assertEquals(0, countPublishedEvents { service.updateStatuses(mapOf(firstBuildId to runningStatus(firstBuildId))) })

        assertEquals(runningStatus(firstBuildId), service.status(firstBuildId))
    }

    fun testUpdateDropsStatusesOfRemovedBuilds() {
        val store = RememberedTeamCityBuildStore.getInstance(project)
        val service = TeamCityBuildStatusService.getInstance(project)
        store.add(firstBuild)
        service.updateStatuses(mapOf(firstBuildId to runningStatus(firstBuildId)))

        store.remove(firstBuildId)

        assertEquals(1, countPublishedEvents { service.updateStatuses(emptyMap()) })
        assertNull(service.status(firstBuildId))
    }

    fun testRequestRefreshFetchesOnceWhileInactive() = runBlocking {
        val store = RememberedTeamCityBuildStore.getInstance(project)
        val service = TeamCityBuildStatusService.getInstance(project)
        val fetches = Channel<Unit>(Channel.UNLIMITED)
        service.fetchStatuses = {
            fetches.send(Unit)
            mapOf(firstBuildId to runningStatus(firstBuildId))
        }
        store.add(firstBuild)

        assertFalse(service.isPolling)
        service.requestRefresh()

        withTimeout(5.seconds) { fetches.receive() }
        assertNoFurtherFetches(fetches)
        assertEquals(runningStatus(firstBuildId), service.status(firstBuildId))
    }

    fun testPollingFetchesPromptlyOnActivationAndCancelsInFlightFetchesOnDeactivation() = runBlocking {
        val service = TeamCityBuildStatusService.getInstance(project)
        val fetches = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)
        service.fetchStatuses = {
            val cancelled = CompletableDeferred<Unit>()
            fetches.send(cancelled)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }

        service.setPollingActive(true)
        val firstFetch = withTimeout(5.seconds) { fetches.receive() }
        assertTrue(service.isPolling)

        service.setPollingActive(false)
        withTimeout(5.seconds) { firstFetch.await() }
        assertFalse(service.isPolling)

        /* Switching back fetches right away instead of waiting for the next interval. */
        service.setPollingActive(true)
        withTimeout(5.seconds) { fetches.receive() }

        service.setPollingActive(false)
        assertNoFurtherFetches(fetches)
    }

    fun testRepeatedActivationStartsOnlyOneLoop() = runBlocking {
        val store = RememberedTeamCityBuildStore.getInstance(project)
        val service = TeamCityBuildStatusService.getInstance(project)
        val fetches = Channel<Unit>(Channel.UNLIMITED)
        service.fetchStatuses = {
            fetches.send(Unit)
            mapOf(firstBuildId to runningStatus(firstBuildId))
        }
        store.add(firstBuild)

        service.setPollingActive(true)
        service.setPollingActive(true)
        service.setPollingActive(true)

        withTimeout(5.seconds) { fetches.receive() }
        assertNoFurtherFetches(fetches)
        assertEquals(runningStatus(firstBuildId), service.status(firstBuildId))

        service.setPollingActive(false)
        service.setPollingActive(false)
        assertFalse(service.isPolling)
    }

    fun testUnexpectedlyEndedLoopIsRestartedOnActivation() = runBlocking {
        val service = TeamCityBuildStatusService.getInstance(project)
        val loops = Channel<Job>(Channel.UNLIMITED)
        var fetchCount = 0

        service.fetchStatuses = {
            fetchCount++
            loops.send(currentCoroutineContext().job)
            if (fetchCount == 1) throw CancellationException("Loop ends unexpectedly")
            awaitCancellation()
        }

        service.setPollingActive(true)
        val endedLoop = withTimeout(5.seconds) { loops.receive() }
        withTimeout(5.seconds) { endedLoop.join() }
        assertFalse(service.isPolling)

        service.setPollingActive(true)
        val restartedLoop = withTimeout(5.seconds) { loops.receive() }

        assertNotSame(endedLoop, restartedLoop)
        assertTrue(service.isPolling)

        service.setPollingActive(false)
    }

    /**
     * The light project is shared between tests, so statuses, polling and the store must not leak.
     */
    override fun tearDown() {
        try {
            project.getServiceIfCreated(RememberedTeamCityBuildStore::class.java)?.let { store ->
                store.builds().forEach { store.remove(it.buildId) }
            }
            project.getServiceIfCreated(TeamCityBuildStatusService::class.java)?.let { service ->
                service.setPollingActive(false)
                service.fetchStatuses = service::fetchAllStatusesOrEmpty
                service.fetchFailedTests = service::fetchFailedTestsFromTeamCity
                service.updateStatuses(emptyMap())
            }
        } finally {
            super.tearDown()
        }
    }

    private suspend fun <T> assertNoFurtherFetches(fetches: Channel<T>) {
        delay(200.milliseconds)
        assertTrue("Unexpected fetch", fetches.tryReceive().isFailure)
    }

    private fun countPublishedEvents(action: () -> Unit): Int {
        var publishedEvents = 0
        val connection = project.messageBus.connect(testRootDisposable)
        connection.subscribe(
            TeamCityBuildStatusService.STATUSES_CHANGED_TOPIC,
            BuildStatusesListener { publishedEvents++ },
        )

        try {
            action()
        } finally {
            connection.disconnect()
        }

        return publishedEvents
    }
}

private fun rememberedBuild(buildId: String, launchTimestamp: Long = 0L) =
    RememberedTeamCityBuild(
        buildId = buildId,
        webUrl = "https://buildserver.labs.intellij.net/build/$buildId",
        branchName = "master",
        launchTimestamp = launchTimestamp,
    )

private fun runningStatus(buildId: String) =
    TeamCityBuildStatus(
        state = BuildState.RUNNING,
        isSuccessful = null,
        statusText = "Running",
        webUrl = "https://buildserver.labs.intellij.net/build/$buildId",
        branchName = null,
        buildTypeId = null,
        buildTypeName = null,
    )

private fun finishedSuccessfulStatus() =
    TeamCityBuildStatus(
        state = BuildState.FINISHED,
        isSuccessful = true,
        statusText = "Success",
        webUrl = "https://buildserver.labs.intellij.net/build/1111002160",
        branchName = null,
        buildTypeId = null,
        buildTypeName = null,
    )

private fun finishedTriggerStatus() =
    TeamCityBuildStatus(
        state = BuildState.FINISHED,
        isSuccessful = false,
        statusText = "https://buildserver.labs.intellij.net/build/1111003000",
        webUrl = "https://buildserver.labs.intellij.net/build/1111002160",
        branchName = "master",
        buildTypeId = AGGREGATE_TRIGGER_BUILD_TYPE_ID,
        buildTypeName = "Aggregate Trigger",
    )
