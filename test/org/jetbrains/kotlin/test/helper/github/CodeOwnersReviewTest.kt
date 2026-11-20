package org.jetbrains.kotlin.test.helper.github

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The fixtures mirror the HTML of the `kotlin-safemerge` bot's "Code Owners" comment: paths broken
 * with zero-width spaces, teams as collapsible member lists (with sub-teams nested inside), approval
 * markers, and the maintainer commands table that follows the code owners table.
 */
class CodeOwnersReviewTest {
    @Test
    fun readsEveryRuleOfTheCodeOwnersTable() {
        val review = parseCodeOwnersComment(
            codeOwnersComment(
                row(
                    rule = path("/compiler/fir/") + ", " + path("**.gradle.kts"),
                    owners = team(
                        "kotlin-frontend",
                        user("alice", "Alice Example"),
                        user("bob", "Bob Example", " (QA) ⏳")
                    ),
                    approval = "✅ Approved<br>" + user("alice", "Alice Example") + " 🔒, " + user("carol") + " 🔒",
                ),
                row(
                    rule = path("/analysis/"),
                    owners = team("kotlin-analysis-api", user("dave", "Dave Example")),
                    approval = "🔴 Changes requested<br>" + user("dave", "Dave Example"),
                ),
                row(
                    rule = path("/plugins/kotlinx-serialization/"),
                    owners = user("erin", "Erin Example") + ", " + user("frank", "Frank Example"),
                    approval = "❌ Review requested<br>" + user("erin", "Erin Example") + ", " + user("frank"),
                ),
                row(
                    rule = path("/compiler/tests/"),
                    owners = team("kotlin-compiler", user("judy"), team("kotlin-jvm", user("ken", "Ken Example"))),
                    approval = "❌ No reviewer",
                ),
                row(
                    rule = path("/repo/"),
                    owners = user("henry") + "<br>" + team("kotlin-build-infrastructure", user("ivan")),
                    approval = "🔄 Commented<br />" + user("grace", "Grace Example"),
                ),
                row(rule = path("*Generated.java"), owners = "", approval = "❌ No reviewer"),
            )
        )

        assertEquals(
            CodeOwnersReview.Rules(
                listOf(
                    CodeOwnersRule(
                        paths = listOf("/compiler/fir/", "**.gradle.kts"),
                        ownerTeams = listOf("kotlin-frontend"),
                        approval = CodeOwnersApproval.APPROVED,
                        reviewers = listOf("alice", "carol"),
                    ),
                    CodeOwnersRule(
                        paths = listOf("/analysis/"),
                        ownerTeams = listOf("kotlin-analysis-api"),
                        approval = CodeOwnersApproval.CHANGES_REQUESTED,
                        reviewers = listOf("dave"),
                    ),
                    CodeOwnersRule(
                        paths = listOf("/plugins/kotlinx-serialization/"),
                        ownerTeams = emptyList(),
                        approval = CodeOwnersApproval.REVIEW_REQUESTED,
                        reviewers = listOf("erin", "frank"),
                    ),
                    CodeOwnersRule(
                        paths = listOf("/compiler/tests/"),
                        ownerTeams = listOf("kotlin-compiler"),
                        approval = CodeOwnersApproval.NO_REVIEWER,
                        reviewers = emptyList(),
                    ),
                    CodeOwnersRule(
                        paths = listOf("/repo/"),
                        ownerTeams = listOf("kotlin-build-infrastructure"),
                        approval = CodeOwnersApproval.COMMENTED,
                        reviewers = listOf("grace"),
                    ),
                    CodeOwnersRule(
                        paths = listOf("*Generated.java"),
                        ownerTeams = emptyList(),
                        approval = CodeOwnersApproval.NO_REVIEWER,
                        reviewers = emptyList(),
                    ),
                )
            ),
            review,
        )
    }

