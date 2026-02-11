package org.jetbrains.kotlin.test.helper.gradle

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration
import org.jetbrains.plugins.gradle.settings.GradleLocalSettings
import org.jetbrains.plugins.gradle.util.GradleConstants


class GradleRunConfig(
    val commandLine: String,
    val title: String?,
    val useProjectBasePath: Boolean,
    val runAsTest: Boolean,
    val debug: Boolean,
)

fun runGradleCommandLine(e: AnActionEvent, config: GradleRunConfig) {
    val project = e.project ?: return
    return runGradleCommandLine(project, config)
}

fun runGradleCommandLine(project: Project, config: GradleRunConfig) {
    val runSettings = createGradleRunAndConfigurationSettings(project,config) ?: return
    ProgramRunnerUtil.executeConfiguration(
        runSettings,
        if (config.debug) DefaultDebugExecutor.getDebugExecutorInstance() else DefaultRunExecutor.getRunExecutorInstance()
    )

    val runManager = RunManager.getInstance(project)
    val existingConfiguration = runManager.findConfigurationByTypeAndName(runSettings.type, runSettings.name)
    if (existingConfiguration == null) {
        runManager.setTemporaryConfiguration(runSettings)
    } else {
        runManager.selectedConfiguration = existingConfiguration
    }
}

private fun createGradleRunAndConfigurationSettings(project: Project, config: GradleRunConfig): RunnerAndConfigurationSettings? {
    val settings = createGradleExternalSystemTaskExecutionSettings(project, config.commandLine, config.useProjectBasePath)
    val runSettings = ExternalSystemUtil.createExternalSystemRunnerAndConfigurationSettings(
        settings,
        project,
        GradleConstants.SYSTEM_ID,
    ) ?: return null

    config.title?.let { runSettings.name = it }

    (runSettings.configuration as GradleRunConfiguration).run {
        isRunAsTest = config.runAsTest
        isDebugServerProcess = false
    }

    return runSettings
}

private val REGEX_MULTIPLE_SPACES = " +".toRegex()

fun createGradleExternalSystemTaskExecutionSettings(
    project: Project,
    fullCommandLine: String,
    useProjectBasePath: Boolean,
): ExternalSystemTaskExecutionSettings {
    return ExternalSystemTaskExecutionSettings().apply {
        externalProjectPath = if (useProjectBasePath) {
            project.basePath
        } else {
            ExternalSystemApiUtil.getLocalSettings<GradleLocalSettings>(
                project,
                GradleConstants.SYSTEM_ID
            ).availableProjects.keys.firstOrNull()?.let { FileUtil.toCanonicalPath(it.path) }
        }
        taskNames = fullCommandLine.split(REGEX_MULTIPLE_SPACES)
        externalSystemIdString = GradleConstants.SYSTEM_ID.id
    }
}