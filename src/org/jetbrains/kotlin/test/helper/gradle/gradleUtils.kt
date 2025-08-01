package org.jetbrains.kotlin.test.helper.gradle

import com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil.runTask
import com.intellij.openapi.externalSystem.util.task.TaskExecutionSpec
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.kotlin.test.helper.actions.filterAndCollectTestDeclarations
import org.jetbrains.plugins.gradle.settings.GradleLocalSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.jetbrains.plugins.gradle.util.GradleConstants.SYSTEM_ID
import kotlin.coroutines.resume

fun Project.isGradleEnabled(): Boolean = ExternalSystemApiUtil
    .getLocalSettings<GradleLocalSettings>(this, GradleConstants.SYSTEM_ID)
    .availableProjects
    .isNotEmpty()

suspend fun generateTestsAndWait(project: Project, files: List<VirtualFile>) {
    val (commandLine, _) = generateTestsCommandLine(project, files)

    suspendCancellableCoroutine {
        runTask(
            TaskExecutionSpec.create(
                project = project,
                systemId = SYSTEM_ID,
                executorId = getRunExecutorInstance().id,
                settings = createGradleExternalSystemTaskExecutionSettings(
                    project, commandLine, useProjectBasePath = true
                )
            )
                .withActivateToolWindowBeforeRun(true)
                .withCallback(object : TaskCallback {
                    override fun onSuccess() = it.resume(Unit)
                    override fun onFailure() = it.resume(Unit)
                }).build()
        )
    }

    for (i in 1..10) {
        val tests = smartReadAction(project) {
            filterAndCollectTestDeclarations(files, project)
        }
        if (tests.isNotEmpty()) break
        delay(500)
    }
}