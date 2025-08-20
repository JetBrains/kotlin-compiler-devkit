package org.jetbrains.kotlin.test.helper.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import java.awt.datatransfer.StringSelection

/**
 * Copies the selected text into the clipboard without common Kotlin testdata metadata (diagnostics, <caret>, etc.).
 * A custom shortcut can be assigned for the "Copy Without Metadata" action in the IDE settings.
 */
class CopyTextWithoutMetadataAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val isEnabled = editor?.selectionModel?.hasSelection() == true
        e.presentation.setEnabled(isEnabled)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectedText = editor.selectionModel.selectedText
        if (selectedText.isNullOrBlank()) return
        val strippedText = selectedText.replace(allMetadataRegex, "")
        CopyPasteManager.getInstance().setContents(StringSelection(strippedText))
    }
}

// See org.jetbrains.kotlin.codeMetaInfo.CodeMetaInfoParser
private val openingDiagnosticRegex = """(<!([^"]*?((".*?")(, ".*?")*?)?[^"]*?)!>)""".toRegex()
private val closingDiagnosticRegex = """(<!>)""".toRegex()

private val xmlLikeTagsRegex = """(</?(?:selection|expr|caret)>)""".toRegex()

val allMetadataRegex =
    """(${closingDiagnosticRegex.pattern}|${openingDiagnosticRegex.pattern}|${xmlLikeTagsRegex.pattern})""".toRegex()
