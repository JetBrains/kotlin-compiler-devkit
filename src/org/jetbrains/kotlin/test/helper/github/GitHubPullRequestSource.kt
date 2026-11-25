package org.jetbrains.kotlin.test.helper.github

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import javax.swing.Icon

internal interface GitHubPullRequestSource {
    val pullRequestIcon: Icon? get() = null

    /**
     * Resolves the open `JetBrains/kotlin` pull request whose head is [branchName], including its
     * pinned head SHA. Missing, ambiguous or failed lookups must never be treated as a pull request.
     */
    suspend fun findOpenPullRequest(branchName: String): OpenKotlinPullRequestResult

    /**
     * Resolves the pull request the Remote Run tree shows for [branchName]: its open
     * `JetBrains/kotlin` pull request, as strictly as [findOpenPullRequest] does, or else its latest
     * pull request if that one is merged. Sources that cannot tell merged pull requests only
     * resolve the open one.
     */
    suspend fun findBranchPullRequest(branchName: String): BranchPullRequestResult =
        findOpenPullRequest(branchName).toBranchPullRequestResult()

    /**
     * Reads the code owners review of [pullRequest] from the "Code Owners" comment of the
     * `kotlin-safemerge` bot. A failed lookup must never be treated as a missing comment.
     */
    suspend fun findCodeOwnersReview(pullRequest: OpenKotlinPullRequest): CodeOwnersReviewResult =
        CodeOwnersReviewResult.Failed("Reading the code owners review is not supported")

    /**
     * Whether the GitHub plugin's "Pull requests" tool window is installed and configured, so a pull
     * request of the `JetBrains/kotlin` repository can be opened in it.
     */
    fun isPullRequestToolWindowAvailable(project: Project): Boolean = false

    /**
     * Opens [pullRequest] in the GitHub plugin's "Pull requests" tool window.
     */
    suspend fun openPullRequestInToolWindow(
        project: Project,
        pullRequest: BranchPullRequest,
    ): OpenPullRequestInToolWindowResult
}

/**
 * Resolves the open Kotlin pull request of a branch through the GitHub plugin's API machinery,
 * which authenticates with the GitHub account configured in the IDE. GitHub's unauthenticated
 * API allows only 60 requests per hour — and per shared IP — which is far too little for the
 * Remote Run tool window. Without the plugin there is no pull request source at all.
 */
internal suspend fun findOpenKotlinPullRequest(branchName: String): OpenKotlinPullRequestResult =
    GITHUB_PULL_REQUEST_SOURCE_EP.extensionList.firstOrNull()?.findOpenPullRequest(branchName)
        ?: OpenKotlinPullRequestResult.Failed("The GitHub integration plugin is not available")

/**
 * Resolves the open or merged Kotlin pull request of a branch through the GitHub plugin, like
 * [findOpenKotlinPullRequest].
 */
internal suspend fun findKotlinBranchPullRequest(branchName: String): BranchPullRequestResult =
    GITHUB_PULL_REQUEST_SOURCE_EP.extensionList.firstOrNull()?.findBranchPullRequest(branchName)
        ?: BranchPullRequestResult.Failed("The GitHub integration plugin is not available")

/**
 * Reads the code owners review of a Kotlin pull request through the GitHub plugin, like
 * [findOpenKotlinPullRequest].
 */
internal suspend fun findKotlinCodeOwnersReview(pullRequest: OpenKotlinPullRequest): CodeOwnersReviewResult =
    GITHUB_PULL_REQUEST_SOURCE_EP.extensionList.firstOrNull()?.findCodeOwnersReview(pullRequest)
        ?: CodeOwnersReviewResult.Failed("The GitHub integration plugin is not available")

internal fun githubPullRequestIcon(): Icon? =
    GITHUB_PULL_REQUEST_SOURCE_EP.extensionList.firstOrNull()?.pullRequestIcon

/**
 * Whether the GitHub plugin's "Pull requests" tool window can open the Kotlin pull requests of this
 * project; without the GitHub integration plugin it cannot.
 */
internal fun canOpenKotlinPullRequestInToolWindow(project: Project): Boolean =
    GITHUB_PULL_REQUEST_SOURCE_EP.extensionList.firstOrNull()?.isPullRequestToolWindowAvailable(project) ?: false

/**
 * Opens a branch's pull request in the GitHub plugin's "Pull requests" tool window, which, like
 * [findOpenKotlinPullRequest], only the GitHub integration can.
 */
internal suspend fun openKotlinPullRequestInToolWindow(
    project: Project,
    pullRequest: BranchPullRequest,
): OpenPullRequestInToolWindowResult =
    GITHUB_PULL_REQUEST_SOURCE_EP.extensionList.firstOrNull()?.openPullRequestInToolWindow(project, pullRequest)
        ?: OpenPullRequestInToolWindowResult.Failed("The GitHub integration plugin is not available")

private val GITHUB_PULL_REQUEST_SOURCE_EP = ExtensionPointName<GitHubPullRequestSource>(
    "org.jetbrains.kotlin.compiler.devkit.githubPullRequestSource"
)
