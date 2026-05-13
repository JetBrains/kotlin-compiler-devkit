package org.jetbrains.kotlin.test.helper.ui.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import com.intellij.util.execution.ParametersListUtil
import org.jetbrains.kotlin.test.helper.PluginSettingsState
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.table.TableCellEditor
import javax.swing.table.TableModel

class RelatedFileSearchPathsPanel(project: Project, private val state: PluginSettingsState) : FileSettingsPanel(project) {
    private val relatedFileSearchPaths: MutableList<Pair<VirtualFile, List<String>>>
        get() = state.relatedFileSearchPaths

    override val numberOfElements: Int
        get() = relatedFileSearchPaths.size

    override fun addElement(index: Int, element: VirtualFile) {
        relatedFileSearchPaths.add(index, Pair(element, mutableListOf()))
    }

    override fun removeElementAt(index: Int) {
        relatedFileSearchPaths.removeAt(index)
    }

    override fun isElementExcluded(element: VirtualFile): Boolean =
        relatedFileSearchPaths.find { it.first == element } != null

    override fun createMainComponent(): JComponent {
        val names = arrayOf(
            "Test files",
            "Where to search for related files (wildcards are supported)"
        )
        // Create a model of the data.
        val dataModel: TableModel = object : TwoColumnTableModel<VirtualFile, List<String>>(relatedFileSearchPaths, names) {
            override fun VirtualFile.presentableFirst(): String {
                return presentableUrl
            }

            override fun List<String>.presentableSecond(): String {
                return ParametersListUtil.join(this)
            }

            override fun parseFirst(
                oldValue: VirtualFile,
                newValue: String
            ): VirtualFile? {
                val fileSystem = oldValue.fileSystem
                return fileSystem.findFileByPath(newValue)
            }

            override fun parseSecond(
                oldValue: List<String>,
                newValue: String
            ): List<String> {
                return ParametersListUtil.parse(newValue)
            }
        }

        val expandableCellEditor = ExpandableCellEditor()

        myTable = object : JBTable(dataModel) {
            override fun getCellEditor(row: Int, column: Int): TableCellEditor {
                if (column == 1) return expandableCellEditor
                return super.getCellEditor(row, column)
            }
        }.apply {
            configure(names, FilePathRenderer { relatedFileSearchPaths[it].first })
        }

        val defaultEditor = myTable.getDefaultEditor(String::class.java)
        if (defaultEditor is DefaultCellEditor) {
            defaultEditor.clickCountToStart = 1
        }

        return ToolbarDecorator.createDecorator(myTable)
            .disableUpAction()
            .disableDownAction()
            .setAddAction { onAddClick() }
            .setRemoveAction { onRemoveClick() }.createPanel()
    }

    init {
        initPanel()
    }
}

