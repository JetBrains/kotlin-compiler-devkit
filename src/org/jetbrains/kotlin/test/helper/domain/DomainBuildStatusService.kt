package org.jetbrains.kotlin.test.helper.domain

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Caches the TeamCity build status of every domain; nodes only read the cached map.
 * Polls only while [setPollingActive] is on (the Domain pane is shown).
 *
 * This service uses the guest API of TeamCity, which does not require any authentification flow,
 * but requires being connected to our (JetBrains) internal network (e.g. using the Office Network or VPN)
 */
@Service(Service.Level.PROJECT)
class DomainBuildStatusService(private val project: Project, private val scope: CoroutineScope) {
    companion object {
        private val logger = Logger.getInstance(DomainBuildStatusService::class.java)

        private val pollingInterval = 5.minutes
        private val connectTimeout = 5.seconds
        private val requestTimeout = 10.seconds

        fun getInstance(project: Project): DomainBuildStatusService = project.service()
    }

    @Volatile
    private var statuses: Map<Domain, DomainBuildStatus> = emptyMap()

    /**
     * Guarded by `this`, like every write to [statuses]; null while polling is inactive.
     */
    private var pollingJob: Job? = null

    /**
     * Replaced in tests to avoid network access.
     */
    internal var fetchStatuses: suspend () -> Map<Domain, DomainBuildStatus> = ::fetchStatusesOrEmpty

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(connectTimeout.toJavaDuration())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    internal fun status(domain: Domain): DomainBuildStatus? = statuses[domain]

    internal val isPolling: Boolean
        get() = synchronized(this) { pollingJob?.isActive == true }

    /**
     * Activating fetches right away and then every [pollingInterval].
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
                val newStatuses = fetchStatuses()
                updateStatuses(newStatuses)
                delay(pollingInterval)
            }
        }
    }

    @Synchronized
    internal fun updateStatuses(newStatuses: Map<Domain, DomainBuildStatus>) {
        if (statuses != newStatuses) {
            statuses = newStatuses
            publishModelChanged()
        }
    }

    /**
     * Published outside the lock, so listeners never run while holding it.
     * Listeners read the latest [statuses], so the order of concurrent publishes does not matter.
     */
    private fun publishModelChanged() {
        if (project.isDisposed) return
        project.messageBus.syncPublisher(DomainProjectService.MODEL_CHANGED_TOPIC).domainModelChanged()
    }

    /**
     * The real fetch; internal so tests can restore it after replacing [fetchStatuses].
     */
    internal suspend fun fetchStatusesOrEmpty(): Map<Domain, DomainBuildStatus> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(DOMAIN_BUILD_STATUSES_URL))
            .timeout(requestTimeout.toJavaDuration())
            .header("Accept", "application/json")
            .GET()
            .build()

        val response = try {
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.debug("Unable to fetch domain build statuses", exception)
            return emptyMap()
        }

        val parsedStatuses = domainBuildStatusesFromResponse(response.statusCode(), response.body())
        if (parsedStatuses == null) {
            logger.debug("Unusable domain build status response (HTTP ${response.statusCode()})")
            return emptyMap()
        }

        return parsedStatuses
    }
}

/**
 * Returns null if the response is not usable (non-200 or unreadable body).
 */
internal fun domainBuildStatusesFromResponse(statusCode: Int, body: String?): Map<Domain, DomainBuildStatus>? {
    if (statusCode != 200 || body == null) return null
    return parseDomainBuildStatuses(body)
}
