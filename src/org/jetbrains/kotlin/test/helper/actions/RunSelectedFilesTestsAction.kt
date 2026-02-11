package org.jetbrains.kotlin.test.helper.actions

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import org.jetbrains.kotlin.test.helper.getTestDataType
import org.jetbrains.kotlin.test.helper.gradle.isGradleEnabled
import org.jetbrains.kotlin.test.helper.services.TestDataRunnerService

class RunSelectedFilesGroup : DefaultActionGroup() {
    override fun update(e: AnActionEvent) {
        e.updateRunSelectedPresentation()
        super.update(e)
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}

abstract class RunSelectedFilesActionBase : AnAction() {
    override fun update(e: AnActionEvent) {
        e.updateRunSelectedPresentation()
        return
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    abstract val isDebug: Boolean
}

val AnActionEvent.hasSelectedTestDataFiles: Boolean
    get() {
        val project = project ?: return false
        val selectedFiles = getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: emptyArray()
        return selectedFiles.any { it.getTestDataType(project) != null } && project.isGradleEnabled()
    }

private fun AnActionEvent.updateRunSelectedPresentation() {
    presentation.isEnabledAndVisible = hasSelectedTestDataFiles
}

abstract class AbstractRunSelectedFilesTestsAction : RunSelectedFilesActionBase() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<TestDataRunnerService>()
            .collectAndRunAllTests(e, e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList(), isDebug)
    }
}

class RunSelectedFilesTestsAction : AbstractRunSelectedFilesTestsAction() {
    override val isDebug: Boolean get() = false
}

class DebugSelectedFilesTestsAction : AbstractRunSelectedFilesTestsAction() {
    override val isDebug: Boolean get() = true
}

abstract class AbstractRunSelectedFilesSpecificTestsAction : RunSelectedFilesActionBase() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<TestDataRunnerService>()
            .collectAndRunSpecificTests(e, e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList(), isDebug)
    }
}

class RunSelectedFilesSpecificTestsAction : AbstractRunSelectedFilesSpecificTestsAction() {
    override val isDebug: Boolean get() = false
}

class DebugSelectedFilesSpecificTestsAction : AbstractRunSelectedFilesSpecificTestsAction() {
    override val isDebug: Boolean get() = true
}