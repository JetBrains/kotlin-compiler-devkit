package org.jetbrains.kotlin.test.helper.teamcity

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.speedSearch.SpeedSearchUtil
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.xml.util.XmlStringUtil
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.test.helper.MyBundle
import org.jetbrains.kotlin.test.helper.git.currentBranchName
import org.jetbrains.kotlin.test.helper.git.kotlinMonorepoRepository
import org.jetbrains.kotlin.test.helper.github.GitHubPullRequestService
import org.jetbrains.kotlin.test.helper.github.OpenKotlinPullRequest
import org.jetbrains.kotlin.test.helper.github.PullRequestsListener
import org.jetbrains.kotlin.test.helper.github.githubPullRequestIcon
import org.jetbrains.kotlin.test.helper.runAnything.findAndLaunchTestsByFqReferences
import org.jetbrains.kotlin.test.helper.toast
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.Timer
import javax.swing.ToolTipManager
import javax.swing.event.DocumentEvent
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeCellRenderer
import javax.swing.tree.TreePath

/**
 * The Remote Run tool window is only offered in Kotlin monorepo projects.
 */
class RemoteRunToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project): Boolean = project.kotlinMonorepoRepository() != null

    /**
     * Git detects repositories asynchronously after the project opened, so [shouldBeAvailable]
     * usually runs too early: re-evaluate the availability whenever the repository mapping changes.
     */
    override fun init(toolWindow: ToolWindow) {
        val project = toolWindow.project

        project.messageBus.connect(toolWindow.disposable).subscribe(
            VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED,
            VcsRepositoryMappingListener {
                ApplicationManager.getApplication().invokeLater(
                    { toolWindow.isAvailable = shouldBeAvailable(project) },
                    project.disposed,
                )
            },
        )
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = RemoteRunToolWindowPanel(project, toolWindow)

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.isCloseable = false
        Disposer.register(content, panel)

        toolWindow.contentManager.addContent(content)
    }
}

/**
 * One branch group in the tree.
 */
private class BranchNode(
    val branchName: String,
    val isCurrent: Boolean,
    val pullRequestUrl: String?,
) : DefaultMutableTreeNode(branchName) {
    override fun isLeaf(): Boolean = false
}

/**
 * One build under its branch: a remembered launch or a fetched aggregate build.
 */
private class BuildNode(
    val build: RememberedTeamCityBuild,
    val failedTests: FailedTestsResult?,
) : DefaultMutableTreeNode(build.buildId) {
    override fun isLeaf(): Boolean = failedTests == null ||
        failedTests.tests.isEmpty() && failedTests.remainingCount == 0
}

private class FailedTestNode(
    val build: RememberedTeamCityBuild,
    val test: FailedTest,
) : DefaultMutableTreeNode(test.displayName) {
    override fun isLeaf(): Boolean = true
}

private class FailedTestClassNode(
    val build: RememberedTeamCityBuild,
    val classFqName: String,
    val testCount: Int,
) : DefaultMutableTreeNode(classFqName) {
    override fun isLeaf(): Boolean = false
}

private class MoreFailedTestsNode(
    val build: RememberedTeamCityBuild,
    val count: Int,
) : DefaultMutableTreeNode(count) {
    override fun isLeaf(): Boolean = true
}

/**
 * The launch in flight, shown with a spinner on top of its branch until the build is remembered.
 */
private class PendingLaunchNode(val progress: TeamCityLaunchProgress) : DefaultMutableTreeNode(progress.stepText) {
    override fun isLeaf(): Boolean = true
}

/**
 * Everything the tree shows; the tree is rebuilt only when this changes.
 */
private data class TreeContent(
    val currentBranchName: String?,
    val localBranchNames: List<String>,
    val launchProgress: TeamCityLaunchProgress?,
    val builds: List<RememberedTeamCityBuild>,
    val failedTests: Map<String, FailedTestsResult>,
    val pullRequestsByBranch: Map<String, OpenKotlinPullRequest>,
    val filterText: String,
)

/**
 * The Remote Run tool window body: all local branches and their TeamCity builds grouped by branch —
 * remembered launches plus the aggregate builds fetched for the branches — with the currently checked
 * out branch pinned first and branches with runs ordered by their latest run.
 *
 * Build statuses come from [TeamCityBuildStatusService]'s cache, which polls only while this
 * tool window is visible; status changes merely repaint the tree, structure changes rebuild it.
 */
private class RemoteRunToolWindowPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : SimpleToolWindowPanel(false, true), Disposable {

