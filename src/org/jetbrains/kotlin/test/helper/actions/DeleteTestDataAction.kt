package org.jetbrains.kotlin.test.helper.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.rd.util.lifetime
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.test.helper.getRelatedTestFiles

class DeleteTestDataAction : AnAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.hasSelectedTestDataFiles
        return
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).orEmpty().ifEmpty { return }

        project.lifetime.coroutineScope.launch {
            @Suppress("UnstableApiUsage")
            writeCommandAction(project, "Deleting Test Data Files") {
                for (file in selectedFiles) {
                    val relatedTestFiles = file.getRelatedTestFiles(project)
                    relatedTestFiles.forEach { it.delete(this) }
                }
            }
        }
    }

}