package org.jetbrains.kotlin.test.helper.domain

import com.intellij.ide.SelectInTarget
import com.intellij.ide.dnd.aware.DnDAwareTree
import com.intellij.ide.impl.ProjectViewSelectInTarget
import com.intellij.ide.projectView.NodeSortOrder
import com.intellij.ide.projectView.NodeSortSettings
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.AbstractProjectViewPane
import com.intellij.ide.projectView.impl.AbstractProjectViewPaneWithAsyncSupport
import com.intellij.ide.projectView.impl.ProjectAbstractTreeStructureBase
import com.intellij.ide.projectView.impl.ProjectTreeStructure
import com.intellij.ide.projectView.impl.ProjectViewListener
import com.intellij.ide.projectView.impl.ProjectViewTree
import com.intellij.ide.projectView.impl.nodes.ProjectViewDirectoryHelper
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.ide.util.treeView.PresentableNodeDescriptor.ColoredFragment as DomainColoredFragment
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.psi.PsiDirectory
import com.intellij.psi.JavaDirectoryService
import com.intellij.psi.PsiManager
import java.awt.Color
import javax.swing.Icon
import javax.swing.tree.DefaultTreeModel

internal val domainIcon: Icon = IconLoader.getIcon("/icons/test_federation.svg", DomainProjectViewPane::class.java)


class DomainProjectViewPane(project: Project) : AbstractProjectViewPaneWithAsyncSupport(project) {
    companion object {
        const val VIEW_ID = "DomainPane"
    }

    @Volatile
    private var paneDisposed = false

    init {
        project.messageBus.connect(this).subscribe(
            DomainProjectService.MODEL_CHANGED_TOPIC,
            DomainModelListener { scheduleTreeRefresh(project) },
        )

        if (DomainProjectService.hasDumpMarker(project)) {
            followBuildStatusPollingActivity(project)
        }
    }

    override fun getTitle(): String = "Domain"

    override fun getId(): String = VIEW_ID

    override fun getIcon(): Icon = domainIcon

    override fun getWeight(): Int = 2

    override fun createSelectInTarget(): SelectInTarget = DomainProjectViewSelectInTarget(myProject)

    override fun createComparator(): Comparator<NodeDescriptor<*>> =
        domainDeclarationOrderComparator(super.createComparator())

    override fun createTree(treeModel: DefaultTreeModel): DnDAwareTree {
        return ProjectViewTree(treeModel)
    }

    override fun dispose() {
        paneDisposed = true
        myProject.getServiceIfCreated(DomainBuildStatusService::class.java)?.setPollingActive(false)
        super.dispose()
    }

    /* Build statuses are polled only while a Domain pane is selected in a visible Project tool window. */
    private fun followBuildStatusPollingActivity(project: Project) {
        val connection = project.messageBus.connect(this)

        connection.subscribe(ProjectViewListener.TOPIC, object : ProjectViewListener {
            override fun paneShown(current: AbstractProjectViewPane, previous: AbstractProjectViewPane?) {
                updateBuildStatusPolling(project)
            }
        })

        connection.subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun stateChanged(toolWindowManager: ToolWindowManager) {
                updateBuildStatusPolling(project)
            }
        })

        /* The pane may already be shown before any of the events above fire. */
        ApplicationManager.getApplication().invokeLater({ updateBuildStatusPolling(project) }, project.disposed)
    }

    private fun updateBuildStatusPolling(project: Project) {
        if (project.isDisposed || paneDisposed) return

        if (isDomainPaneShown(project)) {
            DomainBuildStatusService.getInstance(project).setPollingActive(true)
        } else {
            project.getServiceIfCreated(DomainBuildStatusService::class.java)?.setPollingActive(false)
        }
    }

    private fun scheduleTreeRefresh(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed && !paneDisposed) {
                updateFromRoot(true)
            }
        }
    }

    override fun createStructure(): ProjectAbstractTreeStructureBase =
        DomainTreeStructure(myProject, getId())
}

/* Checks for any Domain pane instance, so that several instances never disagree about polling. */
private fun isDomainPaneShown(project: Project): Boolean {
    if (ProjectView.getInstance(project).currentProjectViewPane !is DomainProjectViewPane) return false

    val projectToolWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROJECT_VIEW)
    return projectToolWindow?.isVisible == true
}

