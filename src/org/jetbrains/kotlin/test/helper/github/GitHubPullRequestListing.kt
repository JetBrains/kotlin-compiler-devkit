package org.jetbrains.kotlin.test.helper.github

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A pull request as GitHub's REST listing of a repository's pull requests returns it; only used to
 * resolve the pull request a branch is shown with.
 *
 * @property headRepository the `owner/name` of the head repository, which GitHub omits once it is deleted.
 */
internal data class ListedPullRequest(
    val number: Long,
    val url: String?,
    val isOpen: Boolean,
    val isMerged: Boolean,
    val headRefName: String?,
    val headRepository: String?,
    val baseRefName: String?,
    val baseRepository: String?,
)

/**
 * Reads one page of `GET /repos/{owner}/{repo}/pulls`. Null unless it is a JSON array of pull
 * requests that each have a number and a state: skipping one could skip the open pull request.
 */
internal fun parsePullRequestListing(json: String): List<ListedPullRequest>? {
    val elements = try {
        Json.parseToJsonElement(json) as? JsonArray
    } catch (_: SerializationException) {
        null
    } ?: return null

    return elements.map { element ->
        val pullRequest = element as? JsonObject ?: return null
        val head = pullRequest["head"] as? JsonObject
        val base = pullRequest["base"] as? JsonObject

        ListedPullRequest(
            number = pullRequest.longOrNull("number") ?: return null,
            url = pullRequest.stringOrNull("html_url"),
            isOpen = when (pullRequest.stringOrNull("state")) {
                "open" -> true
                "closed" -> false
                else -> return null
            },
            isMerged = pullRequest.stringOrNull("merged_at") != null,
            headRefName = head?.stringOrNull("ref"),
            headRepository = head?.repositoryName(),
            baseRefName = base?.stringOrNull("ref"),
            baseRepository = base?.repositoryName(),
        )
    }
}

/**
 * Resolves a branch from the listing of the pull requests with the branch as head. Its only open
 * pull request is read by [readOpenPullRequest], so it is resolved as strictly as by
 * [GitHubPullRequestSource.findOpenPullRequest]; without one, the latest pull request counts if it is merged.
 */
internal suspend fun branchPullRequestResult(
    pullRequests: List<ListedPullRequest>,
    branchName: String,
    readOpenPullRequest: suspend (number: Long) -> OpenKotlinPullRequestResult,
): BranchPullRequestResult {
    val openPullRequests = pullRequests.filter { pullRequest -> pullRequest.isOpen }

    return when (openPullRequests.size) {
        0 -> mergedPullRequestResult(pullRequests, branchName)
        1 -> readOpenPullRequest(openPullRequests.single().number).toBranchPullRequestResult()
        else -> BranchPullRequestResult.Ambiguous
    }
}

/**
 * Only the latest pull request of the branch decides — GitHub numbers pull requests in the order
 * they are opened: when it was closed without merging, the branch moved on from any earlier merged
 * one. Only pull requests whose head and base repository are both `JetBrains/kotlin` count.
 */
private fun mergedPullRequestResult(
    pullRequests: List<ListedPullRequest>,
    branchName: String,
): BranchPullRequestResult {
    val latest = pullRequests
        .filter { pullRequest ->
            pullRequest.headRefName == branchName &&
                pullRequest.headRepository == GITHUB_KOTLIN_REPOSITORY &&
                pullRequest.baseRepository == GITHUB_KOTLIN_REPOSITORY
        }
        .maxByOrNull { pullRequest -> pullRequest.number }

    if (latest == null || !latest.isMerged) return BranchPullRequestResult.NotFound

    val url = latest.url?.takeIf { url -> url == kotlinPullRequestUrl(latest.number) }
        ?: return BranchPullRequestResult.InvalidResponse
    val baseBranchName = latest.baseRefName?.takeIf(String::isNotEmpty)
        ?: return BranchPullRequestResult.InvalidResponse

    return BranchPullRequestResult.Merged(MergedKotlinPullRequest(url, latest.number, baseBranchName))
}

private fun JsonObject.repositoryName(): String? = (get("repo") as? JsonObject)?.stringOrNull("full_name")

private fun JsonObject.stringOrNull(key: String): String? {
    val value = get(key) as? JsonPrimitive ?: return null
    return if (value.isString) value.content else null
}

private fun JsonObject.longOrNull(key: String): Long? {
    val value = get(key) as? JsonPrimitive ?: return null
    return if (value.isString) null else value.content.toLongOrNull()
}
