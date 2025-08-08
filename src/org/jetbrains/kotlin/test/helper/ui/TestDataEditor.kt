package org.jetbrains.kotlin.test.helper.ui

import com.intellij.codeHighlighting.BackgroundEditorHighlighter
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.*
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.pom.Navigatable
import com.intellij.ui.JBSplitter
import com.intellij.util.SingleAlarm
import com.intellij.util.ui.JBUI
import org.jetbrains.kotlin.test.helper.actions.ChooseAdditionalFileAction
import org.jetbrains.kotlin.test.helper.actions.GeneratedTestComboBoxAction
import org.jetbrains.kotlin.test.helper.allExtensions
import org.jetbrains.kotlin.test.helper.state.PreviewEditorState
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import javax.swing.JComponent

class TestDataEditor(
    val baseEditor: TextEditor,
    private val name: String = "Test Data"
) : UserDataHolderBase(), TextEditor {

    init {
        baseEditor.file?.fileSystem?.let { registerListener(it) }
        Disposer.register(this, baseEditor)
    }

    // ------------------------------------- components -------------------------------------

    private val previewEditorState: PreviewEditorState = PreviewEditorState(
        baseEditor,
        PropertiesComponent.getInstance().getValue(lastUsedPreviewPropertyName),
        this,
    )

    private lateinit var editorViewMode: EditorViewMode
    lateinit var chooseAdditionalFileAction: ChooseAdditionalFileAction

    private val splitter: JBSplitter by lazy {
        JBSplitter(false, 0.5f, 0.15f, 0.85f).apply {
            splitterProportionKey = splitterProportionKey
            firstComponent = baseEditor.component
            secondComponent = previewEditorState.currentPreview.component
            dividerWidth = 3
        }
    }

    private val myComponent: JComponent by lazy {
        JBUI.Panels.simplePanel(splitter).addToTop(myToolbarWrapper).also {
            updatePreviewEditor()
        }
    }

    private val myToolbarWrapper: SplitToolbarPanel by lazy {
        fun ActionToolbar.updateConfig() {
            targetComponent = splitter
            isReservePlaceAutoPopupIcon = false
        }

        val leftToolbar = createFileChooserToolbar().apply { updateConfig() }
        val rightToolbar = createTestRunToolbar().apply { updateConfig() }
        SplitToolbarPanel(leftToolbar, rightToolbar)
    }

    enum class EditorViewMode {
        OnlyBaseEditor,
        BaseAndAdditionalEditor;
    }

    private fun createFileChooserToolbar(): ActionToolbar {
        chooseAdditionalFileAction = ChooseAdditionalFileAction(this, previewEditorState)
        return ActionManager
            .getInstance()
            .createActionToolbar(
                "TestDataHelperToolbar_left",
                DefaultActionGroup(
                    chooseAdditionalFileAction,
                    chooseAdditionalFileAction.diffAction
                ),
                true
            )
    }

    fun updatePreviewEditor() {
        val viewMode = if (previewEditorState.baseFileIsChosen) {
            EditorViewMode.OnlyBaseEditor
        } else {
            EditorViewMode.BaseAndAdditionalEditor
        }
        splitter.secondComponent = previewEditorState.currentPreview.component
        editorViewMode = viewMode
        PropertiesComponent.getInstance()
            .setValue(
                lastUsedPreviewPropertyName,
                previewEditorState.currentPreview.file.allExtensions
            )
        baseEditor.component.isVisible = true
        previewEditorState.currentPreview.component.isVisible = editorViewMode == EditorViewMode.BaseAndAdditionalEditor
    }


    private fun createTestRunToolbar(): ActionToolbar {
        val generatedTestComboBoxAction = GeneratedTestComboBoxAction(baseEditor)

        val connection = baseEditor.editor.project?.messageBus?.connect(this)

        connection?.subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
            override fun exitDumbMode() = generatedTestComboBoxAction.state.updateTestsList()
        })

        connection?.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processTerminated(
                executorId: String,
                env: ExecutionEnvironment,
                handler: ProcessHandler,
                exitCode: Int
            ) = generatedTestComboBoxAction.state.updateTestsList()
        })

        return ActionManager
            .getInstance()
            .createActionToolbar(
                "TestDataHelperToolbar_right",
                DefaultActionGroup(
                    generatedTestComboBoxAction,
                    generatedTestComboBoxAction.runAction,
                    generatedTestComboBoxAction.debugAction,
                    generatedTestComboBoxAction.goToAction,
                    generatedTestComboBoxAction.runAllTestsAction,
                    generatedTestComboBoxAction.moreActionsGroup
                ),
                true
            )
    }

    // ------------------------------------- actions -------------------------------------

    override fun setState(state: FileEditorState) {
        if (state !is MyFileEditorState) return
        if (state.firstState != null) {
            baseEditor.setState(state.firstState)
        }
        if (state.secondState != null) {
            this@TestDataEditor.previewEditorState.currentPreview.setState(state.secondState)
        }
        if (state.splitLayout != null) {
            editorViewMode = state.splitLayout
            invalidateLayout()
        }
    }

    private fun invalidateLayout() {
        updatePreviewEditor()
        myToolbarWrapper.refresh()
        myComponent.repaint()
        val focusComponent = preferredFocusedComponent
        if (focusComponent != null) {
            IdeFocusManager.findInstanceByComponent(focusComponent).requestFocus(focusComponent, true)
        }
    }

    private val listenersGenerator: ListenersMultimap = ListenersMultimap()

    private inner class DoublingEventListenerDelegate(private val myDelegate: PropertyChangeListener) : PropertyChangeListener {
        override fun propertyChange(evt: PropertyChangeEvent) {
            myDelegate.propertyChange(
                PropertyChangeEvent(this@TestDataEditor, evt.propertyName, evt.oldValue, evt.newValue)
            )
        }
    }

    private inner class ListenersMultimap {
        private val myMap: MutableMap<PropertyChangeListener, Pair<Int, DoublingEventListenerDelegate>> = HashMap()

        fun addListenerAndGetDelegate(listener: PropertyChangeListener): DoublingEventListenerDelegate {
            if (!myMap.containsKey(listener)) {
                myMap[listener] =
                    Pair.create(
                        1,
                        DoublingEventListenerDelegate(listener)
                    )
            } else {
                val oldPair = myMap[listener]!!
                myMap[listener] =
                    Pair.create(
                        oldPair.getFirst() + 1,
                        oldPair.getSecond()
                    )
            }
            return myMap[listener]!!.getSecond()
        }

        fun removeListenerAndGetDelegate(listener: PropertyChangeListener): DoublingEventListenerDelegate? {
            val oldPair = myMap[listener] ?: return null
            if (oldPair.getFirst() == 1) {
                myMap.remove(listener)
            } else {
                myMap[listener] =
                    Pair.create(
                        oldPair.getFirst() - 1,
                        oldPair.getSecond()
                    )
            }
            return oldPair.getSecond()
        }
    }

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        baseEditor.addPropertyChangeListener(listener)
        val previewEditor = previewEditorState.currentPreview
        previewEditor.addPropertyChangeListener(listener)
        val delegate = listenersGenerator.addListenerAndGetDelegate(listener)
        baseEditor.addPropertyChangeListener(delegate)
        previewEditor.addPropertyChangeListener(delegate)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        baseEditor.removePropertyChangeListener(listener)
        val previewEditor = previewEditorState.currentPreview
        previewEditor.removePropertyChangeListener(listener)
        val delegate = listenersGenerator.removeListenerAndGetDelegate(listener)
        if (delegate != null) {
            baseEditor.removePropertyChangeListener(delegate)
            previewEditor.removePropertyChangeListener(delegate)
        }
    }

    class MyFileEditorState(
        val splitLayout: EditorViewMode?,
        val firstState: FileEditorState?,
        val secondState: FileEditorState?
    ) : FileEditorState {
        override fun canBeMergedWith(otherState: FileEditorState, level: FileEditorStateLevel): Boolean {
            return (otherState is MyFileEditorState
                    && (firstState == null || firstState.canBeMergedWith(otherState.firstState!!, level))
                    && (secondState == null || secondState.canBeMergedWith(otherState.secondState!!, level)))
        }
    }

    private fun registerListener(fileSystem: VirtualFileSystem) {
        val listener = TestDataFileUpdateListener()
        val disposable = Disposable { fileSystem.removeVirtualFileListener(listener) }
        Disposer.register(this, disposable)
        fileSystem.addVirtualFileListener(listener, disposable)
    }

    private inner class TestDataFileUpdateListener : VirtualFileListener {
        private val updateAlarm = SingleAlarm(this::updatePreviewList, 500)

        override fun fileCreated(event: VirtualFileEvent) {
            updateAlarm.cancelAndRequest()
        }

        override fun fileDeleted(event: VirtualFileEvent) {
            updateAlarm.cancelAndRequest()
        }

        override fun fileMoved(event: VirtualFileMoveEvent) {
            updateAlarm.cancelAndRequest()
        }

        private fun updatePreviewList() {
            if (baseEditor.editor.project?.isDisposed == true) {
                updateAlarm.cancel()
                return
            }
            previewEditorState.updatePreviewEditors()
            chooseAdditionalFileAction.updateBoxList()
            updatePreviewEditor()
        }
    }

    // ------------------------------------- default methods -------------------------------------

    override fun getComponent(): JComponent {
        return myComponent
    }

    override fun getBackgroundHighlighter(): BackgroundEditorHighlighter? {
        return baseEditor.backgroundHighlighter
    }

    override fun getCurrentLocation(): FileEditorLocation? {
        return baseEditor.currentLocation
    }

    override fun getStructureViewBuilder(): StructureViewBuilder? {
        return baseEditor.structureViewBuilder
    }

    override fun dispose() {
    }

    override fun selectNotify() {
        baseEditor.selectNotify()
        previewEditorState.previewEditors.forEach { it.selectNotify() }
    }

    override fun deselectNotify() {
        baseEditor.deselectNotify()
        previewEditorState.previewEditors.forEach { it.deselectNotify() }
    }

    override fun getPreferredFocusedComponent(): JComponent? {
        return baseEditor.preferredFocusedComponent
    }

    override fun getName(): String {
        return name
    }

    override fun getState(level: FileEditorStateLevel): FileEditorState {
        return MyFileEditorState(editorViewMode, baseEditor.getState(level), previewEditorState.currentPreview.getState(level))
    }

    override fun isModified(): Boolean {
        return baseEditor.isModified || previewEditorState.previewEditors.any { it.isModified }
    }

    override fun isValid(): Boolean {
        return baseEditor.isValid && previewEditorState.previewEditors.all { it.isValid }
    }

    private val lastUsedPreviewPropertyName: String
        get() = name + "LastUsedPreview"

    override fun getFile(): VirtualFile? {
        return baseEditor.file
    }

    override fun getEditor(): Editor {
        return baseEditor.editor
    }

    override fun canNavigateTo(navigatable: Navigatable): Boolean {
        return baseEditor.canNavigateTo(navigatable)
    }

    override fun navigateTo(navigatable: Navigatable) {
        baseEditor.navigateTo(navigatable)
    }
}
