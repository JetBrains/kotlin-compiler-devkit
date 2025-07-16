package org.jetbrains.kotlin.test.helper.actions

import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.designer.actions.AbstractComboBoxAction
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportSequentialProgress
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.parentsOfType
import com.intellij.testIntegration.TestRunLineMarkerProvider
import com.intellij.ui.components.JBLabel
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.test.helper.TestDataPathsConfiguration
import org.jetbrains.kotlin.test.helper.buildRunnerLabel
import org.jetbrains.kotlin.test.helper.gradle.GradleRunConfig
import org.jetbrains.kotlin.test.helper.gradle.generateTestsAndWait
import org.jetbrains.kotlin.test.helper.gradle.generateTestsCommandLine
import org.jetbrains.kotlin.test.helper.gradle.runGradleCommandLine
import org.jetbrains.kotlin.test.helper.services.TestDataRunnerService
import org.jetbrains.kotlin.test.helper.ui.WidthAdjustingPanel
import java.util.concurrent.Callable
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

class GeneratedTestComboBoxAction(val baseEditor: TextEditor) : AbstractComboBoxAction<String>(), DumbAware {
    companion object {
        private val logger = Logger.getInstance(GeneratedTestComboBoxAction::class.java)
        const val INDEX_RUN = 0
        const val INDEX_DEBUG = 1
    }

    private val configuration = TestDataPathsConfiguration.getInstance(baseEditor.editor.project!!)
    private val testTags: Map<String, Array<String>>
        get() = configuration.testTags

    val runAllTestsAction: RunAllTestsAction = RunAllTestsAction()
    val goToAction: GoToDeclaration = GoToDeclaration()
    val runAction = RunAction(INDEX_RUN, "Run", AllIcons.Actions.Execute)
    val debugAction = RunAction(INDEX_DEBUG, "Debug", AllIcons.Actions.StartDebugger)
    val moreActionsGroup = MoreActionsGroup()

    val state: State = State()

    init {
        state.updateTestsList()
    }

    override fun update(item: String?, presentation: Presentation, popup: Boolean) {
        presentation.text = item
    }

