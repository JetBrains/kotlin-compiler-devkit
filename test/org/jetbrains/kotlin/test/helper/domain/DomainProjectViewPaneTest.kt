package org.jetbrains.kotlin.test.helper.domain

import com.intellij.mock.MockVirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainProjectViewPaneTest {
    @Test
    fun resolvesRepositoryRelativePathsAndRejectsOutsideFiles() {
        val assignedFile = MockVirtualFile.file("assigned.kt")
        val sharedDirectory = MockVirtualFile.dir("shared", assignedFile)
        val baseDirectory = MockVirtualFile.dir("project", MockVirtualFile.dir("src", sharedDirectory))
        val outsideFile = MockVirtualFile.file("outside.kt")
        val repositoryRoot = RepositoryRoot.of(baseDirectory)

        assertEquals(assignedFile, repositoryRoot.resolve(RepositoryPath.of("  src\\shared\\assigned.kt  ")))
        assertNull(repositoryRoot.resolve(RepositoryPath.of("src/shared/missing.kt")))
        assertEquals(RepositoryPath.of("src/shared/assigned.kt"), repositoryRoot.relativePathOf(assignedFile))
        assertEquals(RepositoryPath.of(""), repositoryRoot.relativePathOf(baseDirectory))
        assertNull(repositoryRoot.relativePathOf(outsideFile))

        val tree = DomainPathTree(repositoryRoot)
        assertNull(tree.getOrInsertNode(outsideFile))
        assertFalse(tree.insert(DomainPathNode(outsideFile, isIncluded = true)))
    }

    @Test
    fun buildsDomainHierarchyForMultipleLabels() {
        val baseDirectory = MockVirtualFile.dir(
            "project",
            MockVirtualFile.dir(
                "src",
                MockVirtualFile.dir(
                    "shared",
                    MockVirtualFile.file("assigned.kt"),
                    MockVirtualFile.file("unassigned.kt"),
                    MockVirtualFile.dir("ignored", MockVirtualFile.file("hidden.kt")),
                    MockVirtualFile.dir("build", MockVirtualFile.file("output.bin")),
                ),
                MockVirtualFile.dir("other", MockVirtualFile.file("unrelated.kt")),
            ),
            MockVirtualFile.file("unknown.kt"),
        )
        val dump = DomainDump(
            entries = listOf(
                DomainDumpEntry(setOf(Domain.of("Alpha"), Domain.of("Beta")), RepositoryPath.of("src/shared")),
                DomainDumpEntry(setOf(Domain.of("Unknown")), RepositoryPath.of("unknown.kt")),
            ),
        )
        val repositoryRoot = RepositoryRoot.of(baseDirectory)

        val alphaTree = dump.buildDomainPathTree(Domain.of("Alpha"), repositoryRoot) { true }
        val betaTree = dump.buildDomainPathTree(Domain.of("Beta"), repositoryRoot) { true }
        val unknownTree = dump.buildDomainPathTree(Domain.of("Unknown"), repositoryRoot) { true }
        val filteredUnknownTree = dump.buildDomainPathTree(Domain.of("Unknown"), repositoryRoot) { file ->
            file.name != "unknown.kt"
        }
        val srcDirectory = alphaTree.children.getValue("src")
        val sharedDirectory = srcDirectory.children.getValue("shared")

        assertEquals(setOf("src"), alphaTree.children.keys)
        assertEquals(setOf("src"), betaTree.children.keys)
        assertFalse(srcDirectory.isIncluded)
        assertTrue(sharedDirectory.isIncluded)
        assertEquals(
            setOf("assigned.kt", "unassigned.kt", "ignored"),
            sharedDirectory.resolveChildrenNodes { file -> file.name != "build" }
                .map { node -> node.file.name }
                .toSet(),
        )
        assertEquals(setOf("unknown.kt"), unknownTree.children.keys)
        assertTrue(unknownTree.children.getValue("unknown.kt").isIncluded)
        assertTrue(filteredUnknownTree.children.isEmpty())
    }
}
