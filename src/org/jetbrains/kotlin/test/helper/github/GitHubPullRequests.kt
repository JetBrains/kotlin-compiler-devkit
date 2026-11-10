package org.jetbrains.kotlin.test.helper.github

internal data class OpenKotlinPullRequest(val url: String, val headSha: String)

internal sealed interface OpenKotlinPullRequestResult {
    data class Found(val pullRequest: OpenKotlinPullRequest) : OpenKotlinPullRequestResult
    data object NotFound : OpenKotlinPullRequestResult
    data object Ambiguous : OpenKotlinPullRequestResult
    data object InvalidResponse : OpenKotlinPullRequestResult
    data class Failed(val message: String) : OpenKotlinPullRequestResult
}
