package org.jetbrains.kotlin.test.helper.ui.settings

import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import org.jetbrains.kotlin.test.helper.PluginSettingsState
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor
import javax.swing.table.TableModel

class TestTagsEntriesPanel(val state: PluginSettingsState) : AbstractSettingsPanel<String>() {
    private val testTags: MutableList<Pair<String, List<String>>>
        get() = state.testTags

    override val numberOfElements: Int
        get() = testTags.size

    override fun addElement(index: Int, element: String) {
        testTags.add(index, Pair(element, mutableListOf()))
    }

    override fun removeElementAt(index: Int) {
        testTags.removeAt(index)
    }

    override fun isElementExcluded(element: String): Boolean = false

    override fun createNewElementsOnAddClick(): List<String> {
        return listOf("")
    }

    override fun createMainComponent(): JComponent {
        val names = arrayOf(
            "Generated test name pattern",
            "List of related tags (separated by comma, no spaces)"
        )
        // Create a model of the data.
        val dataModel: TableModel = object : TwoColumnTableModel<String, List<String>>(testTags, names) {
            override fun String.presentableFirst(): String {
                return this
            }

            override fun List<String>.presentableSecond(): String {
                return joinToString(",")
            }

            override fun parseFirst(oldValue: String, newValue: String): String {
                return newValue
            }

            override fun parseSecond(
                oldValue: List<String>,
                newValue: String
            ): List<String> {
                return newValue.split(",")
            }
        }

        val expandableCellEditor = ExpandableCellEditor()

        myTable = object : JBTable(dataModel) {
            override fun getCellEditor(row: Int, column: Int): TableCellEditor {
                if (column == 1) return expandableCellEditor
                return super.getCellEditor(row, column)
            }
        }.apply {
            configure(names, DefaultTableCellRenderer())
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
