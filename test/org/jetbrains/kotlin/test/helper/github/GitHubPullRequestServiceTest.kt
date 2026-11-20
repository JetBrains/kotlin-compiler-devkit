package org.jetbrains.kotlin.test.helper.github

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.messages.MessageBusConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.time.Duration

/**
 * The service tests replace the branch and review lookups, so no network access happens; the
 * unconfined scope makes every refresh run synchronously within its request.
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
        return GitHubPullRequestService(project, scope).apply {
            findCodeOwnersReview = { CodeOwnersReviewResult.Failed("No review in this test") }
        }
    }

    private fun pullRequestConnection(): MessageBusConnection = project.messageBus.connect(testRootDisposable)

    private fun openPullRequest(number: Int) = OpenKotlinPullRequest(
        url = "https://github.com/JetBrains/kotlin/pull/$number",
        headSha = "0123456789abcdef0123456789abcdef01234567",
        number = number.toLong(),
    )

    /**
     * Pull request #42 of [openPullRequest] after it was merged.
     */
    private val mergedPullRequest = MergedKotlinPullRequest(
        url = "https://github.com/JetBrains/kotlin/pull/42",
        number = 42,
        baseBranchName = "master",
    )

    private fun approvedReview(reviewer: String) = CodeOwnersReview.Rules(
        listOf(
            CodeOwnersRule(
                paths = listOf("/compiler/fir/"),
                ownerTeams = listOf("kotlin-frontend"),
                approval = CodeOwnersApproval.APPROVED,
                reviewers = listOf(reviewer),
            )
        )
    )

    fun testResolvesTheOpenPullRequestOfEveryRequestedBranch() {
        val service = service()
        val first = openPullRequest(42)
        val requestedBranches = mutableListOf<String>()
        service.findBranchPullRequest = { branchName ->
            requestedBranches += branchName
            when (branchName) {
                "feature/kt-123456" -> BranchPullRequestResult.Open(first)
                "master" -> BranchPullRequestResult.NotFound
                else -> BranchPullRequestResult.Ambiguous
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
        var result: BranchPullRequestResult = BranchPullRequestResult.Open(openPullRequest(42))
        service.findBranchPullRequest = { result }

        service.requestRefresh(listOf("feature/kt-123456"))
        assertEquals(openPullRequest(42), service.pullRequest("feature/kt-123456"))

        /* The branch is gone from the tree, so its pull request is gone from the cache. */
        service.minimumRefreshInterval = Duration.ZERO
        result = BranchPullRequestResult.NotFound
        service.requestRefresh(emptyList())
        assertNull(service.pullRequest("feature/kt-123456"))
    }

    fun testKeepsThePreviousPullRequestWhenALookupIsIndeterminate() {
        val service = service()
        val cached = openPullRequest(42)
        var result: BranchPullRequestResult = BranchPullRequestResult.Open(cached)
        service.findBranchPullRequest = { result }

        service.requestRefresh(listOf("feature/kt-123456"))
        assertEquals(cached, service.pullRequest("feature/kt-123456"))

        service.minimumRefreshInterval = Duration.ZERO
        result = BranchPullRequestResult.Failed("offline")
        service.requestRefresh(listOf("feature/kt-123456"))
        result = BranchPullRequestResult.InvalidResponse
        service.requestRefresh(listOf("feature/kt-123456"))

        assertEquals(cached, service.pullRequest("feature/kt-123456"))
    }

    fun testPublishesOnlyWhenThePullRequestsChanged() {
        val service = service()
        var pullRequest = openPullRequest(42)
        service.findBranchPullRequest = { BranchPullRequestResult.Open(pullRequest) }
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
        service.findBranchPullRequest = { branchName ->
            requestedBranches += branchName
            BranchPullRequestResult.NotFound
        }

        service.requestRefresh(listOf("feature/kt-123456"))
        service.requestRefresh(listOf("feature/kt-123456"))

        assertEquals(listOf("feature/kt-123456"), requestedBranches)
    }

    fun testRunsASingleRefreshAtATime() {
        val service = service()
        val lookupMayFinish = CompletableDeferred<Unit>()
        val requestedBranches = mutableListOf<String>()
        service.findBranchPullRequest = { branchName ->
            requestedBranches += branchName
            lookupMayFinish.await()
            BranchPullRequestResult.NotFound
        }

        /* The first request suspends inside its lookup; the second one must not start another. */
        service.requestRefresh(listOf("feature/kt-123456"))
        service.requestRefresh(listOf("feature/kt-123456"))

        assertEquals(listOf("feature/kt-123456"), requestedBranches)

        lookupMayFinish.complete(Unit)
        assertNull(service.pullRequest("feature/kt-123456"))
    }

    fun testReadsTheCodeOwnersReviewOfEveryFoundPullRequestOnly() {
        val service = service()
        val pullRequest = openPullRequest(42)
        val review = approvedReview("alice")
        val reviewedPullRequests = mutableListOf<OpenKotlinPullRequest>()
        service.findBranchPullRequest = { branchName ->
            when (branchName) {
                "feature/kt-123456" -> BranchPullRequestResult.Open(pullRequest)
                "master" -> BranchPullRequestResult.NotFound
                else -> BranchPullRequestResult.Ambiguous
            }
        }
        service.findCodeOwnersReview = { requested ->
            reviewedPullRequests += requested
            CodeOwnersReviewResult.Found(review)
        }

        service.requestRefresh(listOf("feature/kt-123456", "master", "sellmair/remote-run"))

        assertEquals(listOf(pullRequest), reviewedPullRequests)
        assertEquals(mapOf("feature/kt-123456" to BranchPullRequest.Open(pullRequest, review)), service.pullRequests())
        assertEquals(review, service.codeOwnersReview("feature/kt-123456"))
    }

    fun testKeepsTheReviewOfTheSamePullRequestWhenReadingItFails() {
        val service = service()
        val review = approvedReview("alice")
        service.findBranchPullRequest = { BranchPullRequestResult.Open(openPullRequest(42)) }
        var reviewResult: CodeOwnersReviewResult = CodeOwnersReviewResult.Found(review)
        service.findCodeOwnersReview = { reviewResult }

        service.requestRefresh(listOf("feature/kt-123456"))
        assertEquals(review, service.codeOwnersReview("feature/kt-123456"))

        service.minimumRefreshInterval = Duration.ZERO
        reviewResult = CodeOwnersReviewResult.Failed("offline")
        service.requestRefresh(listOf("feature/kt-123456"))

        assertEquals(review, service.codeOwnersReview("feature/kt-123456"))
    }

    fun testDropsTheReviewOfAReplacedPullRequestWhenReadingTheNewOneFails() {
        val service = service()
        var pullRequest = openPullRequest(42)
        service.findBranchPullRequest = { BranchPullRequestResult.Open(pullRequest) }
        var reviewResult: CodeOwnersReviewResult = CodeOwnersReviewResult.Found(approvedReview("alice"))
        service.findCodeOwnersReview = { reviewResult }

        service.requestRefresh(listOf("feature/kt-123456"))

        /* The branch got a new pull request, whose review does not exist yet. */
        service.minimumRefreshInterval = Duration.ZERO
        pullRequest = openPullRequest(43)
        reviewResult = CodeOwnersReviewResult.Failed("offline")
        service.requestRefresh(listOf("feature/kt-123456"))

        assertEquals(openPullRequest(43), service.pullRequest("feature/kt-123456"))
        assertNull(service.codeOwnersReview("feature/kt-123456"))
    }

    fun testKeepsThePullRequestAndItsReviewWhenThePullRequestLookupFails() {
        val service = service()
        val review = approvedReview("alice")
        var pullRequestResult: BranchPullRequestResult = BranchPullRequestResult.Open(openPullRequest(42))
        service.findBranchPullRequest = { pullRequestResult }
        var reviewLookups = 0
        service.findCodeOwnersReview = {
            reviewLookups++
            CodeOwnersReviewResult.Found(review)
        }

        service.requestRefresh(listOf("feature/kt-123456"))

        service.minimumRefreshInterval = Duration.ZERO
        pullRequestResult = BranchPullRequestResult.Failed("offline")
        service.requestRefresh(listOf("feature/kt-123456"))
        pullRequestResult = BranchPullRequestResult.InvalidResponse
        service.requestRefresh(listOf("feature/kt-123456"))

        assertEquals(1, reviewLookups)
        assertEquals(
            mapOf("feature/kt-123456" to BranchPullRequest.Open(openPullRequest(42), review)),
            service.pullRequests(),
        )
    }

    fun testPublishesWhenOnlyTheReviewChanged() {
        val service = service()
        service.findBranchPullRequest = { BranchPullRequestResult.Open(openPullRequest(42)) }
        var review: CodeOwnersReview = approvedReview("alice")
        service.findCodeOwnersReview = { CodeOwnersReviewResult.Found(review) }
        var changeCount = 0
        pullRequestConnection().subscribe(
            GitHubPullRequestService.PULL_REQUESTS_CHANGED_TOPIC,
            PullRequestsListener { changeCount++ },
        )

        service.requestRefresh(listOf("feature/kt-123456"))
        assertEquals(1, changeCount)

        service.minimumRefreshInterval = Duration.ZERO

        /* The same review again: no publication. */
        service.requestRefresh(listOf("feature/kt-123456"))
        assertEquals(1, changeCount)

        review = approvedReview("bob")
        service.requestRefresh(listOf("feature/kt-123456"))
        assertEquals(2, changeCount)
        assertEquals(approvedReview("bob"), service.codeOwnersReview("feature/kt-123456"))

        review = CodeOwnersReview.MissingComment
        service.requestRefresh(listOf("feature/kt-123456"))
        assertEquals(3, changeCount)
        assertEquals(CodeOwnersReview.MissingComment, service.codeOwnersReview("feature/kt-123456"))
    }

    fun testCachesTheMergedPullRequestOfABranchWithoutReadingItsReview() {
        val service = service()
        service.findBranchPullRequest = { branchName ->
            when (branchName) {
                "feature/kt-123456" -> BranchPullRequestResult.Merged(mergedPullRequest)
                else -> BranchPullRequestResult.NotFound
            }
        }
        var reviewLookups = 0
        service.findCodeOwnersReview = {
            reviewLookups++
            CodeOwnersReviewResult.Found(approvedReview("alice"))
        }

        service.requestRefresh(listOf("feature/kt-123456", "sellmair/remote-run"))

        assertEquals(0, reviewLookups)
        assertEquals(
            mapOf("feature/kt-123456" to BranchPullRequest.Merged(mergedPullRequest)),
            service.pullRequests(),
        )
        /* Only open pull requests are offered as such, e.g. to the safe-merge launch. */
        assertNull(service.pullRequest("feature/kt-123456"))
        assertNull(service.codeOwnersReview("feature/kt-123456"))
    }

    fun testAMergedPullRequestReplacesTheOpenOneAndItsReview() {
        val service = service()
        var result: BranchPullRequestResult = BranchPullRequestResult.Open(openPullRequest(42))
        service.findBranchPullRequest = { result }
        service.findCodeOwnersReview = { CodeOwnersReviewResult.Found(approvedReview("alice")) }
        var changeCount = 0
        pullRequestConnection().subscribe(
            GitHubPullRequestService.PULL_REQUESTS_CHANGED_TOPIC,
            PullRequestsListener { changeCount++ },
        )

        service.requestRefresh(listOf("feature/kt-123456"))

        service.minimumRefreshInterval = Duration.ZERO
        result = BranchPullRequestResult.Merged(mergedPullRequest)
        service.requestRefresh(listOf("feature/kt-123456"))

        assertEquals(2, changeCount)
        assertEquals(
            mapOf("feature/kt-123456" to BranchPullRequest.Merged(mergedPullRequest)),
            service.pullRequests(),
        )
    }

    fun testKeepsTheMergedPullRequestWhenALookupIsIndeterminate() {
        val service = service()
        var result: BranchPullRequestResult = BranchPullRequestResult.Merged(mergedPullRequest)
        service.findBranchPullRequest = { result }

        service.requestRefresh(listOf("feature/kt-123456"))

        service.minimumRefreshInterval = Duration.ZERO
        result = BranchPullRequestResult.Failed("offline")
        service.requestRefresh(listOf("feature/kt-123456"))
        result = BranchPullRequestResult.InvalidResponse
        service.requestRefresh(listOf("feature/kt-123456"))

        assertEquals(
            mapOf("feature/kt-123456" to BranchPullRequest.Merged(mergedPullRequest)),
            service.pullRequests(),
        )
    }
}
