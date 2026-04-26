package org.jetbrains.kotlin.test.helper

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.PanelWithButtons
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import org.jetbrains.kotlin.test.helper.ui.settings.IgnoredLanguagesForInjectionPanel
import org.jetbrains.kotlin.test.helper.ui.settings.RelatedFileSearchPathsPanel
import org.jetbrains.kotlin.test.helper.ui.settings.TestDataPathEntriesPanel
import org.jetbrains.kotlin.test.helper.ui.settings.TestTagsEntriesPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import kotlin.io.path.Path
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo

class PluginSettingsState(
    var testDataFiles: MutableList<VirtualFile>,
    var testDataDirectories: MutableList<VirtualFile>,
    var relatedFileSearchPaths: MutableList<Pair<VirtualFile, List<String>>>,
    var testTags: MutableList<Pair<String, List<String>>>,
    var ignoredLanguagesForInjection: MutableList<String>,
    var multifilePartsSeparator: Boolean,
    var directiveClassId: String,
)

internal val defaultIgnoredLanguagesForInjection: List<String> =
    listOf("Kotlin", "Java")

internal const val defaultDirectiveClassId: String =
    "org.jetbrains.kotlin.test.directives.model.Directive"

private const val TEST_DATA_FILE_PLACEHOLDER: String = "$" + "TEST_DATA_FILE$"

@Service(Service.Level.PROJECT)
@State(name = "TestDataPluginSettings", storages = [(Storage("kotlinTestDataPluginTestDataPaths.xml"))])
class TestDataPathsConfiguration : PersistentStateComponent<TestDataPathsConfiguration> {
    companion object {
        fun getInstance(project: Project): TestDataPathsConfiguration {
            return project.getService(TestDataPathsConfiguration::class.java)
        }

        private val logger = Logger.getInstance(TestDataPathsConfiguration::class.java)
    }

    var testDataFiles: Array<String> = emptyArray()

    var testDataDirectories: Array<String> = emptyArray()

    var relatedFilesSearchPaths: Map<String, Array<String>> = emptyMap()

    var testTags: Map<String, Array<String>> = emptyMap()

    var ignoredLanguagesForInjection: Array<String> = defaultIgnoredLanguagesForInjection.toTypedArray()

    var multifilePartsSeparator: Boolean = true

    var directiveClassId: String = defaultDirectiveClassId

    override fun getState(): TestDataPathsConfiguration {
        return this
    }

    override fun loadState(state: TestDataPathsConfiguration) {
        loadState(
            state.testDataFiles,
            state.testDataDirectories,
            state.relatedFilesSearchPaths,
            state.testTags,
            state.ignoredLanguagesForInjection,
            state.multifilePartsSeparator,
            state.directiveClassId,
        )
    }

    fun loadState(
        newTestDataFiles: List<VirtualFile>,
        newTestDataDirectories: List<VirtualFile>,
        newRelatedFilesSearchPaths: List<Pair<VirtualFile, List<String>>>,
        newTestTags: List<Pair<String, List<String>>>,
        newIgnoredLanguagesForInjection: List<String>,
        newMultifilePartsSeparator: Boolean,
        newDirectiveClassId: String,
    ) {
        loadState(
            newTestDataFiles.map { it.path }.toTypedArray(),
            newTestDataDirectories.map { it.path }.toTypedArray(),
            newRelatedFilesSearchPaths.associateBy(
                keySelector = { it.first.path },
                valueTransform = { it.second.toTypedArray() }
            ),
            newTestTags.associateBy(
                keySelector = { it.first },
                valueTransform = { it.second.toTypedArray() }
            ),
            newIgnoredLanguagesForInjection.toTypedArray(),
            newMultifilePartsSeparator,
            newDirectiveClassId,
        )
    }