    @Test
    fun readsTeamReviewersAsOrganizationAndTeam() {
        val review = parseCodeOwnersComment(
            codeOwnersComment(
                row(
                    rule = path("/js/"),
                    owners = team("kotlin-js", user("leo")),
                    approval = """❌ Review requested<br><a href="https://github.com/orgs/JetBrains/teams/kotlin-js">kotlin-js</a>""",
                )
            )
        )

        assertEquals(listOf("JetBrains/kotlin-js"), assertIs<CodeOwnersReview.Rules>(review).rules.single().reviewers)
    }

    @Test
    fun decodesCharacterReferencesAndDropsInvisibleCharacters() {
        val review = parseCodeOwnersComment(
            codeOwnersComment(
                row(
                    rule = "<code>/libraries/&lt;tools&gt;/a&amp;b&#8203;/&#x200B;c\u2060/</code>",
                    owners = "",
                    approval = "❌ No reviewer",
                )
            )
        )

        assertEquals(listOf("/libraries/<tools>/a&b/c/"), assertIs<CodeOwnersReview.Rules>(review).rules.single().paths)
    }

    @Test
    fun anUnknownApprovalMakesTheCommentUnreadable() {
        val review = parseCodeOwnersComment(
            codeOwnersComment(row(rule = path("/js/"), owners = "", approval = "⏳ Pending<br>" + user("leo")))
        )

        assertIs<CodeOwnersReview.UnreadableComment>(review)
    }

    @Test
    fun anUnexpectedTableMakesTheCommentUnreadable() {
        val rules = row(rule = path("/js/"), owners = "", approval = "❌ No reviewer")

        assertIs<CodeOwnersReview.UnreadableComment>(parseCodeOwnersComment("### Code Owners\n\nNo table yet."))
        assertIs<CodeOwnersReview.UnreadableComment>(
            parseCodeOwnersComment(
                "### Code Owners\n\n<table><tr><th>Path</th><th>Owners</th><th>Approval</th></tr>$rules</table>"
            )
        )
        assertIs<CodeOwnersReview.UnreadableComment>(
            parseCodeOwnersComment(codeOwnersComment("<tr><td>${path("/js/")}</td><td>❌ No reviewer</td></tr>"))
        )
    }

    @Test
    fun aTableWithoutRulesIsUnreadableRatherThanApproved() {
        assertIs<CodeOwnersReview.UnreadableComment>(parseCodeOwnersComment(codeOwnersComment()))
    }

    @Test
    fun readsTheCommentOfTheBotOnly() {
        val table = codeOwnersComment(row(rule = path("/js/"), owners = "", approval = "❌ No reviewer"))
        val expected = parseCodeOwnersComment(table)

        assertEquals(
            CodeOwnersReview.MissingComment,
            codeOwnersReviewOf(listOf(comment(table, login = "alice", type = "User"))),
        )
        /* Only GitHub Apps have the `[bot]` suffix; the bare login is free for anyone to register. */
        assertEquals(
            CodeOwnersReview.MissingComment,
            codeOwnersReviewOf(listOf(comment(table, login = "kotlin-safemerge", type = "User"))),
        )
        assertEquals(
            CodeOwnersReview.MissingComment,
            codeOwnersReviewOf(listOf(comment(table, login = SAFE_MERGE_BOT_LOGIN, type = "User"))),
        )
        assertEquals(expected, codeOwnersReviewOf(listOf(comment(table))))
    }

    @Test
    fun ignoresTheOtherCommentsOfTheBot() {
        val review = codeOwnersReviewOf(
            listOf(comment("Couldn't reach TeamCity - please try again in a few minutes."))
        )

        assertEquals(CodeOwnersReview.MissingComment, review)
    }

