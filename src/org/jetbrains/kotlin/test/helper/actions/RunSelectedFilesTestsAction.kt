package org.jetbrains.kotlin.test.helper.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import org.jetbrains.kotlin.test.helper.getTestDataType
import org.jetbrains.kotlin.test.helper.services.TestDataRunnerService

abstract class RunSelectedFilesActionBase : AnAction() {
    override fun update(e: AnActionEvent) {
        val project = e.project ?: return
        val selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: emptyArray()
        e.presentation.isEnabledAndVisible = selectedFiles.any { it.getTestDataType(project) != null }
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}

class RunSelectedFilesTestsAction : RunSelectedFilesActionBase() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<TestDataRunnerService>()
            .collectAndRunAllTests(e, e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList())
    }
}

class RunSelectedFilesSpecificTestsAction : RunSelectedFilesActionBase() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<TestDataRunnerService>()
            .collectAndRunSpecificTests(e, e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList())
    }
}
