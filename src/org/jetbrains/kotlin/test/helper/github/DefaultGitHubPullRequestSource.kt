package org.jetbrains.kotlin.test.helper.github

import com.intellij.openapi.components.service
import java.net.URLEncoder
import org.jetbrains.plugins.github.GithubIcons
import org.jetbrains.plugins.github.api.GHRepositoryCoordinates
import org.jetbrains.plugins.github.api.GHRepositoryPath
import org.jetbrains.plugins.github.api.GHGQLRequests
import org.jetbrains.plugins.github.api.GithubApiContentHelper
import org.jetbrains.plugins.github.api.GithubApiRequest
import org.jetbrains.plugins.github.api.GithubApiRequestExecutor
import org.jetbrains.plugins.github.api.GithubApiResponse
import org.jetbrains.plugins.github.api.GithubServerPath
import org.jetbrains.plugins.github.api.data.GithubIssueState
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequest
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequestRestIdOnly
import org.jetbrains.plugins.github.api.executeSuspend
import org.jetbrains.plugins.github.authentication.GHAccountsUtil
import org.jetbrains.plugins.github.authentication.accounts.GHAccountManager
import kotlin.coroutines.cancellation.CancellationException

/**
 * Registered through github-integration.xml, so this class is only loaded when the GitHub
 * plugin is installed. All GitHub requests go through the plugin's API machinery, which
 * authenticates with the GitHub account configured in the IDE; GitHub's GraphQL API, from which
 * the plugin reads the pull request details, effectively requires it, because its unauthenticated
 * per-IP quota is exhausted almost everywhere.
 */
internal class DefaultGitHubPullRequestSource : GitHubPullRequestSource {
    override val pullRequestIcon get() = GithubIcons.PullRequestsToolWindow

    override suspend fun findOpenPullRequest(branchName: String): OpenKotlinPullRequestResult {
        val connection = connectToKotlinRepository()

        /*
         * The listing request is built directly with `Get.jsonPage` instead of the
         * `GithubApiRequests.Repos.PullRequests.find` sugar, whose parameter list grew in newer
         * GitHub plugin versions — calling it directly throws `NoSuchMethodError` there. The
         * `head` parameter restricts the results to pull requests with the head ref
         * `JetBrains:<branch>`; one page is enough, because more than one open pull request per
         * head branch is already ambiguous.
         */
        val pullRequestsUrl = githubPullRequestsListingUrl(connection.apiUrl, branchName)
        val candidates = try {
            connection.executor
                .executeSuspend(GithubApiRequest.Get.jsonPage<GHPullRequestRestIdOnly>(pullRequestsUrl))
                .items
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            return OpenKotlinPullRequestResult.Failed(exception.message ?: "GitHub API request failed")
        }

        return when {
            candidates.isEmpty() -> OpenKotlinPullRequestResult.NotFound
            candidates.size > 1 -> OpenKotlinPullRequestResult.Ambiguous
            else -> readOpenPullRequest(connection, candidates.single().number, branchName)
        }
    }

