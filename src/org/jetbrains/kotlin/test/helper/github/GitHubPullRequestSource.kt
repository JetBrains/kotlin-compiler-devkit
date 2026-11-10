package org.jetbrains.kotlin.test.helper.github

import com.intellij.openapi.extensions.ExtensionPointName
import javax.swing.Icon

internal interface GitHubPullRequestSource {
    val pullRequestIcon: Icon? get() = null

    /**
     * Resolves the open `JetBrains/kotlin` pull request whose head is [branchName], including its
     * pinned head SHA. Missing, ambiguous or failed lookups must never be treated as a pull request.
     */
    suspend fun findOpenPullRequest(branchName: String): OpenKotlinPullRequestResult
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

internal fun githubPullRequestIcon(): Icon? =
    GITHUB_PULL_REQUEST_SOURCE_EP.extensionList.firstOrNull()?.pullRequestIcon

private val GITHUB_PULL_REQUEST_SOURCE_EP = ExtensionPointName<GitHubPullRequestSource>(
    "org.jetbrains.kotlin.compiler.devkit.githubPullRequestSource"
)
