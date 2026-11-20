package org.jetbrains.kotlin.test.helper.github

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * The fixtures follow GitHub's REST listing of a branch's pull requests, of which only a few fields
 * are read; merged pull requests are closed ones with a merge date.
 */
class GitHubPullRequestListingTest {
    @Test
    fun readsThePullRequestsOfTheListing() {
        val listing = listing(
            pullRequest(10007, state = "closed", mergedAt = "2026-11-20T11:13:21Z"),
            pullRequest(9982, state = "closed", headRepository = null),
        )

        assertEquals(
            listOf(
                ListedPullRequest(
                    number = 10007,
                    url = "https://github.com/JetBrains/kotlin/pull/10007",
                    isOpen = false,
                    isMerged = true,
                    headRefName = BRANCH_NAME,
                    headRepository = "JetBrains/kotlin",
                    baseRefName = "master",
                    baseRepository = "JetBrains/kotlin",
                ),
                ListedPullRequest(
                    number = 9982,
                    url = "https://github.com/JetBrains/kotlin/pull/9982",
                    isOpen = false,
                    isMerged = false,
                    headRefName = BRANCH_NAME,
                    headRepository = null,
                    baseRefName = "master",
                    baseRepository = "JetBrains/kotlin",
                ),
            ),
            parsePullRequestListing(listing),
        )
    }

    @Test
    fun aListingWithoutANumberAndAStateForEveryPullRequestIsUnreadable() {
        assertNull(parsePullRequestListing("not json"))
        assertNull(parsePullRequestListing("""{"message": "Not Found"}"""))
        assertNull(parsePullRequestListing("""[1]"""))
        assertNull(parsePullRequestListing(listing(pullRequest(42), """{"state": "open"}""")))
        assertNull(parsePullRequestListing("""[{"number": "42", "state": "open"}]"""))
        assertNull(parsePullRequestListing("""[{"number": 42, "state": "draft"}]"""))
        assertEquals(emptyList(), parsePullRequestListing("[]"))
    }

    @Test
    fun theOnlyOpenPullRequestIsResolvedByReadingIt() = runBlocking {
        val openPullRequest = OpenKotlinPullRequest(kotlinPullRequestUrl(42), HEAD_SHA, number = 42)
        val readNumbers = mutableListOf<Long>()
        val pullRequests = listedPullRequests(
            pullRequest(42),
            pullRequest(41, state = "closed", mergedAt = "2026-11-20T11:13:21Z"),
        )

        val result = branchPullRequestResult(pullRequests, BRANCH_NAME) { number ->
            readNumbers += number
            OpenKotlinPullRequestResult.Found(openPullRequest)
        }

        assertEquals(BranchPullRequestResult.Open(openPullRequest), result)
        assertEquals(listOf(42L), readNumbers)
    }

    @Test
    fun anOpenPullRequestThatCannotBeReadIsNoPullRequest() = runBlocking {
        val pullRequests = listedPullRequests(pullRequest(42))

        assertEquals(
            BranchPullRequestResult.InvalidResponse,
            branchPullRequestResult(pullRequests, BRANCH_NAME) { OpenKotlinPullRequestResult.InvalidResponse },
        )
        assertEquals(
            BranchPullRequestResult.Failed("offline"),
            branchPullRequestResult(pullRequests, BRANCH_NAME) { OpenKotlinPullRequestResult.Failed("offline") },
        )
    }

    @Test
    fun severalOpenPullRequestsAreAmbiguous() = runBlocking {
        val pullRequests = listedPullRequests(pullRequest(42), pullRequest(43))

        assertEquals(BranchPullRequestResult.Ambiguous, branchPullRequestResult(pullRequests, BRANCH_NAME, ::noRead))
    }

    @Test
    fun withoutAnOpenPullRequestTheLatestOneCountsIfItIsMerged() = runBlocking {
        val pullRequests = listedPullRequests(
            pullRequest(9982, state = "closed"),
            pullRequest(10007, state = "closed", mergedAt = "2026-11-20T11:13:21Z", baseRefName = "2.3.20"),
        )

        assertEquals(
            BranchPullRequestResult.Merged(
                MergedKotlinPullRequest(kotlinPullRequestUrl(10007), number = 10007, baseBranchName = "2.3.20")
            ),
            branchPullRequestResult(pullRequests, BRANCH_NAME, ::noRead),
        )
    }

