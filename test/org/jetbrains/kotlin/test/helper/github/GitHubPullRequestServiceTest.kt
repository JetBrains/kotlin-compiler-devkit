package org.jetbrains.kotlin.test.helper.github

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.messages.MessageBusConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.time.Duration

/**
 * The service tests replace the branch lookup, so no network access happens; the unconfined
 * scope makes every refresh run synchronously within its request.
 */
class GitHubPullRequestServiceTest : BasePlatformTestCase() {
    private var serviceScope: CoroutineScope? = null

    override fun tearDown() {
        serviceScope?.cancel()
        super.tearDown()
    }

    private fun service(): GitHubPullRequestService {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        serviceScope = scope
        return GitHubPullRequestService(project, scope)
    }

    private fun pullRequestConnection(): MessageBusConnection = project.messageBus.connect(testRootDisposable)

    private fun openPullRequest(number: Int) = OpenKotlinPullRequest(
        url = "https://github.com/JetBrains/kotlin/pull/$number",
        headSha = "0123456789abcdef0123456789abcdef01234567",
    )

    fun testResolvesTheOpenPullRequestOfEveryRequestedBranch() {
        val service = service()
        val first = openPullRequest(42)
        val requestedBranches = mutableListOf<String>()
        service.findOpenPullRequest = { branchName ->
            requestedBranches += branchName
            when (branchName) {
                "feature/kt-123456" -> OpenKotlinPullRequestResult.Found(first)
                "master" -> OpenKotlinPullRequestResult.NotFound
                else -> OpenKotlinPullRequestResult.Ambiguous
            }
        }

        service.requestRefresh(listOf("feature/kt-123456", "master", "sellmair/remote-run"))

        assertEquals(listOf("feature/kt-123456", "master", "sellmair/remote-run"), requestedBranches)
        assertEquals(first, service.pullRequest("feature/kt-123456"))
        assertNull(service.pullRequest("master"))
        assertNull(service.pullRequest("sellmair/remote-run"))
    }

    fun testRemovesBranchesThatAreNoLongerRequested() {
        val service = service()
        var result: OpenKotlinPullRequestResult = OpenKotlinPullRequestResult.Found(openPullRequest(42))
        service.findOpenPullRequest = { result }

        service.requestRefresh(listOf("feature/kt-123456"))
        assertEquals(openPullRequest(42), service.pullRequest("feature/kt-123456"))

        /* The branch is gone from the tree, so its pull request is gone from the cache. */
        service.minimumRefreshInterval = Duration.ZERO
        result = OpenKotlinPullRequestResult.NotFound
        service.requestRefresh(emptyList())
        assertNull(service.pullRequest("feature/kt-123456"))
    }

    fun testKeepsThePreviousPullRequestWhenALookupIsIndeterminate() {
        val service = service()
        val cached = openPullRequest(42)
        var result: OpenKotlinPullRequestResult = OpenKotlinPullRequestResult.Found(cached)
        service.findOpenPullRequest = { result }

        service.requestRefresh(listOf("feature/kt-123456"))
        assertEquals(cached, service.pullRequest("feature/kt-123456"))

        service.minimumRefreshInterval = Duration.ZERO
        result = OpenKotlinPullRequestResult.Failed("offline")
        service.requestRefresh(listOf("feature/kt-123456"))
        result = OpenKotlinPullRequestResult.InvalidResponse
        service.requestRefresh(listOf("feature/kt-123456"))

        assertEquals(cached, service.pullRequest("feature/kt-123456"))
    }

    fun testPublishesOnlyWhenThePullRequestsChanged() {
        val service = service()
        var pullRequest = openPullRequest(42)
        service.findOpenPullRequest = { OpenKotlinPullRequestResult.Found(pullRequest) }
        var changeCount = 0
        pullRequestConnection().subscribe(
            GitHubPullRequestService.PULL_REQUESTS_CHANGED_TOPIC,
            PullRequestsListener { changeCount++ },
        )

        service.requestRefresh(listOf("feature/kt-123456"))
        assertEquals(1, changeCount)

        service.minimumRefreshInterval = Duration.ZERO

        /* The same pull request again: no publication. */
        service.requestRefresh(listOf("feature/kt-123456"))
        assertEquals(1, changeCount)

        pullRequest = openPullRequest(43)
        service.requestRefresh(listOf("feature/kt-123456"))
        assertEquals(2, changeCount)
        assertEquals(openPullRequest(43), service.pullRequest("feature/kt-123456"))
    }

    fun testThrottlesRepeatedRefreshRequests() {
        val service = service()
        val requestedBranches = mutableListOf<String>()
        service.findOpenPullRequest = { branchName ->
            requestedBranches += branchName
            OpenKotlinPullRequestResult.NotFound
        }

        service.requestRefresh(listOf("feature/kt-123456"))
        service.requestRefresh(listOf("feature/kt-123456"))

        assertEquals(listOf("feature/kt-123456"), requestedBranches)
    }

    fun testRunsASingleRefreshAtATime() {
        val service = service()
        val lookupMayFinish = CompletableDeferred<Unit>()
        val requestedBranches = mutableListOf<String>()
        service.findOpenPullRequest = { branchName ->
            requestedBranches += branchName
            lookupMayFinish.await()
            OpenKotlinPullRequestResult.NotFound
        }

        /* The first request suspends inside its lookup; the second one must not start another. */
        service.requestRefresh(listOf("feature/kt-123456"))
        service.requestRefresh(listOf("feature/kt-123456"))

        assertEquals(listOf("feature/kt-123456"), requestedBranches)

        lookupMayFinish.complete(Unit)
        assertNull(service.pullRequest("feature/kt-123456"))
    }
}
