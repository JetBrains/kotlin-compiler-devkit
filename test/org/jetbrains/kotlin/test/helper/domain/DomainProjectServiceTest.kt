package org.jetbrains.kotlin.test.helper.domain

import com.intellij.mock.MockVirtualFile
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class DomainProjectServiceTest : BasePlatformTestCase() {
    private val projectDescriptor = LightProjectDescriptor()

    override fun getProjectDescriptor(): LightProjectDescriptor = projectDescriptor

    fun testDetectsDumpPathChangesAtExactAncestorAndPreviousPaths() {
        val dumpPath = "/parent/project/repo/domains.dump.txt"

        assertTrue(isDumpPathAffected(dumpPath, listOf(dumpPath)))
        assertTrue(isDumpPathAffected(dumpPath, listOf("\\parent\\project\\repo\\domains.dump.txt")))
        assertTrue(isDumpPathAffected(dumpPath, listOf("/parent/project/repo")))
        assertTrue(isDumpPathAffected(dumpPath, listOf("/parent/project")))
        assertTrue(isDumpPathAffected(dumpPath, listOf("/parent/project/repo/renamed.txt", dumpPath)))
        assertFalse(isDumpPathAffected(dumpPath, listOf("/parent/project/repo/other.txt")))
        assertFalse(isDumpPathAffected(dumpPath, listOf("/parent/project/repo-other/domains.dump.txt")))
        assertFalse(isDumpPathAffected(dumpPath, listOf("/outside/repo/domains.dump.txt")))
    }

    /**
     * VFS events have no public constructors; they are only built here as plain inputs for the classifier.
     */
    @Suppress("UnstableApiUsage")
    fun testFileContentChangesAreNotStructuralRepositoryChanges() {
        val oldParent = MockVirtualFile.dir("old")
        val newParent = MockVirtualFile.dir("new")
        val file = MockVirtualFile.file("source.kt")
        oldParent.addChild(file)

        val contentChange = VFileContentChangeEvent(null, file, 0, 1)
        val create = VFileCreateEvent(null, newParent, "created.kt", false, null, null, null)
        val delete = VFileDeleteEvent(null, file)
        val copy = VFileCopyEvent(null, file, newParent, "copied.kt")
        val move = VFileMoveEvent(null, file, newParent)
        val rename = VFilePropertyChangeEvent(
            null,
            file,
            VirtualFile.PROP_NAME,
            "old.kt",
            "renamed.kt",
        )
        val otherPropertyChange = VFilePropertyChangeEvent(
            null,
            file,
            "writable",
            true,
            false,
        )

        assertFalse(contentChange.isRepositoryStructureChange())
        assertTrue(create.isRepositoryStructureChange())
        assertTrue(delete.isRepositoryStructureChange())
        assertTrue(copy.isRepositoryStructureChange())
        assertTrue(move.isRepositoryStructureChange())
        assertTrue(rename.isRepositoryStructureChange())
        assertFalse(otherPropertyChange.isRepositoryStructureChange())

        assertEquals(listOf(move.path, move.oldPath), move.pathsIncludingPreviousLocation())
        assertEquals(listOf(rename.path, rename.oldPath), rename.pathsIncludingPreviousLocation())
        assertEquals(listOf(delete.path), delete.pathsIncludingPreviousLocation())
    }

    fun testDumpCreationMoveAndDeletionUpdateServiceAndPublishNotifications() {
        val projectRoot = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)
            ?: error("Project base directory is not present in the local VFS")
        val service = DomainProjectService.getInstance(project)
        assertEquals(DomainDump.EMPTY, service.dump)

        val observedDumps = mutableListOf<DomainDump>()
        val connection = project.messageBus.connect()
        try {
            connection.subscribe(
                DomainProjectService.MODEL_CHANGED_TOPIC,
                DomainModelListener {
                    observedDumps += service.dump
                },
            )

            lateinit var repoDirectory: VirtualFile
            lateinit var backupDirectory: VirtualFile
            lateinit var dumpFile: VirtualFile
            WriteAction.runAndWait<RuntimeException> {
                repoDirectory = projectRoot.findChild("repo") ?: projectRoot.createChildDirectory(this, "repo")
                backupDirectory = projectRoot.findChild("repo-backup")
                    ?: projectRoot.createChildDirectory(this, "repo-backup")
                dumpFile = repoDirectory.createChildData(this, "domains.dump.txt")
                dumpFile.setBinaryContent("Alpha:\n- src".toByteArray())
            }
            val expectedDump = parseDomainDump("Alpha:\n- src")
            assertEquals(expectedDump, service.dump)
            assertEquals(expectedDump, observedDumps.lastOrNull())

            WriteAction.runAndWait<RuntimeException> {
                dumpFile.move(this, backupDirectory)
            }
            assertEquals(DomainDump.EMPTY, service.dump)
            assertEquals(DomainDump.EMPTY, observedDumps.lastOrNull())

            WriteAction.runAndWait<RuntimeException> {
                dumpFile.move(this, repoDirectory)
            }
            assertEquals(expectedDump, service.dump)
            assertEquals(expectedDump, observedDumps.lastOrNull())

            WriteAction.runAndWait<RuntimeException> {
                dumpFile.delete(this)
            }
            assertEquals(DomainDump.EMPTY, service.dump)
            assertEquals(DomainDump.EMPTY, observedDumps.lastOrNull())
        } finally {
            connection.disconnect()
        }
    }

    override fun tearDown() {
        val probeDirectories = listOf("repo", "repo-backup")
            .mapNotNull { name -> project.basePath?.let { File(it, name) } }
        try {
            super.tearDown()
        } finally {
            probeDirectories.forEach(FileUtil::delete)
        }
    }
}
