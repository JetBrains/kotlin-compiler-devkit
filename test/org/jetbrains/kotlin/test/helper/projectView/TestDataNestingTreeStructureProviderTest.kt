package org.jetbrains.kotlin.test.helper.projectView

import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewSettings
import com.intellij.ide.projectView.SelectableTreeStructureProvider
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.ProjectViewState
import com.intellij.ide.projectView.impl.nodes.FileNodeWithNestedFileNodes
import com.intellij.ide.projectView.impl.nodes.NestingTreeNode
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.util.registry.Registry
import com.intellij.projectView.TestProjectTreeStructure
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.test.helper.TestDataPathsConfiguration

/** The Project View settings with the defaults, including "File Nesting" on. */
private val nestingSettings: ProjectViewSettings = object : ProjectViewSettings {}

class TestDataNestingTreeStructureProviderTest : BasePlatformTestCase() {
    private lateinit var savedTestDataFiles: Array<String>
    private lateinit var savedTestDataDirectories: Array<String>

    override fun setUp() {
        super.setUp()
        val configuration = TestDataPathsConfiguration.getInstance(project)
        savedTestDataFiles = configuration.testDataFiles
        savedTestDataDirectories = configuration.testDataDirectories
        Registry.get(TEST_DATA_NESTING_REGISTRY_KEY).setValue(true, testRootDisposable)
    }

