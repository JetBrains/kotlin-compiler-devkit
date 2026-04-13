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
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.parentsOfType
import com.intellij.testIntegration.TestRunLineMarkerProvider
import com.intellij.ui.components.JBLabel
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.test.helper.TestDataPathsConfiguration
import org.jetbrains.kotlin.test.helper.buildRunnerLabel
import org.jetbrains.kotlin.test.helper.gradle.GradleRunConfig
import org.jetbrains.kotlin.test.helper.gradle.generateTestsAndWait
import org.jetbrains.kotlin.test.helper.gradle.generateTestsCommandLine
import org.jetbrains.kotlin.test.helper.gradle.hasGradleTestRunner
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

        if (item != null) {
            val index = state.methodsClassNames.indexOf(item)
            val actions = state.debugAndRunActionLists.elementAtOrNull(index) ?: return
            presentation.putClientProperty(ActionUtil.INLINE_ACTIONS, buildList {
                addAll(actions)
                add(object : AnAction("Run Selected && Apply Diffs", null, AllIcons.Diff.ApplyNotConflicts) {
                    override fun actionPerformed(e: AnActionEvent) {
                        runAndApply(e, item)
                    }
                })
            })
        }
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

    private data class UpdateResult(
        val labels: List<String>,
        val actions: List<List<DelegatingRunDebugAction>>?,
        val topLevelDirectory: String?
    )

    inner class State {
        val project: Project = baseEditor.editor.project!!
        var methodsClassNames: List<String> = emptyList()
        var debugAndRunActionLists: List<List<DelegatingRunDebugAction>> = emptyList()
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

        private fun createActionsForTestRunners(): UpdateResult {
            val file = baseEditor.file ?: return UpdateResult(emptyList(), emptyList(), null)
            logger.info("task started")

            val testDescriptions = file.collectTestDescriptions(project)
            val testDeclarations = testDescriptions.map { it.psi }
            goToAction.testMethods = testDeclarations
            logger.info("methods collected")

            val topLevelDirectory = testDeclarations.firstNotNullOfOrNull { method ->
                method.parentsOfType(PsiClass::class.java)
                    .toList()
                    .asReversed()
                    .firstNotNullOfOrNull { it.extractTestMetadataValue() }
            }

            val ex = TestRunLineMarkerProvider()
            val items = testDeclarations.mapNotNull { testMethod ->
                val identifier = testMethod.nameIdentifier ?: return@mapNotNull null
                val info = ex.getInfo(identifier) ?: return@mapNotNull null
                val allActions = info.actions
                if (allActions.size < 2) {
                    logger.info("Not enough actions: ${allActions.size}")
                    return@mapNotNull null
                }
                val topLevelClass = testMethod.parentsOfType<PsiClass>().last()

                val group = allActions.take(2).mapIndexed { index, action ->
                    DelegatingRunDebugAction(
                        action,
                        identifier,
                        topLevelClass,
                        if (index == 0) "Run" else "Debug",
                        if (index == 0) AllIcons.Actions.Execute else AllIcons.Actions.StartDebugger
                    )
                }

                val runnerLabel = topLevelClass.buildRunnerLabel(testTags)
                runnerLabel to group
            }.sortedBy { it.first }

            return UpdateResult(
                labels = items.map { it.first },
                actions = items.map { it.second }.takeUnless { testDescriptions.firstOrNull()?.isOnTheFly() == true },
                topLevelDirectory = topLevelDirectory
            )
        }

        internal fun executeRunConfigAction(e: AnActionEvent, index: Int) {
            if (project.hasGradleTestRunner(baseEditor.file)) {
                val className = methodsClassNames.elementAtOrNull(currentChosenGroup) ?: return
                project.service<TestDataRunnerService>()
                    .collectAndRunAllTests(e, listOf(baseEditor.file), debug = index == 1, filterByClass = className)
            } else {
                val action =
                    debugAndRunActionLists.elementAtOrNull(currentChosenGroup)?.elementAtOrNull(index) ?: return
                ActionUtil.performAction(action, e)
            }
        }

        private fun updateUiAccordingCollectedTests(result: UpdateResult) {
            logger.info("ui update started")
            topLevelDirectory = result.topLevelDirectory
            debugAndRunActionLists = result.actions.orEmpty()
            methodsClassNames = result.labels
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

    private fun runAndApply(e: AnActionEvent, className: String) {
        val project = e.project ?: return
        val service = project.service<TestDataRunnerService>()
        service.scope.launch {
            withBackgroundProgress(project, "Running Selected & Applying Diffs") {
                reportSequentialProgress { reporter ->
                    reporter.indeterminateStep("Running Selected")

                    runTestAndApplyDiffLoop(project) {
                        service.doCollectAndRunAllTests(
                            e,
                            listOf(baseEditor.file),
                            debug = false,
                            filterByClass = className
                        )
                    }
                }
            }
        }
    }

    inner class RunAction(private val index: Int, text: String, icon: Icon) : AnAction(text, text, icon), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            state.executeRunConfigAction(e, index)
        }

        override fun update(e: AnActionEvent) {
            val currentIndex = state.currentChosenGroup
            e.presentation.isEnabled = currentIndex in state.debugAndRunActionLists.indices ||
                    (e.project?.hasGradleTestRunner(baseEditor.file) == true && currentIndex in state.methodsClassNames.indices)
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
        init {
            templatePresentation.putClientProperty(ActionUtil.HIDE_DROPDOWN_ICON, true)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isPopupGroup = true
        }

        override fun getActionUpdateThread(): ActionUpdateThread {
            return ActionUpdateThread.BGT
        }

        private val actions = arrayOf(
            object : AnAction("Reload Tests", null, AllIcons.Actions.Refresh), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    state.updateTestsList()
                }
            },
            object : AnAction("Generate Tests", null, AllIcons.Actions.GeneratedFolder), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    val (commandLine, title) = generateTestsCommandLine()
                    val config =
                        GradleRunConfig(commandLine, title, useProjectBasePath = true, runAsTest = false, debug = false)
                    runGradleCommandLine(e, config)
                }
            },
            object : GradleOnlyAction("Run Selected && Apply Diffs", null, AllIcons.Diff.ApplyNotConflicts), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    val className = state.methodsClassNames.elementAtOrNull(state.currentChosenGroup) ?: return
                    runAndApply(e, className)
                }
            },
            object : GradleOnlyAction("Run All && Apply Diffs", null, AllIcons.Diff.ApplyNotConflicts), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    ActionUtil.performAction(RunAllAndApplyDiffsAction(), e)
                }
            },
            object : GradleOnlyAction("Generate Tests, Run All && Apply Diffs", null, AllIcons.Diff.ApplyNotConflicts), DumbAware {
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
            object : GradleOnlyAction("Generate, Run, Apply Diffs && Commit", null, AllIcons.Actions.Commit) {
                override fun actionPerformed(e: AnActionEvent) {
                    ActionUtil.performAction(CreateReproducerCommitAction(), e)
                }
            }
        )

        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            return actions
        }
    }
}

class DelegatingRunDebugAction(
    private val action: AnAction,
    private val identifier: PsiElement,
    private val topLevelClass: PsiClass,
    text: String,
    icon: Icon,
) : AnAction(text, text, icon), DumbAware {
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
        action.actionPerformed(newEvent)
    }

    override fun update(e: AnActionEvent) {
        action.update(e)
        e.presentation.isEnabledAndVisible = true
        e.presentation.description = topLevelClass.name!!
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun toString(): String {
        return "Run/Debug ${topLevelClass.name}"
    }
}
