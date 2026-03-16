package org.jetbrains.kotlin.test.helper.executor

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportSequentialProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.test.helper.actions.runTestAndApplyDiffLoop
import org.jetbrains.kotlin.test.helper.services.TestDataRunnerService

class RunAndApplyDiffsProgramRunner : ProgramRunner<Nothing> {
    override fun getRunnerId(): String = "RunAndApplyDiffsProgramRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        if (executorId != RunAndApplyDiffsExecutor.EXECUTOR_ID) return false
        return ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, profile) != null
    }

    override fun execute(environment: ExecutionEnvironment) {
        val project = environment.project
        val runnerAndSettings = environment.runnerAndConfigurationSettings ?: return
        val defaultExecutor = DefaultRunExecutor.getRunExecutorInstance()

        val service = project.service<TestDataRunnerService>()
        service.scope.launch {
            withBackgroundProgress(project, "Running and Applying Diffs") {
                reportSequentialProgress { reporter ->
                    reporter.indeterminateStep("Running")
                    runTestAndApplyDiffLoop(project) {
                        withContext(Dispatchers.EDT) {
                            ProgramRunnerUtil.executeConfiguration(runnerAndSettings, defaultExecutor)
                        }
                    }
                }
            }
        }
    }
}
