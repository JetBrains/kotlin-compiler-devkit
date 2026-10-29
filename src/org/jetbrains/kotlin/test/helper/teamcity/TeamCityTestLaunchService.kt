package org.jetbrains.kotlin.test.helper.teamcity

import com.intellij.CommonBundle
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.util.ExecUtil
import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.util.messages.Topic
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.test.helper.MyBundle
import org.jetbrains.kotlin.test.helper.git.BranchPushState
import org.jetbrains.kotlin.test.helper.git.Git
import org.jetbrains.kotlin.test.helper.git.TeamCityRemoteChoice
import org.jetbrains.kotlin.test.helper.git.currentBranchName
import org.jetbrains.kotlin.test.helper.git.kotlinMonorepoRepository
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * The launch currently in flight: the branch it targets and a human readable description of its current step.
 */
internal data class TeamCityLaunchProgress(val branchName: String?, val stepText: String)

internal fun interface TeamCityLaunchProgressListener {
    fun launchProgressChanged(progress: TeamCityLaunchProgress?)
}

/**
 * Triggers the aggregate TeamCity test run (`Kotlin_KotlinDev_AggregateTrigger`, the same run a GitHub
 * /dry-run comment and scripts/teamcity-run.sh start) for a branch and remembers the launched build.
 *
 * The heavy lifting is done by the official `teamcity` CLI; `auth login` and `run start` output stream into
 * killable Run-console tabs, everything else stays quiet. The CLI is always invoked with `--no-push`:
 * pushing happens only after the explicit ask-to-push dialog, to the remote TeamCity builds from.
 */
@Service(Service.Level.PROJECT)
internal class TeamCityTestLaunchService(private val project: Project, private val scope: CoroutineScope) {
    companion object {
        private val logger = Logger.getInstance(TeamCityTestLaunchService::class.java)

        /**
         * Bounds the quiet `--version` and `auth status` checks, which must never block a launch forever.
         */
        private val quietCommandTimeout = 15.seconds

        @Topic.ProjectLevel
        val LAUNCH_PROGRESS_TOPIC: Topic<TeamCityLaunchProgressListener> =
            Topic("Kotlin DevKit TeamCity launch progress", TeamCityLaunchProgressListener::class.java)

        fun getInstance(project: Project): TeamCityTestLaunchService = project.service()
    }

    /**
     * A second launch request while one is in flight is ignored.
     */
    private val launchInFlight = AtomicBoolean(false)

    /**
     * Non-null while a launch is in flight; published via [LAUNCH_PROGRESS_TOPIC] on every change.
     */
    @Volatile
    var launchProgress: TeamCityLaunchProgress? = null
        private set

    val isLaunchInFlight: Boolean get() = launchInFlight.get()