    @Test
    fun recognizesTheCommentByItsHeaderOrItsMarker() {
        val rules = row(rule = path("/js/"), owners = "", approval = "❌ No reviewer")
        val table = "<table><tr><th>Rule</th><th>Owners</th><th>Approval</th></tr>$rules</table>"

        assertIs<CodeOwnersReview.Rules>(codeOwnersReviewOf(listOf(comment("\n## Code owners\n\n$table"))))
        assertIs<CodeOwnersReview.Rules>(
            codeOwnersReviewOf(listOf(comment("### Reviews\n\n$table\n<!-- CODE_OWNERS_REVIEW_COMMENT -->")))
        )
        assertEquals(CodeOwnersReview.MissingComment, codeOwnersReviewOf(listOf(comment("### Reviews\n\n$table"))))
    }

    @Test
    fun theMostRecentlyUpdatedCommentWins() {
        val older = codeOwnersComment(row(rule = path("/js/"), owners = "", approval = "❌ No reviewer"))
        val newer = codeOwnersComment(row(rule = path("/js/"), owners = "", approval = "✅ Approved<br>" + user("leo")))

        val review = codeOwnersReviewOf(
            listOf(
                comment(newer, updatedAt = "2026-11-20T08:32:09Z"),
                comment(older, updatedAt = "2026-11-18T14:15:20Z"),
            )
        )

        assertEquals(parseCodeOwnersComment(newer), review)
    }

    @Test
    fun readsTheIssueCommentsOfAPage() {
        val json = """
            [
              {
                "user": {"login": "kotlin-safemerge[bot]", "type": "Bot"},
                "body": "### Code Owners",
                "updated_at": "2026-11-20T08:32:09Z"
              },
              {"user": null, "body": "A comment of a deleted account"},
              {"user": {"login": "alice", "type": "User"}, "body": null},
              "not a comment"
            ]
        """.trimIndent()

        assertEquals(
            listOf(
                GitHubIssueComment(SAFE_MERGE_BOT_LOGIN, "Bot", "### Code Owners", "2026-11-20T08:32:09Z"),
                GitHubIssueComment(null, null, "A comment of a deleted account", null),
            ),
            parseIssueComments(json),
        )
    }

    @Test
    fun unreadableCommentPagesAreNoComments() {
        assertNull(parseIssueComments("not json"))
        assertNull(parseIssueComments("""{"message": "Not Found"}"""))
    }

