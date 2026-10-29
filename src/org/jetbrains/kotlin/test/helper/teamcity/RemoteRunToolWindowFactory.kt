package org.jetbrains.kotlin.test.helper.teamcity

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.util.text.DateFormatUtil
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.speedSearch.SpeedSearchUtil
import com.intellij.ui.treeStructure.Tree
import com.intellij.xml.util.XmlStringUtil
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import org.jetbrains.kotlin.test.helper.MyBundle
import org.jetbrains.kotlin.test.helper.git.currentBranchName
import org.jetbrains.kotlin.test.helper.git.kotlinMonorepoRepository
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.Timer
import javax.swing.ToolTipManager
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
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
private class BranchNode(val branchName: String, val isCurrent: Boolean) : DefaultMutableTreeNode(branchName) {
    override fun isLeaf(): Boolean = false
}

/**
 * One remembered build under its branch.
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
    val filterText: String,
)

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

    val previouslyLaunchedBranches = (latestBuildTimestampByBranch.keys + listOfNotNull(pendingLaunchBranchName))
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
        addAll(previouslyLaunchedBranches)
        addAll(otherLocalBranches)
    }
}

/**
 * The Remote Run tool window body: all local branches and remembered TeamCity builds grouped by branch,
 * with the currently checked out branch pinned first and previously launched branches ordered by their last run.
 *
 * Build statuses come from [TeamCityBuildStatusService]'s cache, which polls only while this
 * tool window is visible; status changes merely repaint the tree, structure changes rebuild it.
 */
