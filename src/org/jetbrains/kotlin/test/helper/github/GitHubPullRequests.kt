package org.jetbrains.kotlin.test.helper.github

internal const val GITHUB_KOTLIN_REPOSITORY_OWNER: String = "JetBrains"
internal const val GITHUB_KOTLIN_REPOSITORY_NAME: String = "kotlin"
internal const val GITHUB_KOTLIN_REPOSITORY: String = "$GITHUB_KOTLIN_REPOSITORY_OWNER/$GITHUB_KOTLIN_REPOSITORY_NAME"

/**
 * The web URL of a `JetBrains/kotlin` pull request; a pull request with any other URL is a malformed answer.
 */
internal fun kotlinPullRequestUrl(number: Long): String = "https://github.com/$GITHUB_KOTLIN_REPOSITORY/pull/$number"

internal data class OpenKotlinPullRequest(val url: String, val headSha: String, val number: Long)

internal sealed interface OpenKotlinPullRequestResult {
    data class Found(val pullRequest: OpenKotlinPullRequest) : OpenKotlinPullRequestResult
    data object NotFound : OpenKotlinPullRequestResult
    data object Ambiguous : OpenKotlinPullRequestResult
    data object InvalidResponse : OpenKotlinPullRequestResult
    data class Failed(val message: String) : OpenKotlinPullRequestResult
}

/**
 * A merged pull request of a branch.
 *
 * @property baseBranchName the branch the pull request was merged into.
 */
internal data class MergedKotlinPullRequest(val url: String, val number: Long, val baseBranchName: String)

/**
 * The pull request a branch is shown with: its open pull request or, when it has none, its latest
 * pull request if that one is merged. As for [OpenKotlinPullRequestResult], ambiguous, unreadable
 * or failed lookups must never be treated as a pull request.
 */
internal sealed interface BranchPullRequestResult {
    data class Open(val pullRequest: OpenKotlinPullRequest) : BranchPullRequestResult
    data class Merged(val pullRequest: MergedKotlinPullRequest) : BranchPullRequestResult
    data object NotFound : BranchPullRequestResult
    data object Ambiguous : BranchPullRequestResult
    data object InvalidResponse : BranchPullRequestResult
    data class Failed(val message: String) : BranchPullRequestResult
}

internal fun OpenKotlinPullRequestResult.toBranchPullRequestResult(): BranchPullRequestResult = when (this) {
    is OpenKotlinPullRequestResult.Found -> BranchPullRequestResult.Open(pullRequest)
    OpenKotlinPullRequestResult.NotFound -> BranchPullRequestResult.NotFound
    OpenKotlinPullRequestResult.Ambiguous -> BranchPullRequestResult.Ambiguous
    OpenKotlinPullRequestResult.InvalidResponse -> BranchPullRequestResult.InvalidResponse
    is OpenKotlinPullRequestResult.Failed -> BranchPullRequestResult.Failed(message)
}

/**
 * The pull request a listed branch is shown with.
 */
internal sealed interface BranchPullRequest {
    val url: String

    /**
     * The open pull request of the branch with its code owners review, which is null until it could
     * be read once.
     */
    data class Open(
        val pullRequest: OpenKotlinPullRequest,
        val codeOwnersReview: CodeOwnersReview?,
    ) : BranchPullRequest {
        override val url: String get() = pullRequest.url
    }

    /**
     * The merged pull request of a branch without an open one; it has no review left to read.
     */
    data class Merged(val pullRequest: MergedKotlinPullRequest) : BranchPullRequest {
        override val url: String get() = pullRequest.url
    }
}

internal sealed interface CodeOwnersReviewResult {
    data class Found(val review: CodeOwnersReview) : CodeOwnersReviewResult
    data class Failed(val message: String) : CodeOwnersReviewResult
}
