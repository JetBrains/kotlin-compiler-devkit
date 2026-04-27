package org.jetbrains.kotlin.test.helper.codeinsight

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.runIf
import org.jetbrains.kotlin.test.helper.isSupportedByExtension
import org.jetbrains.kotlin.test.helper.isTestDataFile
import org.jetbrains.kotlin.test.helper.lang.MultifileTestDataFileType

class MultifileTestDataFileTypeOverrider: FileTypeOverrider, DumbAware {
    override fun getOverriddenFileType(file: VirtualFile): FileType? {
        return runIf(file.isSupportedByExtension()) {
            val isTestDataFileInAnyProject = ProjectManager.getInstance().openProjects.any {
                file.isTestDataFile(it)
            }
            runIf(isTestDataFileInAnyProject) { MultifileTestDataFileType }
        }
    }
}