private class DomainProjectViewSelectInTarget(project: Project) : ProjectViewSelectInTarget(project), DumbAware {
    override fun getMinorViewId(): String = DomainProjectViewPane.VIEW_ID

    /* Between StandardTargetWeights.PROJECT_WEIGHT and StandardTargetWeights.PACKAGES_WEIGHT. */
    override fun getWeight(): Float = 1.5f

    override fun toString(): String = "Domain Project View"
}

internal class DomainTreeStructure(
    project: Project,
    paneId: String,
) : ProjectTreeStructure(project, paneId) {
    override fun createRoot(project: Project, settings: ViewSettings): AbstractTreeNode<*> =
        DomainRootNode(
            project = project,
            settings = settings,
            domainService = if (DomainProjectService.hasDumpMarker(project))
                DomainProjectService.getInstance(project) else null,
        )
}

private class DomainRootNode(
    project: Project,
    private val settings: ViewSettings,
    private val domainService: DomainProjectService?,
    private val fileFilter: FileFilter = FileFilter.forProject(project),
) : ProjectViewNode<Project>(project, project, settings) {

    @Volatile
    private var currentDomainTreeSnapshot: DomainTreeSnapshot? = null

    override fun getChildren(): Collection<AbstractTreeNode<*>> {
        if (domainService == null) return emptyList()
        val snapshot = treeSnapshot() ?: return emptyList()

        val domainGroups = snapshot.domainPathTrees.entries.withIndex().map { (index, entry) ->
            val (domain, pathTree) = entry
            DomainGroupNode(
                project = project,
                settings = settings,
                domain = domain,
                domainIndex = index,
                repositoryRoot = snapshot.repositoryRoot,
                domainPathTree = pathTree,
            ).also {
                it.setParent(this)
            }
        }

        return domainGroups
    }

    override fun update(presentation: PresentationData) {
        presentation.setPresentableText(getValue().name)
        presentation.setIcon(domainIcon)
    }

    override fun contains(file: VirtualFile): Boolean {
        if (domainService == null) return false
        if (!fileFilter.isEligible(file)) return false
        val snapshot = treeSnapshot() ?: return false
        return snapshot.domainPathTrees.values.any { tree -> tree.containsFile(file) }
    }

    private fun treeSnapshot(): DomainTreeSnapshot? {
        val service = checkNotNull(domainService)
        val dump = service.dump
        val repositoryRevision = service.repositoryRevision

        val repositoryRoot = project.basePath
            ?.let(FileUtil::toSystemIndependentName)
            ?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            ?.let(RepositoryRoot::of)
            ?: return null

        currentDomainTreeSnapshot?.takeIf {
            it.dump === dump && it.repositoryRoot == repositoryRoot && it.repositoryRevision == repositoryRevision
        }?.let { return it }


        val domainPathTrees = dump.paths.keys.toSet().associateWith { domain ->
            dump.buildDomainPathTree(domain, repositoryRoot, fileFilter)
        }

        return DomainTreeSnapshot(
            dump = dump,
            repositoryRoot = repositoryRoot,
            repositoryRevision = repositoryRevision,
            domainPathTrees = domainPathTrees,
        ).also { currentDomainTreeSnapshot = it }
    }
}

private data class DomainTreeSnapshot(
    val dump: DomainDump,
    val repositoryRoot: RepositoryRoot,
    val repositoryRevision: Long,
    val domainPathTrees: Map<Domain, DomainPathNode>,
)

internal class DomainGroupNode(
    project: Project,
    private val settings: ViewSettings,
    internal val domain: Domain,
    internal val domainIndex: Int,
    private val repositoryRoot: RepositoryRoot,
    private val domainPathTree: DomainPathNode,
) : ProjectViewNode<String>(project, domain.name, settings) {
    private val fileFilter = FileFilter.forProject(project)

    override fun getChildren(): Collection<AbstractTreeNode<*>> =
        domainPathTree.children.values.mapNotNull { pathNode ->
            createPathNode(project, settings, repositoryRoot, pathNode)?.also { it.attachTo(this) }
        }

    override fun update(presentation: PresentationData) {
        val buildStatus = project.getServiceIfCreated(DomainBuildStatusService::class.java)?.status(domain)

        presentation.setPresentableText(getValue())
        presentation.setIcon(domainBuildStatusIcon(buildStatus))
        presentation.tooltip = buildStatus?.statusText
    }

    override fun contains(file: VirtualFile): Boolean {
        return fileFilter.isEligible(file) && domainPathTree.containsFile(file)
    }
}

