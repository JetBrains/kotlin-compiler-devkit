package org.jetbrains.kotlin.test.helper.listeners

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.removeUserData
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import org.jetbrains.kotlin.test.helper.allExtensions
import org.jetbrains.kotlin.test.helper.asPathWithoutAllExtensions
import org.jetbrains.kotlin.test.helper.getRelatedTestFiles
import org.jetbrains.kotlin.test.helper.getTestDataType

val RENAME_KEY = Key.create<Boolean>("org.kotlin.test.helper.renaming")

class RelatedFileRenameListener : BulkFileListener {
    override fun before(events: List<VFileEvent>) {
        val toRename = events.filterIsInstance<VFilePropertyChangeEvent>()
            .flatMap { event ->
                if (event.propertyName != VirtualFile.PROP_NAME) return@flatMap emptyList()
                if (event.oldValue !is String || event.newValue !is String) return@flatMap emptyList()
                if (event.file.getUserData(RENAME_KEY) != null) return@flatMap emptyList()
                val project = event.file.findProject() ?: return@flatMap emptyList()
                if (event.file.getTestDataType(project) == null) return@flatMap emptyList()

                event.file.getRelatedTestFiles(project).filterNot { it == event.file }
                    .map { Pair(it, (event.newValue as String).asPathWithoutAllExtensions) }
            }

        if (toRename.isEmpty()) return

        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication().runWriteAction {
                for ((file, name) in toRename) {
                    try {
                        file.putUserData(RENAME_KEY, true)
                        file.rename(this, name + file.allExtensions)
                    } finally {
                        file.removeUserData(RENAME_KEY)
                    }
                }
            }
        }
    }

    private fun VirtualFile.findProject(): Project? {
        return ProjectManager.getInstance()
            .openProjects
            .firstOrNull { project ->
                ProjectFileIndex.getInstance(project)
                    .isInContent(this)
            }
    }
}