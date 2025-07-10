package org.jetbrains.kotlin.test.helper.actions

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.externalSystem.util.task.TaskExecutionSpec
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.VcsNotifier
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.application
import git4idea.GitNotificationIdsHolder
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepositoryManager
import org.jetbrains.kotlin.test.helper.createGradleExternalSystemTaskExecutionSettings
import org.jetbrains.kotlin.test.helper.getTestDataType
import org.jetbrains.kotlin.test.helper.isGradleEnabled
import org.jetbrains.kotlin.test.helper.services.TestDataRunnerService
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Paths

class AutomateReproCommitAction : RunSelectedFilesActionBase() {
    
    companion object {
        private val logger = Logger.getInstance(AutomateReproCommitAction::class.java)
    }
    
    override val isDebug: Boolean = false
    
    // Store selected files for update method
    private var selectedFiles: List<VirtualFile> = emptyList()
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList() ?: return
        
        // Ask for ticket number
        val ticketNumber = Messages.showInputDialog(
            project,
            "Enter ticket number:",
            "Automate Repro Commit",
            Messages.getQuestionIcon()
        )
        
        if (ticketNumber.isNullOrBlank()) {
            return
        }
        
        logger.info("Starting automated repro commit for ticket: $ticketNumber")
        
        // Start the automated process
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Automating Repro Commit for $ticketNumber",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    executeAutomatedReproCommit(project, selectedFiles, ticketNumber, indicator)
                } catch (e: Exception) {
                    logger.error("Error during automated repro commit", e)
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Error during automated repro commit: ${e.message}",
                            "Automate Repro Commit Error"
                        )
                    }
                }
            }
        })
    }
    
    private fun executeAutomatedReproCommit(
        project: Project,
        selectedFiles: List<VirtualFile>,
        ticketNumber: String,
        indicator: ProgressIndicator
    ) {
        indicator.text = "Step 1/5: Generating tests..."
        indicator.fraction = 0.1
        
        // Step 1: Generate tests
        generateTestsAndWait(project, indicator)
        
        indicator.text = "Step 2/5: Running tests and applying diffs..."
        indicator.fraction = 0.2
        
        // Step 2: Run tests and apply diffs iteratively until green
        runTestsUntilGreen(project, selectedFiles, indicator)
        
        indicator.text = "Step 5/5: Committing changes..."
        indicator.fraction = 0.9
        
        // Step 5: Commit changes
        commitChanges(project, ticketNumber, indicator)
        
        indicator.text = "Automated repro commit completed!"
        indicator.fraction = 1.0
        
        ApplicationManager.getApplication().invokeLater {
            VcsNotifier.getInstance(project).notifySuccess(
                GitNotificationIdsHolder.COMMIT_SUCCESSFUL,
                "Automated Repro Commit",
                "Successfully automated repro commit for ticket $ticketNumber"
            )
        }
    }
    
    private fun generateTestsAndWait(project: Project, indicator: ProgressIndicator) {
        val basePath = project.basePath
        val (commandLine, title) = if (basePath != null &&
            (isAncestor(basePath, "compiler", "testData", "diagnostics") ||
                    isAncestor(basePath, "compiler", "fir", "analysis-tests", "testData"))
        ) {
            "generateFrontendApiTests compiler:tests-for-compiler-generator:generateTests" to "Generate Diagnostic Tests"
        } else {
            "generateTests" to "Generate Tests"
        }
        
        var completed = false
        var success = false
        
        ApplicationManager.getApplication().invokeLater {
            ExternalSystemUtil.runTask(
                TaskExecutionSpec.create(
                    project = project,
                    systemId = GradleConstants.SYSTEM_ID,
                    executorId = DefaultRunExecutor.getRunExecutorInstance().id,
                    settings = createGradleExternalSystemTaskExecutionSettings(
                        project, commandLine, useProjectBasePath = true
                    )
                )
                    .withActivateToolWindowBeforeRun(false)
                    .withCallback(object : TaskCallback {
                        override fun onSuccess() {
                            success = true
                            completed = true
                        }
                        
                        override fun onFailure() {
                            success = false
                            completed = true
                        }
                    }).build()
            )
        }
        
        // Wait for test generation to complete
        while (!completed && !indicator.isCanceled) {
            Thread.sleep(1000)
        }
        
        if (!success) {
            throw RuntimeException("Test generation failed")
        }
    }
    
    private fun runTestsUntilGreen(
        project: Project,
        selectedFiles: List<VirtualFile>,
        indicator: ProgressIndicator
    ) {
        var iteration = 1
        val maxIterations = 10
        
        while (iteration <= maxIterations && !indicator.isCanceled) {
            indicator.text = "Step ${2 + iteration}/5: Running tests (iteration $iteration)..."
            indicator.fraction = 0.2 + (iteration * 0.06)
            
            logger.info("Running tests iteration $iteration")
            
            var testsCompleted = false
            var testsPassed = false
            var testResults: SMTestProxy.SMRootTestProxy? = null
            
            // Set up test listener
            val connection = project.messageBus.connect()
            connection.subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsAdapter() {
                override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
                    connection.disconnect()
                    testResults = testsRoot
                    testsPassed = testsRoot.wasSuccessful()
                    testsCompleted = true
                    logger.info("Tests completed. Success: $testsPassed")
                }
            })
            
            // Run tests
            ApplicationManager.getApplication().invokeLater {
                project.service<TestDataRunnerService>()
                    .collectAndRunAllTests(null, selectedFiles, debug = false)
            }
            
            // Wait for tests to complete
            while (!testsCompleted && !indicator.isCanceled) {
                Thread.sleep(1000)
            }
            
            if (indicator.isCanceled) break
            
            if (testsPassed) {
                logger.info("All tests passed!")
                break
            } else {
                logger.info("Tests failed, applying diffs...")
                
                // Apply diffs
                testResults?.let { root ->
                    ApplicationManager.getApplication().invokeLater {
                        applyDiffs(arrayOf(root))
                    }
                    
                    // Wait a bit for diffs to be applied
                    Thread.sleep(2000)
                }
                
                iteration++
            }
        }
        
        if (iteration > maxIterations) {
            throw RuntimeException("Could not get tests to pass after $maxIterations iterations")
        }
    }
    
    private fun commitChanges(project: Project, ticketNumber: String, indicator: ProgressIndicator) {
        val repositoryManager = GitRepositoryManager.getInstance(project)
        val repository = repositoryManager.repositories.firstOrNull()
            ?: throw RuntimeException("No Git repository found")
        
        val git = Git.getInstance()
        
        // Add all changes
        val addHandler = GitLineHandler(project, repository.root, GitCommand.ADD)
        addHandler.addParameters(".")
        val addResult = git.runCommand(addHandler)
        
        if (!addResult.success()) {
            throw RuntimeException("Failed to add changes: ${addResult.errorOutputAsJoinedString}")
        }
        
        // Commit changes
        val commitMessage = "Automated repro commit for $ticketNumber"
        val commitHandler = GitLineHandler(project, repository.root, GitCommand.COMMIT)
        commitHandler.addParameters("-m", commitMessage)
        val commitResult = git.runCommand(commitHandler)
        
        if (!commitResult.success()) {
            throw RuntimeException("Failed to commit changes: ${commitResult.errorOutputAsJoinedString}")
        }
        
        logger.info("Successfully committed changes with message: $commitMessage")
    }
    
    private fun isAncestor(basePath: String, vararg strings: String): Boolean {
        val file = VfsUtil.findFile(Paths.get(basePath, *strings), false) ?: return false
        return selectedFiles.any { VfsUtil.isAncestor(file, it, false) }
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project ?: run {
            e.presentation.isEnabledAndVisible = false
            return
        }
        selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList() ?: emptyList()
        
        e.presentation.isEnabledAndVisible = selectedFiles.isNotEmpty() &&
                selectedFiles.any { it.getTestDataType(project) != null } &&
                project.isGradleEnabled()
    }
    
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}