package org.jetbrains.kotlin.test.helper.github

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

internal fun interface PullRequestsListener {
    fun pullRequestsChanged()
}

/**
 * Caches the pull request of every listed branch — its open JetBrains/kotlin pull request with the
 * code owners review or, without one, its merged pull request — for the Remote Run tree's GitHub
 * indicators and its "Open Pull Request" action.
 *
 * [requestRefresh] resolves each requested branch through the GitHub plugin, at most once per
 * [minimumRefreshInterval], and reads the code owners review of every open pull request it finds. A
 * branch whose lookup fails keeps its previously resolved pull request and review, and a review
 * that cannot be read keeps the previous one of the same pull request; failures only log: the
 * indicators are best effort, unlike the strict pull-request resolution the safe-merge launch
 * performs. A changed map is published on [PULL_REQUESTS_CHANGED_TOPIC]; everything lives in
 * memory only.
 */
@Service(Service.Level.PROJECT)
internal class GitHubPullRequestService(private val project: Project, private val scope: CoroutineScope) {
    companion object {
        private val logger = Logger.getInstance(GitHubPullRequestService::class.java)

        val PULL_REQUESTS_CHANGED_TOPIC: Topic<PullRequestsListener> =
            Topic("GitHub pull requests changed", PullRequestsListener::class.java)

        fun getInstance(project: Project): GitHubPullRequestService = project.service()
    }

    /**
     * Replaced as a whole, so a pull request is never read together with the review of another one.
     */
    @Volatile
    private var pullRequestsByBranch: Map<String, BranchPullRequest> = emptyMap()

    /**
     * Guarded by `this`, like [lastRefreshAtMillis]; a refresh that is already running makes
     * further requests return until it finishes.
     */
    private var refreshJob: Job? = null

    private var lastRefreshAtMillis: Long = 0

    /**
     * Replaced in tests to avoid network access.
     */
    internal var findBranchPullRequest: suspend (branchName: String) -> BranchPullRequestResult =
        ::findKotlinBranchPullRequest

    /**
     * Replaced in tests to avoid network access.
     */
    internal var findCodeOwnersReview: suspend (pullRequest: OpenKotlinPullRequest) -> CodeOwnersReviewResult =
        ::findKotlinCodeOwnersReview

    /**
     * Replaced in tests to fetch on every request.
     */
    @Volatile
    internal var minimumRefreshInterval: Duration = 1.minutes

    fun pullRequest(branchName: String): OpenKotlinPullRequest? =
        (pullRequestsByBranch[branchName] as? BranchPullRequest.Open)?.pullRequest

    fun codeOwnersReview(branchName: String): CodeOwnersReview? =
        (pullRequestsByBranch[branchName] as? BranchPullRequest.Open)?.codeOwnersReview

    /**
     * The pull request of every branch that has an open or a merged one.
     */
    fun pullRequests(): Map<String, BranchPullRequest> = pullRequestsByBranch

    /**
     * Resolves the pull request of every branch in [branchNames] unless a refresh is already
     * running or the last one started less than [minimumRefreshInterval] ago. Failed lookups
     * keep the previous pull requests.
     */
    @Synchronized
    fun requestRefresh(branchNames: Collection<String>) {
        if (refreshJob?.isActive == true) return
        if (System.currentTimeMillis() - lastRefreshAtMillis < minimumRefreshInterval.inWholeMilliseconds) return

        lastRefreshAtMillis = System.currentTimeMillis()
        refreshJob = scope.launch { refreshOnce(branchNames.distinct()) }
    }

    private suspend fun refreshOnce(branchNames: List<String>) {
        val previous = pullRequestsByBranch
        val fetched = mutableMapOf<String, BranchPullRequest>()
        for (branchName in branchNames) {
            fetched[branchName] = when (val result = findBranchPullRequest(branchName)) {
                is BranchPullRequestResult.Open -> BranchPullRequest.Open(
                    result.pullRequest,
                    readCodeOwnersReview(result.pullRequest, previous[branchName]),
                )

                is BranchPullRequestResult.Merged -> BranchPullRequest.Merged(result.pullRequest)

                BranchPullRequestResult.NotFound,
                BranchPullRequestResult.Ambiguous,
                -> continue

                /* An indeterminate outcome keeps the previous pull request, like a failed lookup. */
                is BranchPullRequestResult.Failed -> {
                    logger.debug("Unable to resolve the Kotlin pull request for branch $branchName: ${result.message}")
                    previous[branchName] ?: continue
                }

                BranchPullRequestResult.InvalidResponse -> {
                    logger.debug("GitHub returned an unreadable pull request for branch $branchName")
                    previous[branchName] ?: continue
                }
            }
        }

        if (fetched != previous) {
            pullRequestsByBranch = fetched
            project.messageBus.syncPublisher(PULL_REQUESTS_CHANGED_TOPIC).pullRequestsChanged()
        }
    }

    /**
     * A review that cannot be read keeps the one read before for the same pull request; a new pull
     * request of the branch has none until it is read.
     */
    private suspend fun readCodeOwnersReview(
        pullRequest: OpenKotlinPullRequest,
        previous: BranchPullRequest?,
    ): CodeOwnersReview? = when (val result = findCodeOwnersReview(pullRequest)) {
        is CodeOwnersReviewResult.Found -> result.review

        is CodeOwnersReviewResult.Failed -> {
            logger.debug("Unable to read the code owners review of ${pullRequest.url}: ${result.message}")
            (previous as? BranchPullRequest.Open)
                ?.takeIf { openPullRequest -> openPullRequest.pullRequest.number == pullRequest.number }
                ?.codeOwnersReview
        }
    }
}