    private val navigationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode("Remote Run"))
    private val tree = Tree(treeModel)
    private val branchExpansionState = RemoteRunBranchExpansionState(tree) { path ->
        (path.lastPathComponent as? BranchNode)?.branchName
    }
    private val searchField = SearchTextField(false)
    private val elapsedTimeTimer = Timer(1_000) { tree.repaint() }
    private val fetchStatusLabel = JBLabel()

    private val actionToolbar: ActionToolbar

    /**
     * What the tree currently shows; null before the first build. EDT only.
     */
    private var shownContent: TreeContent? = null

    init {
        actionToolbar = createToolbar()
        toolbar = actionToolbar.component

        searchField.textEditor.emptyText.text = MyBundle.message("teamcity.toolwindow.search.placeholder")
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                rebuildTreeModel()
            }
        })

        val contentPanel = JPanel(BorderLayout())
        contentPanel.add(searchField, BorderLayout.NORTH)
        contentPanel.add(JBScrollPane(tree), BorderLayout.CENTER)
        contentPanel.add(fetchStatusLabel, BorderLayout.SOUTH)
        setContent(contentPanel)

        fetchStatusLabel.isVisible = false
        fetchStatusLabel.border = JBUI.Borders.empty(2, 8)
        updateFetchUi()

        tree.isRootVisible = false
        tree.cellRenderer = object : TreeCellRenderer {
            private val branchRenderer = BuildsTreeCellRenderer()
            private val branchCellRenderer = RemoteRunBranchCellRenderer()

            override fun getTreeCellRendererComponent(
                tree: JTree, value: Any, selected: Boolean,
                expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
            ): Component {
                branchRenderer.parent?.remove(branchRenderer)
                val component = branchRenderer.getTreeCellRendererComponent(
                    tree, value, selected, expanded, leaf, row, hasFocus,
                )
                val branch = value as? BranchNode ?: return component
                val marker = if (branch.isCurrent) {
                    MyBundle.message("teamcity.current.branch.marker")
                } else {
                    null
                }

                return branchCellRenderer.render(
                    branchComponent = component,
                    hasPullRequest = branch.pullRequestUrl != null,
                    currentBranchMarker = marker,
                    background = component.background,
                    toolTipText = (component as? JComponent)?.toolTipText,
                )
            }
        }
        tree.putClientProperty(AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)
        ToolTipManager.sharedInstance().registerComponent(tree)

        /* Right-clicking a row selects it first, so the popup actions apply to the clicked row. Every
           node type gets its own menu with only the actions that apply to it. */
        val branchPopupMenu = createPopupMenu(
            LaunchSelectedBranchAction(),
            LaunchSafeMergeDryRunAction(),
            OpenPullRequestAction(),
            RefreshBranchAction(),
        )
        val buildPopupMenu = createPopupMenu(
            StopSelectedBuildAction(),
            OpenBuildInTeamCityAction(),
            RemoveRememberedBuildAction(),
            RunFailedTestsAction(),
        )
        val failedTestClassPopupMenu = createPopupMenu(NavigateToFailedTestClassAction(), RunFailedTestsOfClassAction())
        val failedTestPopupMenu = createPopupMenu(NavigateToFailedTestAction(), RunTestLocallyAction())

        tree.addMouseListener(object : PopupHandler() {
            override fun invokePopup(component: Component, x: Int, y: Int) {
                val clickedPath = treePathForContextMenuAt(tree, x, y) ?: return
                tree.selectionPath = clickedPath

                when (clickedPath.lastPathComponent) {
                    is BranchNode -> branchPopupMenu.show(component, x, y)
                    is BuildNode, is MoreFailedTestsNode -> buildPopupMenu.show(component, x, y)
                    is FailedTestClassNode -> failedTestClassPopupMenu.show(component, x, y)
                    is FailedTestNode -> failedTestPopupMenu.show(component, x, y)
                }
            }
        })

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                return when (val node = tree.selectionPath?.lastPathComponent) {
                    is FailedTestNode -> {
                        navigateToTest(node.test)
                        true
                    }

                    else -> false
                }
            }
        }.installOn(tree)

        val messageBusConnection = project.messageBus.connect(this)

        messageBusConnection.subscribe(
            RememberedTeamCityBuildStore.CHANGED_TOPIC,
            RememberedBuildsListener { scheduleTreeRebuild() },
        )
        messageBusConnection.subscribe(
            TeamCityBuildStatusService.STATUSES_CHANGED_TOPIC,
            BuildStatusesListener {
                tree.repaint()
                scheduleTreeRebuild()
            },
        )
        messageBusConnection.subscribe(
            TeamCityTestLaunchService.LAUNCH_PROGRESS_TOPIC,
            TeamCityLaunchProgressListener { scheduleTreeRebuild() },
        )
        messageBusConnection.subscribe(
            GitHubPullRequestService.PULL_REQUESTS_CHANGED_TOPIC,
            PullRequestsListener { scheduleTreeRebuild() },
        )
        messageBusConnection.subscribe(
            TeamCityBuildStatusService.FETCH_STATE_TOPIC,
            TeamCityFetchListener { scheduleFetchUiUpdate() },
        )
        messageBusConnection.subscribe(
            GitRepository.GIT_REPO_CHANGE,
            GitRepositoryChangeListener { scheduleTreeRebuild() },
        )
        messageBusConnection.subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(toolWindowManager: ToolWindowManager) {
                    updatePollingActivity()
                }
            },
        )

        /* The tool window may already be visible before the first event fires. */
        ApplicationManager.getApplication().invokeLater({ updatePollingActivity() }, project.disposed)

        rebuildTreeModel()
    }

    override fun dispose() {
        navigationScope.cancel()
        elapsedTimeTimer.stop()
        project.getServiceIfCreated(TeamCityBuildStatusService::class.java)?.setPollingActive(false)
    }

    /**
     * Builds are polled only while the tool window is visible; showing it refreshes right away.
     */
    private fun updatePollingActivity() {
        if (project.isDisposed) return

        val isVisible = toolWindow.isVisible
        TeamCityBuildStatusService.getInstance(project).setPollingActive(isVisible)
        if (isVisible) requestPullRequestRefresh()
        if (isVisible) elapsedTimeTimer.start() else elapsedTimeTimer.stop()
    }

    /**
     * Resolves the open pull request of every listed branch through the GitHub plugin.
     */
    private fun requestPullRequestRefresh() {
        GitHubPullRequestService.getInstance(project).requestRefresh(shownContent?.localBranchNames.orEmpty())
    }

    private fun createToolbar(): ActionToolbar {
        val actionGroup = DefaultActionGroup(
            LaunchTestRunAction(),
            RefreshStatusesAction(),
            ExpandAllBranchesAction(),
            CollapseAllBranchesAction(),
        )

        val actionToolbar = ActionManager.getInstance().createActionToolbar("RemoteRunToolbar", actionGroup, true)
        actionToolbar.targetComponent = this
        return actionToolbar
    }

    private inner class ExpandAllBranchesAction : AnAction(
        MyBundle.message("teamcity.toolwindow.expand.all.text"),
        MyBundle.message("teamcity.toolwindow.expand.all.description"),
        AllIcons.Actions.Expandall,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            branchExpansionState.expandBranches(branchTreePaths())
        }
    }

    private inner class CollapseAllBranchesAction : AnAction(
        MyBundle.message("teamcity.toolwindow.collapse.all.text"),
        MyBundle.message("teamcity.toolwindow.collapse.all.description"),
        AllIcons.Actions.Collapseall,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            branchExpansionState.collapseBranches(branchTreePaths())
        }
    }

    private fun scheduleFetchUiUpdate() {
        ApplicationManager.getApplication().invokeLater({ updateFetchUi() }, project.disposed)
    }

    private fun updateFetchUi() {
        val state = TeamCityBuildStatusService.getInstance(project).fetchState

        updateFetchStatusLabel(state)
        actionToolbar.updateActionsAsync()
    }

    /**
     * The fetch indicator under the tree: a spinner while fetching, an explanation when TeamCity
     * was unreachable or nothing was found, and nothing otherwise.
     */
    private fun updateFetchStatusLabel(state: TeamCityFetchState) {
        when (state) {
            TeamCityFetchState.IN_PROGRESS -> {
                fetchStatusLabel.icon = AnimatedIcon.Default.INSTANCE
                fetchStatusLabel.text = MyBundle.message("teamcity.toolwindow.fetch.in.progress")
                fetchStatusLabel.foreground = UIUtil.getContextHelpForeground()
                fetchStatusLabel.isVisible = true
            }

            TeamCityFetchState.NO_BUILDS_FOUND -> {
                fetchStatusLabel.icon = null
                fetchStatusLabel.text = MyBundle.message("teamcity.toolwindow.fetch.no.builds")
                fetchStatusLabel.foreground = UIUtil.getContextHelpForeground()
                fetchStatusLabel.isVisible = true
            }

            TeamCityFetchState.FAILED -> {
                fetchStatusLabel.icon = null
                fetchStatusLabel.text = MyBundle.message("teamcity.toolwindow.fetch.failed")
                fetchStatusLabel.foreground = SimpleTextAttributes.ERROR_ATTRIBUTES.fgColor
                fetchStatusLabel.isVisible = true
            }

            TeamCityFetchState.COMPLETED -> fetchStatusLabel.isVisible = false
        }
    }

    private fun createPopupMenu(vararg actions: AnAction): JPopupMenu {
        val actionGroup = DefaultActionGroup(*actions)

        return ActionManager.getInstance().createActionPopupMenu("RemoteRunPopup", actionGroup).component
    }

    private fun scheduleTreeRebuild() {
        ApplicationManager.getApplication().invokeLater({ rebuildTreeModel() }, project.disposed)
    }

    /**
     * Rebuilds the tree when its content changed (git repository changes fire often).
     * Selection and collapsed branches survive the rebuild.
     */
    private fun rebuildTreeModel() {
        val repository = project.kotlinMonorepoRepository()
        val statusService = TeamCityBuildStatusService.getInstance(project)
        val builds = mergeRememberedAndFetchedBuilds(
            remembered = RememberedTeamCityBuildStore.getInstance(project).builds(),
            fetched = statusService.fetchedBranchBuilds(),
        )
        val content = TreeContent(
            currentBranchName = repository?.currentBranch?.name,
            localBranchNames = repository?.branches?.localBranches?.map { branch -> branch.name }.orEmpty(),
            launchProgress = TeamCityTestLaunchService.getInstance(project).launchProgress,
            builds = builds,
            failedTests = builds.mapNotNull { build ->
                statusService.failedTests(build.buildId)?.let { failedTests -> build.buildId to failedTests }
            }.toMap(),
            pullRequestsByBranch = GitHubPullRequestService.getInstance(project).pullRequests(),
            filterText = searchField.text,
        )
        if (content == shownContent) return
        val filterChanged = content.filterText != shownContent?.filterText
        shownContent = content

        val selectedBuildId = selectedBuild()?.buildId
        val selectedBranchName = selectedBranchName()
        val selectedFailedTest = selectedFailedTest()?.let { node -> node.build.buildId to node.test }
        val selectedFailedTestClass = selectedFailedTestClass()?.let { node -> node.build.buildId to node.classFqName }
        val expandedBuildIds = branchNodes().flatMap { branchNode ->
            branchNode.children().toList().filterIsInstance<BuildNode>()
                .filter { buildNode -> tree.isExpanded(TreePath(buildNode.path)) }
                .map { buildNode -> buildNode.build.buildId }
        }.toSet()
        val expandedFailedTestClasses = branchNodes().flatMap { branchNode ->
            branchNode.children().toList().filterIsInstance<BuildNode>().flatMap { buildNode ->
                buildNode.children().toList().filterIsInstance<FailedTestClassNode>()
                    .filter { classNode -> tree.isExpanded(TreePath(classNode.path)) }
                    .map { classNode -> buildNode.build.buildId to classNode.classFqName }
            }
        }.toSet()

        branchExpansionState.restoreAfterRebuild {
            treeModel.setRoot(createRootNode(content))
            val root = treeModel.root as DefaultMutableTreeNode
            tree.expandPath(TreePath(root.path))

            branchNodes().map { branchNode ->
                val nestedPaths = branchNode.children().toList().filterIsInstance<BuildNode>().flatMap { buildNode ->
                    val expandedClassPaths = buildNode.children().toList().filterIsInstance<FailedTestClassNode>()
                        .filter { classNode -> (buildNode.build.buildId to classNode.classFqName) in expandedFailedTestClasses }
                        .map { classNode -> TreePath(classNode.path) }
                    val expandedBuildPath = TreePath(buildNode.path)
                        .takeIf { buildNode.build.buildId in expandedBuildIds }

                    expandedClassPaths + listOfNotNull(expandedBuildPath)
                }

                RemoteRunBranchTreePaths(
                    branchName = branchNode.branchName,
                    branchPath = TreePath(branchNode.path),
                    nestedPaths = nestedPaths,
                )
            }
        }

        if (filterChanged) {
            val bestBranchName =
                bestMatchingRemoteRunBranchName(branchNodes().map { node -> node.branchName }, content.filterText)
            val bestBranchNode = branchNodes().firstOrNull { node -> node.branchName == bestBranchName }
            if (bestBranchNode != null) {
                tree.selectionPath = TreePath(bestBranchNode.path)
                return
            }
        }

        restoreSelection(selectedBuildId, selectedBranchName, selectedFailedTest, selectedFailedTestClass)
    }

    private fun branchNodes(): List<BranchNode> {
        val root = treeModel.root as DefaultMutableTreeNode
        return root.children().toList().filterIsInstance<BranchNode>()
    }

    private fun branchTreePaths(): List<RemoteRunBranchTreePaths> =
        branchNodes().map { branchNode ->
            RemoteRunBranchTreePaths(
                branchName = branchNode.branchName,
                branchPath = TreePath(branchNode.path),
            )
        }

    private fun restoreSelection(
        selectedBuildId: String?,
        selectedBranchName: String?,
        selectedFailedTest: Pair<String, FailedTest>?,
        selectedFailedTestClass: Pair<String, String>?,
    ) {
        val branchNode = branchNodes().firstOrNull { node -> node.branchName == selectedBranchName } ?: return
        val buildNode = branchNode.children().toList()
            .filterIsInstance<BuildNode>()
            .firstOrNull { node -> node.build.buildId == selectedBuildId }
        val selectedTestNode = selectedFailedTest
            ?.takeIf { (buildId, _) -> buildNode?.build?.buildId == buildId }
            ?.let { (_, test) -> buildNode?.let(::failedTestNodes)?.firstOrNull { node -> node.test == test } }
        val selectedClassNode = selectedFailedTestClass
            ?.takeIf { (buildId, _) -> buildNode?.build?.buildId == buildId }
            ?.let { (_, classFqName) ->
                buildNode?.children()?.toList()?.filterIsInstance<FailedTestClassNode>()
                    ?.firstOrNull { node -> node.classFqName == classFqName }
            }

        tree.selectionPath = TreePath((selectedTestNode ?: selectedClassNode ?: buildNode ?: branchNode).path)
    }

    private fun failedTestNodes(buildNode: BuildNode): List<FailedTestNode> =
        buildNode.children().toList().flatMap { node ->
            when (node) {
                is FailedTestClassNode -> node.children().toList().filterIsInstance<FailedTestNode>()
                is FailedTestNode -> listOf(node)
                else -> emptyList()
            }
        }

    private fun createRootNode(content: TreeContent): DefaultMutableTreeNode {
        val currentBranchName = content.currentBranchName
        val launchProgress = content.launchProgress
        val buildsByBranch = content.builds.groupBy { rememberedBuild -> rememberedBuild.branchName }
        val orderedBranchNames = orderRemoteRunBranchNames(
            currentBranchName = currentBranchName,
            localBranchNames = content.localBranchNames,
            builds = content.builds,
            pendingLaunchBranchName = launchProgress?.branchName,
        ).let { branchNames -> filterRemoteRunBranchNames(branchNames, content.filterText) }

        val root = DefaultMutableTreeNode("Remote Run")
        for (branchName in orderedBranchNames) {
            val branchNode = BranchNode(
                branchName,
                isCurrent = branchName == currentBranchName,
                pullRequestUrl = content.pullRequestsByBranch[branchName]?.url,
            )
            if (launchProgress != null && launchProgress.branchName == branchName) {
                branchNode.add(PendingLaunchNode(launchProgress))
            }

            buildsByBranch[branchName].orEmpty()
                .sortedByDescending { rememberedBuild -> rememberedBuild.launchTimestamp }
                .forEach { rememberedBuild ->
                    val failedTests = content.failedTests[rememberedBuild.buildId]
                    val buildNode = BuildNode(rememberedBuild, failedTests)
                    groupFailedTestsByClass(failedTests?.tests.orEmpty()).forEach { testGroup ->
                        val sortedTests = testGroup.tests.sortedBy { failedTest -> failedTest.isMuted }
                        if (testGroup.classFqName == null) {
                            sortedTests.forEach { failedTest ->
                                buildNode.add(
                                    FailedTestNode(
                                        rememberedBuild,
                                        failedTest
                                    )
                                )
                            }
                        } else {
                            val classNode =
                                FailedTestClassNode(rememberedBuild, testGroup.classFqName, sortedTests.size)
                            sortedTests.forEach { failedTest ->
                                classNode.add(
                                    FailedTestNode(
                                        rememberedBuild,
                                        failedTest
                                    )
                                )
                            }
                            buildNode.add(classNode)
                        }
                    }
                    failedTests?.remainingCount
                        ?.takeIf { remainingCount -> remainingCount > 0 }
                        ?.let { remainingCount -> buildNode.add(MoreFailedTestsNode(rememberedBuild, remainingCount)) }
                    branchNode.add(buildNode)
                }
            root.add(branchNode)
        }

        return root
    }

    private fun selectedBranchName(): String? {
        return when (val node = tree.selectionPath?.lastPathComponent) {
            is BranchNode -> node.branchName
            is BuildNode -> node.build.branchName
            is FailedTestClassNode -> node.build.branchName
            is FailedTestNode -> node.build.branchName
            is MoreFailedTestsNode -> node.build.branchName
            else -> null
        }
    }

    private fun selectedBranchNode(): BranchNode? = tree.selectionPath?.lastPathComponent as? BranchNode

    private fun selectedBuild(): RememberedTeamCityBuild? = when (val node = tree.selectionPath?.lastPathComponent) {
        is BuildNode -> node.build
        is FailedTestClassNode -> node.build
        is FailedTestNode -> node.build
        is MoreFailedTestsNode -> node.build
        else -> null
    }

    /* Fetched builds are not remembered, so there is nothing to remove for them. */
    private fun isRememberedBuild(build: RememberedTeamCityBuild): Boolean =
        RememberedTeamCityBuildStore.getInstance(project).builds().any { rememberedBuild ->
            rememberedBuild.buildId == build.buildId
        }

    private fun selectedFailedTest(): FailedTestNode? =
        tree.selectionPath?.lastPathComponent as? FailedTestNode

    private fun selectedFailedTestClass(): FailedTestClassNode? =
        tree.selectionPath?.lastPathComponent as? FailedTestClassNode

    /* Toolbar action: always launches the aggregate run for the checked out branch. */
    @Suppress("DialogTitleCapitalization")
    private inner class LaunchTestRunAction : AnAction(
        MyBundle.message("teamcity.toolwindow.launch.text"),
        MyBundle.message("teamcity.toolwindow.launch.description"),
        AllIcons.Actions.Execute,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val isLaunchInFlight = TeamCityTestLaunchService.getInstance(project).isLaunchInFlight
            val targetBranchName = project.currentBranchName()

            e.presentation.isEnabled = !isLaunchInFlight && targetBranchName != null
            e.presentation.text = if (targetBranchName == null) {
                MyBundle.message("teamcity.toolwindow.launch.text")
            } else {
                MyBundle.message("teamcity.toolwindow.launch.branch.text", targetBranchName)
            }
            e.presentation.description = if (isLaunchInFlight) {
                MyBundle.message("teamcity.toolwindow.launch.in.progress")
            } else {
                MyBundle.message("teamcity.toolwindow.launch.description")
            }
        }

        override fun actionPerformed(e: AnActionEvent) {
            val branchName = project.currentBranchName() ?: return
            TeamCityTestLaunchService.getInstance(project).launchAggregateBuild(branchName)
        }
    }

    /* Context menu action: launches the aggregate run for the right-clicked branch. */
    @Suppress("DialogTitleCapitalization")
    private inner class LaunchSelectedBranchAction : AnAction(
        MyBundle.message("teamcity.toolwindow.launch.text"),
        MyBundle.message("teamcity.toolwindow.launch.selected.description"),
        AllIcons.Actions.Execute,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val isLaunchInFlight = TeamCityTestLaunchService.getInstance(project).isLaunchInFlight
            val targetBranchName = selectedBranchName()

            e.presentation.isEnabled = !isLaunchInFlight && targetBranchName != null
            e.presentation.setText(aggregateBranchActionText(targetBranchName), false)
        }

        override fun actionPerformed(e: AnActionEvent) {
            val branchName = selectedBranchName() ?: return
            TeamCityTestLaunchService.getInstance(project).launchAggregateBuild(branchName)
        }
    }

    /* Context menu action: rebases the selected branch against master and runs the safe-merge gate without merging. */
    private inner class LaunchSafeMergeDryRunAction : AnAction(
        MyBundle.message("teamcity.toolwindow.safe.merge.dry.run.text"),
        MyBundle.message("teamcity.toolwindow.safe.merge.dry.run.description"),
        AllIcons.Actions.Execute,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val isLaunchInFlight = TeamCityTestLaunchService.getInstance(project).isLaunchInFlight
            val targetBranchName = selectedBranchName()

            e.presentation.isEnabled = !isLaunchInFlight && targetBranchName != null
            e.presentation.setText(safeMergeDryRunBranchActionText(targetBranchName), false)
        }

        override fun actionPerformed(e: AnActionEvent) {
            val branchName = selectedBranchName() ?: return
            TeamCityTestLaunchService.getInstance(project).launchSafeMergeDryRun(branchName)
        }
    }

    /* Context menu action: fetches the aggregate builds of the right-clicked branch on demand. */
    private inner class RefreshBranchAction : AnAction(
        MyBundle.message("teamcity.toolwindow.refresh.branch.text"),
        MyBundle.message("teamcity.toolwindow.refresh.branch.description"),
        AllIcons.Actions.Refresh,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        /* Disabled while a fetch is in flight: the service has no re-entrancy guard. */
        override fun update(e: AnActionEvent) {
            val statusService = TeamCityBuildStatusService.getInstance(project)

            e.presentation.isEnabled = statusService.fetchState != TeamCityFetchState.IN_PROGRESS
        }

        override fun actionPerformed(e: AnActionEvent) {
            val branchName = selectedBranchName() ?: return
            TeamCityBuildStatusService.getInstance(project).requestBranchRefresh(branchName)
            requestPullRequestRefresh()
        }
    }

    private inner class StopSelectedBuildAction : AnAction(
        MyBundle.message("teamcity.toolwindow.stop.text"),
        MyBundle.message("teamcity.toolwindow.stop.description"),
        AllIcons.Actions.Suspend,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        /* Only unfinished builds can be stopped, so finished ones do not offer the action at all. */
        override fun update(e: AnActionEvent) {
            val build = selectedBuild()
            val status = build?.let { rememberedBuild ->
                TeamCityBuildStatusService.getInstance(project).status(rememberedBuild.buildId)
            }
            e.presentation.isVisible = build != null && isBuildUnfinished(build, status)
        }

        override fun actionPerformed(e: AnActionEvent) {
            val build = selectedBuild() ?: return
            val confirmed = MessageDialogBuilder.yesNo(
                MyBundle.message("teamcity.toolwindow.stop.confirm.title"),
                MyBundle.message("teamcity.toolwindow.stop.confirm.message", build.buildId, build.branchName),
            ).ask(project)
            if (!confirmed) return

            TeamCityTestLaunchService.getInstance(project).stopBuilds(listOf(build))
        }
    }

    private inner class RefreshStatusesAction : AnAction(
        MyBundle.message("teamcity.toolwindow.refresh.text"),
        MyBundle.message("teamcity.toolwindow.refresh.description"),
        AllIcons.Actions.Refresh,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        /* While a fetch round is running, the button ignores clicks and its glyph becomes the spinner. The
           spinner is set as the disabled icon because a disabled action button rasterizes its normal icon
           once into a static image, which would not animate. */
        override fun update(e: AnActionEvent) {
            val isFetching =
                TeamCityBuildStatusService.getInstance(project).fetchState == TeamCityFetchState.IN_PROGRESS

            e.presentation.isEnabled = !isFetching
            e.presentation.disabledIcon = if (isFetching) AnimatedIcon.Default.INSTANCE else null
        }

        override fun actionPerformed(e: AnActionEvent) {
            TeamCityBuildStatusService.getInstance(project).requestRefresh()
            requestPullRequestRefresh()
        }
    }

    private inner class NavigateToFailedTestAction : AnAction(
        MyBundle.message("teamcity.toolwindow.navigate.to.test"),
        null,
        AllIcons.RunConfigurations.TestFailed,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            val failedTestNode = selectedFailedTest() ?: return
            navigateToTest(failedTestNode.test)
        }
    }

    private inner class RunTestLocallyAction : AnAction(
        MyBundle.message("teamcity.toolwindow.run.test.locally"),
        null,
        AllIcons.RunConfigurations.TestState.Red2,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            val failedTestNode = selectedFailedTest() ?: return
            listOf(failedTestNode.test).runLocally()
        }
    }

    private inner class NavigateToFailedTestClassAction : AnAction(
        MyBundle.message("teamcity.toolwindow.navigate.to.class"),
        null,
        AllIcons.Nodes.Class,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            val failedTestClassNode = selectedFailedTestClass() ?: return
            navigateToClass(failedTestClassNode.classFqName)
        }
    }

    private inner class RunFailedTestsOfClassAction : AnAction(
        MyBundle.message("teamcity.toolwindow.run.tests.of.class.locally"),
        null,
        AllIcons.RunConfigurations.TestState.Red2,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            val failedTestClassNode = selectedFailedTestClass() ?: return
            val failedTests = failedTestClassNode.children().toList().filterIsInstance<FailedTestNode>().map { it.test }
            failedTests.runLocally()
        }
    }

    private inner class OpenBuildInTeamCityAction : AnAction(
        MyBundle.message("teamcity.toolwindow.open.text"),
        MyBundle.message("teamcity.toolwindow.open.description"),
        null,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            val build = selectedBuild() ?: return
            BrowserUtil.browse(build.webUrl)
        }
    }

    /* Context menu action: opens the open pull request of the right-clicked branch in the browser. */
    private inner class OpenPullRequestAction : AnAction(
        MyBundle.message("teamcity.toolwindow.open.pull.request.text"),
        MyBundle.message("teamcity.toolwindow.open.pull.request.description"),
        AllIcons.Vcs.Vendors.Github,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        /* Only branches with an open pull request offer the action at all. */
        override fun update(e: AnActionEvent) {
            e.presentation.isVisible = selectedBranchNode()?.pullRequestUrl != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val pullRequestUrl = selectedBranchNode()?.pullRequestUrl ?: return
            BrowserUtil.browse(pullRequestUrl)
        }
    }

    private inner class RunFailedTestsAction : AnAction(
        MyBundle.message("teamcity.toolwindow.run.failed.text"),
        MyBundle.message("teamcity.toolwindow.run.failed.description"),
        AllIcons.RunConfigurations.TestState.Red2,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            val build = selectedBuild() ?: return
            val service = project.getServiceIfCreated(TeamCityBuildStatusService::class.java) ?: return
            val failedTests = service.failedTests(build.buildId) ?: return
            failedTests.tests.runLocally()
        }
    }

    /**
     * NB: [MAX_FAILED_TESTS] limits the number of tests it's possible to rerun locally.
     */
    private fun List<FailedTest>.runLocally() {
        val fqTests = mapNotNullTo(mutableSetOf(), ::failedTestFqName)
        val workingDirectory = project.basePath
            ?: return project.toast("Failed to get the project working directory", MessageType.ERROR)
        findAndLaunchTestsByFqReferences(
            fqTests, workingDirectory, DefaultRunExecutor.getRunExecutorInstance(), project
        )
    }

    private inner class RemoveRememberedBuildAction : AnAction(
        MyBundle.message("teamcity.toolwindow.remove.text"),
        MyBundle.message("teamcity.toolwindow.remove.description"),
        null,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isVisible = selectedBuild()?.let(::isRememberedBuild) == true
        }

        override fun actionPerformed(e: AnActionEvent) {
            val build = selectedBuild() ?: return
            RememberedTeamCityBuildStore.getInstance(project).remove(build.buildId)
        }
    }

    private fun navigateToTest(test: FailedTest) {
        navigateToTarget { findFailedTestTarget(project, test) }
    }

    private fun navigateToClass(classFqName: String) {
        navigateToTarget { findFailedTestClass(project, classFqName) }
    }

    private fun navigateToTarget(findTarget: () -> NavigatablePsiElement?) {
        val modalityState = ModalityState.current()
        navigationScope.launch(modalityState.asContextElement()) {
            val targetPointer = smartReadAction(project) {
                findTarget()?.let { target ->
                    SmartPointerManager.getInstance(project).createSmartPsiElementPointer(target)
                }
            }

            withContext(Dispatchers.EDT) {
                val target = targetPointer?.element
                target?.navigate(true)
            }
        }
    }

    private inner class BuildsTreeCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            when (value) {
                is BranchNode -> {
                    icon = branchIcon(value)
                    val branchNameAttributes =
                        if (value.isCurrent) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                        else SimpleTextAttributes.REGULAR_ATTRIBUTES
                    val matchingFragments =
                        remoteRunBranchMatcher(searchField.text)?.matchingFragments(value.branchName)
                    if (matchingFragments == null) {
                        append(value.branchName, branchNameAttributes)
                    } else {
                        SpeedSearchUtil.appendColoredFragments(
                            this,
                            value.branchName,
                            matchingFragments,
                            branchNameAttributes,
                            SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES,
                        )
                    }
                    val pullRequestLine = value.pullRequestUrl
                        ?.let { MyBundle.message("teamcity.window.branch.pull.request.tooltip") + "\n" }
                    toolTipText = pullRequestLine.orEmpty() + MyBundle.message("teamcity.window.branch.tooltip")
                }

                is BuildNode -> {
                    val status = TeamCityBuildStatusService.getInstance(project).status(value.build.buildId)
                    val isScheduling = isSchedulingInProgress(value.build, status)

                    /* Safe merges carry their own icons instead of the status marks; running gates keep the spinner. */
                    icon = when {
                        isScheduling || (value.build.isSafeMerge && status?.state == BuildState.RUNNING) ->
                            AnimatedIcon.Default.INSTANCE

                        value.build.isSafeMergeDryRun -> safeMergeBuildIcon(isDryRun = true, status)
                        value.build.isSafeMerge -> safeMergeBuildIcon(isDryRun = false, status)
                        else -> buildStatusIcon(status)
                    }
                    if (isScheduling) {
                        append(
                            MyBundle.message("teamcity.build.scheduling.aggregate"),
                            SimpleTextAttributes.GRAYED_ATTRIBUTES,
                        )
                    } else {
                        appendBuildSummary(status)
                    }
                    if (value.build.isSafeMergeDryRun) {
                        append(
                            " · " + MyBundle.message("teamcity.build.safe.merge.dry.run"),
                            SimpleTextAttributes.GRAYED_ATTRIBUTES,
                        )
                    }
                    elapsedStartTimestamp(value.build, status, isScheduling)?.let { startTimestamp ->
                        val elapsed = formatElapsed(System.currentTimeMillis() - startTimestamp)
                        append(
                            " " + MyBundle.message("teamcity.build.elapsed", elapsed),
                            SimpleTextAttributes.GRAYED_ATTRIBUTES
                        )
                    }
                    append(
                        "  ${DateFormatUtil.formatDateTime(value.build.launchTimestamp)}",
                        SimpleTextAttributes.GRAYED_ATTRIBUTES,
                    )

                    toolTipText = buildTooltip(value.build, status, isScheduling)
                }

                is PendingLaunchNode -> {
                    icon = AnimatedIcon.Default.INSTANCE
                    append(value.progress.stepText, SimpleTextAttributes.GRAYED_ATTRIBUTES)

                    toolTipText = MyBundle.message("teamcity.toolwindow.launch.in.progress")
                }

                is FailedTestNode -> {
                    icon = AllIcons.RunConfigurations.TestFailed
                    val testAttributes = if (value.test.isMuted) {
                        SimpleTextAttributes.GRAYED_ATTRIBUTES
                    } else {
                        SimpleTextAttributes.ERROR_ATTRIBUTES
                    }
                    append(value.test.displayName, testAttributes)
                    value.test.buildTypeName?.let { buildTypeName ->
                        append("  $buildTypeName", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    toolTipText = MyBundle.message("teamcity.toolwindow.failed.test.tooltip")
                }

                is FailedTestClassNode -> {
                    icon = AllIcons.Nodes.Class
                    append(value.classFqName)
                    append(
                        "  " + MyBundle.message("teamcity.build.tests.failed", value.testCount),
                        SimpleTextAttributes.GRAYED_ATTRIBUTES,
                    )
                    toolTipText = MyBundle.message("teamcity.toolwindow.failed.test.class.tooltip")
                }

                is MoreFailedTestsNode -> {
                    icon = buildFailureIcon
                    append(
                        MyBundle.message("teamcity.build.tests.more", value.count),
                        SimpleTextAttributes.GRAYED_ATTRIBUTES
                    )
                    toolTipText = MyBundle.message("teamcity.toolwindow.open.build.tooltip")
                }
            }
        }

        private fun branchIcon(branchNode: BranchNode): Icon {
            val latestBuild = branchNode.children().toList()
                .filterIsInstance<BuildNode>()
                .maxByOrNull { buildNode -> buildNode.build.launchTimestamp }
                ?.build
            val status =
                latestBuild?.let { build -> TeamCityBuildStatusService.getInstance(project).status(build.buildId) }
            val isPendingLaunch = branchNode.children().toList().any { node -> node is PendingLaunchNode }

            return when (branchRunIndicator(latestBuild, status, isPendingLaunch)) {
                BranchRunIndicator.NONE -> AllIcons.Vcs.Branch
                BranchRunIndicator.IN_PROGRESS -> AnimatedIcon.Default.INSTANCE
                BranchRunIndicator.SUCCESS -> buildSuccessIcon
                BranchRunIndicator.FAILURE -> buildFailureIcon
            }
        }

        private fun appendBuildSummary(status: TeamCityBuildStatus?) {
            val counts = status?.testCounts
            var hasCounts = false

            fun appendCount(count: Int, messageKey: String, attributes: SimpleTextAttributes) {
                if (count <= 0) return
                if (hasCounts) append(", ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append(MyBundle.message(messageKey, count), attributes)
                hasCounts = true
            }

            if (counts != null) {
                appendCount(counts.failed, "teamcity.build.tests.failed", SimpleTextAttributes.ERROR_ATTRIBUTES)
                appendCount(counts.passed, "teamcity.build.tests.passed", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                appendCount(counts.ignored, "teamcity.build.tests.ignored", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                appendCount(counts.muted, "teamcity.build.tests.muted", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }

            if (hasCounts) return

            val fallback = when (status?.state) {
                BuildState.QUEUED -> MyBundle.message("teamcity.build.state.queued")
                BuildState.RUNNING -> MyBundle.message("teamcity.build.tests.running.no.tests")
                BuildState.FINISHED -> MyBundle.message("teamcity.build.tests.none")
                null -> MyBundle.message("teamcity.build.default.name")
            }
            append(fallback, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }

    private fun buildTooltip(
        build: RememberedTeamCityBuild,
        status: TeamCityBuildStatus?,
        isScheduling: Boolean
    ): String {
        val stateText = if (isScheduling) {
            MyBundle.message("teamcity.build.state.scheduling")
        } else {
            when (status?.state) {
                BuildState.QUEUED -> MyBundle.message("teamcity.build.state.queued")
                BuildState.RUNNING -> MyBundle.message("teamcity.build.state.running")
                BuildState.FINISHED -> MyBundle.message("teamcity.build.state.finished")
                null -> MyBundle.message("teamcity.build.state.unknown")
            }
        }

        val tooltip =
            MyBundle.message("teamcity.build.tooltip", build.branchName, stateText, status?.statusText.orEmpty())
        val safeMergeLine = when {
            build.isSafeMergeDryRun -> MyBundle.message("teamcity.build.tooltip.safe.merge.dry.run")
            build.isSafeMerge -> MyBundle.message("teamcity.build.tooltip.safe.merge")
            else -> null
        }
        val tooltipWithSafeMerge = safeMergeLine?.let { line -> "$line\n$tooltip" } ?: tooltip

        return XmlStringUtil.wrapInHtml(
            StringUtil.escapeXmlEntities(tooltipWithSafeMerge.trimEnd()).replace("\n", "<br>")
        )
    }
}

internal fun aggregateBranchActionText(branchName: String?): String =
    if (branchName == null) {
        MyBundle.message("teamcity.toolwindow.launch.text")
    } else {
        MyBundle.message("teamcity.toolwindow.launch.selected.branch.text", branchName)
    }

internal fun safeMergeDryRunBranchActionText(branchName: String?): String =
    if (branchName == null) {
        MyBundle.message("teamcity.toolwindow.safe.merge.dry.run.text")
    } else {
        MyBundle.message("teamcity.toolwindow.safe.merge.dry.run.branch.text", branchName)
    }

internal class RemoteRunBranchCellRenderer(
    pullRequestIcon: Icon = AllIcons.Vcs.Vendors.Github,
    private val pullRequestStatusIcon: Icon? = null,
) {
    private val panel = JPanel().apply {
        layout = HorizontalLayout(0)
        isOpaque = true
        border = JBUI.Borders.emptyRight(4)
    }
    private val pullRequestBadge = JBLabel(pullRequestIcon).apply {
        isOpaque = false
    }
    private val pullRequestStatusBadge = JBLabel().apply {
        isOpaque = false
        border = JBUI.Borders.emptyLeft(2)
    }
    private val currentBranchMarker = JBLabel().apply {
        isOpaque = false
    }

    fun render(
        branchComponent: Component,
        hasPullRequest: Boolean,
        currentBranchMarker: String?,
        background: Color?,
        toolTipText: String?,
    ): JPanel {
        panel.removeAll()
        panel.background = background
        panel.isOpaque = background != null
        panel.toolTipText = toolTipText
        pullRequestBadge.border =
            if (hasPullRequest && currentBranchMarker != null) JBUI.Borders.emptyLeft(4) else null
        panel.add(branchComponent)

        if (currentBranchMarker != null) {
            this.currentBranchMarker.text = currentBranchMarker
            this.currentBranchMarker.font = branchComponent.font.deriveFont(Font.ITALIC)
            this.currentBranchMarker.foreground = branchComponent.foreground
            panel.add(this.currentBranchMarker)
        }

        if (hasPullRequest) {
            panel.add(pullRequestBadge)
            pullRequestStatusBadge.icon = pullRequestStatusIcon ?: githubPullRequestIcon()
            if (pullRequestStatusBadge.icon != null) panel.add(pullRequestStatusBadge)
        }

        return panel
    }
}

internal fun treePathForContextMenuAt(tree: JTree, x: Int, y: Int): TreePath? {
    if (x !in 0 until tree.width) return null

    val path = tree.getClosestPathForLocation(x, y) ?: return null
    val rowBounds = tree.getPathBounds(path) ?: return null

    return path.takeIf { y >= rowBounds.y && y < rowBounds.y + rowBounds.height }
}

/**
 * The status shown next to a branch icon, summarizing the branch's latest run.
 */
internal enum class BranchRunIndicator { NONE, IN_PROGRESS, SUCCESS, FAILURE }

internal fun branchRunIndicator(
    latestBuild: RememberedTeamCityBuild?,
    status: TeamCityBuildStatus?,
    isPendingLaunch: Boolean,
): BranchRunIndicator = when {
    isPendingLaunch -> BranchRunIndicator.IN_PROGRESS
    latestBuild == null || status == null -> BranchRunIndicator.NONE
    isSchedulingInProgress(latestBuild, status) -> BranchRunIndicator.IN_PROGRESS
    status.state != BuildState.FINISHED -> BranchRunIndicator.IN_PROGRESS
    status.isSuccessful == true -> BranchRunIndicator.SUCCESS
    else -> BranchRunIndicator.FAILURE
}

/**
 * Uses the same fuzzy matcher as the Git branches popup.
 */
internal fun remoteRunBranchMatcher(query: String): MinusculeMatcher? {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty() || trimmedQuery == "/") return null

    return NameUtil.buildMatcher("*$trimmedQuery").build()
}

internal fun filterRemoteRunBranchNames(branchNames: List<String>, query: String): List<String> {
    val matcher = remoteRunBranchMatcher(query) ?: return branchNames

    return branchNames.filter { branchName ->
        matcher.matchingFragments(branchName) != null
    }
}

internal fun bestMatchingRemoteRunBranchName(branchNames: List<String>, query: String): String? {
    val matcher = remoteRunBranchMatcher(query) ?: return null
    var bestBranchName: String? = null
    var bestMatchingDegree = Int.MIN_VALUE
    var bestMatchStartOffset = Int.MAX_VALUE

    for (branchName in branchNames) {
        val fragments = matcher.matchingFragments(branchName) ?: continue
        val matchingDegree = matcher.matchingDegree(branchName, false, fragments)
        val matchStartOffset = fragments.head.startOffset

        if (matchingDegree > bestMatchingDegree ||
            matchingDegree == bestMatchingDegree && matchStartOffset < bestMatchStartOffset
        ) {
            bestBranchName = branchName
            bestMatchingDegree = matchingDegree
            bestMatchStartOffset = matchStartOffset
        }
    }

    return bestBranchName
}

internal fun orderRemoteRunBranchNames(
    currentBranchName: String?,
    localBranchNames: List<String>,
    builds: List<RememberedTeamCityBuild>,
    pendingLaunchBranchName: String?,
): List<String> {
    val latestBuildTimestampByBranch = builds.groupBy { build -> build.branchName }
        .mapValues { (_, branchBuilds) -> branchBuilds.maxOf { build -> build.launchTimestamp } }

    val branchesWithBuilds = (latestBuildTimestampByBranch.keys + listOfNotNull(pendingLaunchBranchName))
        .filter { branchName -> branchName != currentBranchName }
        .distinct()
        .sortedWith(
            compareBy<String> { branchName -> if (branchName == pendingLaunchBranchName) 0 else 1 }
                .thenByDescending { branchName -> latestBuildTimestampByBranch[branchName] ?: Long.MIN_VALUE }
                .thenBy { branchName -> branchName },
        )
    val otherLocalBranches = localBranchNames.asSequence()
        .distinct()
        .filter { branchName ->
            branchName != currentBranchName &&
                branchName !in latestBuildTimestampByBranch &&
                branchName != pendingLaunchBranchName
        }
        .sorted()
        .toList()

    return buildList {
        currentBranchName?.let(::add)
        addAll(branchesWithBuilds)
        addAll(otherLocalBranches)
    }.filterNot { branchName -> branchName == MASTER_BRANCH_NAME }
}

/**
 * The builds the tree shows: the remembered launches plus the fetched branch builds.
 * Remembered launches win for the same build id, since their launch time is the local one.
 */
internal fun mergeRememberedAndFetchedBuilds(
    remembered: List<RememberedTeamCityBuild>,
    fetched: List<RememberedTeamCityBuild>,
): List<RememberedTeamCityBuild> {
    val rememberedBuildIds = remembered.map { build -> build.buildId }.toSet()

    return remembered + fetched.filterNot { build -> build.buildId in rememberedBuildIds }
}

internal data class RemoteRunBranchTreePaths(
    val branchName: String,
    val branchPath: TreePath,
    val nestedPaths: List<TreePath> = emptyList(),
)

internal class RemoteRunBranchExpansionState(
    private val tree: JTree,
    private val branchNameForPath: (TreePath) -> String?,
) {
    private val expandedBranchNames = mutableSetOf<String>()
    private var isRestoringTree = false

    init {
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) {
                if (!isRestoringTree) branchNameForPath(event.path)?.let { branchName ->
                    setExpanded(branchName, true)
                }
            }

            override fun treeCollapsed(event: TreeExpansionEvent) {
                if (!isRestoringTree) branchNameForPath(event.path)?.let { branchName ->
                    setExpanded(branchName, false)
                }
            }
        })
    }

    fun isExpanded(branchName: String): Boolean = branchName in expandedBranchNames

    fun setExpanded(branchName: String, isExpanded: Boolean) {
        if (isExpanded) expandedBranchNames.add(branchName) else expandedBranchNames.remove(branchName)
    }

    fun expandBranches(branches: List<RemoteRunBranchTreePaths>) {
        setBranchesExpanded(branches, isExpanded = true)
    }

    fun collapseBranches(branches: List<RemoteRunBranchTreePaths>) {
        setBranchesExpanded(branches, isExpanded = false)
    }

    private fun setBranchesExpanded(branches: List<RemoteRunBranchTreePaths>, isExpanded: Boolean) {
        val root = tree.model.root as DefaultMutableTreeNode
        tree.expandPath(TreePath(root.path))

        branches.forEach { branchPaths ->
            setExpanded(branchPaths.branchName, isExpanded)
            if (isExpanded) tree.expandPath(branchPaths.branchPath) else tree.collapsePath(branchPaths.branchPath)
        }
    }

    fun restoreAfterRebuild(rebuild: () -> List<RemoteRunBranchTreePaths>) {
        val wasRestoringTree = isRestoringTree
        isRestoringTree = true
        try {
            rebuild().forEach { branchPaths ->
                if (!isExpanded(branchPaths.branchName)) return@forEach

                tree.expandPath(branchPaths.branchPath)
                branchPaths.nestedPaths.forEach(tree::expandPath)
            }
        } finally {
            isRestoringTree = wasRestoringTree
        }
    }
}