    @Test
    fun readsTheNextPageLinkOnly() {
        val nextPage = "https://api.github.com/repositories/3432266/issues/42/comments?per_page=100&page=2"
        val lastPage = "https://api.github.com/repositories/3432266/issues/42/comments?per_page=100&page=5"
        val firstPage = "https://api.github.com/repositories/3432266/issues/42/comments?per_page=100&page=1"

        assertEquals(nextPage, nextPageLink("""<$nextPage>; rel="next", <$lastPage>; rel="last""""))
        assertEquals(nextPage, nextPageLink("""<$firstPage>; rel="prev", <$nextPage>; rel="next""""))
        assertNull(nextPageLink("""<$firstPage>; rel="prev", <$firstPage>; rel="first""""))
        assertNull(nextPageLink(null))
    }

    @Test
    fun changesRequestedWinOverPendingReviewsAndMissingReviewers() {
        assertEquals(
            CodeOwnersReviewIndicator.CHANGES_REQUESTED,
            indicatorOf(
                CodeOwnersApproval.APPROVED,
                CodeOwnersApproval.NO_REVIEWER,
                CodeOwnersApproval.REVIEW_REQUESTED,
                CodeOwnersApproval.COMMENTED,
                CodeOwnersApproval.CHANGES_REQUESTED,
            ),
        )
    }

    @Test
    fun pendingReviewsWinOverMissingReviewers() {
        assertEquals(
            CodeOwnersReviewIndicator.AWAITING_REVIEW,
            indicatorOf(
                CodeOwnersApproval.APPROVED,
                CodeOwnersApproval.NO_REVIEWER,
                CodeOwnersApproval.REVIEW_REQUESTED
            ),
        )
        assertEquals(
            CodeOwnersReviewIndicator.AWAITING_REVIEW,
            indicatorOf(CodeOwnersApproval.NO_REVIEWER, CodeOwnersApproval.COMMENTED),
        )
    }

    @Test
    fun aRuleWithoutReviewerIsAMissingReviewer() {
        assertEquals(
            CodeOwnersReviewIndicator.MISSING_REVIEWER,
            indicatorOf(CodeOwnersApproval.APPROVED, CodeOwnersApproval.NO_REVIEWER),
        )
    }

    @Test
    fun theReviewIsApprovedOnlyWhenEveryRuleIs() {
        assertEquals(
            CodeOwnersReviewIndicator.APPROVED,
            indicatorOf(CodeOwnersApproval.APPROVED, CodeOwnersApproval.APPROVED),
        )
    }

    @Test
    fun aMissingOrUnreadableCommentIsAMissingComment() {
        assertEquals(
            CodeOwnersReviewIndicator.MISSING_COMMENT,
            codeOwnersReviewIndicator(CodeOwnersReview.MissingComment),
        )
        assertEquals(
            CodeOwnersReviewIndicator.MISSING_COMMENT,
            codeOwnersReviewIndicator(CodeOwnersReview.UnreadableComment("unknown approval")),
        )
    }

    private fun indicatorOf(vararg approvals: CodeOwnersApproval): CodeOwnersReviewIndicator =
        codeOwnersReviewIndicator(
            CodeOwnersReview.Rules(
                approvals.map { approval ->
                    CodeOwnersRule(
                        paths = listOf("/compiler/"),
                        ownerTeams = listOf("kotlin-compiler"),
                        approval = approval,
                        reviewers = listOf("alice"),
                    )
                }
            )
        )

    private fun comment(
        body: String,
        login: String = SAFE_MERGE_BOT_LOGIN,
        type: String = "Bot",
        updatedAt: String = "2026-11-20T08:32:09Z",
    ) = GitHubIssueComment(login, type, body, updatedAt)

    private fun codeOwnersComment(vararg rows: String): String =
        "### Code Owners\n\n" +
            "<table><tr><th>Rule</th><th>Owners</th><th>Approval</th></tr>${rows.joinToString("")}</table>\n\n" +
            "<details><summary><b>PR commands for maintainers</b></summary><table>" +
            "<tr><th>Command</th><th>Description</th><th>Parameters</th></tr>" +
            "<tr><td><code>/safe-merge</code></td><td>Rebase-merges with automatic fixup commit squashing</td>" +
            "<td><code>--fixup</code> Autosquash fixup commits (on by default)</td></tr>" +
            "<tr><td><code>/codeowners</code></td><td>Triggers code owners check and comment update</td><td>—</td></tr>" +
            "</table></details><!-- KPR_COMMANDS:e30= -->\n" +
            "<!-- CODE_OWNERS_REVIEW_COMMENT -->"

    private fun row(rule: String, owners: String, approval: String): String =
        """<tr><td>$rule</td><td>$owners</td><td align="center">$approval</td></tr>"""

    /**
     * The bot lets paths wrap after every `/`, `.` and `-`.
     */
    private fun path(path: String): String =
        "<code>" + path.replace(Regex("([/.-])"), "$1$ZERO_WIDTH_SPACE") + "</code>"

    private fun user(login: String, name: String? = null, suffix: String = ""): String {
        val text = if (name == null) login else "$name ($login)"
        return """<a href="https://github.com/$login"><b><code>$text</code></b></a>$suffix"""
    }

    private fun team(name: String, vararg members: String): String =
        """<details><summary><a href="https://github.com/orgs/JetBrains/teams/$name">$name</a></summary>""" +
            "<ul>${members.joinToString("") { member -> "<li>$member</li>" }}</ul></details>"
}

private const val ZERO_WIDTH_SPACE = "\u200B"