internal class DomainDirectoryNode(
    project: Project,
    directory: PsiDirectory,
    private val settings: ViewSettings,
    private val repositoryRoot: RepositoryRoot,
    private val projection: DomainDirectoryProjection,
) : ProjectViewNode<VirtualFile>(project, projection.terminalPathNode.file, settings) {
    private val standardDirectoryNode = PsiDirectoryNode(project, directory, settings)
    private val fileFilter = FileFilter.forProject(project)

    internal fun setPresentationParent(parent: AbstractTreeNode<*>) {
        standardDirectoryNode.setParent(parent)
    }

    internal fun updatedPresentation(): PresentationData {
        update()
        return presentation
    }

    override fun getVirtualFile(): VirtualFile = value

    override fun getFileStatus(): FileStatus = standardDirectoryNode.fileStatus

    override fun getFileStatusColor(status: FileStatus): Color? = standardDirectoryNode.getFileStatusColor(status)

    override fun canRepresent(element: Any?): Boolean = when (element) {
        is VirtualFile -> element in projection.representedDirectories
        is PsiDirectory -> element.virtualFile in projection.representedDirectories
        else -> false
    }

    @Suppress("UnstableApiUsage")
    override fun getSortOrder(settings: NodeSortSettings): NodeSortOrder = standardDirectoryNode.getSortOrder(settings)

    override fun getTypeSortWeight(sortByType: Boolean): Int = standardDirectoryNode.getTypeSortWeight(sortByType)

    override fun getWeight(): Int = standardDirectoryNode.weight

    override fun getSortKey(): Comparable<*>? = standardDirectoryNode.sortKey

    override fun getTypeSortKey(): Comparable<*>? = standardDirectoryNode.typeSortKey

    override fun getTimeSortKey(): Comparable<*>? = standardDirectoryNode.timeSortKey

    override fun getManualOrderKey(): Comparable<*>? = standardDirectoryNode.manualOrderKey

    override fun getQualifiedNameSortKey(): String = standardDirectoryNode.qualifiedNameSortKey.orEmpty()

    override fun update(presentation: PresentationData) {
        /* Start with the standard directory icon, labels, and text styling. */
        standardDirectoryNode.update(presentation)

        /* Identify module names so their bracketed labels can be removed. */
        val moduleManager = ModuleManager.getInstance(project)
        val isModuleName: (String) -> Boolean = { name -> moduleManager.findModuleByName(name) != null }

        /* Use the IDE's package name for flattened packages, otherwise the projected directory name. */
        val displayName = if (projection.isFlattenedPackage) {
            ProjectViewDirectoryHelper.getInstance(project).getNodeName(
                settings,
                standardDirectoryNode.value.parent,
                standardDirectoryNode.value,
            )
        } else {
            projection.displayName
        }

        /* Remove module labels from the display name and location. */
        val cleanedDisplayName = withoutBracketedModuleNames(displayName, isModuleName)
        presentation.presentableText = cleanedDisplayName
        presentation.locationString = withoutBracketedModuleNames(presentation.locationString, isModuleName)

        /* Clean styled text fragments while preserving tooltips and attributes, dropping empty fragments. */
        val fragments = presentation.coloredText.mapNotNull { fragment ->
            val text = withoutBracketedModuleNames(fragment.text, isModuleName).orEmpty()
            text.takeIf(String::isNotEmpty)?.let {
                DomainColoredFragment(it, fragment.toolTip, fragment.attributes)
            }
        }

        /* Rebuild styled text with the projected name when possible, otherwise retain the cleaned fragments. */
        presentation.clearText()
        val nameFragment = fragments.firstOrNull()
        val isProjected = projection.representedDirectories.size > 1 || projection.isFlattenedPackage
        if (isProjected && cleanedDisplayName != null && nameFragment?.attributes != null) {
            presentation.addText(
                DomainColoredFragment(cleanedDisplayName, nameFragment.toolTip, nameFragment.attributes),
            )
        } else {
            fragments.forEach(presentation::addText)
        }
    }

    override fun getChildren(): Collection<AbstractTreeNode<*>> {
        val childNodes = if (settings.isFlattenPackages && isSourceRoot(project, projection.terminalPathNode.file)) {
            flattenedDomainPathChildren(
                project,
                projection.terminalPathNode,
                settings.isHideEmptyMiddlePackages,
                fileFilter,
            ).map { child ->
                createPathNode(
                    project,
                    settings,
                    repositoryRoot,
                    child.pathNode,
                    child.takeIf { it.isFlattenedPackage },
                )
            }
        } else {
            projection.terminalPathNode.resolveChildrenNodes(fileFilter)
                .filterNot { projection.isFlattenedPackage && isPackageDirectory(project, it.file) }
                .map { childNode ->
                    createPathNode(project, settings, repositoryRoot, childNode)
                }
        }
        return childNodes.mapNotNull { childNode ->
            childNode?.also { it.attachTo(this) }
        }
    }

    override fun contains(file: VirtualFile): Boolean {
        if (!fileFilter.isEligible(file)) return false
        if (file in projection.representedDirectories) return true
        return projection.pathNode.containsFile(file)
    }
}

