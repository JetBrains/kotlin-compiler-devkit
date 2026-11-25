package org.jetbrains.kotlin.test.helper.github

import com.intellij.collaboration.api.dto.GraphQLCursorPageInfoDTO
import com.intellij.collaboration.api.dto.GraphQLNodesDTO
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.plugins.github.GithubIcons
import org.jetbrains.plugins.github.api.data.GHRepository
import org.jetbrains.plugins.github.api.data.GHRepositoryOwnerName
import org.jetbrains.plugins.github.api.data.GHReactable
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequest
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequestMergeableState
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequestReviewDecision
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequestState
import java.net.URI
import java.net.URLDecoder
import java.util.Date

class GitHubPullRequestSourceTest : BasePlatformTestCase() {
    fun testPullRequestIconComesFromGitHubIntegration() {
        assertSame(GithubIcons.PullRequestsToolWindow, githubPullRequestIcon())
    }

    fun testMapsAKotlinPullRequestToItsUrlAndHeadSha() {
        val result = pullRequest().toOpenKotlinPullRequestResult("sellmair/remote-run")

        assertEquals(
            OpenKotlinPullRequestResult.Found(
                OpenKotlinPullRequest("https://github.com/JetBrains/kotlin/pull/42", headSha, number = 42)
            ),
            result,
        )
    }

    fun testPullRequestListingUrlUsesOwnerAndEncodesBranchAsSingleQueryValue() {
        val branchName = "feature/topic?x=1&y=two"
        val queryParameters = queryParameters(githubPullRequestsListingUrl("https://api.github.com", branchName))

        assertEquals("JetBrains:$branchName", queryParameters["head"])
        assertEquals(2, queryParameters.size)
    }

    fun testBranchPullRequestsUrlListsTheNewestPullRequestsOfEveryState() {
        val branchName = "feature/topic?x=1&y=two"
        val listingUrl = githubBranchPullRequestsUrl("https://api.github.com", branchName)

        assertEquals("/repos/JetBrains/kotlin/pulls", URI(listingUrl).rawPath)
        assertEquals(
            mapOf(
                "state" to "all",
                "head" to "JetBrains:$branchName",
                "sort" to "created",
                "direction" to "desc",
                "per_page" to "100",
            ),
            queryParameters(listingUrl),
        )
    }

    fun testIssueCommentsUrlListsTheCommentsOfThePullRequestInLargePages() {
        assertEquals(
            "https://api.github.com/repos/JetBrains/kotlin/issues/42/comments?per_page=100",
            githubIssueCommentsUrl("https://api.github.com", 42),
        )
    }

    fun testPullRequestUrlReadsTheSinglePullRequest() {
        assertEquals(
            "https://api.github.com/repos/JetBrains/kotlin/pulls/42",
            githubPullRequestUrl("https://api.github.com", 42),
        )
    }

    fun testAMissingPullRequestIsAnInvalidResponse() {
        assertEquals(OpenKotlinPullRequestResult.InvalidResponse, null.toOpenKotlinPullRequestResult("any"))
    }

    fun testADifferentHeadBranchIsAnInvalidResponse() {
        val result = pullRequest().toOpenKotlinPullRequestResult("sellmair/other-branch")

        assertEquals(OpenKotlinPullRequestResult.InvalidResponse, result)
    }

    fun testAPullRequestWithoutAKotlinHeadRepositoryIsAnInvalidResponse() {
        val result = pullRequest(headRepository = null).toOpenKotlinPullRequestResult("sellmair/remote-run")

        assertEquals(OpenKotlinPullRequestResult.InvalidResponse, result)
    }

    fun testAPullRequestIntoAnotherRepositoryIsNoKotlinPullRequest() {
        val result = pullRequest(baseRepository = "fork/kotlin").toOpenKotlinPullRequestResult("sellmair/remote-run")

        assertEquals(OpenKotlinPullRequestResult.NotFound, result)
    }

    fun testAPullRequestWithoutABaseRepositoryIsAnInvalidResponse() {
        val result = pullRequest(baseRepository = null).toOpenKotlinPullRequestResult("sellmair/remote-run")

        assertEquals(OpenKotlinPullRequestResult.InvalidResponse, result)
    }

    fun testAPullRequestWithAnUnexpectedUrlIsAnInvalidResponse() {
        val result = pullRequest(url = "https://example.com/pull/42").toOpenKotlinPullRequestResult("sellmair/remote-run")

        assertEquals(OpenKotlinPullRequestResult.InvalidResponse, result)
    }

    fun testAPullRequestWhoseUrlNamesAnotherNumberIsAnInvalidResponse() {
        val result = pullRequest(url = "https://github.com/JetBrains/kotlin/pull/43")
            .toOpenKotlinPullRequestResult("sellmair/remote-run")

        assertEquals(OpenKotlinPullRequestResult.InvalidResponse, result)
    }

    fun testAPullRequestWithAnInvalidHeadShaIsAnInvalidResponse() {
        val result = pullRequest(headRefOid = "not-a-sha").toOpenKotlinPullRequestResult("sellmair/remote-run")

        assertEquals(OpenKotlinPullRequestResult.InvalidResponse, result)
    }

    private val headSha = "0123456789abcdef0123456789abcdef01234567"

    private fun queryParameters(url: String): Map<String, String> =
        URI(url).rawQuery.split("&").associate { parameter ->
            val (name, value) = parameter.split("=", limit = 2)
            name to URLDecoder.decode(value, Charsets.UTF_8)
        }

    private fun kotlinRepository(nameWithOwner: String?): GHRepository? = nameWithOwner?.let {
        GHRepository(
            id = "repository-id",
            owner = GHRepositoryOwnerName.User("JetBrains"),
            nameWithOwner = it,
            viewerPermission = null,
            mergeCommitAllowed = true,
            squashMergeAllowed = true,
            rebaseMergeAllowed = true,
            defaultBranchRef = null,
            isFork = false,
            url = "https://github.com/$it",
            sshUrl = "",
        )
    }

    private fun pullRequest(
        headRefName: String = "sellmair/remote-run",
        headRefOid: String = headSha,
        headRepository: String? = "JetBrains/kotlin",
        baseRepository: String? = "JetBrains/kotlin",
        url: String = "https://github.com/JetBrains/kotlin/pull/42",
    ): GHPullRequest = GHPullRequest(
        id = "pull-request-id",
        url = url,
        number = 42,
        title = "Remote Run",
        state = GHPullRequestState.OPEN,
        isDraft = false,
        author = null,
        createdAt = Date(0),
        updatedAt = Date(0),
        assignees = GraphQLNodesDTO(),
        labels = GraphQLNodesDTO(),
        reviewRequests = GraphQLNodesDTO(),
        reviewThreads = GraphQLNodesDTO(),
        reviews = GraphQLNodesDTO(),
        reviewDecision = GHPullRequestReviewDecision.APPROVED,
        mergeable = GHPullRequestMergeableState.MERGEABLE,
        viewerCanUpdate = false,
        viewerCanReact = false,
        viewerDidAuthor = false,
        viewerCanDeleteHeadRef = false,
        body = "",
        baseRefName = "master",
        baseRefOid = headSha,
        baseRepository = kotlinRepository(baseRepository),
        headRefName = headRefName,
        headRefOid = headRefOid,
        headRef = null,
        headRepository = kotlinRepository(headRepository),
        reactions = GHReactable.ReactionConnection(GraphQLCursorPageInfoDTO(null, false, null, false)),
    )
}
