package org.jetbrains.kotlin.test.helper.projectView

import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewSettings
import com.intellij.ide.projectView.SelectableTreeStructureProvider
import com.intellij.ide.projectView.TreeStructureProvider
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.ProjectViewState
import com.intellij.ide.projectView.impl.nodes.FileNodeWithNestedFileNodes
import com.intellij.ide.projectView.impl.nodes.NestingTreeNode
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.registry.RegistryValue
import com.intellij.openapi.util.registry.RegistryValueListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.test.helper.SUPPORTED_EXTENSIONS
import org.jetbrains.kotlin.test.helper.TestDataPathsConfiguration
import org.jetbrains.kotlin.test.helper.asPathWithoutAllExtensions

internal const val TEST_DATA_NESTING_REGISTRY_KEY: String = "kotlin.compiler.devkit.project.view.nest.test.data.variants"

internal fun isTestDataNestingEnabled(): Boolean = Registry.`is`(TEST_DATA_NESTING_REGISTRY_KEY, false)

/**
 * Nests the variants of a test data file under its main file in the Project View: `foo.txt`, `foo.fir.txt` and
 * `foo.ll.kt` become children of `foo.kt`.
 *
 * The files are grouped like [org.jetbrains.kotlin.test.helper.mainTestFileOrNull] does: by the name without all
 * extensions, with `<name>.<extension>` as the main file for the first extension of [SUPPORTED_EXTENSIONS] that has one.
 * A group without a main file stays flat.
 *
 * Only directories under the configured test data files (and not under the test data directories) are affected, and
 * only when the [TEST_DATA_NESTING_REGISTRY_KEY] registry key and the "File Nesting" option of the Project View are on.
 *
 * The provider runs after the platform [com.intellij.ide.projectView.impl.NestingTreeStructureProvider], so the
 * nodes it nests keep the files the platform rules nested, and before `ClassesTreeStructureProvider` and the Kotlin
 * providers, which keep the nested files of the node they replace ([FileNodeWithNestedFileNodes]).
 *
 * The work per expanded directory is one pass over its children by name, without loading PSI or file contents.
 */
class TestDataNestingTreeStructureProvider : TreeStructureProvider, DumbAware {
    override fun modify(
        parent: AbstractTreeNode<*>,
        children: Collection<AbstractTreeNode<*>>,
        settings: ViewSettings?,
    ): Collection<AbstractTreeNode<*>> {
        if (!isTestDataNestingEnabled()) return children
        if (settings !is ProjectViewSettings || !settings.isUseFileNestingRules) return children
        val directory = (parent as? ProjectViewNode<*>)?.virtualFile?.takeIf { it.isDirectory } ?: return children
        val project = parent.project ?: return children
        if (!TestDataPathsConfiguration.getInstance(project).containsTestDataFilesIn(directory)) return children
        return nestTestDataVariants(children)
    }
}

private fun TestDataPathsConfiguration.containsTestDataFilesIn(directory: VirtualFile): Boolean {
    val path = directory.path
    return testDataFiles.any { FileUtil.isAncestor(it, path, false) } &&
            testDataDirectories.none { FileUtil.isAncestor(it, path, false) }
}

internal fun nestTestDataVariants(children: Collection<AbstractTreeNode<*>>): Collection<AbstractTreeNode<*>> {
    var nodesByBaseName: MutableMap<String, MutableList<PsiFileNode>>? = null
    for (child in children) {
        if (child !is PsiFileNode) continue
        val name = child.virtualFile?.name ?: continue
        if (nodesByBaseName == null) nodesByBaseName = HashMap()
        nodesByBaseName.getOrPut(name.asPathWithoutAllExtensions) { ArrayList(1) }.add(child)
    }
    if (nodesByBaseName == null) return children

    var mainToNesting: MutableMap<PsiFileNode, PsiFileNode>? = null
    var nestedNodes: MutableSet<PsiFileNode>? = null
    for ((baseName, group) in nodesByBaseName) {
        if (group.size < 2) continue
        val main = group.findMainNode(baseName) ?: continue
        val variants = group.filter { it !== main }
        if (mainToNesting == null) mainToNesting = HashMap()
        if (nestedNodes == null) nestedNodes = HashSet()
        mainToNesting[main] = NestingTreeNode(main, main.nestedPsiFileNodes() + variants.flatMap { listOf(it) + it.nestedPsiFileNodes() })
        nestedNodes.addAll(variants)
    }
    if (mainToNesting == null || nestedNodes == null) return children

    val result = ArrayList<AbstractTreeNode<*>>(children.size - nestedNodes.size)
    for (child in children) {
        when (child) {
            in nestedNodes -> continue
            in mainToNesting -> result.add(mainToNesting.getValue(child as PsiFileNode))
            else -> result.add(child)
        }
    }
    return result
}

