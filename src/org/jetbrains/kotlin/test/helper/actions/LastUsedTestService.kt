package org.jetbrains.kotlin.test.helper.actions

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import kotlin.io.path.Path

@Service(Service.Level.PROJECT)
class LastUsedTestService(val project: Project)  {
    companion object {
        fun getInstance(project: Project): LastUsedTestService {
            return project.service<LastUsedTestService>()
        }
    }

    private val chosenRunnerByDirectory: MutableMap<String, String> = mutableMapOf()

    fun updateChosenRunner(directory: String?, runnerName: String) {
        if (directory == null) return
        chosenRunnerByDirectory[directory] = runnerName
    }

    fun getLastUsedRunnerForFile(testDataFile: VirtualFile): String? {
        val baseDirectory = project.basePath ?: return null
        val virtualFileManager = VirtualFileManager.getInstance()
        return chosenRunnerByDirectory.entries.firstOrNull p@{ (directory, _) ->
            val path = Path(baseDirectory, directory)
            val directoryFile = virtualFileManager.findFileByNioPath(path) ?: return@p false
            VfsUtil.isAncestor(directoryFile, testDataFile, false)
        }?.value
    }
}
