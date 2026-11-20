package org.jetbrains.kotlin.test.helper.teamcity

import com.intellij.xml.util.XmlStringUtil
import org.jetbrains.kotlin.test.helper.github.BranchPullRequest
import org.jetbrains.kotlin.test.helper.github.CodeOwnersApproval
import org.jetbrains.kotlin.test.helper.github.CodeOwnersReview
import org.jetbrains.kotlin.test.helper.github.CodeOwnersRule
import org.jetbrains.kotlin.test.helper.github.MergedKotlinPullRequest
import org.jetbrains.kotlin.test.helper.github.OpenKotlinPullRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class CodeOwnersReviewBadgeTest {
    @Test
    fun anApprovedReviewIsReadyToMerge() {
        val review = review(
            rule(CodeOwnersApproval.APPROVED, reviewers = listOf("alice")),
            rule(CodeOwnersApproval.APPROVED, reviewers = listOf("bob")),
        )

        assertEquals(listOf("Approved, ready to merge"), codeOwnersReviewTooltipLines(review))
    }

    @Test
    fun listsWhoRequestedChangesWhoseReviewIsPendingAndWhichSubsystemsHaveNoReviewer() {
        val review = review(
            rule(CodeOwnersApproval.APPROVED, reviewers = listOf("alice")),
            rule(CodeOwnersApproval.CHANGES_REQUESTED, reviewers = listOf("dave")),
            rule(CodeOwnersApproval.CHANGES_REQUESTED, reviewers = listOf("dave", "carol")),
            rule(CodeOwnersApproval.REVIEW_REQUESTED, reviewers = listOf("erin", "frank")),
            rule(CodeOwnersApproval.COMMENTED, reviewers = listOf("grace", "erin")),
            rule(CodeOwnersApproval.NO_REVIEWER, ownerTeams = listOf("kotlin-frontend", "kotlin-jvm")),
            rule(CodeOwnersApproval.NO_REVIEWER, paths = listOf("/plugins/kotlinx-serialization/")),
            rule(
                CodeOwnersApproval.NO_REVIEWER,
                ownerTeams = listOf("kotlin-frontend"),
                paths = listOf("/plugins/scripting/"),
            ),
        )

        assertEquals(
            listOf(
                "Changes requested by @dave, @carol",
                "Waiting for review from @erin, @frank, @grace",
                "Missing reviewers for subsystems " +
                    "\"kotlin-frontend\", \"kotlin-jvm\", \"/plugins/kotlinx-serialization/\"",
            ),
            codeOwnersReviewTooltipLines(review),
        )
    }

    @Test
    fun leavesOutTheLinesWithNothingToList() {
        val review = review(
            rule(CodeOwnersApproval.APPROVED, reviewers = listOf("alice")),
            rule(CodeOwnersApproval.NO_REVIEWER, ownerTeams = listOf("kotlin-build-tools")),
        )

        assertEquals(
            listOf("Missing reviewers for subsystems \"kotlin-build-tools\""),
            codeOwnersReviewTooltipLines(review),
        )
    }

    @Test
    fun mentionsTeamReviewersLikeGitHub() {
        val review = review(rule(CodeOwnersApproval.REVIEW_REQUESTED, reviewers = listOf("JetBrains/kotlin-js")))

        assertEquals(listOf("Waiting for review from @JetBrains/kotlin-js"), codeOwnersReviewTooltipLines(review))
    }

    @Test
    fun keepsTheLinesOfRulesThatNameNobody() {
        val review = review(
            rule(CodeOwnersApproval.CHANGES_REQUESTED),
            rule(CodeOwnersApproval.REVIEW_REQUESTED),
            rule(CodeOwnersApproval.NO_REVIEWER),
        )

        assertEquals(
            listOf("Changes requested", "Waiting for review", "Missing reviewers"),
            codeOwnersReviewTooltipLines(review),
        )
    }

    @Test
    fun explainsAMissingOrUnreadableComment() {
        assertEquals(
            listOf(
                "The kotlin-safemerge bot has not posted its code owners comment; " +
                    "comment /codeowners on the pull request to request it."
            ),
            codeOwnersReviewTooltipLines(CodeOwnersReview.MissingComment),
        )
        assertEquals(
            listOf("The code owners comment of the kotlin-safemerge bot could not be read."),
            codeOwnersReviewTooltipLines(CodeOwnersReview.UnreadableComment("unknown approval")),
        )
    }

    @Test
    fun theTooltipEscapesTheTextOfTheComment() {
        val review = review(
            rule(CodeOwnersApproval.CHANGES_REQUESTED, reviewers = listOf("dave")),
            rule(CodeOwnersApproval.NO_REVIEWER, paths = listOf("/a<b>&c/")),
        )

        assertEquals(
            XmlStringUtil.wrapInHtml(
                "Changes requested by @dave<br>Missing reviewers for subsystems &quot;/a&lt;b&gt;&amp;c/&quot;"
            ),
            codeOwnersReviewBadge(review).toolTipText,
        )
    }

    @Test
    fun theIconShowsTheIndicatorOfTheReview() {
        assertSame(CodeOwnersReviewIcons.Approved, iconOf(CodeOwnersApproval.APPROVED))
        assertSame(
            CodeOwnersReviewIcons.ChangesRequested,
            iconOf(CodeOwnersApproval.NO_REVIEWER, CodeOwnersApproval.CHANGES_REQUESTED),
        )
        assertSame(
            CodeOwnersReviewIcons.AwaitingReview,
            iconOf(CodeOwnersApproval.NO_REVIEWER, CodeOwnersApproval.COMMENTED)
        )
        assertSame(
            CodeOwnersReviewIcons.MissingReviewer,
            iconOf(CodeOwnersApproval.APPROVED, CodeOwnersApproval.NO_REVIEWER)
        )
        assertSame(CodeOwnersReviewIcons.MissingComment, codeOwnersReviewBadge(CodeOwnersReview.MissingComment).icon)
        assertSame(
            CodeOwnersReviewIcons.MissingComment,
            codeOwnersReviewBadge(CodeOwnersReview.UnreadableComment("no table")).icon,
        )
    }

    @Test
    fun aMergedPullRequestShowsTheBranchItWasMergedInto() {
        val badge = mergedPullRequestBadge(mergedPullRequest(baseBranchName = "2.3.20"))

        assertSame(CodeOwnersReviewIcons.Merged, badge.icon)
        assertEquals(XmlStringUtil.wrapInHtml("Merged into 2.3.20"), badge.toolTipText)
    }

    @Test
    fun anOpenPullRequestShowsItsReviewOnceReadAndAMergedOneItsMerge() {
        val openPullRequest = OpenKotlinPullRequest(
            url = "https://github.com/JetBrains/kotlin/pull/42",
            headSha = "0123456789abcdef0123456789abcdef01234567",
            number = 42,
        )
        val review = review(rule(CodeOwnersApproval.APPROVED, reviewers = listOf("alice")))

        assertNull(pullRequestReviewBadge(BranchPullRequest.Open(openPullRequest, codeOwnersReview = null)))
        assertEquals(
            codeOwnersReviewBadge(review),
            pullRequestReviewBadge(BranchPullRequest.Open(openPullRequest, review)),
        )
        assertEquals(
            mergedPullRequestBadge(mergedPullRequest(baseBranchName = "master")),
            pullRequestReviewBadge(BranchPullRequest.Merged(mergedPullRequest(baseBranchName = "master"))),
        )
    }

    private fun iconOf(vararg approvals: CodeOwnersApproval) =
        codeOwnersReviewBadge(review(*approvals.map { approval -> rule(approval) }.toTypedArray())).icon

    private fun mergedPullRequest(baseBranchName: String) = MergedKotlinPullRequest(
        url = "https://github.com/JetBrains/kotlin/pull/41",
        number = 41,
        baseBranchName = baseBranchName,
    )

    private fun review(vararg rules: CodeOwnersRule) = CodeOwnersReview.Rules(rules.toList())

    private fun rule(
        approval: CodeOwnersApproval,
        reviewers: List<String> = emptyList(),
        ownerTeams: List<String> = emptyList(),
        paths: List<String> = emptyList(),
    ) = CodeOwnersRule(paths = paths, ownerTeams = ownerTeams, approval = approval, reviewers = reviewers)
}
