package org.jetbrains.kotlin.test.helper.domain

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/* Polling tests replace the fetcher, so no network access happens. */
class DomainBuildStatusServiceTest : BasePlatformTestCase() {
    private val analysisApi = Domain.of("AnalysisApi")
    private val compiler = Domain.of("Compiler")

    private val greenAnalysisApi = DomainBuildStatus(
        isSuccess = true,
        statusText = "Tests passed: 94190, ignored: 148",
        webUrl = "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_Domain_AnalysisApi/1",
    )

    private val redCompiler = DomainBuildStatus(
        isSuccess = false,
        statusText = "Tests failed: 3",
        webUrl = "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_Domain_Compiler/2",
    )

    fun testIdenticalStatusesPublishNothing() {
        val service = DomainBuildStatusService.getInstance(project)
        service.updateStatuses(mapOf(analysisApi to greenAnalysisApi))

        val publishedEvents = countPublishedEvents {
            service.updateStatuses(mapOf(Domain.of("AnalysisApi") to greenAnalysisApi.copy()))
        }

        assertEquals(0, publishedEvents)
        assertEquals(greenAnalysisApi, service.status(analysisApi))
    }

    fun testChangedStatusesPublishOnce() {
        val service = DomainBuildStatusService.getInstance(project)
        service.updateStatuses(mapOf(analysisApi to greenAnalysisApi))

        val publishedEvents = countPublishedEvents {
            service.updateStatuses(mapOf(analysisApi to greenAnalysisApi, compiler to redCompiler))
        }

        assertEquals(1, publishedEvents)
        assertEquals(greenAnalysisApi, service.status(analysisApi))
        assertEquals(redCompiler, service.status(compiler))
    }

    fun testEmptyStatusesClearCache() {
        val service = DomainBuildStatusService.getInstance(project)
        service.updateStatuses(mapOf(analysisApi to greenAnalysisApi, compiler to redCompiler))

        val publishedEvents = countPublishedEvents {
            service.updateStatuses(emptyMap())
        }

        assertEquals(1, publishedEvents)
        assertNull(service.status(analysisApi))
        assertNull(service.status(compiler))
    }

    fun testUnusableResponsesAreFailures() {
        assertNull(domainBuildStatusesFromResponse(404, """{"buildType":[]}"""))
        assertNull(domainBuildStatusesFromResponse(500, null))
        assertNull(domainBuildStatusesFromResponse(200, null))
        assertNull(domainBuildStatusesFromResponse(200, "<html>login</html>"))
        assertEquals(
            emptyMap<Domain, DomainBuildStatus>(),
            domainBuildStatusesFromResponse(200, """{"buildType":[]}""")
        )
    }

    fun testParsedStatusesAreFoundByDumpDomains() {
        val response = """
            {"buildType": [
              {"id": "Kotlin_KotlinDev_Domain_AnalysisApi", "builds": {"build": [{"id": 1, "status": "SUCCESS", "webUrl": "https://analysis"}]}}
            ]}
        """.trimIndent()

        val service = DomainBuildStatusService.getInstance(project)
        service.updateStatuses(domainBuildStatusesFromResponse(200, response)!!)

        /* Domains from the dump go through Domain.of as well, which trims the name. */
        val dumpDomain = parseDomainDump("AnalysisApi :\n- compiler/foo").entries.single().domains.single()
        assertEquals("https://analysis", service.status(dumpDomain)?.webUrl)
        assertEquals("https://analysis", service.status(Domain.of(" AnalysisApi "))?.webUrl)

        assertNull(service.status(compiler))
        assertNull(service.status(Domain.of("analysisapi")))
        assertNull(service.status(Domain.of("Kotlin_KotlinDev_Domain_AnalysisApi")))
    }

    fun testPollingFetchesPromptlyOnActivationAndCancelsInFlightFetchOnDeactivation() = runBlocking {
        val service = DomainBuildStatusService.getInstance(project)
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
        val service = DomainBuildStatusService.getInstance(project)
        val fetches = Channel<Unit>(Channel.UNLIMITED)
        service.fetchStatuses = {
            fetches.send(Unit)
            mapOf(analysisApi to greenAnalysisApi)
        }

        service.setPollingActive(true)
        service.setPollingActive(true)
        service.setPollingActive(true)

        withTimeout(5.seconds) { fetches.receive() }
        assertNoFurtherFetches(fetches)
        assertEquals(greenAnalysisApi, service.status(analysisApi))

        service.setPollingActive(false)
        service.setPollingActive(false)
        assertFalse(service.isPolling)
    }

    fun testInactivePollingNeverFetches() = runBlocking {
        val service = DomainBuildStatusService.getInstance(project)
        val fetches = Channel<Unit>(Channel.UNLIMITED)
        service.fetchStatuses = {
            fetches.send(Unit)
            emptyMap()
        }

        service.setPollingActive(false)

        assertFalse(service.isPolling)
        assertNoFurtherFetches(fetches)
    }

    fun testUnexpectedlyEndedLoopIsRestartedOnActivation() = runBlocking {
        val service = DomainBuildStatusService.getInstance(project)
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
    }

    fun testDisposingDomainPaneStopsPolling() {
        val service = DomainBuildStatusService.getInstance(project)
        service.fetchStatuses = { awaitCancellation() }
        service.setPollingActive(true)

        Disposer.dispose(DomainProjectViewPane(project))

        assertFalse(service.isPolling)
    }

    private suspend fun <T> assertNoFurtherFetches(fetches: Channel<T>) {
        delay(200.milliseconds)
        assertTrue("Unexpected fetch", fetches.tryReceive().isFailure)
    }

    /* The light project is shared between tests, so cached statuses and polling must not leak. */
    override fun tearDown() {
        try {
            project.getServiceIfCreated(DomainBuildStatusService::class.java)?.let { service ->
                service.setPollingActive(false)
                service.fetchStatuses = service::fetchStatusesOrEmpty
                service.updateStatuses(emptyMap())
            }
        } finally {
            super.tearDown()
        }
    }

    private fun countPublishedEvents(action: () -> Unit): Int {
        var publishedEvents = 0
        val connection = project.messageBus.connect(testRootDisposable)
        connection.subscribe(DomainProjectService.MODEL_CHANGED_TOPIC, DomainModelListener { publishedEvents++ })

        try {
            action()
        } finally {
            connection.disconnect()
        }

        return publishedEvents
    }
}
