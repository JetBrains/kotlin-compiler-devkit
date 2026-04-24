package org.jetbrains.kotlin.test.helper.codeinsight

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.test.helper.getTestDataType
import org.jetbrains.kotlin.test.helper.lang.MultifileTestDataFileType

class MultifileTestDataFileTypeOverrider: FileTypeOverrider, DumbAware {
    override fun getOverriddenFileType(file: VirtualFile): FileType? =
        MultifileTestDataFileType.takeIf {
            ProjectManager.getInstance().openProjects.any {
                file.getTestDataType(it) != null
            }
        }
}