    /**
     * Lists the pull requests with the branch as head in every state, so that telling merged
     * branches apart takes no request beyond the ones resolving the open pull request. One page of
     * the newest pull requests is enough: a branch rarely has more than a few.
     */
    override suspend fun findBranchPullRequest(branchName: String): BranchPullRequestResult {
        val connection = connectToKotlinRepository()
        val page = try {
            connection.executor.executeSuspend(
                RawJsonPageRequest(githubBranchPullRequestsUrl(connection.apiUrl, branchName))
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            return BranchPullRequestResult.Failed(exception.message ?: "GitHub API request failed")
        }

        val pullRequests = parsePullRequestListing(page.body) ?: return BranchPullRequestResult.InvalidResponse
        return branchPullRequestResult(pullRequests, branchName) { number ->
            readOpenPullRequest(connection, number, branchName)
        }
    }

    /**
     * Reads the full pull request, which the strict mapping of [toOpenKotlinPullRequestResult] checks.
     */
    private suspend fun readOpenPullRequest(
        connection: KotlinRepositoryConnection,
        number: Long,
        branchName: String,
    ): OpenKotlinPullRequestResult {
        val pullRequest = try {
            connection.executor.executeSuspend(GHGQLRequests.PullRequest.findOne(connection.repository, number))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            return OpenKotlinPullRequestResult.Failed(exception.message ?: "GitHub API request failed")
        }

        return pullRequest.toOpenKotlinPullRequestResult(branchName)
    }

    /**
     * Reads the comments of the pull request page by page. The bot comments right after the pull
     * request is opened, so its comment is practically always on the first page.
     */
    override suspend fun findCodeOwnersReview(pullRequest: OpenKotlinPullRequest): CodeOwnersReviewResult {
        val connection = connectToKotlinRepository()
        val comments = mutableListOf<GitHubIssueComment>()
        var pageUrl = githubIssueCommentsUrl(connection.apiUrl, pullRequest.number)

        repeat(MAX_COMMENT_PAGES) {
            val page = try {
                connection.executor.executeSuspend(RawJsonPageRequest(pageUrl))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                return CodeOwnersReviewResult.Failed(exception.message ?: "GitHub API request failed")
            }

            comments += parseIssueComments(page.body)
                ?: return CodeOwnersReviewResult.Failed("GitHub returned unreadable pull request comments")

            val nextPageUrl = page.nextPageUrl ?: return CodeOwnersReviewResult.Found(codeOwnersReviewOf(comments))
            if (!nextPageUrl.startsWith("${connection.apiUrl}/")) {
                return CodeOwnersReviewResult.Failed("GitHub returned an unexpected next page: $nextPageUrl")
            }
            pageUrl = nextPageUrl
        }

        /* The unread pages may hold the comment, so it is missing only if none was found so far. */
        return when (val reviewResult = codeOwnersReviewOf(comments)) {
            is CodeOwnersReview.MissingComment -> CodeOwnersReviewResult.Failed("No code owners comment in the first $MAX_COMMENT_PAGES pages of comments")
            else -> CodeOwnersReviewResult.Found(reviewResult)
        }
    }

    private suspend fun connectToKotlinRepository(): KotlinRepositoryConnection {
        val account = GHAccountsUtil.accounts.firstOrNull { it.server.isGithubDotCom }
        val executorFactory = GithubApiRequestExecutor.Factory.getInstance()
        val token = account?.let { service<GHAccountManager>().findCredentials(it) }
        val executor = if (account != null && token != null) {
            executorFactory.create(account.server, token)
        } else {
            executorFactory.create()
        }

        val repository = GHRepositoryCoordinates(
            account?.server ?: GithubServerPath(),
            GHRepositoryPath(GITHUB_KOTLIN_REPOSITORY_OWNER, GITHUB_KOTLIN_REPOSITORY_NAME),
        )

        return KotlinRepositoryConnection(executor, repository)
    }
}

private class KotlinRepositoryConnection(
    val executor: GithubApiRequestExecutor,
    val repository: GHRepositoryCoordinates,
) {
    val apiUrl: String get() = repository.serverPath.toApiUrl()
}

private class RawJsonPage(val body: String, val nextPageUrl: String?)

/**
 * Reads one page of a REST listing as JSON text, for listings the GitHub plugin has no fitting
 * model for: its issue comment model, for one, does not expose the comment body.
 */
private class RawJsonPageRequest(url: String) :
    GithubApiRequest.Get<RawJsonPage>(url, GithubApiContentHelper.V3_JSON_MIME_TYPE) {

    override fun extractResult(response: GithubApiResponse): RawJsonPage = RawJsonPage(
        body = response.readBody { reader -> reader.readText() },
        nextPageUrl = nextPageLink(response.findHeader("Link")),
    )
}

internal fun githubPullRequestsListingUrl(apiUrl: String, branchName: String): String {
    val encodedHead = URLEncoder.encode("$GITHUB_KOTLIN_REPOSITORY_OWNER:$branchName", Charsets.UTF_8)
    return "$apiUrl/repos/$GITHUB_KOTLIN_REPOSITORY/pulls?state=${GithubIssueState.open}&head=$encodedHead"
}

/**
 * Lists the pull requests of every state with [branchName] as head, newest first.
 */
internal fun githubBranchPullRequestsUrl(apiUrl: String, branchName: String): String {
    val encodedHead = URLEncoder.encode("$GITHUB_KOTLIN_REPOSITORY_OWNER:$branchName", Charsets.UTF_8)
    return "$apiUrl/repos/$GITHUB_KOTLIN_REPOSITORY/pulls?state=all&head=$encodedHead" +
        "&sort=created&direction=desc&per_page=$PULL_REQUESTS_PER_PAGE"
}

/**
 * Pull requests are issues for GitHub's REST API, so their conversation comments are issue comments.
 */
internal fun githubIssueCommentsUrl(apiUrl: String, pullRequestNumber: Long): String =
    "$apiUrl/repos/$GITHUB_KOTLIN_REPOSITORY/issues/$pullRequestNumber/comments?per_page=$COMMENTS_PER_PAGE"

/**
 * Maps the full pull request the GitHub plugin fetched to the model of the Remote Run tool
 * window. Only pull requests whose head and base repository are both `JetBrains/kotlin` count;
 * malformed GitHub answers must never be used to start a Safe-Merge Coordinator build.
 */
internal fun GHPullRequest?.toOpenKotlinPullRequestResult(branchName: String): OpenKotlinPullRequestResult {
    if (this == null) return OpenKotlinPullRequestResult.InvalidResponse
    if (headRefName != branchName) return OpenKotlinPullRequestResult.InvalidResponse
    if (headRepository?.nameWithOwner != GITHUB_KOTLIN_REPOSITORY) return OpenKotlinPullRequestResult.InvalidResponse

    val baseRepository = baseRepository ?: return OpenKotlinPullRequestResult.InvalidResponse
    if (baseRepository.nameWithOwner != GITHUB_KOTLIN_REPOSITORY) return OpenKotlinPullRequestResult.NotFound

    if (url != kotlinPullRequestUrl(number)) return OpenKotlinPullRequestResult.InvalidResponse
    if (!gitCommitShaPattern.matches(headRefOid)) return OpenKotlinPullRequestResult.InvalidResponse

    return OpenKotlinPullRequestResult.Found(OpenKotlinPullRequest(url, headRefOid, number))
}

private const val PULL_REQUESTS_PER_PAGE = 100
private const val COMMENTS_PER_PAGE = 100
private const val MAX_COMMENT_PAGES = 3
private val gitCommitShaPattern = Regex("""[0-9a-fA-F]{40}""")