    override fun selectionChanged(item: String?): Boolean {
        state.currentChosenGroup = state.methodsClassNames.indexOf(item)
        state.onSelectionUpdated()
        return true
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        return WidthAdjustingPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JBLabel("Tests: "))
            val panel = super.createCustomComponent(presentation, place) as JPanel
            add(panel)
        }
    }

    private fun updateBox() {
        setItems(state.methodsClassNames, state.methodsClassNames.elementAtOrNull(state.currentChosenGroup))
    }

    inner class State {
        val project: Project = baseEditor.editor.project!!
        var methodsClassNames: List<String> = emptyList()
        var debugAndRunActionLists: List<List<AnAction>> = emptyList()
        var currentChosenGroup: Int = 0
        var topLevelDirectory: String? = null

        fun updateTestsList() {
            logger.info("task scheduled")
            methodsClassNames = emptyList()
            debugAndRunActionLists = emptyList()
            ReadAction
                .nonBlocking(Callable { createActionsForTestRunners().also { logger.info("actions created") } })
                .inSmartMode(project)
                .finishOnUiThread(ModalityState.defaultModalityState(), this@State::updateUiAccordingCollectedTests)
                .submit(AppExecutorUtil.getAppExecutorService())
        }

        private fun createActionsForTestRunners(): List<Pair<String, List<AnAction>>> {
            val file = baseEditor.file ?: return emptyList() // TODO: log
            logger.info("task started")

            val testDeclarations = file.collectTestMethodsIfTestData(project)
            goToAction.testMethods = testDeclarations
            logger.info("methods collected")

            topLevelDirectory = testDeclarations.firstNotNullOfOrNull { method ->
                method.parentsOfType(PsiClass::class.java)
                    .toList()
                    .asReversed()
                    .firstNotNullOfOrNull { it.extractTestMetadataValue() }
            }

            val ex = TestRunLineMarkerProvider()
            return testDeclarations.mapNotNull { testMethod ->
                val identifier = testMethod.nameIdentifier ?: return@mapNotNull null
                val info = ex.getInfo(identifier) ?: return@mapNotNull null
                val allActions = info.actions
                if (allActions.size < 2) {
                    logger.info("Not enough actions: ${allActions.size}")
                    return@mapNotNull null
                }
                val topLevelClass = testMethod.parentsOfType<PsiClass>().last()

                val group: List<AnAction> = allActions.take(2).map {
                    object : AnAction(), DumbAware {
                        override fun actionPerformed(e: AnActionEvent) {
                            val dataContext = SimpleDataContext.builder().apply {
                                val newLocation = PsiLocation.fromPsiElement(identifier)
                                setParent(e.dataContext)
                                add(Location.DATA_KEY, newLocation)
                            }.build()

                            val newEvent = AnActionEvent(
                                dataContext,
                                e.presentation,
                                e.place,
                                e.uiKind,
                                e.inputEvent,
                                e.modifiers,
                                e.actionManager,
                            )
                            it.actionPerformed(newEvent)
                        }

                        override fun update(e: AnActionEvent) {
                            it.update(e)
                            e.presentation.isEnabledAndVisible = true
                            e.presentation.description = topLevelClass.name!!
                        }

                        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

                        override fun toString(): String {
                            return "Run/Debug ${topLevelClass.name}"
                        }
                    }
                }

                val runnerLabel = topLevelClass.buildRunnerLabel(testTags)
                runnerLabel to group
            }.sortedBy { it.first }
        }

        internal fun executeRunConfigAction(e: AnActionEvent, index: Int) {
            val action = debugAndRunActionLists.elementAtOrNull(currentChosenGroup)?.elementAtOrNull(index) ?: return
            ActionUtil.performActionDumbAwareWithCallbacks(action, e)
        }

        private fun updateUiAccordingCollectedTests(classAndActions: List<Pair<String, List<AnAction>>>) {
            logger.info("ui update started")
            debugAndRunActionLists = classAndActions.map { it.second }
            methodsClassNames = classAndActions.map { it.first }
            val lastUsedRunner = LastUsedTestService.getInstance(project).getLastUsedRunnerForFile(baseEditor.file!!)
            methodsClassNames.indexOf(lastUsedRunner).takeIf { it in methodsClassNames.indices }?.let {
                currentChosenGroup = it
            }

            onSelectionUpdated()
            updateBox()
            logger.info("ui update finished")
        }

        fun onSelectionUpdated() {
            val runnerName = methodsClassNames.elementAtOrNull(currentChosenGroup) ?: return
            LastUsedTestService.getInstance(project).updateChosenRunner(topLevelDirectory, runnerName)
        }
    }

    inner class RunAction(private val index: Int, text: String, icon: Icon) : AnAction(text, text, icon), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            state.executeRunConfigAction(e, index)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = state.currentChosenGroup in state.debugAndRunActionLists.indices
            super.update(e)
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }

    inner class GoToDeclaration : AnAction(
        "Go To Test Method",
        "Go to test method declaration",
        AllIcons.Nodes.Method
    ), DumbAware {
        var testMethods: List<PsiNameIdentifierOwner> = emptyList()

        override fun actionPerformed(e: AnActionEvent) {
            PsiTargetNavigator { testMethods }.navigate(baseEditor.editor, "")
        }

    }

    inner class RunAllTestsAction : GradleOnlyAction(
        "Run All...",
        "Run all tests via gradle",
        AllIcons.RunConfigurations.Junit
    ), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            e.project?.service<TestDataRunnerService>()
                ?.collectAndRunAllTests(e, listOf(baseEditor.file), debug = false)
        }
    }

    inner class MoreActionsGroup : ActionGroup(
        "More",
        "More actions",
        AllIcons.Actions.More,
    ), DumbAware {
        override fun update(e: AnActionEvent) {
            e.presentation.isPopupGroup = true
        }

        override fun getActionUpdateThread(): ActionUpdateThread {
            return ActionUpdateThread.BGT
        }

        private val actions = arrayOf(
            object : AnAction("Reload Tests"), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    state.updateTestsList()
                }
            },
            object : AnAction("Generate Tests"), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    val project = e.project ?: return
                    val (commandLine, title) = generateTestsCommandLine(project, listOf(baseEditor.file))
                    val config =
                        GradleRunConfig(commandLine, title, useProjectBasePath = true, runAsTest = false, debug = false)
                    runGradleCommandLine(e, config)
                }
            },
            object : AnAction("Run Selected && Apply Diffs"), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    val project = e.project ?: return
                    project.service<TestDataRunnerService>().scope.launch {
                        withBackgroundProgress(project, "Running Selected & Applying Diffs") {
                            reportSequentialProgress { reporter ->
                                reporter.indeterminateStep("Running Selected")
                                runTestAndApplyDiffLoop(project) {
                                    withContext(Dispatchers.EDT) {
                                        state.executeRunConfigAction(e, INDEX_RUN)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            object : GradleOnlyAction("Run All && Apply Diffs"), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    val project = e.project ?: return

                    val service = project.service<TestDataRunnerService>()
                    service.scope.launch {
                        withBackgroundProgress(project, "Running All Tests & Applying Diffs") {
                            reportSequentialProgress { reporter ->
                                reporter.indeterminateStep("Running All Tests")
                                runTestAndApplyDiffLoop(project) {
                                    service.doCollectAndRunAllTests(e, listOf(baseEditor.file), debug = false)
                                }
                            }
                        }
                    }
                }
            },
            object : GradleOnlyAction("Generate Tests, Run All && Apply Diffs"), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    val project = e.project ?: return
                    val service = project.service<TestDataRunnerService>()
                    service.scope.launch {
                        withBackgroundProgress(project, "Generating Tests, Running All & Applying Diffs") {
                            reportSequentialProgress { reporter ->
                                reporter.nextStep(33)
                                reporter.nextStep(66, "Generating Tests") {
                                    generateTestsAndWait(project, listOf(baseEditor.file))
                                }

                                reporter.nextStep(100, "Running All Tests") {
                                    runTestAndApplyDiffLoop(project) {
                                        service.doCollectAndRunAllTests(e, listOf(baseEditor.file), debug = false)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            object : GradleOnlyAction("Generate, Run, Apply Diffs && Commit") {
                override fun actionPerformed(e: AnActionEvent) {
                    ActionUtil.performActionDumbAwareWithCallbacks(CreateReproducerCommitAction(), e)
                }
            }
        )

        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            return actions
        }
    }
}
