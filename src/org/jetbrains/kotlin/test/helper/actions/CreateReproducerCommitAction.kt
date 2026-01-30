package org.jetbrains.kotlin.test.helper.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.changes.actions.RefreshAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportSequentialProgress
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.test.helper.TestDataType
import org.jetbrains.kotlin.test.helper.getTestDataType
import org.jetbrains.kotlin.test.helper.gradle.generateTestsAndWait
import org.jetbrains.kotlin.test.helper.services.TestDataRunnerService

class CreateReproducerCommitAction : RunSelectedFilesActionBase() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList() ?: return

        val ticketNumber = Messages.showInputDialog(
            project,
            "Enter ticket number (e.g., KT-12345):",
            "Create Reproducer Commit",
            Messages.getQuestionIcon()
        )?.trim()?.ifBlank { null } ?: return

        val service = project.service<TestDataRunnerService>()
        service.scope.launch {
            withBackgroundProgress(project, "Creating Reproducer Commit for $ticketNumber") {
                reportSequentialProgress { reporter ->
                    reporter.nextStep(25) {
                        for (file in files) {
                            file.insertTicketNumberIfNecessary(ticketNumber, project)
                        }
                    }

                    reporter.nextStep(50, "Generating Tests") {
                        generateTestsAndWait(project, files)
                    }
                    reporter.nextStep(75, "Running Tests and Applying Diffs") {
                        runTestAndApplyDiffLoop(project) { service.doCollectAndRunAllTests(e, files, debug = false) }
                    }

                    reporter.nextStep(100, "Committing Changes") {
                        commitAll(project, ticketNumber)

                        withContext(Dispatchers.EDT) {
                            ActionUtil.performAction(RefreshAction(), e)
                        }
                    }

                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Kotlin Compiler DevKit Notifications")
                        .createNotification("Finished creating reproducer for $ticketNumber." , NotificationType.INFORMATION)
                        .notify(project)
                }
            }
        }
    }

    override val isDebug: Boolean
        get() = false

    private suspend fun VirtualFile.insertTicketNumberIfNecessary(ticketNumber: String, project: Project) {
        if (this.isDirectory || getTestDataType(project) != TestDataType.File) {
            return
        }

        @Suppress("UnstableApiUsage")
        writeCommandAction(project, "Adding Ticket Number") {
            val document = findDocument() ?: return@writeCommandAction

            if (document.text.lineSequence().any { it.startsWith("// ISSUE") && it.contains(ticketNumber) }) {
                return@writeCommandAction
            }

            document.insertString(0, "// ISSUE: $ticketNumber\n")
        }
    }

    private fun commitAll(project: Project, ticketNumber: String) {
        val repository = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
            ?: return

        val git = Git.getInstance()

        git.runCommand(
            GitLineHandler(
                project,
                repository.root,
                GitCommand.ADD
            ).apply {
                addParameters(".")
            }
        )

        git.runCommand(
            GitLineHandler(
                project,
                repository.root,
                GitCommand.COMMIT
            ).apply {
                addParameters("-m", "[Tests] Reproduce #$ticketNumber")
            }
        )
    }
}
