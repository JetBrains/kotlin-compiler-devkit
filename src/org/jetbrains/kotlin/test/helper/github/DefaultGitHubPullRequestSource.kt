package org.jetbrains.kotlin.test.helper.github

import com.intellij.openapi.components.service
import java.net.URLEncoder
import org.jetbrains.plugins.github.GithubIcons
import org.jetbrains.plugins.github.api.GHRepositoryCoordinates
import org.jetbrains.plugins.github.api.GHRepositoryPath
import org.jetbrains.plugins.github.api.GHGQLRequests
import org.jetbrains.plugins.github.api.GithubApiRequest
import org.jetbrains.plugins.github.api.GithubApiRequestExecutor
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

        /*
         * The listing request is built directly with `Get.jsonPage` instead of the
         * `GithubApiRequests.Repos.PullRequests.find` sugar, whose parameter list grew in newer
         * GitHub plugin versions — calling it directly throws `NoSuchMethodError` there. The
         * `head` parameter restricts the results to pull requests with the head ref
         * `JetBrains:<branch>`; one page is enough, because more than one open pull request per
         * head branch is already ambiguous.
         */
        val pullRequestsUrl = githubPullRequestsListingUrl(repository.serverPath.toApiUrl(), branchName)
        val candidates = try {
            executor.executeSuspend(GithubApiRequest.Get.jsonPage<GHPullRequestRestIdOnly>(pullRequestsUrl)).items
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            return OpenKotlinPullRequestResult.Failed(exception.message ?: "GitHub API request failed")
        }

        return when {
            candidates.isEmpty() -> OpenKotlinPullRequestResult.NotFound
            candidates.size > 1 -> OpenKotlinPullRequestResult.Ambiguous

            else -> {
                val pullRequest = try {
                    executor.executeSuspend(GHGQLRequests.PullRequest.findOne(repository, candidates.single().number))
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    return OpenKotlinPullRequestResult.Failed(exception.message ?: "GitHub API request failed")
                }

                pullRequest.toOpenKotlinPullRequestResult(branchName)
            }
        }
    }
}

internal fun githubPullRequestsListingUrl(apiUrl: String, branchName: String): String {
    val encodedHead = URLEncoder.encode("$GITHUB_KOTLIN_REPOSITORY_OWNER:$branchName", Charsets.UTF_8)
    return "$apiUrl/repos/$GITHUB_KOTLIN_REPOSITORY/pulls?state=${GithubIssueState.open}&head=$encodedHead"
}

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

    if (!githubPullRequestUrlPattern.matches(url)) return OpenKotlinPullRequestResult.InvalidResponse
    if (!gitCommitShaPattern.matches(headRefOid)) return OpenKotlinPullRequestResult.InvalidResponse

    return OpenKotlinPullRequestResult.Found(OpenKotlinPullRequest(url, headRefOid))
}

private const val GITHUB_KOTLIN_REPOSITORY_OWNER = "JetBrains"
private const val GITHUB_KOTLIN_REPOSITORY_NAME = "kotlin"
private const val GITHUB_KOTLIN_REPOSITORY = "$GITHUB_KOTLIN_REPOSITORY_OWNER/$GITHUB_KOTLIN_REPOSITORY_NAME"
private val githubPullRequestUrlPattern = Regex("""https://github\.com/JetBrains/kotlin/pull/\d+""")
private val gitCommitShaPattern = Regex("""[0-9a-fA-F]{40}""")
