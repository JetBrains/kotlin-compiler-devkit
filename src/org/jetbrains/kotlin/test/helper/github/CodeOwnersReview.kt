package org.jetbrains.kotlin.test.helper.github

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * The REST login of the `kotlin-safemerge` GitHub App. Only apps have the `[bot]` suffix, so no user
 * account can post a comment under this login.
 */
internal const val SAFE_MERGE_BOT_LOGIN: String = "kotlin-safemerge[bot]"

/**
 * The approval of one code owners rule, as the Approval column of the bot's comment reports it.
 */
internal enum class CodeOwnersApproval {
    APPROVED,
    CHANGES_REQUESTED,
    REVIEW_REQUESTED,

    /**
     * A code owner reviewed with comments only, so their approval is still pending.
     */
    COMMENTED,
    NO_REVIEWER,
}

/**
 * One row of the code owners table.
 *
 * @property paths the patterns of the Rule column.
 * @property ownerTeams the GitHub teams owning the rule; the sub-teams listed inside a team are not included.
 * @property reviewers the GitHub logins of the Approval column, or `<organization>/<team>` for teams.
 */
internal data class CodeOwnersRule(
    val paths: List<String>,
    val ownerTeams: List<String>,
    val approval: CodeOwnersApproval,
    val reviewers: List<String>,
)

/**
 * The code owners review of a pull request, read from the "Code Owners" comment of the
 * `kotlin-safemerge` bot rather than from GitHub's reviewers.
 */
internal sealed interface CodeOwnersReview {
    data class Rules(val rules: List<CodeOwnersRule>) : CodeOwnersReview

    /**
     * The bot has not commented on the pull request: something is wrong with the merge infrastructure.
     */
    data object MissingComment : CodeOwnersReview

    /**
     * The comment is not in the expected format, so its approvals cannot be trusted.
     */
    data class UnreadableComment(val reason: String) : CodeOwnersReview
}

/**
 * The status the Remote Run tool window shows for a [CodeOwnersReview].
 */
internal enum class CodeOwnersReviewIndicator {
    MISSING_REVIEWER,
    AWAITING_REVIEW,
    CHANGES_REQUESTED,
    APPROVED,
    MISSING_COMMENT,
}

/**
 * Requested changes win over pending reviews, which win over rules without a reviewer; the review
 * is approved only when every rule is.
 */
internal fun codeOwnersReviewIndicator(review: CodeOwnersReview): CodeOwnersReviewIndicator = when (review) {
    CodeOwnersReview.MissingComment, is CodeOwnersReview.UnreadableComment -> CodeOwnersReviewIndicator.MISSING_COMMENT

    is CodeOwnersReview.Rules -> {
        val approvals = review.rules.mapTo(mutableSetOf()) { rule -> rule.approval }
        when {
            CodeOwnersApproval.CHANGES_REQUESTED in approvals -> CodeOwnersReviewIndicator.CHANGES_REQUESTED

            CodeOwnersApproval.REVIEW_REQUESTED in approvals || CodeOwnersApproval.COMMENTED in approvals ->
                CodeOwnersReviewIndicator.AWAITING_REVIEW

            CodeOwnersApproval.NO_REVIEWER in approvals -> CodeOwnersReviewIndicator.MISSING_REVIEWER
            else -> CodeOwnersReviewIndicator.APPROVED
        }
    }
}

/**
 * A pull request comment as GitHub's REST API returns it; only used to find the bot's comment.
 */
internal data class GitHubIssueComment(
    val authorLogin: String?,
    val authorType: String?,
    val body: String,
    val updatedAt: String?,
)

/**
 * Reads one page of `GET /repos/{owner}/{repo}/issues/{number}/comments`; null when it is not a
 * JSON array. Elements without a body are skipped.
 */
