package org.jetbrains.kotlin.test.helper.teamcity

import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.StringUtil
import com.intellij.xml.util.XmlStringUtil
import org.jetbrains.kotlin.test.helper.MyBundle
import org.jetbrains.kotlin.test.helper.github.BranchPullRequest
import org.jetbrains.kotlin.test.helper.github.CodeOwnersApproval
import org.jetbrains.kotlin.test.helper.github.CodeOwnersReview
import org.jetbrains.kotlin.test.helper.github.CodeOwnersReviewIndicator
import org.jetbrains.kotlin.test.helper.github.CodeOwnersRule
import org.jetbrains.kotlin.test.helper.github.MergedKotlinPullRequest
import org.jetbrains.kotlin.test.helper.github.codeOwnersReviewIndicator
import javax.swing.Icon

/**
 * Icons for the review status of a branch's pull request — its code owners review, or its merge —
 * shown next to its pull request badge in the Remote Run tool window. The `_dark` variants next to
 * each path are picked up by [IconLoader] automatically.
 */
internal object CodeOwnersReviewIcons {
    private const val MISSING_REVIEWER_PATH = "/icons/codeOwnersMissingReviewer.svg"
    private const val AWAITING_REVIEW_PATH = "/icons/codeOwnersAwaitingReview.svg"
    private const val CHANGES_REQUESTED_PATH = "/icons/codeOwnersChangesRequested.svg"
    private const val APPROVED_PATH = "/icons/codeOwnersApproved.svg"
    private const val MISSING_COMMENT_PATH = "/icons/codeOwnersMissingComment.svg"
    private const val MERGED_PATH = "/icons/pullRequestMerged.svg"

    /**
     * A white circle: a code owners rule has no reviewer.
     */
    @JvmField
    val MissingReviewer: Icon = IconLoader.getIcon(MISSING_REVIEWER_PATH, CodeOwnersReviewIcons::class.java)

    /**
     * A yellow circle: a review is requested, or a code owner only commented.
     */
    @JvmField
    val AwaitingReview: Icon = IconLoader.getIcon(AWAITING_REVIEW_PATH, CodeOwnersReviewIcons::class.java)

    /**
     * A red circle: a code owner requested changes.
     */
    @JvmField
    val ChangesRequested: Icon = IconLoader.getIcon(CHANGES_REQUESTED_PATH, CodeOwnersReviewIcons::class.java)

    /**
     * A green circle: every code owners rule is approved.
     */
    @JvmField
    val Approved: Icon = IconLoader.getIcon(APPROVED_PATH, CodeOwnersReviewIcons::class.java)

    /**
     * A red cross: the code owners comment of the `kotlin-safemerge` bot is missing or unreadable.
     */
    @JvmField
    val MissingComment: Icon = IconLoader.getIcon(MISSING_COMMENT_PATH, CodeOwnersReviewIcons::class.java)

    /**
     * A purple circle: the pull request is merged, so its review is over.
     */
    @JvmField
    val Merged: Icon = IconLoader.getIcon(MERGED_PATH, CodeOwnersReviewIcons::class.java)
}

/**
 * The review status as a branch row shows it: the status icon and the tooltip of that icon.
 */
internal data class RemoteRunReviewBadge(val icon: Icon, val toolTipText: String)

/**
 * The code owners review of an open pull request, once it could be read, or the merge of a merged one.
 */
internal fun pullRequestReviewBadge(pullRequest: BranchPullRequest): RemoteRunReviewBadge? = when (pullRequest) {
    is BranchPullRequest.Open -> pullRequest.codeOwnersReview?.let(::codeOwnersReviewBadge)
    is BranchPullRequest.Merged -> mergedPullRequestBadge(pullRequest.pullRequest)
}

internal fun codeOwnersReviewBadge(review: CodeOwnersReview): RemoteRunReviewBadge = RemoteRunReviewBadge(
    icon = codeOwnersReviewIcon(codeOwnersReviewIndicator(review)),
    toolTipText = reviewToolTipText(codeOwnersReviewTooltipLines(review)),
)

/**
 * A merged pull request replaces the review status, whatever its review was.
 */
