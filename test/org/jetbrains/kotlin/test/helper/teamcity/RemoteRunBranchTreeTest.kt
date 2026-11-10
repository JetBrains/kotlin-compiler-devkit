package org.jetbrains.kotlin.test.helper.teamcity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.util.ui.JBUI
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class RemoteRunBranchTreeTest {
    private val branches = listOf("master", "sellmair/fed/test-2", "rr/sellmair/KT-123", "backup/old")

    @Test
    fun pullRequestBadgeHasSpaceAfterCurrentBranchMarker() = onEdt {
        val githubIcon = ImageIcon()
        val pullRequestIcon = ImageIcon()
        val renderer = RemoteRunBranchCellRenderer(pullRequestIcon = githubIcon, pullRequestStatusIcon = pullRequestIcon)

        val markedRow = renderer.render(JLabel("branch"), true, "current", null, null)
        val markedBadge = markedRow.getComponent(2) as JComponent
        val markedBadgeLeftInset = markedBadge.border?.getBorderInsets(markedBadge)?.left ?: 0

        assertEquals(JBUI.scale(4), markedBadgeLeftInset)
        assertEquals(4, markedRow.componentCount)
        assertEquals(githubIcon, (markedRow.getComponent(2) as JLabel).icon)
        assertEquals(pullRequestIcon, (markedRow.getComponent(3) as JLabel).icon)
        val statusBadge = markedRow.getComponent(3) as JComponent
        assertEquals(JBUI.scale(2), statusBadge.border.getBorderInsets(statusBadge).left)

        val unmarkedRow = renderer.render(JLabel("branch"), true, null, null, null)
        val unmarkedBadge = unmarkedRow.getComponent(1) as JComponent
        val unmarkedBadgeLeftInset = unmarkedBadge.border?.getBorderInsets(unmarkedBadge)?.left ?: 0

        assertEquals(0, unmarkedBadgeLeftInset)
        assertEquals(3, unmarkedRow.componentCount)

        val rowWithoutPullRequest = renderer.render(JLabel("branch"), false, null, null, null)
        assertEquals(1, rowWithoutPullRequest.componentCount)
    }

    @Test
    fun launchActionLabelsIncludeBranchNamesWithoutParsingMnemonics() = onEdt {
        val branchName = "feature/<release>&_draft"
        val suffix = "for '$branchName'"
        val aggregateText = aggregateBranchActionText(branchName)
        val safeMergeText = safeMergeDryRunBranchActionText(branchName)

        assertEquals("Launch: Aggregate (master) $suffix", aggregateText)
        assertEquals("Launch: Safe Merge Dry Run (Rebase) $suffix", safeMergeText)
        assertEquals("Launch: Aggregate (master)", aggregateBranchActionText(null))
        assertEquals("Launch: Safe Merge Dry Run (Rebase)", safeMergeDryRunBranchActionText(null))

        listOf(aggregateText, safeMergeText).forEach { text ->
            val presentation = Presentation()
            presentation.setText(text, false)
            assertEquals(text, presentation.text)
        }
    }

    @Test
    fun blankFilterKeepsAllBranches() {
        assertEquals(branches, filterRemoteRunBranchNames(branches, ""))
        assertEquals(branches, filterRemoteRunBranchNames(branches, "   "))
        assertEquals(branches, filterRemoteRunBranchNames(branches, "/"))
    }

    @Test
    fun filterUsesGitBranchesFuzzyMatchingCaseInsensitively() {
        assertEquals(listOf("sellmair/fed/test-2"), filterRemoteRunBranchNames(branches, "sfed"))
        assertEquals(listOf("rr/sellmair/KT-123"), filterRemoteRunBranchNames(branches, "kt-"))
    }

    @Test
    fun filterWithoutMatchIsEmpty() {
        assertEquals(emptyList(), filterRemoteRunBranchNames(branches, "nothing"))
    }

    @Test
    fun bestMatchPrefersHighestScoreAndThenEarliestMatch() {
        val candidates = listOf("sellmair/fed/test-2", "rr/fed/test-2")

        assertEquals("rr/fed/test-2", bestMatchingRemoteRunBranchName(candidates, "fed"))
        assertEquals("sellmair/fed/test-2", bestMatchingRemoteRunBranchName(branches, "sfed"))
    }

    @Test
    fun pendingLaunchIsInProgress() {
        assertEquals(BranchRunIndicator.IN_PROGRESS, branchRunIndicator(null, null, isPendingLaunch = true))
    }

    @Test
    fun branchWithoutBuildOrStatusHasNoIndicator() {
        assertEquals(BranchRunIndicator.NONE, branchRunIndicator(null, null, isPendingLaunch = false))
        assertEquals(BranchRunIndicator.NONE, branchRunIndicator(build(), null, isPendingLaunch = false))
    }

    @Test
    fun schedulingQueuedAndRunningAreInProgress() {
        val trigger = build().copy(isAggregateTrigger = true)

        assertEquals(BranchRunIndicator.IN_PROGRESS, branchRunIndicator(trigger, status(BuildState.RUNNING, null), false))
        assertEquals(BranchRunIndicator.IN_PROGRESS, branchRunIndicator(build(), status(BuildState.QUEUED, null), false))
        assertEquals(BranchRunIndicator.IN_PROGRESS, branchRunIndicator(build(), status(BuildState.RUNNING, true), false))
    }

    @Test
    fun finishedBuildsAreSuccessOrFailure() {
        assertEquals(BranchRunIndicator.SUCCESS, branchRunIndicator(build(), status(BuildState.FINISHED, true), false))
        assertEquals(BranchRunIndicator.FAILURE, branchRunIndicator(build(), status(BuildState.FINISHED, false), false))
        assertEquals(BranchRunIndicator.FAILURE, branchRunIndicator(build(), status(BuildState.FINISHED, null), false))
    }

    @Test
    fun branchesStartCollapsedAndNewBranchesStayCollapsedAfterRefresh() = onEdt {
        val fixture = branchExpansionFixture()

        fixture.rebuild(listOf("master", "feature"))

        assertFalse(fixture.tree.isExpanded(fixture.branchPath("master")))
        assertFalse(fixture.tree.isExpanded(fixture.branchPath("feature")))

        fixture.tree.expandPath(fixture.branchPath("feature"))
        fixture.rebuild(listOf("master", "feature", "new"))

        assertFalse(fixture.tree.isExpanded(fixture.branchPath("master")))
        assertTrue(fixture.tree.isExpanded(fixture.branchPath("feature")))
        assertFalse(fixture.tree.isExpanded(fixture.branchPath("new")))
    }

    @Test
    fun branchExpansionSurvivesFilteringAndClearingTheFilter() = onEdt {
        val fixture = branchExpansionFixture()
        fixture.rebuild(listOf("master", "feature"))
        fixture.tree.expandPath(fixture.branchPath("feature"))

        fixture.rebuild(listOf("master"))
        assertTrue(fixture.expansionState.isExpanded("feature"))

        fixture.rebuild(listOf("master", "feature"))

        assertTrue(fixture.tree.isExpanded(fixture.branchPath("feature")))
        assertFalse(fixture.tree.isExpanded(fixture.branchPath("master")))
    }

    @Test
    fun nestedExpansionDoesNotReopenCollapsedBranchAfterRebuild() = onEdt {
        val fixture = branchExpansionFixture()
        fixture.rebuild(listOf("feature"))
        fixture.tree.expandPath(fixture.branchPath("feature"))
        fixture.tree.expandPath(fixture.buildPath("feature"))
        fixture.tree.collapsePath(fixture.branchPath("feature"))

        fixture.rebuild(listOf("feature"), restoreNestedPathsFor = setOf("feature"))

        assertFalse(fixture.tree.isExpanded(fixture.branchPath("feature")))
        assertFalse(fixture.tree.isExpanded(fixture.buildPath("feature")))
    }

    @Test
    fun toolbarOperationsAffectDisplayedBranchesAndSurviveRebuilds() = onEdt {
        val fixture = branchExpansionFixture()
        val allBranchNames = listOf("master", "feature", "hidden")
        fixture.rebuild(allBranchNames)
        fixture.tree.expandPath(fixture.branchPath("hidden"))

        fixture.expansionState.expandBranches(fixture.branchPaths(listOf("master", "feature")))

        assertTrue(fixture.tree.isExpanded(fixture.branchPath("master")))
        assertTrue(fixture.tree.isExpanded(fixture.branchPath("feature")))
        assertFalse(fixture.tree.isExpanded(fixture.buildPath("master")))
        assertFalse(fixture.tree.isExpanded(fixture.buildPath("feature")))
        assertTrue(fixture.tree.isExpanded(fixture.branchPath("hidden")))

        fixture.rebuild(listOf("master", "feature"))
        fixture.expansionState.collapseBranches(fixture.branchPaths(listOf("master", "feature")))

        assertFalse(fixture.tree.isExpanded(fixture.branchPath("master")))
        assertFalse(fixture.tree.isExpanded(fixture.branchPath("feature")))
        assertTrue(fixture.expansionState.isExpanded("hidden"))

        fixture.rebuild(allBranchNames)

        assertFalse(fixture.tree.isExpanded(fixture.branchPath("master")))
        assertFalse(fixture.tree.isExpanded(fixture.branchPath("feature")))
        assertTrue(fixture.tree.isExpanded(fixture.branchPath("hidden")))

        fixture.expansionState.expandBranches(fixture.branchPaths(listOf("master", "feature")))
        fixture.rebuild(allBranchNames)

        assertTrue(fixture.tree.isExpanded(fixture.branchPath("master")))
        assertTrue(fixture.tree.isExpanded(fixture.branchPath("feature")))
        assertTrue(fixture.tree.isExpanded(fixture.branchPath("hidden")))
        assertFalse(fixture.tree.isExpanded(fixture.buildPath("master")))
        assertFalse(fixture.tree.isExpanded(fixture.buildPath("feature")))
    }

    @Test
    fun toolbarOperationsAreSafeWhenNoBranchesAreDisplayed() = onEdt {
        val fixture = branchExpansionFixture()
        fixture.rebuild(listOf("hidden"))
        fixture.tree.expandPath(fixture.branchPath("hidden"))
        fixture.rebuild(emptyList())

        fixture.expansionState.expandBranches(fixture.branchPaths(emptyList()))
        fixture.expansionState.collapseBranches(fixture.branchPaths(emptyList()))

        assertTrue(fixture.expansionState.isExpanded("hidden"))
        fixture.rebuild(listOf("hidden"))
        assertTrue(fixture.tree.isExpanded(fixture.branchPath("hidden")))
    }

    @Test
    fun mergePrefersRememberedBuildsOverFetchedDuplicates() {
        val remembered = listOf(build())
        val fetched = listOf(
            build().copy(launchTimestamp = 99),
            build().copy(buildId = "2", branchName = "feature", launchTimestamp = 5),
        )

        assertEquals(
            listOf(build(), build().copy(buildId = "2", branchName = "feature", launchTimestamp = 5)),
            mergeRememberedAndFetchedBuilds(remembered, fetched),
        )
    }

    @Test
    fun mergeWithoutRememberedBuildsShowsEveryFetchedBuild() {
        val fetched = listOf(build(), build().copy(buildId = "2"))

        assertEquals(fetched, mergeRememberedAndFetchedBuilds(remembered = emptyList(), fetched = fetched))
    }

    @Test
    fun contextMenuPathCoversTheEntireRow() {
        val (tree, branchPath, _, _) = treeWithNestedRows()
        val rowBounds = requireNotNull(tree.getPathBounds(branchPath))
        val rowY = rowBounds.y + rowBounds.height / 2

        assertEquals(branchPath, treePathForContextMenuAt(tree, rowBounds.x + rowBounds.width / 2, rowY))
        assertEquals(branchPath, treePathForContextMenuAt(tree, 0, rowY))
        assertEquals(branchPath, treePathForContextMenuAt(tree, tree.width - 1, rowY))
    }

    @Test
    fun contextMenuPathUsesTheNestedRowUnderThePointer() {
        val (tree, _, buildPath, _) = treeWithNestedRows()
        val rowBounds = requireNotNull(tree.getPathBounds(buildPath))
        val rowY = rowBounds.y + rowBounds.height / 2

        assertEquals(buildPath, treePathForContextMenuAt(tree, tree.width - 1, rowY))
    }

    @Test
    fun contextMenuPathRejectsPointsOutsideTheTreeRows() {
        val (tree, branchPath, _, lastBranchPath) = treeWithNestedRows()
        val rowBounds = requireNotNull(tree.getPathBounds(branchPath))
        val rowY = rowBounds.y + rowBounds.height / 2
        val lastRowBounds = requireNotNull(tree.getPathBounds(lastBranchPath))

        assertNull(treePathForContextMenuAt(tree, -1, rowY))
        assertNull(treePathForContextMenuAt(tree, tree.width, rowY))
        assertNull(treePathForContextMenuAt(tree, tree.width - 1, lastRowBounds.y + lastRowBounds.height))
    }

    private fun treeWithNestedRows(): TreeFixture {
        val root = DefaultMutableTreeNode("root")
        val branch = DefaultMutableTreeNode("branch")
        val build = DefaultMutableTreeNode("build")
        val lastBranch = DefaultMutableTreeNode("last branch")
        root.add(branch)
        branch.add(build)
        root.add(lastBranch)

        val tree = JTree(root)
        val rootPath = TreePath(root.path)
        val branchPath = TreePath(branch.path)
        val buildPath = TreePath(build.path)
        val lastBranchPath = TreePath(lastBranch.path)
        tree.isRootVisible = false
        tree.rowHeight = 24
        tree.expandPath(rootPath)
        tree.expandPath(branchPath)
        tree.setSize(300, 120)

        return TreeFixture(tree, branchPath, buildPath, lastBranchPath)
    }

    private fun branchExpansionFixture(): BranchExpansionFixture = BranchExpansionFixture()

    private class BranchExpansionFixture {
        val tree: JTree
        val expansionState: RemoteRunBranchExpansionState
        private val model: DefaultTreeModel

        init {
            val root = DefaultMutableTreeNode("root")
            model = DefaultTreeModel(root)
            tree = JTree(model).apply { isRootVisible = false }
            expansionState = RemoteRunBranchExpansionState(tree) { path ->
                (path.lastPathComponent as? TestBranchNode)?.branchName
            }
        }

        fun rebuild(branchNames: List<String>, restoreNestedPathsFor: Set<String> = emptySet()) {
            expansionState.restoreAfterRebuild {
                val root = DefaultMutableTreeNode("root")
                val branches = branchNames.map { branchName ->
                    val branch = TestBranchNode(branchName)
                    val build = DefaultMutableTreeNode("build")
                    build.add(DefaultMutableTreeNode("test class"))
                    branch.add(build)
                    root.add(branch)
                    branch to build
                }
                model.setRoot(root)
                tree.expandPath(TreePath(root.path))

                branches.map { (branch, build) ->
                    RemoteRunBranchTreePaths(
                        branchName = branch.branchName,
                        branchPath = TreePath(branch.path),
                        nestedPaths = if (branch.branchName in restoreNestedPathsFor) {
                            listOf(TreePath(build.path))
                        } else {
                            emptyList()
                        },
                    )
                }
            }
        }

        fun branchPath(branchName: String): TreePath {
            val root = model.root as DefaultMutableTreeNode
            val branch = root.children().toList().filterIsInstance<TestBranchNode>()
                .first { node -> node.branchName == branchName }
            return TreePath(branch.path)
        }

        fun branchPaths(branchNames: List<String>): List<RemoteRunBranchTreePaths> =
            branchNames.map { branchName ->
                RemoteRunBranchTreePaths(
                    branchName = branchName,
                    branchPath = branchPath(branchName),
                )
            }

        fun buildPath(branchName: String): TreePath {
            val branch = branchPath(branchName).lastPathComponent as TestBranchNode
            val build = branch.children().toList().first() as DefaultMutableTreeNode
            return TreePath(build.path)
        }
    }

    private class TestBranchNode(val branchName: String) : DefaultMutableTreeNode(branchName) {
        override fun isLeaf(): Boolean = false
    }

    private fun onEdt(action: () -> Unit) {
        SwingUtilities.invokeAndWait { action() }
    }

    private data class TreeFixture(
        val tree: JTree,
        val branchPath: TreePath,
        val buildPath: TreePath,
        val lastBranchPath: TreePath,
    )

    private fun build() =
        RememberedTeamCityBuild(
            buildId = "1",
            webUrl = "https://buildserver.example/build/1",
            branchName = "master",
            launchTimestamp = 1,
        )

    private fun status(state: BuildState, isSuccessful: Boolean?) =
        TeamCityBuildStatus(
            state = state,
            isSuccessful = isSuccessful,
            statusText = null,
            webUrl = "https://buildserver.example/build/1",
            branchName = null,
            buildTypeId = null,
            buildTypeName = null,
        )
}
