package org.jetbrains.kotlin.test.helper.domain

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.ide.util.treeView.NodeRenderer
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.LayeredIcon
import java.io.File
import java.lang.reflect.Proxy
import javax.swing.Icon
import javax.swing.JTree

private const val IGNORED_TOOLTIP = "Ignored by test federation"

class TestFederationIgnoreProjectViewNodeDecoratorTest : BasePlatformTestCase() {
    /* A fresh project per test, so service-creation assertions do not depend on other tests. */
    override fun getProjectDescriptor(): LightProjectDescriptor = LightProjectDescriptor()

    fun testMarkerAbsentProjectDoesNotCreateIgnoreService() {
        val file = addProjectFile("tests/mute-common.csv", "muted")
        /* A directory at the marker path is not a configuration file. */
        addProjectDirectory("repo/domains.yaml")
        val node = PsiFileNode(project, PsiManager.getInstance(project).findFile(file)!!, viewSettings())

        node.update()
        TestFederationIgnoreProjectViewNodeDecorator().decorate(node, PresentationData())

        assertEquals(0, badgeCount(node.presentation.getIcon(false)))
        assertFalse(isIgnoredByTestFederation(project, file))
        assertNull(project.getServiceIfCreated(TestFederationIgnoreService::class.java))
    }

    fun testDecoratorActivatesWhenYamlMarkerAppears() {
        val ignoredFile = addProjectFile("tests/mute-common.csv", "muted")
        val decorator = TestFederationIgnoreProjectViewNodeDecorator()
        val node = PsiFileNode(project, PsiManager.getInstance(project).findFile(ignoredFile)!!, viewSettings())
        val presentation = PresentationData().apply { setIcon(AllIcons.FileTypes.Text) }

        decorator.decorate(node, presentation)
        assertEquals(AllIcons.FileTypes.Text, presentation.getIcon(false))
        assertNull(presentation.tooltip)

        addProjectFile(
            "repo/domains.yaml",
            """
                ignore:
                  - tests/mute-common.csv
                """.trimIndent(),
        )
        decorator.decorate(node, presentation)

        val layeredIcon = presentation.getIcon(false) as? LayeredIcon
            ?: error("Expected the ignored file to receive a badge after YAML marker creation")
        assertEquals(AllIcons.FileTypes.Text, layeredIcon.getIcon(0))
        assertEquals(ignoredBadgeIcon(), layeredIcon.getIcon(1))
        assertNotNull(project.getServiceIfCreated(TestFederationIgnoreService::class.java))

        val configurationFile = addProjectFile(
            "repo/domains.yaml",
            """
                ignore:
                  - tests/other.csv
                """.trimIndent(),
        )
        assertEquals(0, badgeCount(decoratedTextIcon(decorator, node)))

        addProjectFile(
            "repo/domains.yaml",
            """
                ignore:
                  - tests/*
                  - "!tests/other.csv"
                """.trimIndent(),
        )
        assertEquals(1, badgeCount(decoratedTextIcon(decorator, node)))

        WriteAction.runAndWait<RuntimeException> { configurationFile.rename(this, "domains.yaml.disabled") }
        assertEquals(0, badgeCount(decoratedTextIcon(decorator, node)))
    }

    fun testIgnoredFilesAndDirectoriesReceiveAnIdempotentBadge() {
        addProjectFile(
            "repo/domains.yaml",
            """
                ignore:
                  - tests/mute-common.csv
                  - tests/ignored
                """.trimIndent(),
        )
        val ignoredFile = addProjectFile("tests/mute-common.csv", "muted")
        val siblingFile = addProjectFile("tests/other.csv", "visible")
        val ignoredDirectory = addProjectDirectory("tests/ignored")
        val settings = viewSettings()
        val decorator = TestFederationIgnoreProjectViewNodeDecorator()
        val psiManager = PsiManager.getInstance(project)

        val ignoredFilePresentation = PresentationData().apply { setIcon(AllIcons.FileTypes.Text) }
        decorator.decorate(PsiFileNode(project, psiManager.findFile(ignoredFile)!!, settings), ignoredFilePresentation)
        val ignoredFileIcon = ignoredFilePresentation.getIcon(false) as? LayeredIcon
            ?: error("Expected the ignored file to have a badge")
        assertEquals(AllIcons.FileTypes.Text, ignoredFileIcon.getIcon(0))
        assertEquals(ignoredBadgeIcon(), ignoredFileIcon.getIcon(1))
        assertEquals(AllIcons.FileTypes.Text.iconWidth, ignoredFileIcon.iconWidth)
        assertEquals(AllIcons.FileTypes.Text.iconHeight, ignoredFileIcon.iconHeight)

        val siblingPresentation = PresentationData().apply { setIcon(AllIcons.FileTypes.Text) }
        decorator.decorate(PsiFileNode(project, psiManager.findFile(siblingFile)!!, settings), siblingPresentation)
        assertEquals(AllIcons.FileTypes.Text, siblingPresentation.getIcon(false))
        assertNull(siblingPresentation.tooltip)

        val directoryPresentation = PresentationData().apply {
            setIcon(AllIcons.Nodes.Folder)
            tooltip = "Existing tooltip"
        }
        val directoryNode = PsiDirectoryNode(project, psiManager.findDirectory(ignoredDirectory)!!, settings)
        decorator.decorate(directoryNode, directoryPresentation)
        decorator.decorate(directoryNode, directoryPresentation)
        directoryPresentation.markFederationIgnored()

        assertEquals(1, badgeCount(directoryPresentation.getIcon(false)))
        assertEquals(AllIcons.Nodes.Folder, (directoryPresentation.getIcon(false) as LayeredIcon).getIcon(0))
        assertEquals("Existing tooltip\n$IGNORED_TOOLTIP", directoryPresentation.tooltip)
    }