    private fun loadState(
        newTestDataFiles: Array<String>,
        newTestDataDirectories: Array<String>,
        newRelatedFilesSearchPaths: Map<String, Array<String>>,
        newTestTags: Map<String, Array<String>>,
        newIgnoredLanguagesForInjection: Array<String>,
        newMultifilePartsSeparator: Boolean,
        newDirectiveClassId: String,
    ) {
        testDataFiles = newTestDataFiles.copyOf()
        testDataDirectories = newTestDataDirectories.copyOf()
        relatedFilesSearchPaths = newRelatedFilesSearchPaths.toMap()
        testTags = newTestTags.toMap()
        ignoredLanguagesForInjection = newIgnoredLanguagesForInjection.copyOf()
        multifilePartsSeparator = newMultifilePartsSeparator
        directiveClassId = newDirectiveClassId
    }

    /**
     * Iterate over files in [relatedFilesSearchPaths] related to [baseFile].
     *
     * The [run] closure accepts a search pattern and returns a [Boolean] indicating whether to abort iteration,
     * or continue.
     *
     * @return true if [run] returned true, otherwise false.
     */
    private fun searchRelatedFiles(
        baseFile: VirtualFile,
        simpleNameUntilFirstDot: String,
        run: (String) -> Boolean
    ): Boolean {
        for ((testDataSubdir, searchPaths) in relatedFilesSearchPaths) {
            try {
                val testDataSubdirPath = Path(testDataSubdir)
                val baseFilePath = baseFile.toNioPath()

                if (!baseFilePath.startsWith(testDataSubdirPath))
                    continue

                for (searchPath in searchPaths) {
                    val testDataFileReplacement = baseFilePath.relativeTo(testDataSubdirPath).parent
                        ?.resolve(simpleNameUntilFirstDot)?.pathString
                        ?: simpleNameUntilFirstDot
                    val actualSearchPath = searchPath.replace(TEST_DATA_FILE_PLACEHOLDER, testDataFileReplacement).trim()

                    if (run(actualSearchPath))
                        return true
                }
            } catch (e: IllegalArgumentException) {
                logger.warn(e)
                continue
            }
        }
        return false
    }

    fun findAdditionalRelatedFiles(
        baseFile: VirtualFile,
        simpleNameUntilFirstDot: String,
    ): List<VirtualFile> {
        val relatedFiles = mutableListOf<VirtualFile>()
        searchRelatedFiles(baseFile, simpleNameUntilFirstDot) { searchPattern ->
            glob(searchPattern) {
                baseFile.fileSystem.findFileByPath(it.pathString)?.let(relatedFiles::add)
            }
            false
        }
        return relatedFiles
    }
}

