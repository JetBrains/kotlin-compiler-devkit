package org.jetbrains.kotlin.test.helper.state

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.test.helper.TestDataPathsConfiguration
import org.jetbrains.kotlin.test.helper.allExtensions
import org.jetbrains.kotlin.test.helper.simpleNameUntilFirstDot

class PreviewEditorState(
    private val baseEditor: TextEditor,
    currentPreviewExtension: String?,
    private val parent: Disposable,
) {
    var previewEditors: List<FileEditor> = findPreviewEditors()
        private set

    var currentPreviewIndex: Int = 0
        private set(value) {
            field = if (value !in previewEditors.indices) {
                0
            } else {
                value
            }
        }

    init {
        val indexByExtension = previewEditors.indexOfFirst { it.file?.allExtensions == currentPreviewExtension }
        currentPreviewIndex = if (indexByExtension != -1) {
            indexByExtension
        } else {
            previewEditors.indexOfFirst { it.file == baseEditor.file }
        }
    }

    val currentPreview: FileEditor
        get() = previewEditors.getOrNull(currentPreviewIndex) ?: run {
            currentPreviewIndex = 0
            baseEditor
        }

    val baseFileIsChosen: Boolean
        get() = baseEditor.file == currentPreview.file

    fun chooseNewEditor(editor: FileEditor) {
        if (editor !in previewEditors) {
            // TODO: log
            return
        }
        currentPreviewIndex = previewEditors.indexOf(editor).takeIf { it in previewEditors.indices } ?: 0
    }

    fun updatePreviewEditors() {
        val chosenPreview = currentPreview
        previewEditors = findPreviewEditors()
        currentPreviewIndex = previewEditors.indexOf(chosenPreview)
    }

    private fun findPreviewEditors(): List<FileEditor> {
        val file = baseEditor.file ?: return emptyList()
        if (!file.isValid) return emptyList()
        val project = baseEditor.editor.project ?: return emptyList()
        val configuration = TestDataPathsConfiguration.getInstance(project)

        val curFileName = file.simpleNameUntilFirstDot

        val relatedFiles = file.parent.children.filter { it.name.startsWith("$curFileName.") } +
                configuration.findAdditionalRelatedFiles(file, curFileName)

        return relatedFiles.map {
            TextEditorProvider.getInstance().createEditor(project, it).also { Disposer.register(parent, it) }
        }
    }
}
