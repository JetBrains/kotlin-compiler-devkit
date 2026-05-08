package org.jetbrains.kotlin.test.helper.actions

import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.stacktrace.DiffHyperlink
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.rd.util.lifetime
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import com.intellij.psi.PsiDocumentManager
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.test.helper.SUPPORTED_EXTENSIONS
import org.jetbrains.kotlin.test.helper.collectDiffsRecursively
import org.jetbrains.kotlin.test.helper.getRelatedTestFiles
import java.nio.file.Paths
import kotlin.collections.orEmpty

abstract class AbstractModifyTestFileAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val presentation = e.presentation
        val context = e.dataContext
        val tests = AbstractTestProxy.DATA_KEYS.getData(context).orEmpty()
        presentation.isEnabledAndVisible = tests
            .flatMap { it.collectDiffsRecursively(mutableListOf()) }
            .any { acceptDiff(it) }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val tests = AbstractTestProxy.DATA_KEYS.getData(e.dataContext) ?: return
        val project = e.project ?: return
        project.lifetime.coroutineScope.launch {
            val paths = tests
                .flatMap { it.collectDiffsRecursively(mutableListOf()) }
                .filter { acceptDiff(it) }
                .mapNotNull { it.filePath }
                .distinct()
                .ifEmpty { return@launch }

            @Suppress("UnstableApiUsage")
            writeCommandAction(project, "Modifying Test Files") {
                for (path in paths) {
                    val virtualFile = VfsUtil.findFile(Paths.get(path), true) ?: continue
                    for (relatedFile in virtualFile.getRelatedTestFiles(project)) {
                        handleFile(relatedFile, project)
                    }
                }
            }
        }
    }

    protected open fun handleFile(file: VirtualFile, project: Project) {
        if (file.extension !in SUPPORTED_EXTENSIONS) return
        val document = file.findDocument() ?: return
        updateDocument(document)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    protected abstract fun acceptDiff(hyperlink: DiffHyperlink): Boolean
    protected abstract fun updateDocument(document: Document)
}

class RemoveFirIdenticalAction : AbstractModifyTestFileAction() {
    override fun acceptDiff(hyperlink: DiffHyperlink): Boolean {
        return hyperlink.left.contains("FIR_IDENTICAL")
    }

    override fun updateDocument(document: Document) {
        document.removeLine("// FIR_IDENTICAL")
    }
}

class AddLatestLvDifference : AbstractModifyTestFileAction() {
    override fun acceptDiff(hyperlink: DiffHyperlink): Boolean {
        return !hyperlink.left.contains("LATEST_LV_DIFFERENCE")
    }

    override fun updateDocument(document: Document) {
        document.insertString(0, "// LATEST_LV_DIFFERENCE\n")
    }
}

class RemoveLatestLvDifference : AbstractModifyTestFileAction() {
    override fun acceptDiff(hyperlink: DiffHyperlink): Boolean {
        return hyperlink.left.contains("LATEST_LV_DIFFERENCE")
    }

    override fun handleFile(file: VirtualFile, project: Project) {
        if (SUPPORTED_EXTENSIONS.any { file.name.endsWith(".latestLV.$it") }) {
            file.delete(this)
        } else {
            super.handleFile(file, project)
        }
    }

    override fun updateDocument(document: Document) {
        document.removeLine("// LATEST_LV_DIFFERENCE")
    }
}

private fun Document.removeLine(string: String) {
    val newText = this.text.lines().filterNot { it == string }.joinToString("\n")
    replaceString(0, this.textLength, newText)
}