class TestDataPathsConfigurable(private val project: Project) :
    BoundConfigurable(MyBundle.message("pluginSettingsDisplayName"), "Tools.KotlinTestDataPluginSettings") {

    private val configuration: TestDataPathsConfiguration = TestDataPathsConfiguration.getInstance(project)

    // -------------------------------- state --------------------------------

    private val state: PluginSettingsState = PluginSettingsState(
        testDataFiles = resetTestData(isFiles = true),
        testDataDirectories = resetTestData(isFiles = false),
        relatedFileSearchPaths = resetRelatedFileSearchPaths(),
        testTags = resetTestTags(),
        ignoredLanguagesForInjection = resetIgnoredLanguagesForInjection(),
        multifilePartsSeparator = resetMultifilePartsSeparator(),
        directiveClassId = resetDirectiveClassId(),
    )

    private var testDataFiles: MutableList<VirtualFile>
        get() = state.testDataFiles
        set(value) { state.testDataFiles = value }

    private var testDataDirectories: MutableList<VirtualFile>
        get() = state.testDataDirectories
        set(value) { state.testDataDirectories = value }

    private var relatedFileSearchPaths: MutableList<Pair<VirtualFile, List<String>>>
        get() = state.relatedFileSearchPaths
        set(value) { state.relatedFileSearchPaths = value }

    private var testTags: MutableList<Pair<String, List<String>>>
        get() = state.testTags
        set(value) { state.testTags = value }

    private var ignoredLanguagesForInjection: MutableList<String>
        get() = state.ignoredLanguagesForInjection
        set(value) { state.ignoredLanguagesForInjection = value }

    private var multifilePartsSeparator: Boolean
        get() = state.multifilePartsSeparator
        set(value) { state.multifilePartsSeparator = value }

    private var directiveClassId: String
        get() = state.directiveClassId
        set(value) { state.directiveClassId = value }

    // -------------------------------- state initialization --------------------------------

    private fun resetTestData(isFiles: Boolean): MutableList<VirtualFile> {
        val fileSystem = VirtualFileManager.getInstance().getFileSystem("file")
        return (if (isFiles) configuration.testDataFiles else configuration.testDataDirectories).mapNotNullTo(mutableListOf()) { fileSystem.findFileByPath(it) }
    }

    private fun resetRelatedFileSearchPaths(): MutableList<Pair<VirtualFile, List<String>>> {
        val fileSystem = VirtualFileManager.getInstance().getFileSystem("file")
        return configuration.relatedFilesSearchPaths.mapNotNullTo(mutableListOf()) { entry ->
            fileSystem.findFileByPath(entry.key)?.let { Pair(it, entry.value.toList()) }
        }
    }

    private fun resetTestTags(): MutableList<Pair<String, List<String>>> {
        return configuration.testTags.mapNotNullTo(mutableListOf()) { entry ->
            entry.key to entry.value.toList()
        }
    }

    private fun resetIgnoredLanguagesForInjection(): MutableList<String> {
        return configuration.ignoredLanguagesForInjection.toMutableList()
    }

    private fun resetMultifilePartsSeparator(): Boolean {
        return configuration.multifilePartsSeparator
    }

    private fun resetDirectiveClassId(): String {
        return configuration.directiveClassId
    }

    // -------------------------------- panels --------------------------------

    private val testDataFilesPathPanel: TestDataPathEntriesPanel by lazy {
        TestDataPathEntriesPanel(project, state.testDataFiles)
    }

    private val testDataDirectoriesPathPanel: TestDataPathEntriesPanel by lazy {
        TestDataPathEntriesPanel(project, state.testDataDirectories)
    }

    private val relatedFilesSearchPathsPanel: RelatedFileSearchPathsPanel by lazy {
        RelatedFileSearchPathsPanel(project, state)
    }

    private val testTagsPanel: TestTagsEntriesPanel by lazy {
        TestTagsEntriesPanel(state)
    }

    private val ignoredLanguagesForInjectionPanel: IgnoredLanguagesForInjectionPanel by lazy {
        IgnoredLanguagesForInjectionPanel(state)
    }

    private val multifilePartsSeparatorCheckbox: JBCheckBox by lazy {
        JBCheckBox("Show separators between multifile test data parts", state.multifilePartsSeparator).apply {
            addChangeListener {
                state.multifilePartsSeparator = isSelected
            }
        }
    }

    private val directiveClassIdField: JBTextField by lazy {
        JBTextField(state.directiveClassId).apply {
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) {
                    state.directiveClassId = text.trim()
                }

                override fun removeUpdate(e: DocumentEvent?) {
                    state.directiveClassId = text.trim()
                }

                override fun changedUpdate(e: DocumentEvent?) {
                    state.directiveClassId = text.trim()
                }
            })
        }
    }

    override fun createPanel(): DialogPanel {
        fun Panel.panelRow(title: String, panel: PanelWithButtons) {
            row(title) {}
            row {
                cell(panel).align(AlignX.FILL)
            }
            separator()
        }

        return panel {
            panelRow("Test data files:", testDataFilesPathPanel)
            panelRow("Test data directories:", testDataDirectoriesPathPanel)
            panelRow("Related file search paths:", relatedFilesSearchPathsPanel)
            panelRow("Test tags:", testTagsPanel)
            panelRow("Ignored injected languages:", ignoredLanguagesForInjectionPanel)
            row {
                cell(multifilePartsSeparatorCheckbox)
            }
            row("Directive class id:") {
                cell(directiveClassIdField).align(AlignX.FILL)
            }
        }
    }

    // -------------------------------- other stuff --------------------------------

    private fun testDataModified(isFiles: Boolean): Boolean {
        val testDataFilesOrDirectories: List<VirtualFile>
        val filesOrDirectoriesFromConfiguration: Array<String>
        if (isFiles) {
            testDataFilesOrDirectories = testDataFiles
            filesOrDirectoriesFromConfiguration = configuration.testDataFiles
        } else {
            testDataFilesOrDirectories = testDataDirectories
            filesOrDirectoriesFromConfiguration = configuration.testDataDirectories
        }

        if (filesOrDirectoriesFromConfiguration.size != testDataFilesOrDirectories.size) return true
        return filesOrDirectoriesFromConfiguration.zip(testDataFilesOrDirectories).any { it.first != it.second.path }
    }

    private fun relatedFilesSearchPathsModified(): Boolean {
        val pathsFromConfiguration = configuration.relatedFilesSearchPaths
        return pathsFromConfiguration.size != relatedFileSearchPaths.size || pathsFromConfiguration.asSequence().zip(relatedFileSearchPaths.asSequence())
            .any { it.first.key != it.second.first.path || it.first.value.toList() != it.second.second }
    }

    private fun testTagsModified(): Boolean {
        val tagsFromConfiguration = configuration.testTags
        return tagsFromConfiguration.size != testTags.size || tagsFromConfiguration.asSequence().zip(testTags.asSequence())
            .any { it.first.key != it.second.first || it.first.value.toList() != it.second.second }
    }

    private fun ignoredLanguagesForInjectionModified(): Boolean {
        val ignoredLanguagesFromConfiguration = configuration.ignoredLanguagesForInjection
        return ignoredLanguagesFromConfiguration.size != ignoredLanguagesForInjection.size || ignoredLanguagesFromConfiguration.asSequence().zip(ignoredLanguagesForInjection.asSequence())
            .any { it.first != it.second }
    }

    private fun multifilePartsSeparatorModified(): Boolean {
        return configuration.multifilePartsSeparator != multifilePartsSeparator
    }

    private fun directiveClassIdModified(): Boolean {
        return configuration.directiveClassId != directiveClassId
    }

    override fun isModified(): Boolean {
        return testDataModified(isFiles = true) ||
            testDataModified(isFiles = false) ||
            relatedFilesSearchPathsModified() ||
            testTagsModified() ||
            ignoredLanguagesForInjectionModified() ||
            multifilePartsSeparatorModified() ||
            directiveClassIdModified()
    }

    override fun reset() {
        super.reset()
        testDataFiles = resetTestData(isFiles = true)
        testDataDirectories = resetTestData(isFiles = false)
        relatedFileSearchPaths = resetRelatedFileSearchPaths()
        testTags = resetTestTags()
        ignoredLanguagesForInjection = resetIgnoredLanguagesForInjection()
        multifilePartsSeparator = resetMultifilePartsSeparator()
        directiveClassId = resetDirectiveClassId()
        multifilePartsSeparatorCheckbox.isSelected = multifilePartsSeparator
        directiveClassIdField.text = directiveClassId
        (testDataFilesPathPanel.myTable.model as? AbstractTableModel)?.fireTableDataChanged()
        (testDataDirectoriesPathPanel.myTable.model as? AbstractTableModel)?.fireTableDataChanged()
        (relatedFilesSearchPathsPanel.myTable.model as? AbstractTableModel)?.fireTableDataChanged()
        (testTagsPanel.myTable.model as? AbstractTableModel)?.fireTableDataChanged()
        (ignoredLanguagesForInjectionPanel.myTable.model as? AbstractTableModel)?.fireTableDataChanged()
    }

    override fun apply() {
        super.apply()
        configuration.loadState(
            testDataFiles,
            testDataDirectories,
            relatedFileSearchPaths,
            testTags,
            ignoredLanguagesForInjection,
            multifilePartsSeparator,
            directiveClassId,
        )
    }
}