private class RemoteRunToolWindowPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : SimpleToolWindowPanel(false, true), Disposable {

    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode("Remote Run"))
    private val tree = Tree(treeModel)
    private val searchField = SearchTextField(false)
    private val elapsedTimeTimer = Timer(1_000) { tree.repaint() }

    /**
     * What the tree currently shows; null before the first build. EDT only.
     */
    private var shownContent: TreeContent? = null

    init {
        toolbar = createToolbar()

        searchField.textEditor.emptyText.text = MyBundle.message("teamcity.toolwindow.search.placeholder")
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                rebuildTreeModel()
            }
        })

        val contentPanel = JPanel(BorderLayout())
        contentPanel.add(searchField, BorderLayout.NORTH)
        contentPanel.add(JBScrollPane(tree), BorderLayout.CENTER)
        setContent(contentPanel)

        tree.isRootVisible = false
        tree.cellRenderer = BuildsTreeCellRenderer()
        tree.putClientProperty(AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)
        ToolTipManager.sharedInstance().registerComponent(tree)

        /* Right-clicking a row selects it first, so the popup actions apply to the clicked branch or build. */
        val popupMenu = createPopupMenu()
        tree.addMouseListener(object : PopupHandler() {
            override fun invokePopup(component: Component, x: Int, y: Int) {
                tree.getPathForLocation(x, y)?.let { clickedPath -> tree.selectionPath = clickedPath }
                popupMenu.show(component, x, y)
            }
        })

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                return when (val node = tree.selectionPath?.lastPathComponent) {
                    is FailedTestNode -> {
                        navigateToTest(node.build, node.test)
                        true
                    }
                    is MoreFailedTestsNode -> {
                        BrowserUtil.browse(node.build.webUrl)
                        true
                    }
                    is BuildNode -> {
                        BrowserUtil.browse(node.build.webUrl)
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
        if (isVisible) elapsedTimeTimer.start() else elapsedTimeTimer.stop()
    }

    private fun createToolbar(): JComponent {
        val actionGroup = DefaultActionGroup(
            LaunchTestRunAction(),
            RefreshStatusesAction(),
        )

        val actionToolbar = ActionManager.getInstance().createActionToolbar("RemoteRunToolbar", actionGroup, true)
        actionToolbar.targetComponent = this
        return actionToolbar.component
    }

    private fun createPopupMenu(): JPopupMenu {
        val actionGroup = DefaultActionGroup(
            NavigateToFailedTestAction(),
            LaunchSelectedBranchAction(),
            StopSelectedBuildAction(),
            OpenBuildInTeamCityAction(),
            RemoveRememberedBuildAction(),
        )

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
        val builds = RememberedTeamCityBuildStore.getInstance(project).builds()
        val statusService = TeamCityBuildStatusService.getInstance(project)
        val content = TreeContent(
            currentBranchName = repository?.currentBranch?.name,
            localBranchNames = repository?.branches?.localBranches?.map { branch -> branch.name }.orEmpty(),
            launchProgress = TeamCityTestLaunchService.getInstance(project).launchProgress,
            builds = builds,
            failedTests = builds.mapNotNull { build ->
                statusService.failedTests(build.buildId)?.let { failedTests -> build.buildId to failedTests }
            }.toMap(),
            filterText = searchField.text,
        )
        if (content == shownContent) return
        val filterChanged = content.filterText != shownContent?.filterText
        shownContent = content

        val selectedBuildId = selectedBuild()?.buildId
        val selectedBranchName = selectedBranchName()
        val collapsedBranchNames = branchNodes()
            .filter { branchNode -> tree.isCollapsed(TreePath(branchNode.path)) }
            .map { branchNode -> branchNode.branchName }
            .toSet()
        val expandedBuildIds = branchNodes().flatMap { branchNode ->
            branchNode.children().toList().filterIsInstance<BuildNode>()
                .filter { buildNode -> tree.isExpanded(TreePath(buildNode.path)) }
                .map { buildNode -> buildNode.build.buildId }
        }.toSet()

        treeModel.setRoot(createRootNode(content))

        for (branchNode in branchNodes()) {
            if (branchNode.branchName !in collapsedBranchNames) tree.expandPath(TreePath(branchNode.path))
            branchNode.children().toList().filterIsInstance<BuildNode>()
                .filter { buildNode -> buildNode.build.buildId in expandedBuildIds }
                .forEach { buildNode -> tree.expandPath(TreePath(buildNode.path)) }
        }

        if (filterChanged) {
            val bestBranchName = bestMatchingRemoteRunBranchName(branchNodes().map { node -> node.branchName }, content.filterText)
            val bestBranchNode = branchNodes().firstOrNull { node -> node.branchName == bestBranchName }
            if (bestBranchNode != null) {
                tree.selectionPath = TreePath(bestBranchNode.path)
                return
            }
        }

        restoreSelection(selectedBuildId, selectedBranchName)
    }

    private fun branchNodes(): List<BranchNode> {
        val root = treeModel.root as DefaultMutableTreeNode
        return root.children().toList().filterIsInstance<BranchNode>()
    }

    /**
     * Reselects the previously selected build, else its branch; nothing when both are gone.
     */
    private fun restoreSelection(selectedBuildId: String?, selectedBranchName: String?) {
        val branchNode = branchNodes().firstOrNull { node -> node.branchName == selectedBranchName } ?: return
        val buildNode = branchNode.children().toList()
            .filterIsInstance<BuildNode>()
            .firstOrNull { node -> node.build.buildId == selectedBuildId }

        tree.selectionPath = TreePath((buildNode ?: branchNode).path)
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
            val branchNode = BranchNode(branchName, isCurrent = branchName == currentBranchName)
            if (launchProgress != null && launchProgress.branchName == branchName) {
                branchNode.add(PendingLaunchNode(launchProgress))
            }

            buildsByBranch[branchName].orEmpty()
                .sortedByDescending { rememberedBuild -> rememberedBuild.launchTimestamp }
                .forEach { rememberedBuild ->
                    val failedTests = content.failedTests[rememberedBuild.buildId]
                    val buildNode = BuildNode(rememberedBuild, failedTests)
                    failedTests?.tests.orEmpty()
                        .sortedBy { failedTest -> failedTest.isMuted }
                        .forEach { failedTest -> buildNode.add(FailedTestNode(rememberedBuild, failedTest)) }
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
            is FailedTestNode -> node.build.branchName
            is MoreFailedTestsNode -> node.build.branchName
            else -> null
        }
    }

    private fun selectedBuild(): RememberedTeamCityBuild? = when (val node = tree.selectionPath?.lastPathComponent) {
        is BuildNode -> node.build
        is FailedTestNode -> node.build
        is MoreFailedTestsNode -> node.build
        else -> null
    }

    private fun selectedFailedTest(): FailedTestNode? =
        tree.selectionPath?.lastPathComponent as? FailedTestNode

    /* Toolbar action: always launches the aggregate run for the checked out branch. */
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
            e.presentation.text = if (targetBranchName == null) {
                MyBundle.message("teamcity.toolwindow.launch.text")
            } else {
                MyBundle.message("teamcity.toolwindow.launch.branch.text", targetBranchName)
            }
        }

        override fun actionPerformed(e: AnActionEvent) {
            val branchName = selectedBranchName() ?: return
            TeamCityTestLaunchService.getInstance(project).launchAggregateBuild(branchName)
        }
    }

    private inner class StopSelectedBuildAction : AnAction(
        MyBundle.message("teamcity.toolwindow.stop.text"),
        MyBundle.message("teamcity.toolwindow.stop.description"),
        AllIcons.Actions.Suspend,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val build = selectedBuild()
            val status = build?.let { rememberedBuild ->
                TeamCityBuildStatusService.getInstance(project).status(rememberedBuild.buildId)
            }
            e.presentation.isEnabled = build != null && isBuildUnfinished(build, status)
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

        override fun actionPerformed(e: AnActionEvent) {
            TeamCityBuildStatusService.getInstance(project).requestRefresh()
        }
    }

    private inner class NavigateToFailedTestAction : AnAction(
        MyBundle.message("teamcity.toolwindow.navigate.to.test"),
        null,
        AllIcons.RunConfigurations.TestFailed,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedFailedTest() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val failedTestNode = selectedFailedTest() ?: return
            navigateToTest(failedTestNode.build, failedTestNode.test)
        }
    }

    private inner class OpenBuildInTeamCityAction : AnAction(
        MyBundle.message("teamcity.toolwindow.open.text"),
        MyBundle.message("teamcity.toolwindow.open.description"),
        null,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedBuild() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val build = selectedBuild() ?: return
            BrowserUtil.browse(build.webUrl)
        }
    }

    private inner class RemoveRememberedBuildAction : AnAction(
        MyBundle.message("teamcity.toolwindow.remove.text"),
        MyBundle.message("teamcity.toolwindow.remove.description"),
        null,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedBuild() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val build = selectedBuild() ?: return
            RememberedTeamCityBuildStore.getInstance(project).remove(build.buildId)
        }
    }

    private fun navigateToTest(build: RememberedTeamCityBuild, test: FailedTest) {
        DumbService.getInstance(project).runWhenSmart {
            val target = ReadAction.compute<NavigatablePsiElement?, RuntimeException> {
                findFailedTestTarget(project, test)
            }
            if (target == null) {
                BrowserUtil.browse(build.webUrl)
            } else {
                target.navigate(true)
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
                    val matchingFragments = remoteRunBranchMatcher(searchField.text)?.matchingFragments(value.branchName)
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
                    if (value.isCurrent) {
                        /* Properties files strip leading whitespace, so the separating space is added here. */
                        append(" " + MyBundle.message("teamcity.current.branch.marker"), SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES)
                    }

                    toolTipText = MyBundle.message("teamcity.window.branch.tooltip")
                }

                is BuildNode -> {
                    val status = TeamCityBuildStatusService.getInstance(project).status(value.build.buildId)
                    val isScheduling = isSchedulingInProgress(value.build, status)

                    icon = if (isScheduling) AnimatedIcon.Default.INSTANCE else buildStatusIcon(status)
                    if (isScheduling) {
                        append(
                            MyBundle.message("teamcity.build.scheduling.aggregate"),
                            SimpleTextAttributes.GRAYED_ATTRIBUTES,
                        )
                    } else {
                        appendBuildSummary(status)
                    }
                    elapsedStartTimestamp(value.build, status, isScheduling)?.let { startTimestamp ->
                        val elapsed = formatElapsed(System.currentTimeMillis() - startTimestamp)
                        append(" " + MyBundle.message("teamcity.build.elapsed", elapsed), SimpleTextAttributes.GRAYED_ATTRIBUTES)
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
                    failedTestClassFqName(value.test)?.let { className ->
                        append("  $className", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    value.test.buildTypeName?.let { buildTypeName ->
                        append("  $buildTypeName", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    toolTipText = MyBundle.message("teamcity.toolwindow.failed.test.tooltip")
                }

                is MoreFailedTestsNode -> {
                    icon = buildFailureIcon
                    append(MyBundle.message("teamcity.build.tests.more", value.count), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    toolTipText = MyBundle.message("teamcity.toolwindow.open.build.tooltip")
                }
            }
        }

        private fun branchIcon(branchNode: BranchNode): Icon {
            val latestBuild = branchNode.children().toList()
                .filterIsInstance<BuildNode>()
                .maxByOrNull { buildNode -> buildNode.build.launchTimestamp }
                ?.build
            val status = latestBuild?.let { build -> TeamCityBuildStatusService.getInstance(project).status(build.buildId) }
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

    private fun buildTooltip(build: RememberedTeamCityBuild, status: TeamCityBuildStatus?, isScheduling: Boolean): String {
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

        val tooltip = MyBundle.message("teamcity.build.tooltip", build.branchName, stateText, status?.statusText.orEmpty())
        return XmlStringUtil.wrapInHtml(StringUtil.escapeXmlEntities(tooltip.trimEnd()).replace("\n", "<br>"))
    }
}
