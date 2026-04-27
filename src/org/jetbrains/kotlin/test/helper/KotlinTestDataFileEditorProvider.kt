package org.jetbrains.kotlin.test.helper

import com.intellij.openapi.fileEditor.AsyncFileEditorProvider
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.application
import org.jetbrains.kotlin.test.helper.ui.TestDataEditor

class KotlinTestDataFileEditorProvider: AsyncFileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file.isTestDataFile(project)
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return application.runReadAction<FileEditor> { createEditorAsync(project, file).build() }
    }

    override fun getEditorTypeId(): String = "Kotlin TestData Editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR

    override fun createEditorAsync(project: Project, file: VirtualFile): AsyncFileEditorProvider.Builder {
        return object: AsyncFileEditorProvider.Builder() {
            override fun build(): FileEditor {
                val baseEditor = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor

                return TestDataEditor(baseEditor)
            }
        }
    }
}