    override fun tearDown() {
        try {
            val configuration = TestDataPathsConfiguration.getInstance(project)
            configuration.testDataFiles = savedTestDataFiles
            configuration.testDataDirectories = savedTestDataDirectories
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testVariantsAreNestedUnderTheMainFile() {
        val directory = addFiles(
            "testData",
            "bar.kt", "foo.fir.txt", "foo.kt", "foo.ll.kt", "foo.txt", "noMain.fir.txt", "noMain.txt", "single.txt",
        )
        configureTestDataFiles()

        val result = modify(directory)

        assertEquals(listOf("bar.kt", "foo.kt", "noMain.fir.txt", "noMain.txt", "single.txt"), result.names())
        val foo = result.single { it.name() == "foo.kt" }
        assertInstanceOf(foo, NestingTreeNode::class.java)
        assertEquals(listOf("foo.fir.txt", "foo.ll.kt", "foo.txt"), foo.nestedNames())
        assertTrue((foo as ProjectViewNode<*>).contains(directory.virtualFile.findChild("foo.ll.kt")!!))
        assertFalse(result.single { it.name() == "bar.kt" } is NestingTreeNode)
    }

    fun testMainFileFollowsTheOrderOfSupportedExtensions() {
        val directory = addFiles("testData", "foo.args", "foo.kts", "foo.kt", "foo.kt.can-freeze-ide")
        configureTestDataFiles()

        val result = modify(directory)

        assertEquals(listOf("foo.kt"), result.names())
        assertEquals(listOf("foo.args", "foo.kt.can-freeze-ide", "foo.kts"), result.single().nestedNames())
    }

    fun testNumericPartsBelongToTheBaseName() {
        val directory = addFiles("testData", "foo.1.kt", "foo.1.txt", "foo.kt", "foo.txt")
        configureTestDataFiles()

        val result = modify(directory)

        assertEquals(listOf("foo.1.kt", "foo.kt"), result.names())
        assertEquals(listOf("foo.1.txt"), result.single { it.name() == "foo.1.kt" }.nestedNames())
        assertEquals(listOf("foo.txt"), result.single { it.name() == "foo.kt" }.nestedNames())
    }

    fun testNestedSubdirectoryOfTestDataIsGrouped() {
        val directory = addFiles("testData/nested", "foo.kt", "foo.txt")
        configureTestDataFiles()

        assertEquals(listOf("foo.kt"), modify(directory).names())
    }

    fun testDisabledRegistryKeepsChildren() {
        val directory = addFiles("testData", "foo.kt", "foo.txt")
        configureTestDataFiles()
        Registry.get(TEST_DATA_NESTING_REGISTRY_KEY).setValue(false)

        assertKeepsChildren(directory)
    }

    fun testDisabledFileNestingKeepsChildren() {
        val directory = addFiles("testData", "foo.kt", "foo.txt")
        configureTestDataFiles()
        val settings = object : ProjectViewSettings {
            override fun isUseFileNestingRules(): Boolean = false
        }

        assertKeepsChildren(directory, settings)
    }

    fun testDirectoryOutsideTestDataKeepsChildren() {
        val directory = addFiles("testData2", "foo.kt", "foo.txt")
        configureTestDataFiles()

        assertKeepsChildren(directory)
    }

    fun testDirectoryUnderTestDataDirectoriesKeepsChildren() {
        val directory = addFiles("testData/directoryTests/case", "foo.kt", "foo.txt")
        configureTestDataFiles()
        TestDataPathsConfiguration.getInstance(project).testDataDirectories = arrayOf(path("testData/directoryTests"))

        assertKeepsChildren(directory)
    }

    fun testFilesNestedByPlatformRulesAreKept() {
        val directory = addFiles("testData", "foo.fir.txt", "foo.fir.txt.map", "foo.kt", "foo.kt.map", "foo.txt")
        configureTestDataFiles()
        val nodes = fileNodes(directory).associateBy { it.name() }
        val children = listOf(
            NestingTreeNode(nodes.getValue("foo.fir.txt"), listOf(nodes.getValue("foo.fir.txt.map"))),
            NestingTreeNode(nodes.getValue("foo.kt"), listOf(nodes.getValue("foo.kt.map"))),
            nodes.getValue("foo.txt"),
        )

        val result = TestDataNestingTreeStructureProvider().modify(directoryNode(directory), children, nestingSettings)

        assertEquals(listOf("foo.kt"), result.names())
        assertEquals(listOf("foo.kt.map", "foo.fir.txt", "foo.fir.txt.map", "foo.txt"), result.single().nestedNames())
    }

    fun testChildrenOfFileNodeAreKept() {
        val directory = addFiles("testData", "foo.kt", "foo.txt")
        configureTestDataFiles()
        val fileNode = fileNodes(directory).first()

        val children = listOf(fileNode)
        assertSame(children, TestDataNestingTreeStructureProvider().modify(fileNode, children, nestingSettings))
    }

    /**
     * Runs all registered providers in their order: the Kotlin provider, which comes later, replaces the nesting node
     * of `foo.kt` with its own node and keeps the nested files. The children come in the directory listing order, which
     * the Project View sorts on its own, so the names are compared sorted.
     */
    fun testRegisteredProvidersKeepNestedFilesOfKotlinFile() {
        val directory = addFiles("testData", "foo.fir.kt", "foo.kt", "foo.txt")
        configureTestDataFiles()
        val structure = TestProjectTreeStructure(project, testRootDisposable)

        val children = structure.getChildElements(PsiDirectoryNode(project, directory, structure))

        val foo = children.single() as AbstractTreeNode<*>
        assertFalse(foo.javaClass.name, foo is NestingTreeNode)
        assertEquals(listOf("foo.fir.kt", "foo.txt"), foo.nestedNames().sorted())
        assertEquals(listOf("foo.fir.kt", "foo.txt"), structure.getChildElements(foo).map { (it as AbstractTreeNode<*>).name() }.sorted())
    }

    private fun assertKeepsChildren(directory: PsiDirectory, settings: ViewSettings = nestingSettings) {
        val children = fileNodes(directory)
        assertSame(children, TestDataNestingTreeStructureProvider().modify(directoryNode(directory), children, settings))
    }

    /**
     * Asks the registered selectable providers in their order and takes the first answer, like
     * `ProjectViewSelectInTarget.select` does, so the Kotlin provider, which answers for Kotlin files, is asked too.
     */
    fun testSelectInVariantOfKotlinMainFileSelectsTheMainFile() {
        val directory = addFiles("testData", "foo.fir.kt", "foo.kt", "foo.txt")
        val text = myFixture.addFileToProject("testData/bar.txt", "text")
        addFiles("testData", "bar.kts")
        configureTestDataFiles()

        assertEquals("foo.kt", selectedFileName(directory.findFile("foo.txt")!!))
        assertEquals("foo.kt", selectedFileName(directory.findFile("foo.fir.kt")!!))
        assertEquals("bar.kts", selectedFileName(text.findElementAt(0)!!))
    }

    fun testSelectInKeepsMainFilesAndVariantsOfOtherMainFiles() {
        val directory = addFiles("testData", "bar.args", "bar.txt", "foo.kt", "single.txt")
        configureTestDataFiles()

        for (name in listOf("bar.txt", "foo.kt", "single.txt")) {
            assertNull(name, TestDataNestingSelectInProvider().getTopLevelElement(directory.findFile(name)!!))
        }
    }

    fun testSelectInKeepsVariantsWhenDisabledOrOutsideTestData() {
        val directory = addFiles("testData", "foo.kt", "foo.txt")
        val outside = addFiles("testData2", "foo.kt", "foo.txt")
        configureTestDataFiles()
        val variant = directory.findFile("foo.txt")!!
        assertNotNull(TestDataNestingSelectInProvider().getTopLevelElement(variant))

        assertNull(TestDataNestingSelectInProvider().getTopLevelElement(outside.findFile("foo.txt")!!))

        val projectViewState = project.getService(ProjectViewState::class.java)
        projectViewState.useFileNestingRules = false
        try {
            assertNull(TestDataNestingSelectInProvider().getTopLevelElement(variant))
        } finally {
            projectViewState.useFileNestingRules = true
        }

        Registry.get(TEST_DATA_NESTING_REGISTRY_KEY).setValue(false)
        assertNull(TestDataNestingSelectInProvider().getTopLevelElement(variant))
    }

    private fun selectedFileName(element: PsiElement): String? {
        val providers = TestProjectTreeStructure(project, testRootDisposable).providers.orEmpty()
        val selected = providers.filterIsInstance<SelectableTreeStructureProvider>().firstNotNullOfOrNull { it.getTopLevelElement(element) }
        return (selected as? PsiFile)?.name
    }

    private fun modify(directory: PsiDirectory): Collection<AbstractTreeNode<*>> =
        TestDataNestingTreeStructureProvider().modify(directoryNode(directory), fileNodes(directory), nestingSettings)

    private fun addFiles(directoryPath: String, vararg names: String): PsiDirectory =
        names.map { myFixture.addFileToProject("$directoryPath/$it", "") }.first().containingDirectory

    private fun configureTestDataFiles() {
        TestDataPathsConfiguration.getInstance(project).testDataFiles = arrayOf(path("testData"))
    }

    private fun path(directoryPath: String): String = "${myFixture.tempDirFixture.getFile("")!!.path}/$directoryPath"

    private fun directoryNode(directory: PsiDirectory) = PsiDirectoryNode(project, directory, nestingSettings)

    private fun fileNodes(directory: PsiDirectory): List<PsiFileNode> =
        directory.files.sortedBy { it.name }.map { PsiFileNode(project, it, nestingSettings) }

    private fun AbstractTreeNode<*>.name(): String = (this as PsiFileNode).virtualFile!!.name

    private fun Collection<AbstractTreeNode<*>>.names(): List<String> = map { it.name() }

    private fun AbstractTreeNode<*>.nestedNames(): List<String> =
        (this as FileNodeWithNestedFileNodes).nestedFileNodes.map { it.name() }
}
