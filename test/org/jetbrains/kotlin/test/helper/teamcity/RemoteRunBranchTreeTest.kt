package org.jetbrains.kotlin.test.helper.teamcity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class RemoteRunBranchTreeTest {
    private val branches = listOf("master", "sellmair/fed/test-2", "rr/sellmair/KT-123", "backup/old")

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