internal fun mergedPullRequestBadge(pullRequest: MergedKotlinPullRequest): RemoteRunReviewBadge =
    RemoteRunReviewBadge(
        icon = CodeOwnersReviewIcons.Merged,
        toolTipText = reviewToolTipText(
            listOf(MyBundle.message("teamcity.window.branch.review.merged", pullRequest.baseBranchName))
        ),
    )

private fun reviewToolTipText(lines: List<String>): String =
    XmlStringUtil.wrapInHtml(lines.joinToString("<br>") { line -> StringUtil.escapeXmlEntities(line) })

internal fun codeOwnersReviewIcon(indicator: CodeOwnersReviewIndicator): Icon = when (indicator) {
    CodeOwnersReviewIndicator.MISSING_REVIEWER -> CodeOwnersReviewIcons.MissingReviewer
    CodeOwnersReviewIndicator.AWAITING_REVIEW -> CodeOwnersReviewIcons.AwaitingReview
    CodeOwnersReviewIndicator.CHANGES_REQUESTED -> CodeOwnersReviewIcons.ChangesRequested
    CodeOwnersReviewIndicator.APPROVED -> CodeOwnersReviewIcons.Approved
    CodeOwnersReviewIndicator.MISSING_COMMENT -> CodeOwnersReviewIcons.MissingComment
}

/**
 * The full review status, whichever icon it shows: who requested changes, whose review is pending
 * (commented reviews included, since their approval still is) and which subsystems have no
 * reviewer. A subsystem is a team owning the rule, or the rule's paths when no team owns it.
 */
internal fun codeOwnersReviewTooltipLines(review: CodeOwnersReview): List<String> = when (review) {
    CodeOwnersReview.MissingComment -> listOf(MyBundle.message("teamcity.window.branch.review.missing.comment"))
    is CodeOwnersReview.UnreadableComment -> listOf(MyBundle.message("teamcity.window.branch.review.unreadable.comment"))
    is CodeOwnersReview.Rules -> codeOwnersRulesTooltipLines(review.rules)
}

private fun codeOwnersRulesTooltipLines(rules: List<CodeOwnersRule>): List<String> {
    if (rules.all { rule -> rule.approval == CodeOwnersApproval.APPROVED }) {
        return listOf(MyBundle.message("teamcity.window.branch.review.approved"))
    }

    val changesRequested = rules.filter { rule -> rule.approval == CodeOwnersApproval.CHANGES_REQUESTED }
    val awaitingReview = rules.filter { rule ->
        rule.approval == CodeOwnersApproval.REVIEW_REQUESTED || rule.approval == CodeOwnersApproval.COMMENTED
    }
    val missingReviewer = rules.filter { rule -> rule.approval == CodeOwnersApproval.NO_REVIEWER }

    return listOfNotNull(
        changesRequested.takeIf { it.isNotEmpty() }?.let { changesRequestedRules ->
            reviewerMentions(changesRequestedRules)
                ?.let { mentions -> MyBundle.message("teamcity.window.branch.review.changes.requested", mentions) }
                ?: MyBundle.message("teamcity.window.branch.review.changes.requested.anonymous")
        },
        awaitingReview.takeIf { it.isNotEmpty() }?.let { awaitingReviewRules ->
            reviewerMentions(awaitingReviewRules)
                ?.let { mentions -> MyBundle.message("teamcity.window.branch.review.waiting", mentions) }
                ?: MyBundle.message("teamcity.window.branch.review.waiting.anonymous")
        },
        missingReviewer.takeIf { it.isNotEmpty() }?.let { missingReviewerRules ->
            subsystemNames(missingReviewerRules)
                ?.let { subsystems -> MyBundle.message("teamcity.window.branch.review.missing.reviewers", subsystems) }
                ?: MyBundle.message("teamcity.window.branch.review.missing.reviewers.anonymous")
        },
    )
}

private fun reviewerMentions(rules: List<CodeOwnersRule>): String? =
    rules.flatMap { rule -> rule.reviewers }
        .distinct()
        .takeIf { it.isNotEmpty() }
        ?.joinToString { reviewer -> "@$reviewer" }

private fun subsystemNames(rules: List<CodeOwnersRule>): String? =
    rules.flatMap { rule -> rule.ownerTeams.ifEmpty { rule.paths } }
        .distinct()
        .takeIf { it.isNotEmpty() }
        ?.joinToString { subsystem -> "\"$subsystem\"" }
