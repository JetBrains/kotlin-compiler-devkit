package org.jetbrains.kotlin.test.helper.ui.settings

import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import org.jetbrains.kotlin.test.helper.PluginSettingsState
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableModel

class IgnoredLanguagesForInjectionPanel(private val state: PluginSettingsState) : AbstractSettingsPanel<String>() {
    private val ignoredLanguagesForInjection: MutableList<String>
        get() = state.ignoredLanguagesForInjection

    override val numberOfElements: Int
        get() = ignoredLanguagesForInjection.size

    override fun addElement(index: Int, element: String) {
        ignoredLanguagesForInjection.add(index, element)
    }

    override fun removeElementAt(index: Int) {
        ignoredLanguagesForInjection.removeAt(index)
    }

    override fun isElementExcluded(element: String): Boolean = false

    override fun createNewElementsOnAddClick(): List<String> = listOf("")

    override fun createMainComponent(): JComponent {
        val names = arrayOf("Language ID")
        val dataModel: TableModel = object : javax.swing.table.AbstractTableModel() {
            override fun getRowCount(): Int = ignoredLanguagesForInjection.size

            override fun getColumnCount(): Int = names.size

            override fun getColumnName(column: Int): String = names[column]

            override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java

            override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true

            override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = ignoredLanguagesForInjection[rowIndex]

            override fun setValueAt(aValue: Any, rowIndex: Int, columnIndex: Int) {
                if (aValue !is String) return
                ignoredLanguagesForInjection[rowIndex] = aValue
            }
        }

        myTable = JBTable(dataModel).apply {
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
            .setRemoveAction { onRemoveClick() }
            .createPanel()
    }

    init {
        initPanel()
    }
}
