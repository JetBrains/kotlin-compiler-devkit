package org.jetbrains.kotlin.test.helper.actions

import com.intellij.designer.actions.AbstractComboBoxAction
import com.intellij.diff.actions.CompareFilesAction
import com.intellij.diff.chains.DiffRequestChain
import com.intellij.filename.UniqueNameBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.util.minimumWidth
import com.intellij.util.ui.JBUI
import org.jetbrains.kotlin.test.helper.allExtensions
import org.jetbrains.kotlin.test.helper.state.PreviewEditorState
import org.jetbrains.kotlin.test.helper.ui.TestDataEditor
import org.jetbrains.kotlin.test.helper.ui.WidthAdjustingPanel
import java.io.File
import javax.swing.*

class ChooseAdditionalFileAction(
    private val testDataEditor: TestDataEditor,
    private val previewEditorState: PreviewEditorState
) : AbstractComboBoxAction<FileEditor>(), DumbAware {
    companion object {
        private const val NO_NAME_PROVIDED = "## no name provided ##"
    }

    /**
     * If two or more files have the same name, we want to display the parts of their full paths that differ.
     * This is the same thing that IDEA does for tab titles when two files with the same names are opened.
     */
    private var uniqueNameBuilder = createUniqueNameBuilder()

    val diffAction by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ShowDiffAction()
    }

    init {
        updateBoxList()
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        val label = JBLabel("Split with: ")

        return WidthAdjustingPanel(1).apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(label)
            add(super.createCustomComponent(presentation, place))
        }
    }

    override fun createComboBoxButton(presentation: Presentation): ComboBoxButton {
        val comboBoxButton = super.createComboBoxButton(presentation)
        @Suppress("DEPRECATION")
        comboBoxButton.minimumWidth = JBUI.scale(50)
        return comboBoxButton
    }

    override fun update(item: FileEditor, presentation: Presentation, popup: Boolean) {
        presentation.text = item.presentableName
    }

    private val FileEditor?.presentableName: String
        get() {
            val file = this?.file ?: return NO_NAME_PROVIDED
            val mainFile = testDataEditor.baseEditor.file
            if (file == mainFile) return "None"
            if (file.toNioPath().parent == mainFile?.toNioPath()?.parent)
                return file.allExtensions
            return uniqueNameBuilder?.getShortPath(file) ?: file.name
        }

    override fun selectionChanged(item: FileEditor): Boolean {
        previewEditorState.chooseNewEditor(item)
        testDataEditor.updatePreviewEditor()
        return true
    }

    private fun createUniqueNameBuilder(): UniqueNameBuilder<VirtualFile>? {
        val project = testDataEditor.editor.project ?: return null
        val builder = UniqueNameBuilder<VirtualFile>(project.basePath, File.separator)
        for (file in previewEditorState.previewEditors.mapNotNull { it.file }) {
            builder.addPath(file, file.path)
        }
        return builder
    }

    fun updateBoxList() {
        uniqueNameBuilder = createUniqueNameBuilder()
        setItems(previewEditorState.previewEditors, previewEditorState.currentPreview)
    }

    inner class ShowDiffAction : AnAction(
        "Show Diff",
        "Show diff between base and additional files",
        AllIcons.Actions.Diff
    ), DumbAware {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = previewEditorState.currentPreview.file.let { it != null && it != testDataEditor.file }
        }

        override fun actionPerformed(e: AnActionEvent) {
            val delegateAction = object : CompareFilesAction() {
                override fun getDiffRequestChain(e: AnActionEvent): DiffRequestChain? {
                    val originalFile = testDataEditor.file ?: return null
                    val secondFile = previewEditorState.currentPreview.file ?: return null
                    return createMutableChainFromFiles(
                        e.project,
                        originalFile,
                        secondFile,
                        null
                    )
                }
            }
            delegateAction.actionPerformed(e)
        }

        override fun getActionUpdateThread(): ActionUpdateThread {
            return ActionUpdateThread.BGT
        }
    }
}
