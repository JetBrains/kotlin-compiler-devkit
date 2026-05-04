package org.jetbrains.kotlin.test.helper

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "TestDataWorkspaceSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class TestDataWorkspaceConfiguration : PersistentStateComponent<TestDataWorkspaceConfiguration> {
    companion object {
        fun getInstance(project: Project): TestDataWorkspaceConfiguration {
            return project.getService(TestDataWorkspaceConfiguration::class.java)
        }
    }

    var injectMultifileTestDataFileType: Boolean = true

    override fun getState(): TestDataWorkspaceConfiguration {
        return this
    }

    override fun loadState(state: TestDataWorkspaceConfiguration) {
        injectMultifileTestDataFileType = state.injectMultifileTestDataFileType
    }
}
