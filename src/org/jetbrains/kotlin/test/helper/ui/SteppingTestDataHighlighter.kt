package org.jetbrains.kotlin.test.helper.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.event.BulkAwareDocumentListener
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.util.DocumentUtil

/**
 * In [TestDataEditor], if a given cursor is at a line which looks like
 * test.kt:6 box
 * highlights the line at the indicated number as though it was the current execution point of a dubugger, in this example line 6.
 * This is a convention used by Kotlin stepping tests, to map every debugger stop into a line of debugged Kotlin source code.
 * It helps to "feel" the debugging experience without the need to actually execute a given test program.
 */
class SteppingTestDataHighlighter(
    private val textEditor: TextEditor,
) : CaretListener, BulkAwareDocumentListener {
    private val highlightLineTextAttr = TextAttributesKey.find("EXECUTIONPOINT_ATTRIBUTES")
    private val markupPerCursor = mutableMapOf<Caret, RangeHighlighter>()

    fun register() {
        textEditor.editor.document.addDocumentListener(this, textEditor)
        textEditor.editor.caretModel.addCaretListener(this, textEditor)
    }

    override fun documentChanged(event: DocumentEvent) {
        val document = textEditor.editor.document
        val lineNumber = document.getLineNumber(event.offset)
        val caret = textEditor.editor.caretModel.currentCaret
        if (caret.logicalPosition.line == lineNumber) {
            updateLineHighlight(caret)
        }
    }

    override fun caretPositionChanged(event: CaretEvent) = updateLineHighlight(event.caret)
    override fun caretAdded(event: CaretEvent) = updateLineHighlight(event.caret)
    override fun caretRemoved(event: CaretEvent) {
        removeLineHighlight(event.caret)
    }

    private fun updateLineHighlight(caret: Caret) {
        // Wait for the selection model to update.
        ApplicationManager.getApplication().invokeLater {
            removeLineHighlight(caret)

            if (!caret.isValid) {
                return@invokeLater
            }

            val document = caret.editor.document
            if (document.getLineNumber(caret.selectionStart) != document.getLineNumber(caret.selectionEnd)) {
                // Do nothing if multiple lines are selected to avoid confusion.
                return@invokeLater
            }

            val caretLineNumber = caret.logicalPosition.line
            val lineRange = DocumentUtil.getLineTextRange(document, caretLineNumber)
            val lineText = document.getText(lineRange)

            val stepLineMatch = StepLineRegex.matchAt(lineText, 0)
            if (stepLineMatch != null) {
                val referenceLineNumber = (stepLineMatch.groupValues[1].toIntOrNull() ?: return@invokeLater) - 1
                if (DocumentUtil.isValidLine(referenceLineNumber, document)) {
                    val markup = caret.editor.markupModel.addLineHighlighter(
                        highlightLineTextAttr, referenceLineNumber, HighlighterLayer.CARET_ROW - 1
                    )
                    markupPerCursor[caret] = markup
                }
            }
        }
    }

    private fun removeLineHighlight(caret: Caret) {
        markupPerCursor.remove(caret)?.dispose()
    }

    companion object {
        private val StepLineRegex = Regex("""\/\/ (?:[\w-.]+):(\d+) """)
    }
}