internal fun parseIssueComments(json: String): List<GitHubIssueComment>? {
    val elements = try {
        Json.parseToJsonElement(json) as? JsonArray
    } catch (_: SerializationException) {
        null
    } ?: return null

    return elements.mapNotNull { element ->
        val comment = element as? JsonObject ?: return@mapNotNull null
        val user = comment["user"] as? JsonObject

        GitHubIssueComment(
            authorLogin = user?.stringOrNull("login"),
            authorType = user?.stringOrNull("type"),
            body = comment.stringOrNull("body") ?: return@mapNotNull null,
            updatedAt = comment.stringOrNull("updated_at"),
        )
    }
}

/**
 * The bot's code owners comment starts with a "Code Owners" header and carries a marker comment;
 * either identifies it among the bot's comments.
 */
internal fun isCodeOwnersComment(comment: GitHubIssueComment): Boolean {
    if (comment.authorLogin != SAFE_MERGE_BOT_LOGIN || comment.authorType != "Bot") return false

    val firstLine = comment.body.lineSequence().firstOrNull { line -> line.isNotBlank() }?.trim()
    val startsWithHeader = firstLine != null && codeOwnersHeaderPattern.matches(firstLine)

    return startsWithHeader || CODE_OWNERS_COMMENT_MARKER in comment.body
}

/**
 * Reads the bot's latest code owners comment among [comments]. The bot edits its comment in place,
 * but should there be several, the most recently updated one wins.
 */
internal fun codeOwnersReviewOf(comments: List<GitHubIssueComment>): CodeOwnersReview {
    val comment = comments.filter(::isCodeOwnersComment)
        .maxByOrNull { codeOwnersComment -> codeOwnersComment.updatedAt.orEmpty() }
        ?: return CodeOwnersReview.MissingComment

    return parseCodeOwnersComment(comment.body)
}

/**
 * Reads the code owners table: the first HTML table of the comment, with a Rule, an Owners and an
 * Approval column. The comment continues with other tables (e.g. the maintainer commands), which are
 * never read. Anything unexpected makes the comment unreadable instead of guessed, so that no
 * approval is shown that the bot did not report.
 */
internal fun parseCodeOwnersComment(body: String): CodeOwnersReview {
    val table = Jsoup.parseBodyFragment(body).selectFirst("table") ?: return unreadable("no table")
    val rows = table.select("tr")
    val header = rows.firstOrNull()?.let(::headerNames).orEmpty()

    val ruleColumn = header.indexOf("rule")
    val ownersColumn = header.indexOf("owners")
    val approvalColumn = header.indexOf("approval")
    if (ruleColumn < 0 || ownersColumn < 0 || approvalColumn < 0) {
        return unreadable("unexpected table header $header")
    }

    val rules = rows.drop(1).map { row ->
        val cells = row.select("td")
        if (cells.size != header.size) {
            return unreadable("a row has ${cells.size} cells instead of ${header.size}")
        }

        /* The approval label comes first; the reviewers follow on the next line. */
        val approvalCell = ApprovalCell(cells[approvalColumn])
        val label = cleanText(approvalCell.label)
        val approval = approvalOfLabel(label) ?: return unreadable("unknown approval '$label'")

        CodeOwnersRule(
            paths = rulePaths(cells[ruleColumn]),
            ownerTeams = ownerTeams(cells[ownersColumn]),
            approval = approval,
            reviewers = reviewers(approvalCell.reviewerLinks),
        )
    }

    /* The bot always lists the catch-all rule, so an empty table must not count as approved. */
    if (rules.isEmpty()) return unreadable("no rules")
    return CodeOwnersReview.Rules(rules)
}

/**
 * The `rel="next"` URL of a GitHub `Link` response header, which paginates REST responses.
 */
internal fun nextPageLink(linkHeader: String?): String? =
    linkHeader?.let { header -> nextPageLinkPattern.find(header)?.groupValues?.get(1) }

private fun unreadable(reason: String): CodeOwnersReview = CodeOwnersReview.UnreadableComment(reason)

private fun headerNames(headerRow: Element): List<String> =
    headerRow.select("th").map { cell -> cleanText(cell.text()).lowercase() }

/**
 * Labels are compared without their leading emoji, which only repeats the label.
 */