internal data class DomainDirectoryProjection(
    val pathNode: DomainPathNode,
    val terminalPathNode: DomainPathNode,
    val representedDirectories: List<VirtualFile>,
    val displayName: String,
    val isFlattenedPackage: Boolean = false,
)

private data class FlattenedDomainPathChild(
    val pathNode: DomainPathNode,
    val displayName: String? = null,
    val representedDirectories: List<VirtualFile> = emptyList(),
    val isFlattenedPackage: Boolean = false,
)

internal fun projectDomainDirectoryPath(
    project: Project,
    pathNode: DomainPathNode,
    settings: ViewSettings,
    applyCompactDirectories: Boolean = true,
    fileEligibility: FileFilter = FileFilter.forProject(project),
): DomainDirectoryProjection {
    /* Track every directory represented by this node, starting with the original path. */
    val representedDirectories = mutableListOf(pathNode.file)
    var terminalPathNode = pathNode

    /* Enable directory compaction and, outside flattened view, empty-middle-package compaction. */
    val compactDirectories = applyCompactDirectories && settings.isCompactDirectories
    val compactEmptyMiddlePackages = !settings.isFlattenPackages && settings.isHideEmptyMiddlePackages

    while (compactDirectories || compactEmptyMiddlePackages) {
        /* Only compact a chain with exactly one visible child, which must be a directory. */
        val children = terminalPathNode.resolveChildrenNodes(fileEligibility)
        val nextDirectory = children.singleOrNull()?.takeIf { it.file.isDirectory } ?: break

        /* Empty-middle-package compaction requires both directories to be packages. */
        val isPackageCompaction = compactEmptyMiddlePackages &&
            isPackageDirectory(project, terminalPathNode.file) &&
            isPackageDirectory(project, nextDirectory.file)

        /* General directory compaction must not merge a source root with its parent or child. */
        val isDirectoryCompaction = compactDirectories &&
            !isSourceRoot(project, terminalPathNode.file) &&
            !isSourceRoot(project, nextDirectory.file)

        /* Stop when neither rule applies; otherwise extend the represented directory chain. */
        if (!isPackageCompaction && !isDirectoryCompaction) break
        terminalPathNode = nextDirectory
        representedDirectories += nextDirectory.file
    }

    /* Keep both ends of the chain and join names with '/' if any directory is not a package, otherwise '.'. */
    return DomainDirectoryProjection(
        pathNode = pathNode,
        terminalPathNode = terminalPathNode,
        representedDirectories = representedDirectories,
        displayName = representedDirectories.joinToString(
            if (compactDirectories && representedDirectories.any { !isPackageDirectory(project, it) }) "/" else ".",
        ) { it.name },
    )
}

