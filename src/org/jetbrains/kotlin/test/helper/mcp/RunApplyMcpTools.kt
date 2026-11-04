package org.jetbrains.kotlin.test.helper.mcp

import andel.intervals.toReversedList
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.TestProxyRoot
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportSequentialProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.kotlin.test.helper.actions.awaitTestRun
import org.jetbrains.kotlin.test.helper.actions.collectAndGroupTestDescriptions
import org.jetbrains.kotlin.test.helper.actions.runTestAndApplyDiffLoop
import org.jetbrains.kotlin.test.helper.getLeafs
import org.jetbrains.kotlin.test.helper.gradle.GradleRunConfig
import org.jetbrains.kotlin.test.helper.gradle.computeGradleCommandLine
import org.jetbrains.kotlin.test.helper.gradle.runGradleCommandLineCancellable
import org.jetbrains.kotlin.test.helper.services.TestDataRunnerService
import org.jetbrains.kotlin.test.helper.toFileNamesString
import kotlin.io.path.Path

class RunApplyMcpTools : McpToolset {
    @McpTool
    @McpDescription("Returns the available runner names for the given test data files.")
    suspend fun kotlin_test_data_get_runners(
        @McpDescription("The paths to the test data files") paths: List<String>
    ): TestDataRunnersResult {
        val project = currentCoroutineContext().project
        val files = paths.toVirtualFiles(project)
        val testsByRunners = readAction { collectAndGroupTestDescriptions(files, project) }
        return TestDataRunnersResult(testsByRunners.keys.toList())
    }


    @McpTool
    @McpDescription("For the given test data files, runs the given test runners")
    suspend fun kotlin_test_data_run(
        @McpDescription("The paths to the test data files") paths: List<String>,
        @McpDescription("The list of runner to use") runners: List<String>,
    ): TestDataRunResult {
        val project = currentCoroutineContext().project
        val files = paths.toVirtualFiles(project)
        val service = project.service<TestDataRunnerService>()

        val result = withBackgroundProgress(project, "Running Specific") {
            val commandLine = smartReadAction(service.project) {
                val byRunners = collectAndGroupTestDescriptions(files, project)
                computeGradleCommandLine(runners.flatMap { byRunners[it].orEmpty() })
            }

            reportSequentialProgress { reporter ->
                reporter.indeterminateStep("Running Specific Tests")

                val cb = withContext(Dispatchers.EDT) {
                    val config = gradleRunConfig(files, runners, commandLine, project)
                    runGradleCommandLineCancellable(project, config)
                }
                awaitTestRun(project, cb)
            }
        }

        return TestDataRunResult.from(result)
    }

    @McpTool
    @McpDescription("For the given test data files, runs the given test runners and applies diffs to the test data.")
    suspend fun kotlin_test_data_run_and_apply_diffs(
        @McpDescription("The paths to the test data files") paths: List<String>,
        @McpDescription("The list of runner to use") runners: List<String>,
    ): TestDataRunResult {
        val project = currentCoroutineContext().project
        val files = paths.toVirtualFiles(project)
        val service = project.service<TestDataRunnerService>()

        val result = withBackgroundProgress(project, "Running Specific & Applying Diffs") {
            val commandLine = smartReadAction(service.project) {
                val byRunners = collectAndGroupTestDescriptions(files, project)
                computeGradleCommandLine(runners.flatMap { byRunners[it].orEmpty() })
            }

            reportSequentialProgress { reporter ->
                reporter.indeterminateStep("Running Specific Tests")

                runTestAndApplyDiffLoop(project, isInteractive = false) {
                    withContext(Dispatchers.EDT) {
                        val config = gradleRunConfig(files, runners, commandLine, project)
                        runGradleCommandLineCancellable(project, config)
                    }
                }
            }
        }

        return TestDataRunResult.from(result)
    }

    private fun List<String>.toVirtualFiles(project: Project): List<VirtualFile> {
        val vfsManager = VirtualFileManager.getInstance()
        val basePath = project.basePath ?: mcpFail("Can't determine base directory.")
        return mapNotNull { vfsManager.findFileByNioPath(Path(basePath, it)) }
    }

    private fun gradleRunConfig(
        files: List<VirtualFile>,
        runners: List<String>,
        commandLine: String,
        project: Project
    ): GradleRunConfig {
        val prefix = runners.singleOrNull() ?: runners.toString()
        return GradleRunConfig(
            commandLine,
            title = "$prefix: ${files.toFileNamesString(project)}",
            debug = false,
            useProjectBasePath = false,
            runAsTest = true,
        )
    }
}

@Serializable
class TestDataRunnersResult(
    @property:McpDescription("The list of available runner names") val runners: List<String>,
)

@Serializable
class TestDataRunResult(
    @property:McpDescription("The list of test results") val results: List<TestResult>
) {
    companion object {
        fun from(root: SMTestProxy.SMRootTestProxy): TestDataRunResult {
            return TestDataRunResult(
                root.getLeafs().map { TestResult.from(it) }
            )
        }
    }
}

@Serializable
class TestResult(
    @property:McpDescription("The name of the test") val name: String,
    @property:McpDescription("If the test passed successfully") val passed: Boolean,
    @property:McpDescription("If the test was ignored") val ignored: Boolean,
    @property:McpDescription("The error message or null") val errorMessage: String?,
    @property:McpDescription("The stacktrace or null") val stacktrace: String?,
) {
    companion object {
        fun from(test: AbstractTestProxy): TestResult {
            return TestResult(
                name = getName(test),
                passed = test.isPassed,
                ignored = test.isIgnored,
                errorMessage = test.errorMessage,
                stacktrace = test.stacktrace,
            )
        }

        private fun getName(test: AbstractTestProxy): String {
            return sequence {
                var x = test
                while (x !is TestProxyRoot) {
                    yield(x.name)
                    x = x.parent ?: break
                }
            }.toReversedList()
                .joinToString(".")
        }
    }
}