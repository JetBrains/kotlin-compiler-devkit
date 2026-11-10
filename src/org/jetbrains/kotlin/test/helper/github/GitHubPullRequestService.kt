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
 * Caches the open JetBrains/kotlin pull request of every listed branch, for the Remote Run
 * tree's GitHub indicator and its "Open Pull Request" action.
 *
 * [requestRefresh] resolves each requested branch through the GitHub plugin, at most once per
 * [minimumRefreshInterval]. A branch whose lookup fails keeps its previously resolved pull
 * request and only logs: the indicator is best effort, unlike the strict pull-request
 * resolution the safe-merge launch performs. A changed map is published on
 * [PULL_REQUESTS_CHANGED_TOPIC]; the resolved pull requests live in memory only.
 */
@Service(Service.Level.PROJECT)
internal class GitHubPullRequestService(private val project: Project, private val scope: CoroutineScope) {
    companion object {
        private val logger = Logger.getInstance(GitHubPullRequestService::class.java)

        val PULL_REQUESTS_CHANGED_TOPIC: Topic<PullRequestsListener> =
            Topic("GitHub pull requests changed", PullRequestsListener::class.java)

        fun getInstance(project: Project): GitHubPullRequestService = project.service()
    }

    @Volatile
    private var pullRequestsByBranch: Map<String, OpenKotlinPullRequest> = emptyMap()

    /**
     * Guarded by `this`, like [lastRefreshAtMillis]; a refresh that is already running makes
     * further requests return until it finishes.
     */
    private var refreshJob: Job? = null

    private var lastRefreshAtMillis: Long = 0

    /**
     * Replaced in tests to avoid network access.
     */
    internal var findOpenPullRequest: suspend (branchName: String) -> OpenKotlinPullRequestResult =
        ::findOpenKotlinPullRequest

    /**
     * Replaced in tests to fetch on every request.
     */
    @Volatile
    internal var minimumRefreshInterval: Duration = 1.minutes

    fun pullRequest(branchName: String): OpenKotlinPullRequest? = pullRequestsByBranch[branchName]

    fun pullRequests(): Map<String, OpenKotlinPullRequest> = pullRequestsByBranch

    /**
     * Resolves the open pull request of every branch in [branchNames] unless a refresh is already
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
        val fetched = mutableMapOf<String, OpenKotlinPullRequest>()
        for (branchName in branchNames) {
            when (val result = findOpenPullRequest(branchName)) {
                is OpenKotlinPullRequestResult.Found -> fetched[branchName] = result.pullRequest

                OpenKotlinPullRequestResult.NotFound,
                OpenKotlinPullRequestResult.Ambiguous,
                -> Unit

                /* An indeterminate outcome keeps the previous pull request, like a failed lookup. */
                is OpenKotlinPullRequestResult.Failed -> {
                    logger.debug("Unable to resolve the open Kotlin pull request for branch $branchName: ${result.message}")
                    pullRequestsByBranch[branchName]?.let { pullRequest -> fetched[branchName] = pullRequest }
                }

                OpenKotlinPullRequestResult.InvalidResponse -> {
                    logger.debug("GitHub returned an unreadable pull request for branch $branchName")
                    pullRequestsByBranch[branchName]?.let { pullRequest -> fetched[branchName] = pullRequest }
                }
            }
        }

        if (fetched != pullRequestsByBranch) {
            pullRequestsByBranch = fetched
            project.messageBus.syncPublisher(PULL_REQUESTS_CHANGED_TOPIC).pullRequestsChanged()
        }
    }
}
