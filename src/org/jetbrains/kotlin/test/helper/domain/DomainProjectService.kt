package org.jetbrains.kotlin.test.helper.domain

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.RefreshQueue
import com.intellij.openapi.vfs.newvfs.events.*
import com.intellij.util.messages.Topic
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import kotlin.io.path.isRegularFile

@Service(Service.Level.PROJECT)
class DomainProjectService(private val project: Project) : Disposable {
    companion object {

        private val logger = Logger.getInstance(DomainProjectService::class.java)

        val MODEL_CHANGED_TOPIC: Topic<DomainModelListener> = Topic.create(
            "Domain model changed",
            DomainModelListener::class.java,
        )

        fun getInstance(project: Project): DomainProjectService = project.getService(DomainProjectService::class.java)

        private val dumpPath = RepositoryPath.of("repo/domains.dump.txt")

        internal fun hasDumpMarker(project: Project): Boolean {
            return project.basePath?.let(Path::of)?.resolve(dumpPath.relativePathString)?.isRegularFile() == true
        }
    }

    private val localFileSystem = LocalFileSystem.getInstance()

    private val repositoryRoot: RepositoryRoot?
        get() {
            if (!hasDumpMarker(project)) return null
            val basePath = project.basePath ?: return null
            val virtualFile = localFileSystem.findFileByPath(basePath) ?: return null
            return RepositoryRoot.of(virtualFile)
        }


    @Volatile
    private var disposed = false

    @Volatile
    var dump: DomainDump = DomainDump.EMPTY
        private set

    @Volatile
    var repositoryRevision: Long = 0
        private set

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) = afterVFSChanges(events)
            },
        )
        refresh()
    }

    fun refresh() {
        val repositoryRoot = repositoryRoot ?: run {
            publishIfChanged(DomainDump.EMPTY)
            return
        }
        val dumpFile = repositoryRoot.resolve(dumpPath)
            ?.takeIf { it.isValid && !it.isDirectory }

        if (dumpFile == null) {
            publishIfChanged(DomainDump.EMPTY)
            return
        }

        VfsUtil.markDirty(false, false, dumpFile)
        publishIfChanged(loadDump())
        RefreshQueue.getInstance().refresh(true, false, Runnable {
            if (disposed || project.isDisposed) return@Runnable
            publishIfChanged(loadDump())
        }, dumpFile)
    }

    private fun afterVFSChanges(events: List<VFileEvent>) {
        val basePath = project.basePath ?: return

        val expectedDumpPath = Path.of(basePath, dumpPath.relativePathString).toString()
        if (events.any { event -> event.affectsDumpPath(expectedDumpPath) }) {
            refresh()
        }

        if (dump.paths.isNotEmpty()) {
            val repositoryChanged = events.any { event ->
                event.isRepositoryStructureChange() && event.pathsIncludingPreviousLocation().any { eventPath ->
                    FileUtil.isAncestor(basePath, eventPath, false)
                }
            }

            if (repositoryChanged) {
                repositoryRevision++
                publishModelChanged()
            }
        }
    }

    private fun publishIfChanged(newDump: DomainDump) {
        if (newDump == dump) return
        dump = newDump
        publishModelChanged()
    }

    private fun publishModelChanged() {
        project.messageBus.syncPublisher(MODEL_CHANGED_TOPIC).domainModelChanged()
    }

    private fun loadDump(): DomainDump {
        val repositoryRoot = repositoryRoot ?: return DomainDump.EMPTY
        val file = repositoryRoot.resolve(dumpPath)?.takeIf { it.isValid } ?: return DomainDump.EMPTY
        return try {
            parseDomainDump(file.contentsToByteArray().decodeToString())
        } catch (_: NoSuchFileException) {
            DomainDump.EMPTY
        } catch (_: NotDirectoryException) {
            DomainDump.EMPTY
        } catch (exception: IOException) {
            logger.warn("Unable to read domain dump at $file", exception)
            DomainDump.EMPTY
        } catch (exception: SecurityException) {
            logger.warn("Unable to read domain dump at $file", exception)
            DomainDump.EMPTY
        }
    }

    override fun dispose() {
        disposed = true
    }
}

internal fun isDumpPathAffected(dumpPath: String, eventPaths: Iterable<String>): Boolean {
    val normalizedDumpPath = FileUtil.toSystemIndependentName(dumpPath)
    return eventPaths.any { eventPath ->
        val normalizedEventPath = FileUtil.toSystemIndependentName(eventPath)
        normalizedEventPath == normalizedDumpPath || FileUtil.isAncestor(normalizedEventPath, normalizedDumpPath, false)
    }
}

internal fun VFileEvent.isRepositoryStructureChange(): Boolean =
    this is VFileCreateEvent ||
        this is VFileDeleteEvent ||
        this is VFileCopyEvent ||
        this is VFileMoveEvent ||
        (this is VFilePropertyChangeEvent && propertyName == VirtualFile.PROP_NAME)

private fun VFileEvent.affectsDumpPath(dumpPath: String): Boolean = when (this) {
    is VFileContentChangeEvent -> isDumpPathAffected(dumpPath, listOf(path))
    else -> isRepositoryStructureChange() && isDumpPathAffected(dumpPath, pathsIncludingPreviousLocation())
}

internal fun VFileEvent.pathsIncludingPreviousLocation(): List<String> = buildList {
    add(path)
    when (this@pathsIncludingPreviousLocation) {
        is VFileMoveEvent -> add(oldPath)
        is VFilePropertyChangeEvent -> add(oldPath)
    }
}

fun interface DomainModelListener {
    fun domainModelChanged()
}