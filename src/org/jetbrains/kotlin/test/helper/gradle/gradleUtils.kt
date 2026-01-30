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
import org.jetbrains.kotlin.test.helper.actions.filterAndCollectTestDescriptions
import org.jetbrains.plugins.gradle.settings.GradleLocalSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.jetbrains.plugins.gradle.settings.TestRunner
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Paths
import kotlin.coroutines.resume

/**
 * True when Gradle is available but not necessarily used as test runner.
 *
 * [hasGradleTestRunner] implies [isGradleEnabled] but not the other way round.
 */
fun Project.isGradleEnabled(): Boolean {
    return ExternalSystemApiUtil
        .getLocalSettings<GradleLocalSettings>(this, GradleConstants.SYSTEM_ID)
        .availableProjects
        .isNotEmpty()
}

/**
 * True when "Run tests using" is set to Gradle for the module containing the given [file].
 */
fun Project.hasGradleTestRunner(file: VirtualFile): Boolean {
    val projectSettings = GradleSettings.getInstance(this).linkedProjectsSettings
        .firstOrNull { file.toNioPath().startsWith(Paths.get(it.externalProjectPath)) }
    return projectSettings?.testRunner == TestRunner.GRADLE
}

suspend fun generateTestsAndWait(project: Project, files: List<VirtualFile>) {
    val (commandLine, _) = generateTestsCommandLine()

    suspendCancellableCoroutine {
        runTask(
            TaskExecutionSpec.create().apply {
                withProject(project)
                withSystemId(GradleConstants.SYSTEM_ID)
                withExecutorId(getRunExecutorInstance().id)
                withSettings(
                    createGradleExternalSystemTaskExecutionSettings(
                        project,
                        commandLine,
                        useProjectBasePath = true
                    )
                )
                withActivateToolWindowBeforeRun(true)
                withCallback(object : TaskCallback {
                    override fun onSuccess() = it.resume(Unit)
                    override fun onFailure() = it.resume(Unit)
                })
            }.build()
        )
    }

    for (i in 1..10) {
        val tests = smartReadAction(project) {
            filterAndCollectTestDescriptions(files, project)
        }
        if (tests.isNotEmpty()) break
        delay(500)
    }
}