private fun flattenedDomainPathChildren(
    project: Project,
    sourceRoot: DomainPathNode,
    hideEmptyMiddlePackages: Boolean,
    fileEligibility: FileFilter,
): List<FlattenedDomainPathChild> {
    val result = mutableListOf<FlattenedDomainPathChild>()

    fun collectPackage(pathNode: DomainPathNode, hiddenAncestors: List<VirtualFile>) {
        /* Hide a middle package only when its visible children are all package directories. */
        val children = pathNode.resolveChildrenNodes(fileEligibility)
        val packageChildren = children.filter { isPackageDirectory(project, it.file) }
        val hasVisibleFiles = children.any { !it.file.isDirectory }
        val hasOnlyPackageDirectoryChildren = children
            .filter { it.file.isDirectory }
            .all { isPackageDirectory(project, it.file) }
        val isHiddenMiddlePackage = hideEmptyMiddlePackages && !hasVisibleFiles &&
            packageChildren.isNotEmpty() && hasOnlyPackageDirectoryChildren

        /* Keep hidden ancestors represented by the next visible package node. */
        val representedDirectories = hiddenAncestors + pathNode.file

        /* Add visible packages directly to the flattened result, using their qualified names. */
        if (!isHiddenMiddlePackage) {
            val directory = PsiManager.getInstance(project).findDirectory(pathNode.file)
            val packageName = directory?.let(JavaDirectoryService.getInstance()::getPackageInSources)?.qualifiedName
            if (packageName != null) {
                result += FlattenedDomainPathChild(
                    pathNode = pathNode,
                    displayName = packageName,
                    representedDirectories = representedDirectories,
                    isFlattenedPackage = true,
                )
            }
        }

        /* Collect nested packages as siblings, carrying ancestors only through hidden packages. */
        val nextHiddenAncestors = if (isHiddenMiddlePackage) representedDirectories else emptyList()
        packageChildren.forEach { child -> collectPackage(child, nextHiddenAncestors) }
    }

    /* Flatten packages below the source root; keep other immediate children unchanged. */
    val children = sourceRoot.resolveChildrenNodes(fileEligibility)
    for (child in children) {
        if (isPackageDirectory(project, child.file)) {
            collectPackage(child, emptyList())
        } else {
            result += FlattenedDomainPathChild(child)
        }
    }
    return result
}

private fun isSourceRoot(project: Project, file: VirtualFile): Boolean =
    file.isDirectory && PsiManager.getInstance(project).findDirectory(file)?.let {
        JavaDirectoryService.getInstance().isSourceRoot(it)
    } == true

private fun isPackageDirectory(project: Project, file: VirtualFile): Boolean {
    if (!file.isDirectory || isSourceRoot(project, file) ||
        !ProjectFileIndex.getInstance(project).isInSourceContent(file)
    ) {
        return false
    }
    val directory = PsiManager.getInstance(project).findDirectory(file) ?: return false
    return JavaDirectoryService.getInstance().getPackageInSources(directory)?.qualifiedName?.isNotEmpty() == true
}

private fun AbstractTreeNode<*>.attachTo(parent: AbstractTreeNode<*>) {
    setParent(parent)
    (this as? DomainDirectoryNode)?.setPresentationParent(parent)
}

private val bracketedNamesPattern = Regex("\\s*\\[([^]]+)]")

/* Only bracketed names are looked up, so no module enumeration or cache invalidation is needed. */
internal fun withoutBracketedModuleNames(text: String?, isModuleName: (String) -> Boolean): String? {
    if (text == null || '[' !in text) return text
    val cleaned = text.replace(bracketedNamesPattern) { match ->
        val names = match.groupValues[1].split(", ")
        if (names.all(isModuleName)) "" else match.value
    }
    return if (cleaned == text) text else cleaned.trim()
}


internal fun interface FileFilter {
    fun isEligible(file: VirtualFile): Boolean

    companion object {
        fun forProject(project: Project): FileFilter =
            FileFilter { file -> isEligibleRepositoryFile(project, file) }
    }
}

internal class DomainPathTree(
    val repositoryRoot: RepositoryRoot,
) {
    private var rootNode = DomainPathNode(repositoryRoot.file)

    /**
     * Will return the [DomainPathNode] associated with this [virtualFile] or create the
     * node and all intermediate nodes.
     *
     * Returns `null` if the [virtualFile] is outside the repository root
     */
    fun getOrInsertNode(virtualFile: VirtualFile): DomainPathNode? {
        if (!VfsUtil.isAncestor(repositoryRoot.file, virtualFile, false)) return null
        if (virtualFile == repositoryRoot.file) return rootNode
        val parentNode = getOrInsertNode(virtualFile.parent) ?: return null
        val thisNode = parentNode.children[virtualFile.name]
        if (thisNode != null) return thisNode
        val newNode = DomainPathNode(virtualFile)
        val newParentNode = parentNode.withChild(newNode)
        insert(newParentNode)
        return newNode
    }

    /**
     * Will insert the given [node].
     * If the node already exists, then this [insert] idempotent updates the tree.
     *
     * @return false if nothing has changed
     */
    fun insert(node: DomainPathNode): Boolean {
        if (!VfsUtil.isAncestor(repositoryRoot.file, node.file, false)) return false
        if (node.file == repositoryRoot.file) {
            rootNode = node
            return true
        }

        val parentNode = getOrInsertNode(node.file.parent) ?: return false
        val newParentNode = parentNode.withChild(node)
        return parentNode != newParentNode && insert(newParentNode)
    }


    fun root(): DomainPathNode = rootNode

}