private fun List<PsiFileNode>.findMainNode(baseName: String): PsiFileNode? =
    SUPPORTED_EXTENSIONS.firstNotNullOfOrNull { extension ->
        val mainName = "$baseName.$extension"
        firstOrNull { it.virtualFile?.name == mainName }
    }

private fun PsiFileNode.nestedPsiFileNodes(): List<PsiFileNode> =
    (this as? FileNodeWithNestedFileNodes)?.nestedFileNodes?.filterIsInstance<PsiFileNode>().orEmpty()

/** The extensions of the files whose node the Kotlin plugin replaces with its own one, see [TestDataNestingSelectInProvider]. */
private val KOTLIN_EXTENSIONS: Set<String> = setOf("kt", "kts")

/**
 * Makes "Select in Project View" and autoscroll from source land on the main file for a variant nested under a Kotlin
 * main file (`foo.txt` under `foo.kt`), instead of selecting nothing.
 *
 * The Kotlin plugin replaces the [NestingTreeNode] of a Kotlin file with its own node, which keeps the nested files but
 * whose `contains()` matches only its own file, so the Project View does not look for the variant inside it. Under
 * other main files (`foo.args`) the [NestingTreeNode] stays, and its `contains()` lets the variant itself be selected.
 *
 * The platform asks the selectable providers in their order and takes the first answer, and the Kotlin plugin answers
 * for every Kotlin file, so this provider is registered `first`. Its [modify] changes nothing.
 *
 * A workaround for KTIJ-41271: remove it once the oldest supported platform has the Kotlin nodes match their nested
 * files in `contains()`.
 */
class TestDataNestingSelectInProvider : SelectableTreeStructureProvider, DumbAware {
    override fun modify(
        parent: AbstractTreeNode<*>,
        children: Collection<AbstractTreeNode<*>>,
        settings: ViewSettings?,
    ): Collection<AbstractTreeNode<*>> = children

    override fun getTopLevelElement(element: PsiElement): PsiElement? {
        if (!isTestDataNestingEnabled()) return null
        val file = element.containingFile?.virtualFile ?: return null
        val project = element.project
        if (!project.getService(ProjectViewState::class.java).useFileNestingRules) return null
        val directory = file.parent ?: return null
        if (!TestDataPathsConfiguration.getInstance(project).containsTestDataFilesIn(directory)) return null
        val main = findMainFile(file.name.asPathWithoutAllExtensions, directory) ?: return null
        if (main == file || main.extension !in KOTLIN_EXTENSIONS) return null
        return PsiManager.getInstance(project).findFile(main)
    }

    private fun findMainFile(baseName: String, directory: VirtualFile): VirtualFile? =
        SUPPORTED_EXTENSIONS.firstNotNullOfOrNull { extension -> directory.findChild("$baseName.$extension") }
}

/**
 * Rebuilds the Project View when [TEST_DATA_NESTING_REGISTRY_KEY] changes, so the nesting shows up without a reopen.
 * Registry listeners run on the thread that changed the value, and the rebuild touches Swing trees, hence `invokeLater`.
 */
class TestDataNestingRegistryListener : RegistryValueListener {
    override fun afterValueChanged(value: RegistryValue) {
        if (value.key != TEST_DATA_NESTING_REGISTRY_KEY) return
        ApplicationManager.getApplication().invokeLater {
            for (project in ProjectManager.getInstance().openProjects) {
                if (!project.isDisposed) refreshTestDataNesting(project)
            }
        }
    }
}

/**
 * Rebuilds every Project View pane that has a tree already. [ProjectView.refresh] rebuilds only the current pane,
 * and switching to another pane restores its old tree without rebuilding it.
 */
internal fun refreshTestDataNesting(project: Project) {
    val projectView = ProjectView.getInstance(project)
    for (paneId in projectView.paneIds) {
        val pane = projectView.getProjectViewPaneById(paneId) ?: continue
        if (pane.tree != null) pane.updateFromRoot(true)
    }
}
