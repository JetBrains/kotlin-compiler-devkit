package org.jetbrains.kotlin.test.helper.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportSequentialProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.test.helper.gradle.GradleRunConfig
import org.jetbrains.kotlin.test.helper.gradle.computeGradleCommandLine
import org.jetbrains.kotlin.test.helper.gradle.runGradleCommandLineCancellable
import org.jetbrains.kotlin.test.helper.services.TestDataRunnerService
import org.jetbrains.kotlin.test.helper.toFileNamesString

class RunSelectedAndApplyDiffsAction : GradleOnlyAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList() ?: return

        val service = project.service<TestDataRunnerService>()
        service.scope.launch {
            withBackgroundProgress(project, "Running Specific & Applying Diffs") {
                val result = service.selectTestMethods(files, e) ?: return@withBackgroundProgress
                val commandLine = smartReadAction(service.project) {
                    computeGradleCommandLine(result.tests)
                }

                reportSequentialProgress { reporter ->
                    reporter.indeterminateStep("Running Specific Tests")

                    runTestAndApplyDiffLoop(project) {
                        withContext(Dispatchers.EDT) {
                            val config = GradleRunConfig(
                                commandLine,
                                title = e.toFileNamesString()?.let { "${result.prefix}: $it" },
                                debug = false,
                                useProjectBasePath = false,
                                runAsTest = true,
                            )

                            runGradleCommandLineCancellable(e, config)
                        }
                    }
                }
            }
        }
    }
}