    fun launchAggregateBuild(branchName: String?) {
        if (!launchInFlight.compareAndSet(false, true)) {
            logger.debug("Ignoring a TeamCity launch request; another launch is in flight")
            return
        }

        val progressBranchName = branchName ?: project.currentBranchName()
        publishLaunchProgress(TeamCityLaunchProgress(progressBranchName, MyBundle.message("teamcity.launch.step.preparing")))

        scope.launch {
            try {
                withBackgroundProgress(project, MyBundle.message("teamcity.launch.progress.title"), cancellable = true) {
                    launchAggregateBuildInternal(branchName, LaunchSteps(progressBranchName))
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.warn("Launching the TeamCity test run failed", exception)
                notifyLaunchError(MyBundle.message("teamcity.launch.unexpected.error", escapeHtml(exception.message.orEmpty())))
            } finally {
                launchInFlight.set(false)
                publishLaunchProgress(null)
            }
        }
    }

    /* Publishes the current pipeline step, shown next to the target branch in the tool window. */
    private inner class LaunchSteps(private val branchName: String?) {
        fun step(stepText: String) {
            publishLaunchProgress(TeamCityLaunchProgress(branchName, stepText))
        }
    }

    private fun publishLaunchProgress(progress: TeamCityLaunchProgress?) {
        launchProgress = progress
        if (!project.isDisposed) {
            project.messageBus.syncPublisher(LAUNCH_PROGRESS_TOPIC).launchProgressChanged(progress)
        }
    }

    private suspend fun launchAggregateBuildInternal(requestedBranchName: String?, launchSteps: LaunchSteps) {
        val repository = project.kotlinMonorepoRepository()
        if (repository == null) {
            notifyLaunchError(MyBundle.message("teamcity.launch.no.repository"))
            return
        }

        val branchName = requestedBranchName ?: repository.currentBranch?.name
        if (branchName == null) {
            notifyLaunchError(MyBundle.message("teamcity.launch.detached.head"))
            return
        }

        val statusService = TeamCityBuildStatusService.getInstance(project)
        val buildsToStop = askAboutUnfinishedBuilds(
            branchName,
            unfinishedBuildsOnBranch(
                builds = RememberedTeamCityBuildStore.getInstance(project).builds(),
                statusOf = statusService::status,
                branchName = branchName,
            ),
        ) ?: return

        val remote = chooseRemote(repository, branchName) ?: return

        launchSteps.step(MyBundle.message("teamcity.launch.step.push.state", branchName, remote.name))
        if (!ensureBranchIsPushed(repository, remote, branchName, launchSteps)) return

        launchSteps.step(MyBundle.message("teamcity.launch.step.cli"))

        val cliExecutable = findCliExecutable()
        if (cliExecutable == null) {
            notifyLaunchError(cliInstallHint())
            return
        }

        val workingDirectory = File(repository.root.path)

        if (!isCliVersionSufficient(cliExecutable, workingDirectory)) return

        if (!isAuthenticated(cliExecutable, workingDirectory)) {
            launchSteps.step(MyBundle.message("teamcity.launch.step.login"))

            val loginResult = runInRunConsole(
                commandLine = authLoginCommandLine(cliExecutable.absolutePath, workingDirectory),
                consoleTitle = MyBundle.message("teamcity.console.cli.title"),
            )

            if (!loginResult.success) {
                notifyCliFailure(loginResult)
                return
            }
        }

        launchSteps.step(MyBundle.message("teamcity.launch.step.trigger", branchName))

        val runResult = try {
            runInRunConsole(
                commandLine = runStartCommandLine(cliExecutable.absolutePath, AGGREGATE_TRIGGER_BUILD_TYPE_ID, branchName, workingDirectory),
                consoleTitle = MyBundle.message("teamcity.console.run.title", branchName),
                inBackground = true,
            )
        } catch (exception: CancellationException) {
            /* The CLI is stopped, but it may have queued the build already. */
            notifyCancelledWhileTriggering(branchName)
            throw exception
        }

        if (!runResult.success) {
            notifyCliFailure(runResult)
            return
        }

        if (buildsToStop.isNotEmpty()) {
            withContext(NonCancellable) {
                stopBuildsWithCli(buildsToStop, cliExecutable, workingDirectory)
            }
        }

        val launchedBuild = parseLaunchedBuild(runResult.stdout)

        if (launchedBuild == null) {
            notifyLaunchSuccess(branchName, buildUrl = null)
            return
        }

        RememberedTeamCityBuildStore.getInstance(project).add(
            RememberedTeamCityBuild(
                buildId = launchedBuild.buildId,
                webUrl = launchedBuild.webUrl,
                branchName = branchName,
                launchTimestamp = System.currentTimeMillis(),
                isAggregateTrigger = true,
            ),
        )
        TeamCityBuildStatusService.getInstance(project).requestRefresh()

        notifyLaunchSuccess(branchName, launchedBuild.webUrl)
    }

    fun stopBuilds(builds: List<RememberedTeamCityBuild>) {
        if (builds.isEmpty()) return

        scope.launch {
            try {
                withBackgroundProgress(project, MyBundle.message("teamcity.stop.progress.title"), cancellable = true) {
                    val repository = project.kotlinMonorepoRepository()
                    if (repository == null) {
                        notifyLaunchError(MyBundle.message("teamcity.launch.no.repository"))
                        return@withBackgroundProgress
                    }

                    val cliExecutable = findCliExecutable()
                    if (cliExecutable == null) {
                        notifyLaunchError(cliInstallHint())
                        return@withBackgroundProgress
                    }

                    val workingDirectory = File(repository.root.path)
                    if (!isCliVersionSufficient(cliExecutable, workingDirectory)) return@withBackgroundProgress
                    if (!isAuthenticated(cliExecutable, workingDirectory)) {
                        notifyLaunchError(MyBundle.message("teamcity.stop.not.authenticated"))
                        return@withBackgroundProgress
                    }

                    stopBuildsWithCli(builds, cliExecutable, workingDirectory)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.warn("Stopping TeamCity builds failed", exception)
                notifyLaunchError(MyBundle.message("teamcity.stop.unexpected.error", escapeHtml(exception.message.orEmpty())))
            }
        }
    }

    private suspend fun stopBuildsWithCli(
        builds: List<RememberedTeamCityBuild>,
        cliExecutable: File,
        workingDirectory: File,
    ) {
        for (build in builds) {
            val result = try {
                runQuietlyWithResult(runCancelCommandLine(cliExecutable.absolutePath, build.buildId, workingDirectory))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.warn("Failed to stop TeamCity build ${build.buildId}", exception)
                null
            }

            if (result?.success != true) {
                val detail = result?.let { commandResult -> parseCliErrorMessage(commandResult.stderr) }
                    ?: MyBundle.message("teamcity.stop.failed.generic")
                notifyLaunchError(MyBundle.message("teamcity.stop.failed", build.buildId, escapeHtml(detail)))
            }
        }

        TeamCityBuildStatusService.getInstance(project).requestRefresh()
    }

    private suspend fun askAboutUnfinishedBuilds(
        branchName: String,
        builds: List<RememberedTeamCityBuild>,
    ): List<RememberedTeamCityBuild>? {
        if (builds.isEmpty()) return emptyList()

        val stopOption = MyBundle.message("teamcity.launch.running.stop")
        val keepOption = MyBundle.message("teamcity.launch.running.keep")
        val cancelOption = MyBundle.message("teamcity.launch.running.cancel")
        val selectedOption = withContext(Dispatchers.EDT) {
            Messages.showDialog(
                project,
                MyBundle.message("teamcity.launch.running.message", builds.size, branchName),
                MyBundle.message("teamcity.launch.running.title"),
                arrayOf(stopOption, keepOption, cancelOption),
                0,
                Messages.getQuestionIcon(),
            )
        }

        return when (selectedOption) {
            0 -> builds
            1 -> emptyList()
            else -> null
        }
    }

    /**
     * The remote to check and push [branchName] against: a github.com/JetBrains/kotlin remote,
     * or the git.jetbrains.team mirror after an explicit confirmation. Null when the launch must not proceed.
     */
    private suspend fun chooseRemote(repository: GitRepository, branchName: String): GitRemote? {
        val remoteName = when (val choice = Git.teamCityRemoteChoice(repository, branchName)) {
            is TeamCityRemoteChoice.GitHubSource -> choice.remoteName

            is TeamCityRemoteChoice.MirrorOnly -> {
                if (!confirmMirrorDialog(branchName, choice.remoteName)) return null
                choice.remoteName
            }

            TeamCityRemoteChoice.None -> {
                notifyLaunchError(MyBundle.message("teamcity.launch.no.remote"))
                return null
            }
        }

        val remote = repository.remotes.firstOrNull { remote -> remote.name == remoteName }
        if (remote == null) notifyLaunchError(MyBundle.message("teamcity.launch.no.remote"))

        return remote
    }

    /**
     * Cancel is the default: launching via the mirror only works once the branch reached GitHub.
     */
    private suspend fun confirmMirrorDialog(branchName: String, remoteName: String): Boolean = withContext(Dispatchers.EDT) {
        val launchOption = MyBundle.message("teamcity.mirror.dialog.launch")
        val options = arrayOf(launchOption, CommonBundle.getCancelButtonText())

        val chosenIndex = Messages.showDialog(
            project,
            MyBundle.message("teamcity.mirror.dialog.message", branchName, remoteName),
            MyBundle.message("teamcity.mirror.dialog.title"),
            options,
            /* defaultOptionIndex = */ 1,
            Messages.getWarningIcon(),
        )

        chosenIndex == 0
    }

    /**
     * The ask-to-push policy, checked against [remote] (where TeamCity sees the branch):
     * unpushed local commits or a branch missing on the remote ask before pushing;
     * diverged, missing or unreadable branches refuse and never force-push.
     */
    private suspend fun ensureBranchIsPushed(
        repository: GitRepository,
        remote: GitRemote,
        branchName: String,
        launchSteps: LaunchSteps,
    ): Boolean = with(project) {
        when (Git.branchPushState(repository, remote, branchName)) {
            BranchPushState.Pushed, BranchPushState.RemoteOnly -> true

            BranchPushState.Unpushed, BranchPushState.NotOnRemote -> {
                if (!confirmPushDialog(branchName, remote.name)) return@with false

                launchSteps.step(MyBundle.message("teamcity.launch.step.push", branchName, remote.name))

                val pushed = Git.pushBranchAndWait(repository, remote, branchName)
                if (!pushed) {
                    notifyLaunchError(MyBundle.message("teamcity.launch.push.failed", branchName, remote.name))
                }

                pushed
            }

            BranchPushState.Diverged -> {
                notifyLaunchError(MyBundle.message("teamcity.launch.diverged", branchName, remote.name))
                false
            }

            BranchPushState.Missing -> {
                notifyLaunchError(MyBundle.message("teamcity.launch.branch.missing", branchName, remote.name))
                false
            }

            BranchPushState.Unknown -> {
                notifyLaunchError(MyBundle.message("teamcity.launch.push.state.unknown", branchName))
                false
            }
        }
    }

    private suspend fun confirmPushDialog(branchName: String, remoteName: String): Boolean = withContext(Dispatchers.EDT) {
        MessageDialogBuilder.yesNo(
            MyBundle.message("teamcity.push.dialog.title"),
            MyBundle.message("teamcity.push.dialog.message", branchName, remoteName),
        ).ask(project)
    }

    private suspend fun isCliVersionSufficient(cliExecutable: File, workingDirectory: File): Boolean {
        val version = runQuietly(versionCommandLine(cliExecutable.absolutePath, workingDirectory))
            ?.let(::parseCliVersion)

        if (version == null) {
            notifyLaunchError(MyBundle.message("teamcity.launch.version.unknown", MINIMUM_TEAMCITY_CLI_VERSION))
            return false
        }

        if (!isVersionAtLeast(version, MINIMUM_TEAMCITY_CLI_VERSION)) {
            notifyLaunchError(MyBundle.message("teamcity.launch.version.too.old", version, MINIMUM_TEAMCITY_CLI_VERSION))
            return false
        }

        return true
    }

    private suspend fun isAuthenticated(cliExecutable: File, workingDirectory: File): Boolean =
        runQuietly(authStatusCommandLine(cliExecutable.absolutePath, workingDirectory))?.let(::parseAuthStatus) == true

    /**
     * Runs a short-lived command without a console, killing it after [quietCommandTimeout].
     * Returns its stdout, or null when it failed to run or timed out.
     */
    private suspend fun runQuietly(commandLine: GeneralCommandLine): String? =
        runQuietlyWithResult(commandLine)?.takeIf { result -> result.success }?.stdout

    private suspend fun runQuietlyWithResult(commandLine: GeneralCommandLine): ConsoleProcessResult? = runInterruptible(Dispatchers.IO) {
        try {
            val output = ExecUtil.execAndGetOutput(commandLine, quietCommandTimeout.inWholeMilliseconds.toInt())
            if (output.isTimeout) {
                logger.warn("Timed out running ${commandLine.commandLineString}")
                null
            } else {
                ConsoleProcessResult(output.exitCode == 0, output.stdout, output.stderr)
            }
        } catch (exception: ExecutionException) {
            logger.debug("Failed to run ${commandLine.commandLineString}", exception)
            null
        }
    }

    private data class ConsoleProcessResult(val success: Boolean, val stdout: String, val stderr: String)

    /**
     * Runs [commandLine] in a killable Run-console tab, echoing the executed command as its first line,
     * and suspends until the process ends. The collected stdout and stderr are returned for parsing.
     * When [inBackground] is true, adding the tab does not activate the Run tool window or focus its content.
     */
    private suspend fun runInRunConsole(
        commandLine: GeneralCommandLine,
        consoleTitle: String,
        inBackground: Boolean = false,
    ): ConsoleProcessResult {
        val processHandler = try {
            KillableProcessHandler(commandLine)
        } catch (exception: ExecutionException) {
            logger.warn("Failed to start ${commandLine.commandLineString}", exception)
            return ConsoleProcessResult(success = false, stdout = "", stderr = exception.message.orEmpty())
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()

        /* The process is already running: whatever goes wrong from here on (e.g. cancellation), it must not outlive us. */
        try {
            processHandler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val buffer = when (outputType) {
                        ProcessOutputTypes.STDOUT -> stdout
                        ProcessOutputTypes.STDERR -> stderr
                        else -> return
                    }

                    synchronized(buffer) {
                        buffer.append(event.text)
                    }
                }
            })

            withContext(Dispatchers.EDT) {
                val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
                console.print("$ ${commandLine.commandLineString}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                console.attachToProcess(processHandler)

                val descriptor = RunContentDescriptor(console, processHandler, console.component, consoleTitle, null, null)
                descriptor.isActivateToolWindowWhenAdded = !inBackground
                descriptor.isAutoFocusContent = !inBackground
                RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
            }

            processHandler.startNotify()
            awaitProcessTermination(processHandler)
        } catch (exception: Throwable) {
            stopProcess(processHandler)
            throw exception
        }

        return ConsoleProcessResult(
            success = processHandler.exitCode == 0,
            stdout = synchronized(stdout) { stdout.toString() },
            stderr = synchronized(stderr) { stderr.toString() },
        )
    }

    private suspend fun awaitProcessTermination(processHandler: ProcessHandler) =
        suspendCancellableCoroutine { continuation ->
            processHandler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    /* Resuming an already resumed or cancelled continuation is a harmless no-op. */
                    continuation.resumeWith(Result.success(Unit))
                }
            })

            if (processHandler.isProcessTerminated) continuation.resumeWith(Result.success(Unit))

            /* Cancelling the background progress stops the CLI as well. */
            continuation.invokeOnCancellation { stopProcess(processHandler) }
        }

    /**
     * `destroyProcess` waits for `startNotify`, so a handler that was never started is started first.
     */
    private fun stopProcess(processHandler: ProcessHandler) {
        if (processHandler.isProcessTerminated) return
        if (!processHandler.isStartNotified) processHandler.startNotify()
        processHandler.destroyProcess()
    }

    private fun notifyCancelledWhileTriggering(branchName: String) {
        if (project.isDisposed) return

        val notification = createLaunchNotification(
            MyBundle.message("teamcity.launch.cancelled.while.triggering", branchName),
            NotificationType.INFORMATION,
            title = MyBundle.message("teamcity.notification.cancelled.title"),
        )

        notification.addAction(object : AnAction(MyBundle.message("teamcity.launch.open.build.configuration")) {
            override fun actionPerformed(e: AnActionEvent) {
                BrowserUtil.browse(buildConfigurationUrl(AGGREGATE_TRIGGER_BUILD_TYPE_ID, branchName))
            }
        })

        notification.notify(project)
    }

    private fun notifyLaunchSuccess(branchName: String, buildUrl: String?) {
        val content = if (buildUrl == null) {
            MyBundle.message("teamcity.launch.success.no.link", branchName)
        } else {
            MyBundle.message("teamcity.launch.success", branchName)
        }

        val notification = createLaunchNotification(content, NotificationType.INFORMATION)

        if (buildUrl != null) {
            notification.addAction(object : AnAction(MyBundle.message("teamcity.launch.open.in.teamcity")) {
                override fun actionPerformed(e: AnActionEvent) {
                    BrowserUtil.browse(buildUrl)
                }
            })
        }

        notification.notify(project)
    }

    /**
     * Shows the CLI's own error message when it printed one, else points to the Run console.
     */
    private fun notifyCliFailure(result: ConsoleProcessResult) {
        val cliErrorMessage = parseCliErrorMessage(result.stderr)

        if (cliErrorMessage == null) {
            notifyLaunchError(MyBundle.message("teamcity.launch.process.failed"))
        } else {
            notifyLaunchError(MyBundle.message("teamcity.launch.process.failed.with.message", escapeHtml(cliErrorMessage)))
        }
    }

    /**
     * Notification content is HTML: external text (e.g. CLI output) must not be interpreted as markup.
     */
    private fun escapeHtml(text: String): String = StringUtil.escapeXmlEntities(text)

    private fun notifyLaunchError(content: String) {
        createLaunchNotification(content, NotificationType.ERROR).notify(project)
    }

    private fun createLaunchNotification(
        content: String,
        type: NotificationType,
        title: String = defaultNotificationTitle(type),
    ): Notification =
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Kotlin Compiler DevKit Notifications")
            .createNotification(title, content.replace("\n", "<br>"), type)

    private fun defaultNotificationTitle(type: NotificationType): String =
        if (type == NotificationType.ERROR) {
            MyBundle.message("teamcity.notification.error.title")
        } else {
            MyBundle.message("teamcity.notification.success.title")
        }
}