    fun testDomainPaneNodesReceiveYamlBadgeThroughRegisteredDecorator() {
        addProjectFile(
            "repo/domains.yaml",
            """
                ignore:
                  - domain-ignore-test/ignored
                  - domain-ignore-test/visible/*
                  - "!domain-ignore-test/visible/Kept.txt"
                  - domain-ignore-test/both
                """.trimIndent(),
        )
        val contentRoot = addProjectDirectory("domain-ignore-test")
        addProjectDirectory("domain-ignore-test/ignored")
        addProjectDirectory("domain-ignore-test/visible")
        addProjectDirectory("domain-ignore-test/both")
        addProjectFile("domain-ignore-test/visible/Ignored.txt", "ignored")
        addProjectFile("domain-ignore-test/visible/Kept.txt", "kept")
        WriteAction.runAndWait<RuntimeException> {
            val rootModel = ModuleRootManager.getInstance(module).modifiableModel
            rootModel.addContentEntry(contentRoot)
            rootModel.commit()
        }
        val settings = viewSettings()
        val dump = DomainDump(
            entries = listOf(
                DomainDumpEntry(setOf(Domain.of("Alpha")), RepositoryPath.of("ignored")),
                DomainDumpEntry(setOf(Domain.of("Alpha")), RepositoryPath.of("visible")),
                DomainDumpEntry(setOf(Domain.of("Alpha")), RepositoryPath.of("both")),
            ),
        )
        val domainNode = DomainGroupNode(
            project,
            settings,
            Domain.of("Alpha"),
            0,
            RepositoryRoot.of(contentRoot),
            dump.buildDomainPathTree(Domain.of("Alpha"), RepositoryRoot.of(contentRoot)) { true },
        )

        val directoryNodes = domainNode.children.filterIsInstance<DomainDirectoryNode>().associateBy { it.value.name }
        assertRendersBadge(directoryNodes.getValue("ignored"), expectedBadges = 1)
        val visibleNode = directoryNodes.getValue("visible")
        assertRendersBadge(visibleNode, expectedBadges = 0)
        val visibleFiles = visibleNode.children.associateBy { (it.value as PsiFile).name }
        assertRendersBadge(visibleFiles.getValue("Ignored.txt"), expectedBadges = 1)
        assertRendersBadge(visibleFiles.getValue("Kept.txt"), expectedBadges = 0)

        assertRendersBadge(directoryNodes.getValue("both"), expectedBadges = 1)
    }

    private fun assertRendersBadge(node: AbstractTreeNode<*>, expectedBadges: Int) {
        node.update()
        val renderer = NodeRenderer()
        renderer.customizeCellRenderer(JTree(), node, false, false, true, 0, false)

        assertEquals("Badges on ${node.value}", expectedBadges, badgeCount(renderer.icon))
        assertEquals(
            "Ignored tooltips on ${node.value}",
            expectedBadges,
            StringUtil.getOccurrenceCount(renderer.toolTipText.orEmpty(), IGNORED_TOOLTIP),
        )
    }

    private fun decoratedTextIcon(decorator: TestFederationIgnoreProjectViewNodeDecorator, node: PsiFileNode): Icon? {
        val presentation = PresentationData().apply { setIcon(AllIcons.FileTypes.Text) }
        decorator.decorate(node, presentation)
        return presentation.getIcon(false)
    }

    private fun badgeCount(icon: Icon?): Int = when (icon) {
        ignoredBadgeIcon() -> 1
        is LayeredIcon -> (0 until icon.iconCount).sumOf { badgeCount(icon.getIcon(it)) }
        else -> 0
    }

    private fun addProjectFile(path: String, contents: String): VirtualFile {
        val directory = addProjectDirectory(path.substringBeforeLast('/', ""))
        lateinit var file: VirtualFile
        WriteAction.runAndWait<RuntimeException> {
            val name = path.substringAfterLast('/')
            file = directory.findChild(name)?.takeIf { it.isValid } ?: directory.createChildData(this, name)
            file.setBinaryContent(contents.toByteArray())
        }
        return file
    }

    private fun addProjectDirectory(path: String): VirtualFile {
        File(project.basePath!!, path).mkdirs()
        lateinit var directory: VirtualFile
        WriteAction.runAndWait<RuntimeException> {
            directory = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)
                ?: error("Project base directory is not present in the local VFS")
            for (segment in path.split('/').filter { it.isNotEmpty() }) {
                directory = directory.findChild(segment)?.takeIf { it.isValid }
                    ?: directory.createChildDirectory(this, segment)
            }
        }
        return directory
    }

    private fun viewSettings(): ViewSettings = Proxy.newProxyInstance(
        ViewSettings::class.java.classLoader,
        arrayOf(ViewSettings::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            Integer.TYPE -> 0
            else -> null
        }
    } as ViewSettings

    private fun ignoredBadgeIcon() = IconLoader.getIcon("/icons/test_federation_ignored.svg", javaClass)

    override fun tearDown() {
        val probeDirectories = listOf("repo", "tests", "domain-ignore-test").map { File(project.basePath!!, it) }
        try {
            super.tearDown()
        } finally {
            /* Disk-only cleanup: a VFS delete event would trigger the bundled Vue plugin's LSP initialization. */
            probeDirectories.forEach(FileUtil::delete)
        }
    }
}