    @Test
    fun aLaterPullRequestClosedWithoutMergingMeansTheBranchMovedOn() = runBlocking {
        val pullRequests = listedPullRequests(
            pullRequest(9982, state = "closed", mergedAt = "2026-11-19T15:49:00Z"),
            pullRequest(10007, state = "closed"),
        )

        assertEquals(BranchPullRequestResult.NotFound, branchPullRequestResult(pullRequests, BRANCH_NAME, ::noRead))
    }

    @Test
    fun pullRequestsFromOtherRepositoriesOrBranchesDoNotCount() = runBlocking {
        val mergedAt = "2026-11-20T11:13:21Z"
        val pullRequests = listedPullRequests(
            pullRequest(9982, state = "closed", mergedAt = mergedAt),
            pullRequest(10001, state = "closed", headRepository = "someone/kotlin"),
            pullRequest(10002, state = "closed", headRepository = null),
            pullRequest(10003, state = "closed", headRefName = "other-branch"),
            pullRequest(10004, state = "closed", baseRepository = "someone/kotlin"),
        )

        assertEquals(
            BranchPullRequestResult.Merged(
                MergedKotlinPullRequest(kotlinPullRequestUrl(9982), number = 9982, baseBranchName = "master")
            ),
            branchPullRequestResult(pullRequests, BRANCH_NAME, ::noRead),
        )
        assertEquals(BranchPullRequestResult.NotFound, branchPullRequestResult(emptyList(), BRANCH_NAME, ::noRead))
    }

    @Test
    fun aMergedPullRequestWithAnUnexpectedUrlOrWithoutABaseBranchIsAnInvalidResponse() = runBlocking {
        val mergedAt = "2026-11-20T11:13:21Z"

        assertEquals(
            BranchPullRequestResult.InvalidResponse,
            branchPullRequestResult(
                listedPullRequests(
                    pullRequest(42, state = "closed", mergedAt = mergedAt, url = kotlinPullRequestUrl(43))
                ),
                BRANCH_NAME,
                ::noRead,
            ),
        )
        assertEquals(
            BranchPullRequestResult.InvalidResponse,
            branchPullRequestResult(
                listedPullRequests(pullRequest(42, state = "closed", mergedAt = mergedAt, baseRefName = null)),
                BRANCH_NAME,
                ::noRead,
            ),
        )
    }

    private fun noRead(number: Long): OpenKotlinPullRequestResult = fail("Pull request #$number must not be read")

    private fun listedPullRequests(vararg pullRequests: String): List<ListedPullRequest> =
        parsePullRequestListing(listing(*pullRequests)) ?: fail("The listing must be readable")

    private fun listing(vararg pullRequests: String): String = pullRequests.joinToString(prefix = "[", postfix = "]")

    private fun pullRequest(
        number: Long,
        state: String = "open",
        mergedAt: String? = null,
        url: String = kotlinPullRequestUrl(number),
        headRefName: String = BRANCH_NAME,
        headRepository: String? = "JetBrains/kotlin",
        baseRefName: String? = "master",
        baseRepository: String = "JetBrains/kotlin",
    ): String = """
        {
          "number": $number,
          "state": "$state",
          "html_url": "$url",
          "merged_at": ${mergedAt?.let { "\"$it\"" } ?: "null"},
          "head": {"ref": "$headRefName", "sha": "$HEAD_SHA", "repo": ${repository(headRepository)}},
          "base": {"ref": ${baseRefName?.let { "\"$it\"" } ?: "null"}, "repo": ${repository(baseRepository)}}
        }
    """.trimIndent()

    private fun repository(nameWithOwner: String?): String =
        nameWithOwner?.let { """{"full_name": "$it"}""" } ?: "null"

    private companion object {
        const val BRANCH_NAME = "rr/alice/KT-12345"
        const val HEAD_SHA = "0123456789abcdef0123456789abcdef01234567"
    }
}
