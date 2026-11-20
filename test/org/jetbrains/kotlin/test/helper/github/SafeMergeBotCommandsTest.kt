package org.jetbrains.kotlin.test.helper.github

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Nothing here runs `gh`: every outcome of the command is faked.
 */
class SafeMergeBotCommandsTest {
    @Test
    fun noCommandIsOfferedWithoutAnOpenPullRequest() {
        val mergedPullRequest = BranchPullRequest.Merged(
            MergedKotlinPullRequest(url = PULL_REQUEST_URL, number = 42, baseBranchName = "master")
        )

        for (command in SafeMergeBotCommand.entries) {
            assertFalse(isSafeMergeBotCommandAvailable(command, pullRequest = null))
            assertFalse(isSafeMergeBotCommandAvailable(command, mergedPullRequest))
        }
    }

    @Test
    fun aSafeMergeIsOfferedOnlyOnceEveryCodeOwnersRuleIsApproved() {
        assertTrue(
            isSafeMergeBotCommandAvailable(
                SafeMergeBotCommand.SAFE_MERGE,
                openPullRequest(review(CodeOwnersApproval.APPROVED, CodeOwnersApproval.APPROVED)),
            )
        )

        val reviewsWithoutApproval = listOf(
            null,
            CodeOwnersReview.MissingComment,
            CodeOwnersReview.UnreadableComment("no table"),
            review(CodeOwnersApproval.APPROVED, CodeOwnersApproval.CHANGES_REQUESTED),
            review(CodeOwnersApproval.APPROVED, CodeOwnersApproval.REVIEW_REQUESTED),
            review(CodeOwnersApproval.APPROVED, CodeOwnersApproval.COMMENTED),
            review(CodeOwnersApproval.APPROVED, CodeOwnersApproval.NO_REVIEWER),
        )
        for (review in reviewsWithoutApproval) {
            assertFalse(
                isSafeMergeBotCommandAvailable(SafeMergeBotCommand.SAFE_MERGE, openPullRequest(review)),
                "A safe merge must not be offered for $review",
            )
        }
    }

    @Test
    fun commentsTheCommandOnTheKotlinPullRequestWithoutPrompting() {
        val workingDirectory = File("/checkout/kotlin")

        val commandLine = pullRequestCommentCommandLine("/usr/bin/gh", 42, "/safe-merge", workingDirectory)

        assertEquals("/usr/bin/gh", commandLine.exePath)
        assertEquals(
            listOf("pr", "comment", "42", "--repo", "JetBrains/kotlin", "--body", "/safe-merge"),
            commandLine.parametersList.parameters,
        )
        assertEquals(workingDirectory.toPath(), commandLine.workingDirectory)
        assertEquals("1", commandLine.environment["GH_PROMPT_DISABLED"])
        assertEquals("1", commandLine.environment["GH_NO_UPDATE_NOTIFIER"])
    }

    @Test
    fun aPostedCommandLinksItsComment() = runBlocking {
        val commentUrl = "$PULL_REQUEST_URL#issuecomment-3571234567"
        val commandLines = mutableListOf<GeneralCommandLine>()

        val result = post(SafeMergeBotCommand.SAFE_MERGE) { commandLine ->
            commandLines += commandLine
            ProcessOutput("$commentUrl\n", "", 0, false, false)
        }

        assertEquals(SafeMergeBotCommandResult.Posted(commentUrl), result)
        assertEquals(
            listOf(listOf("pr", "comment", "42", "--repo", "JetBrains/kotlin", "--body", "/safe-merge")),
            commandLines.map { commandLine -> commandLine.parametersList.parameters },
        )
    }

    @Test
    fun aPostedCommandWithoutTheCommentUrlLinksThePullRequest() = runBlocking {
        val result = post(SafeMergeBotCommand.SAFE_MERGE) { ProcessOutput("", "", 0, false, false) }

        assertEquals(SafeMergeBotCommandResult.Posted(PULL_REQUEST_URL), result)
    }

    @Test
    fun withoutGhNothingRuns() = runBlocking {
        val result = postSafeMergeBotCommand(
            pullRequest = OPEN_PULL_REQUEST,
            command = SafeMergeBotCommand.SAFE_MERGE,
            ghExecutable = null,
            workingDirectory = null,
            runGh = { fail("gh must not run") },
        )

        assertEquals(SafeMergeBotCommandResult.GhNotFound, result)
    }

    @Test
    fun aFailedCommandReportsWhatGhPrinted() = runBlocking {
        val notSignedIn = "To get started with GitHub CLI, please run:  gh auth login"

        assertEquals(
            SafeMergeBotCommandResult.Failed(notSignedIn),
            post(SafeMergeBotCommand.SAFE_MERGE) { ProcessOutput("", "$notSignedIn\n", 4, false, false) },
        )
        assertEquals(
            SafeMergeBotCommandResult.Failed("gh exited with code 1"),
            post(SafeMergeBotCommand.SAFE_MERGE) { ProcessOutput("", "", 1, false, false) },
        )
        assertEquals(
            SafeMergeBotCommandResult.Failed("Cannot run program"),
            post(SafeMergeBotCommand.SAFE_MERGE) { throw ExecutionException("Cannot run program") },
        )
        assertEquals(
            SafeMergeBotCommandResult.TimedOut,
            post(SafeMergeBotCommand.SAFE_MERGE) { ProcessOutput("", "", -1, true, false) },
        )
    }

    private suspend fun post(
        command: SafeMergeBotCommand,
        runGh: suspend (GeneralCommandLine) -> ProcessOutput,
    ): SafeMergeBotCommandResult = postSafeMergeBotCommand(
        pullRequest = OPEN_PULL_REQUEST,
        command = command,
        ghExecutable = File("/usr/bin/gh"),
        workingDirectory = null,
        runGh = runGh,
    )

    private fun openPullRequest(review: CodeOwnersReview?) = BranchPullRequest.Open(OPEN_PULL_REQUEST, review)

    private fun review(vararg approvals: CodeOwnersApproval) = CodeOwnersReview.Rules(
        approvals.map { approval ->
            CodeOwnersRule(paths = listOf("/compiler/"), ownerTeams = emptyList(), approval = approval, reviewers = emptyList())
        }
    )

    private companion object {
        const val PULL_REQUEST_URL = "https://github.com/JetBrains/kotlin/pull/42"

        val OPEN_PULL_REQUEST = OpenKotlinPullRequest(
            url = PULL_REQUEST_URL,
            headSha = "0123456789abcdef0123456789abcdef01234567",
            number = 42,
        )
    }
}
