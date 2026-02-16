package org.jetbrains.kotlin.test.helper.actions.test.data.manager

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.test.helper.actions.RunSelectedFilesActionBase
import org.jetbrains.kotlin.test.helper.actions.hasSelectedTestDataFiles
import org.jetbrains.kotlin.test.helper.getTestDataType
import org.jetbrains.kotlin.test.helper.gradle.GradleRunConfig
import org.jetbrains.kotlin.test.helper.gradle.hasGradleTask
import org.jetbrains.kotlin.test.helper.gradle.runGradleCommandLine

class TestDataManagerGroup : DefaultActionGroup() {
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible =
            e.hasSelectedTestDataFiles && project?.hasManageTestDataGloballyTask == true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * The base class for actions that manage test data.
 *
 * No need to check [hasManageTestDataGloballyTask] explicitly since [TestDataManagerGroup] does it for us.
 */
abstract class TestDataManagerActionBase : RunSelectedFilesActionBase() {
    protected fun runTestDataManager(project: Project, configure: TestDataManagerCommandBuilder.() -> Unit = {}) {
        val builder = TestDataManagerCommandBuilder().apply(configure)
        val command = builder.build()
        runGradleCommandLine(
            project = project,
            config = GradleRunConfig(
                commandLine = command,
                title = builder.asTitle(),
                debug = isDebug,
                useProjectBasePath = true,
                runAsTest = true,
            )
        )
    }
}

val Project.hasManageTestDataGloballyTask: Boolean
    get() = hasGradleTask("manageTestDataGlobally")

/**
 * The list of selected test data paths for the action.
 *
 * Paths are relative to the project base path.
 */
val AnActionEvent.testDataPaths: List<String>
    get() {
        val project = project ?: return emptyList()
        val basePath = project.basePath ?: return emptyList()
        val files = getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.asList() ?: return emptyList()

        return files
            .filter { it.getTestDataType(project) != null }
            .map { it.path.removePrefix(basePath).removePrefix("/") }
            .distinct()
    }

/**
 * An action that expects a test data input.
 */
abstract class TestDataManagerActionWithTestDataBase : TestDataManagerActionBase() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val testDataPaths = e.testDataPaths
        if (testDataPaths.isEmpty()) return

        runTestDataManager(project) {
            this.testDataPaths = testDataPaths
            configureTestDataManagerBuilder(this)
        }
    }

    /**
     * The [TestDataManagerCommandBuilder] to configure.
     *
     * Only [TestDataManagerCommandBuilder.testDataPaths] is configured by default.
     */
    protected abstract fun configureTestDataManagerBuilder(builder: TestDataManagerCommandBuilder)
}

class TestDataManagerUpdateAction : TestDataManagerActionWithTestDataBase() {
    override val isDebug: Boolean get() = false

    override fun configureTestDataManagerBuilder(builder: TestDataManagerCommandBuilder) {
        with(builder) {
            mode = TestDataManagerMode.UPDATE
        }
    }
}

class TestDataManagerUpdateGoldenAction : TestDataManagerActionWithTestDataBase() {
    override val isDebug: Boolean get() = false

    override fun configureTestDataManagerBuilder(builder: TestDataManagerCommandBuilder) {
        with(builder) {
            mode = TestDataManagerMode.UPDATE
            goldenOnly = true
        }
    }
}

class TestDataManagerUpdateIncrementalAction : TestDataManagerActionWithTestDataBase() {
    override val isDebug: Boolean get() = false

    override fun configureTestDataManagerBuilder(builder: TestDataManagerCommandBuilder) {
        with(builder) {
            mode = TestDataManagerMode.UPDATE
            incremental = true
        }
    }
}

class TestDataManagerCheckAction : TestDataManagerActionWithTestDataBase() {
    override val isDebug: Boolean get() = false

    override fun configureTestDataManagerBuilder(builder: TestDataManagerCommandBuilder) {
        with(builder) {
            mode = TestDataManagerMode.CHECK
        }
    }
}

class TestDataManagerCheckGoldenAction : TestDataManagerActionWithTestDataBase() {
    override val isDebug: Boolean get() = false

    override fun configureTestDataManagerBuilder(builder: TestDataManagerCommandBuilder) {
        with(builder) {
            mode = TestDataManagerMode.CHECK
            goldenOnly = true
        }
    }
}