internal data class DomainPathNode(
    val file: VirtualFile, val children: Map<String, DomainPathNode> = emptyMap(),

    /**
     * Marker to show if the node is actually included into the domain:
     * If [isIncluded] is false, then the node may just denote a parent in the tree
     * which is required to reach actually included nodes.
     */
    val isIncluded: Boolean = false,
) {
    fun withChild(node: DomainPathNode): DomainPathNode {
        if (children[node.file.name] == node) return this
        return copy(children = children + (node.file.name to node))
    }
}

internal fun DomainDump.buildDomainPathTree(
    domain: Domain,
    repositoryRoot: RepositoryRoot,
    fileEligibility: FileFilter,
): DomainPathNode {
    val tree = DomainPathTree(repositoryRoot)

    paths[domain].orEmpty().forEach { path ->
        val file = repositoryRoot.resolve(path)
            ?.takeIf { file -> file.isValid }
            ?.takeIf { file -> fileEligibility.isEligible(file) }
            ?: return@forEach

        val node = DomainPathNode(file, isIncluded = true)
        tree.insert(node)
    }

    return tree.root()
}

internal fun DomainPathNode.resolveChildrenNodes(
    fileEligibility: FileFilter,
): List<DomainPathNode> {
    return file.children.mapNotNull { childFile ->
        if (!fileEligibility.isEligible(childFile)) return@mapNotNull null
        if (!containsFile(childFile)) return@mapNotNull null
        val existingNode = children[childFile.name]
        when {
            existingNode == null -> DomainPathNode(childFile, emptyMap(), isIncluded = true)
            else -> existingNode
        }
    }
}

private fun createPathNode(
    project: Project,
    settings: ViewSettings,
    repositoryRoot: RepositoryRoot,
    pathNode: DomainPathNode,
    flattenedPackageProjection: FlattenedDomainPathChild? = null,
): AbstractTreeNode<*>? {
    if (!pathNode.file.isValid) return null
    val psiManager = PsiManager.getInstance(project)
    if (pathNode.file.isDirectory) {
        val projection = projectDomainDirectoryPath(
            project,
            pathNode,
            settings,
            applyCompactDirectories = flattenedPackageProjection == null,
        )
        val directoryFile = flattenedPackageProjection?.pathNode?.file ?: projection.terminalPathNode.file
        val directory = psiManager.findDirectory(directoryFile) ?: return null
        val finalProjection = if (flattenedPackageProjection == null) projection else projection.copy(
            representedDirectories = flattenedPackageProjection.representedDirectories,
            displayName = flattenedPackageProjection.displayName ?: projection.displayName,
            isFlattenedPackage = true,
        )
        return DomainDirectoryNode(project, directory, settings, repositoryRoot, finalProjection)
    }

    val file = psiManager.findFile(pathNode.file) ?: return null
    return PsiFileNode(project, file, settings)
}

private fun isEligibleRepositoryFile(project: Project, file: VirtualFile): Boolean =
    file.isValid &&
        !ProjectFileIndex.getInstance(project).isUnderIgnored(file) &&
        !FileTypeManager.getInstance().isFileIgnored(file.name)

private fun DomainPathNode.containsFile(file: VirtualFile): Boolean {
    if (!this.file.isValid) return false
    if (this.file == file) return true
    if (!VfsUtil.isAncestor(this.file, file, true)) return false
    if (isIncluded) return true
    return children.values.any { child ->
        child.containsFile(file)
    }
}

internal fun domainDeclarationOrderComparator(
    fallback: Comparator<NodeDescriptor<*>>,
): Comparator<NodeDescriptor<*>> = Comparator { first, second ->
    if (first is DomainGroupNode && second is DomainGroupNode) {
        first.domainIndex.compareTo(second.domainIndex)
    } else {
        fallback.compare(first, second)
    }
}
