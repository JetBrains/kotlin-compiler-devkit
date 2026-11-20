package org.jetbrains.kotlin.test.helper.github

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.util.ExecUtil
import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.jetbrains.kotlin.test.helper.MyBundle
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes

/**
 * Posts `kotlin-safemerge` bot commands on pull requests for the Remote Run tree's context menu: in
 * the background, with the GitHub CLI, notifying how it went.
 */
@Service(Service.Level.PROJECT)
internal class SafeMergeBotCommandService(private val project: Project, private val scope: CoroutineScope) {
    companion object {
        private val logger = Logger.getInstance(SafeMergeBotCommandService::class.java)

        private val ghTimeout = 1.minutes

        fun getInstance(project: Project): SafeMergeBotCommandService = project.service()
    }

    /**
     * The pull request numbers and commands being posted: a second click while the first comment is
     * on its way must not post the command twice.
     */
    private val postingCommands: MutableSet<Pair<Long, SafeMergeBotCommand>> = ConcurrentHashMap.newKeySet()

    /**
     * Replaced in tests.
     */
    internal var findGh: () -> File? = ::findGhExecutable

    /**
     * Replaced in tests, so that no comment is ever posted.
     */
    internal var runGh: suspend (GeneralCommandLine) -> ProcessOutput = { commandLine ->
        runInterruptible(Dispatchers.IO) {
            ExecUtil.execAndGetOutput(commandLine, ghTimeout.inWholeMilliseconds.toInt())
        }
    }

    fun isPosting(pullRequest: OpenKotlinPullRequest, command: SafeMergeBotCommand): Boolean =
        (pullRequest.number to command) in postingCommands

    /**
     * Comments [command] on [pullRequest] unless the same command is already being posted on it, in
     * which case it returns null.
     */
    fun post(pullRequest: OpenKotlinPullRequest, command: SafeMergeBotCommand): Job? {
        val postingCommand = pullRequest.number to command
        if (!postingCommands.add(postingCommand)) return null

        return scope.launch {
            try {
                val progressTitle =
                    MyBundle.message("github.bot.command.progress", command.comment, pullRequest.number.toString())
                val result = withBackgroundProgress(project, progressTitle, cancellable = true) {
                    postSafeMergeBotCommand(
                        pullRequest = pullRequest,
                        command = command,
                        ghExecutable = findGh(),
                        workingDirectory = project.basePath?.let(::File)?.takeIf(File::isDirectory),
                        runGh = runGh,
                    )
                }
                notifyResult(pullRequest, command, result)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.warn("Commenting ${command.comment} on ${pullRequest.url} failed", exception)
                notifyResult(pullRequest, command, SafeMergeBotCommandResult.Failed(exception.message.orEmpty()))
            } finally {
                postingCommands.remove(postingCommand)
            }
        }
    }

    private fun notifyResult(
        pullRequest: OpenKotlinPullRequest,
        command: SafeMergeBotCommand,
        result: SafeMergeBotCommandResult,
    ) {
        val number = pullRequest.number.toString()
        val notification = when (result) {
            is SafeMergeBotCommandResult.Posted -> notification(
                MyBundle.message("github.bot.command.posted.title"),
                MyBundle.message("github.bot.command.posted", command.comment, number),
                NotificationType.INFORMATION,
            ).addAction(object : AnAction(MyBundle.message("github.bot.command.open.on.github")) {
                override fun actionPerformed(e: AnActionEvent) {
                    BrowserUtil.browse(result.url)
                }
            })

            SafeMergeBotCommandResult.GhNotFound -> failureNotification(MyBundle.message("github.bot.command.gh.not.found"))

            SafeMergeBotCommandResult.TimedOut ->
                failureNotification(MyBundle.message("github.bot.command.timed.out", command.comment, number))

            is SafeMergeBotCommandResult.Failed -> failureNotification(
                MyBundle.message("github.bot.command.failed", command.comment, number, result.message)
            )
        }

        notification.notify(project)
    }

    private fun failureNotification(content: String): Notification =
        notification(MyBundle.message("github.bot.command.failed.title"), content, NotificationType.ERROR)

    /**
     * Notification content is HTML: the text, e.g. the error output of `gh`, must not be read as markup.
     */
    private fun notification(title: String, content: String, type: NotificationType): Notification =
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Kotlin Compiler DevKit Notifications")
            .createNotification(title, StringUtil.escapeXmlEntities(content).replace("\n", "<br>"), type)
}
