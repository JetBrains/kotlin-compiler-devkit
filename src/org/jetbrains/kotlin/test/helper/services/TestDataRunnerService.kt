package org.jetbrains.kotlin.test.helper.services

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportSequentialProgress
import com.intellij.psi.PsiClass
import com.intellij.psi.util.parentsOfType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.test.helper.TestDataPathsConfiguration
import org.jetbrains.kotlin.test.helper.actions.CancellationCallback
import org.jetbrains.kotlin.test.helper.actions.TestDescription
import org.jetbrains.kotlin.test.helper.actions.filterAndCollectTestDescriptions
import org.jetbrains.kotlin.test.helper.buildRunnerLabel
import org.jetbrains.kotlin.test.helper.gradle.GradleRunConfig
import org.jetbrains.kotlin.test.helper.gradle.computeGradleCommandLine
import org.jetbrains.kotlin.test.helper.gradle.runGradleCommandLine
import org.jetbrains.kotlin.test.helper.gradle.runGradleCommandLineCancellable
import org.jetbrains.kotlin.test.helper.isHeavyTest
import org.jetbrains.kotlin.test.helper.toFileNamesString
import javax.swing.ListSelectionModel
import kotlin.coroutines.resume

@Service(Service.Level.PROJECT)
class TestDataRunnerService(
    val project: Project,
    val scope: CoroutineScope
) {
    fun collectAndRunAllTests(
        e: AnActionEvent,
        files: List<VirtualFile>?,
        debug: Boolean,
        filterByClass: String? = null
    ) {
        if (files == null) return

        scope.launch(Dispatchers.Default) {
            doCollectAndRunAllTests(e, files, debug, filterByClass)
        }
    }

    suspend fun doCollectAndRunAllTests(
        e: AnActionEvent,
        files: List<VirtualFile>,
        debug: Boolean,
        filterByClass: String? = null
    ): CancellationCallback? {
        val commandLine = withBackgroundProgress(project, title = "Running all tests") {
            reportSequentialProgress { reporter ->
                reporter.indeterminateStep("Collecting tests")

                smartReadAction(project) {
                    val testDeclarations = filterAndCollectTestDescriptions(files, project)
                        .filter { !it.psi.isHeavyTest() }
                        .ifEmpty { return@smartReadAction null }

                    val filtered = if (!filterByClass.isNullOrEmpty()) {
                        groupTests(testDeclarations)[filterByClass] ?: testDeclarations
                    } else {
                        testDeclarations
                    }

                    computeGradleCommandLine(filtered)
                }
            }
        } ?: return null

        val fileNamesString = files.toFileNamesString(project)

        val config = GradleRunConfig(
            commandLine,
            title = if (filterByClass != null) "$filterByClass: $fileNamesString" else fileNamesString ,
            debug = debug,
            useProjectBasePath = false,
            runAsTest = true
        )
        return runGradleCommandLineCancellable(e, config)
    }

    fun collectAndRunSpecificTests(e: AnActionEvent, files: List<VirtualFile>?, debug: Boolean) {
        if (files == null) return

        scope.launch(Dispatchers.Default) {
            val result = selectTestMethods(files, e) ?: return@launch

            doRunTests(result, e, debug)
        }
    }

    data class TestSelectionResult(val tests: List<TestDescription>, val prefix: String)

    suspend fun selectTestMethods(files: List<VirtualFile>, e: AnActionEvent): TestSelectionResult? {
        val byClass = withBackgroundProgress(project, title = "Running specific tests") {
            reportSequentialProgress { reporter ->
                reporter.indeterminateStep("Collecting tests")

                smartReadAction(project) {
                    val testDeclarations = filterAndCollectTestDescriptions(files, project)
                    groupTests(testDeclarations)
                }
            }
        }

        val selected = withContext(Dispatchers.EDT) {
            suspendCancellableCoroutine<Set<String>?> { continuation ->
                val popup = JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(byClass.keys.sortedBy { it })
                    .setTitle("Select Test Class")
                    .setItemsChosenCallback { selected ->
                        continuation.resume(selected)
                    }
                    .addListener(object: JBPopupListener {
                        override fun onClosed(event: LightweightWindowEvent) {
                            if (!event.isOk) {
                                continuation.resume(null)
                            }
                        }
                    })
                    .setNamerForFiltering { it }
                    .setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
                    .createPopup()

                popup.showInBestPositionFor(e.dataContext)

                continuation.invokeOnCancellation { popup.cancel() }
            }
        } ?: return null

        val testMethods = selected.flatMap { byClass[it].orEmpty() }.ifEmpty { return null }

        return TestSelectionResult(testMethods, selected.singleOrNull() ?: selected.toString())
    }

    suspend fun doRunTests(
        result: TestSelectionResult,
        e: AnActionEvent,
        debug: Boolean = false
    ) {
        val commandLine = smartReadAction(project) {
            computeGradleCommandLine(result.tests)
        }
        withContext(Dispatchers.EDT) {
            val config = GradleRunConfig(
                commandLine,
                title = e.toFileNamesString()?.let { "${result.prefix}: $it" },
                debug = debug,
                useProjectBasePath = false,
                runAsTest = true,
            )
            runGradleCommandLine(e, config)
        }
    }


    private fun groupTests(testDeclarations: List<TestDescription>): Map<String, List<TestDescription>> {
        val testTags = TestDataPathsConfiguration.getInstance(project).testTags
        return testDeclarations
            .groupBy { it.psi.parentsOfType<PsiClass>().last() }
            .mapKeys { it.key.buildRunnerLabel(testTags) }
    }
}