private fun approvalOfLabel(label: String): CodeOwnersApproval? =
    when (label.dropWhile { char -> !char.isLetter() }.lowercase()) {
        "approved" -> CodeOwnersApproval.APPROVED
        "changes requested" -> CodeOwnersApproval.CHANGES_REQUESTED
        "review requested" -> CodeOwnersApproval.REVIEW_REQUESTED
        "commented" -> CodeOwnersApproval.COMMENTED
        "no reviewer" -> CodeOwnersApproval.NO_REVIEWER
        else -> null
    }

/**
 * The parts of the Approval cell: its label before the first line break, and the reviewer links after it.
 */
private class ApprovalCell(cell: Element) {
    val label: String
    val reviewerLinks: List<Element>

    init {
        val nodes = cell.nodeStream().toList()
        val lineBreak = nodes.indexOfFirst { node -> node is Element && node.tagName() == "br" }
        val labelEnd = if (lineBreak < 0) nodes.size else lineBreak

        label = nodes.subList(0, labelEnd)
            .filterIsInstance<TextNode>()
            .joinToString("") { textNode -> textNode.wholeText }
        reviewerLinks = nodes.subList(labelEnd, nodes.size)
            .filterIsInstance<Element>()
            .filter { node -> node.tagName() == "a" }
    }
}

private fun rulePaths(ruleCell: Element): List<String> {
    val paths = ruleCell.select("code")
        .map { code -> cleanText(code.text()) }
        .filter(String::isNotEmpty)

    return paths.ifEmpty { listOfNotNull(cleanText(ruleCell.text()).takeIf(String::isNotEmpty)) }
}

/**
 * Teams are collapsible lists whose summary links the team. A team lists its sub-teams the same way
 * inside its own list, so only the teams on the top level are the rule's owners.
 */
private fun ownerTeams(ownersCell: Element): List<String> =
    ownersCell.select("details")
        .filter { details -> details.parents().none { it.tagName() == "details" } }
        .mapNotNull { details ->
            val summary = details.children().firstOrNull { it.tagName() == "summary" } ?: return@mapNotNull null
            val linksTheTeam = summary.select("[href]").any { teamUrlPattern.matchEntire(it.attr("href")) != null }
            if (linksTheTeam) cleanText(summary.text()).takeIf(String::isNotEmpty) else null
        }
        .distinct()

/**
 * Reviewers are linked to their GitHub profile; teams to their team page.
 */
private fun reviewers(links: List<Element>): List<String> =
    links.mapNotNull { link ->
        val url = link.attr("href")
        userUrlPattern.matchEntire(url)?.groupValues?.get(1)
            ?: teamUrlPattern.matchEntire(url)?.let { team -> "${team.groupValues[1]}/${team.groupValues[2]}" }
    }.distinct()

/**
 * The text of a parsed fragment: jsoup has stripped its tags and decoded its character references, so
 * only the invisible characters the bot inserts for line wrapping remain to be removed.
 */
private fun cleanText(text: String): String =
    whitespacePattern.replace(invisibleCharacterPattern.replace(text, ""), " ").trim()

private fun JsonObject.stringOrNull(key: String): String? {
    val value = get(key) as? JsonPrimitive ?: return null
    return if (value.isString) value.content else null
}

private const val CODE_OWNERS_COMMENT_MARKER = "<!-- CODE_OWNERS_REVIEW_COMMENT -->"

private val codeOwnersHeaderPattern = Regex("""#{1,6}\s*Code\s+Owners\s*#*""", RegexOption.IGNORE_CASE)
private val userUrlPattern = Regex("""https://github\.com/([A-Za-z0-9-]+)/?""")
private val teamUrlPattern = Regex("""https://github\.com/orgs/([A-Za-z0-9-]+)/teams/([A-Za-z0-9._-]+)/?""")
private val invisibleCharacterPattern = Regex("[\u200B\u200C\u200D\u2060\uFEFF]")
private val whitespacePattern = Regex("""[\s\u00A0]+""")
private val nextPageLinkPattern = Regex("""<([^>]*)>\s*;\s*rel\s*=\s*"next"""")
