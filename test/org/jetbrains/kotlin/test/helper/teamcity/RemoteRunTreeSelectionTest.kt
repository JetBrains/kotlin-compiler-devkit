package org.jetbrains.kotlin.test.helper.teamcity

import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteRunTreeSelectionTest {
    @Test
    fun rightClickInsideSelectionKeepsItAndPutsTheClickedRowFirst() = onEdt {
        val (tree, paths) = fixture()
        tree.selectionPaths = arrayOf(paths.classPath, paths.bareTestPath)

        selectForContextMenu(tree, paths.bareTestPath)

        assertEquals(listOf(paths.bareTestPath, paths.classPath), tree.selectionPaths.orEmpty().toList())
    }

    @Test
    fun rightClickOutsideSelectionCollapsesToTheClickedRow() = onEdt {
        val (tree, paths) = fixture()
        tree.selectionPaths = arrayOf(paths.classPath, paths.bareTestPath)

        selectForContextMenu(tree, paths.buildPath)

        assertEquals(listOf(paths.buildPath), tree.selectionPaths.orEmpty().toList())
    }

    @Test
    fun rightClickWithoutSelectionSelectsTheClickedRow() = onEdt {
        val (tree, paths) = fixture()

        selectForContextMenu(tree, paths.classPath)

        assertEquals(listOf(paths.classPath), tree.selectionPaths.orEmpty().toList())
    }

    @Test
    fun rightClickOnTheOnlySelectedRowKeepsIt() = onEdt {
        val (tree, paths) = fixture()
        tree.selectionPaths = arrayOf(paths.classPath)

        selectForContextMenu(tree, paths.classPath)

        assertEquals(listOf(paths.classPath), tree.selectionPaths.orEmpty().toList())
    }

    @Test
    fun failedTestsInSelectionCoversSelectedTestsAndClasses() = onEdt {
        val (tree, paths) = fixture()
        tree.selectionPaths = arrayOf(paths.classPath, paths.bareTestPath, paths.unrelatedPath)

        val failedTests = failedTestsInSelection(tree)

        assertEquals(listOf(failedTest("testA"), failedTest("testB"), failedTest("testC")), failedTests)
    }

    @Test
    fun selectedBuildsInSelectionDeduplicatesBuilds() = onEdt {
        val (tree, paths) = fixture()
        tree.selectionPaths = arrayOf(paths.buildPath, paths.morePath, paths.otherBuildPath)

        val builds = selectedBuildsInSelection(tree)

        assertEquals(listOf(rememberedBuild("1"), rememberedBuild("2")), builds)
    }

    private data class TreeFixture(val tree: JTree, val paths: TreeFixturePaths)

    private data class TreeFixturePaths(
        val classPath: TreePath,
        val bareTestPath: TreePath,
        val buildPath: TreePath,
        val morePath: TreePath,
        val otherBuildPath: TreePath,
        val unrelatedPath: TreePath,
    )

    /**
     * A build with a failed-test class of two tests, one bare failed test and a "more failed tests" row,
     * plus a second build and an unrelated row. The helpers under test walk the selection only, so the
     * nodes hang directly off the root without branch nodes.
     */
    private fun fixture(): TreeFixture {
        val classNode = FailedTestClassNode(rememberedBuild("1"), "org.example.MyTest", 2)
        classNode.add(FailedTestNode(rememberedBuild("1"), failedTest("testA")))
        classNode.add(FailedTestNode(rememberedBuild("1"), failedTest("testB")))

        val bareTestNode = FailedTestNode(rememberedBuild("1"), failedTest("testC"))
        val buildNode = BuildNode(rememberedBuild("1"), null)
        val moreNode = MoreFailedTestsNode(rememberedBuild("1"), 5)
        val otherBuildNode = BuildNode(rememberedBuild("2"), null)
        val unrelatedNode = DefaultMutableTreeNode("unrelated")

        buildNode.add(classNode)
        buildNode.add(bareTestNode)
        buildNode.add(moreNode)

        val root = DefaultMutableTreeNode("Remote Run")
        root.add(buildNode)
        root.add(otherBuildNode)
        root.add(unrelatedNode)

        return TreeFixture(
            tree = JTree(DefaultTreeModel(root)),
            paths = TreeFixturePaths(
                classPath = TreePath(classNode.path),
                bareTestPath = TreePath(bareTestNode.path),
                buildPath = TreePath(buildNode.path),
                morePath = TreePath(moreNode.path),
                otherBuildPath = TreePath(otherBuildNode.path),
                unrelatedPath = TreePath(unrelatedNode.path),
            ),
        )
    }

    private fun failedTest(methodName: String) = FailedTest(
        name = methodName,
        packageName = "org.example",
        className = "MyTest",
        methodName = methodName,
        displayName = methodName,
        isMuted = false,
        buildTypeName = null,
    )

    private fun rememberedBuild(buildId: String) = RememberedTeamCityBuild(
        buildId = buildId,
        webUrl = "https://buildserver.example/build/$buildId",
        branchName = "master",
        launchTimestamp = 1,
    )

    private fun onEdt(action: () -> Unit) {
        SwingUtilities.invokeAndWait { action() }
    }
}
