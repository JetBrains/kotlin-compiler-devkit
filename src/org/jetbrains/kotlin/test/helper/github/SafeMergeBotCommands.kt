package org.jetbrains.kotlin.test.helper.github

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.ProcessOutput
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * The commands of the `kotlin-safemerge` bot that the Remote Run tree offers for a branch's pull
 * request. The bot reads them from the pull request's comments.
 *
 * @property comment the comment that issues the command.
 */
internal enum class SafeMergeBotCommand(val comment: String) {
    /**
     * Runs the test pipeline with the changes rebased on the latest master.
     */
    DRY_RUN("/dry-run"),

    /**
     * Rebase-merges the pull request.
     */
    SAFE_MERGE("/safe-merge"),
}

/**
 * Every command needs an open pull request; a safe merge also needs the approval of every code
 * owners rule, as the bot's comment reported it when the review was last read.
 */
internal fun isSafeMergeBotCommandAvailable(command: SafeMergeBotCommand, pullRequest: BranchPullRequest?): Boolean {
    val openPullRequest = pullRequest as? BranchPullRequest.Open ?: return false

    return when (command) {
        SafeMergeBotCommand.DRY_RUN -> true
        SafeMergeBotCommand.SAFE_MERGE ->
            openPullRequest.codeOwnersReview?.let(::codeOwnersReviewIndicator) == CodeOwnersReviewIndicator.APPROVED
    }
}

internal sealed interface SafeMergeBotCommandResult {
    /**
     * @property url the new comment, or the pull request when `gh` did not print the comment.
     */
    data class Posted(val url: String) : SafeMergeBotCommandResult
    data object GhNotFound : SafeMergeBotCommandResult
    data object TimedOut : SafeMergeBotCommandResult
    data class Failed(val message: String) : SafeMergeBotCommandResult
}

/**
 * Comments [command] on [pullRequest] with the GitHub CLI, which posts as the account `gh auth login`
 * signed in, not as the GitHub account configured in the IDE.
 */
internal suspend fun postSafeMergeBotCommand(
    pullRequest: OpenKotlinPullRequest,
    command: SafeMergeBotCommand,
    ghExecutable: File?,
    workingDirectory: File?,
    runGh: suspend (GeneralCommandLine) -> ProcessOutput,
): SafeMergeBotCommandResult {
    if (ghExecutable == null) return SafeMergeBotCommandResult.GhNotFound

    val commandLine = pullRequestCommentCommandLine(
        ghPath = ghExecutable.absolutePath,
        pullRequestNumber = pullRequest.number,
        body = command.comment,
        workingDirectory = workingDirectory,
    )
    val output = try {
        runGh(commandLine)
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: ExecutionException) {
        return SafeMergeBotCommandResult.Failed(exception.message ?: "gh could not be started")
    }

    return when {
        output.isTimeout -> SafeMergeBotCommandResult.TimedOut
        output.exitCode != 0 -> SafeMergeBotCommandResult.Failed(
            output.stderr.trim().ifEmpty { "gh exited with code ${output.exitCode}" }
        )

        else -> SafeMergeBotCommandResult.Posted(commentUrlOf(output.stdout, pullRequest) ?: pullRequest.url)
    }
}

/**
 * `gh pr comment` with the body given never prompts. The repository is named explicitly: the
 * number is one of a `JetBrains/kotlin` pull request, whatever remotes the checkout has, and `gh`
 * refuses to guess between several.
 */
internal fun pullRequestCommentCommandLine(
    ghPath: String,
    pullRequestNumber: Long,
    body: String,
    workingDirectory: File?,
): GeneralCommandLine =
    GeneralCommandLine(
        ghPath, "pr", "comment", pullRequestNumber.toString(),
        "--repo", GITHUB_KOTLIN_REPOSITORY,
        "--body", body,
    )
        .withWorkingDirectory(workingDirectory?.toPath())
        .withEnvironment(GH_ENVIRONMENT)

/**
 * `gh pr comment` prints the URL of the new comment, `<pull request URL>#issuecomment-<id>`.
 */
internal fun commentUrlOf(stdout: String, pullRequest: OpenKotlinPullRequest): String? =
    stdout.lineSequence().map(String::trim).firstOrNull { line -> line.startsWith("${pullRequest.url}#") }

/**
 * The `gh` executable on the PATH or in a well-known install location.
 */
internal fun findGhExecutable(): File? =
    PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("gh")
        ?: fallbackGhLocations.map(::File).firstOrNull(File::canExecute)

/**
 * Install locations of the GitHub CLI that may be missing from the IDE's PATH (e.g. when launched from the Dock).
 */
private val fallbackGhLocations = listOf("/opt/homebrew/bin/gh", "/usr/local/bin/gh")

/**
 * No prompts, which nobody could answer, and no update notices mixed into the output.
 */
private val GH_ENVIRONMENT = mapOf("GH_PROMPT_DISABLED" to "1", "GH_NO_UPDATE_NOTIFIER" to "1")
