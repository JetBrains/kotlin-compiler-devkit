package org.jetbrains.kotlin.test.helper.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportSequentialProgress
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.test.helper.services.TestDataRunnerService

class RunAllAndApplyDiffsAction : GradleOnlyAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList() ?: return

        val service = project.service<TestDataRunnerService>()
        service.launchExclusively {
            withBackgroundProgress(project, "Running All Tests & Applying Diffs") {
                reportSequentialProgress { reporter ->
                    reporter.indeterminateStep("Running All Tests")
                    runTestAndApplyDiffLoop(project) {
                        service.doCollectAndRunAllTests(e, files, debug = false)
                    }
                }
            }
        }
    }
}