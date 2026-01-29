package org.jetbrains.kotlin.test.helper.runAnything

import com.intellij.ide.actions.runAnything.RunAnythingAction.EXECUTOR_KEY
import com.intellij.ide.actions.runAnything.RunAnythingUtil
import com.intellij.ide.actions.runAnything.activity.RunAnythingCommandLineProvider
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.test.helper.actions.TestDescription
import org.jetbrains.kotlin.test.helper.gradle.computeGradleCommandLine
import org.jetbrains.plugins.gradle.action.GradleExecuteTaskAction
import java.awt.Component
import javax.swing.JWindow

private const val COMMAND = "testGlobally"
private const val TESTS = "--tests"
private val QUOTES = listOf('\'', '\"')

class TestGloballyRunAnythingProvider : RunAnythingCommandLineProvider() {
    /**
     * Basically a map that transforms the input somehow.
     * The plugin will only ever receive something coming through a successful mapping.
     * Otherwise, we consider the input as not-ours and don't work with it.
     */
    override fun findMatchingValue(dataContext: DataContext, pattern: String): String? =
        pattern.takeIf { COMMAND.startsWith(pattern) || pattern.startsWith(COMMAND) }

    /**
     * Some strange thing that is checked against the input command on whether one
     * is a prefix of the other.
     * See [execute] -> [RunAnythingCommandLineProvider.run] -> [RunAnythingCommandLineProvider.parseCommandLine].
     */
    override fun getHelpCommand(): String = COMMAND

    /**
     * Must be present for [suggestCompletionVariants] to be called.
     */
    override fun getCompletionGroupTitle(): String = "Global Test Runner"

    override fun suggestCompletionVariants(dataContext: DataContext, commandLine: CommandLine): Sequence<String> {
        return when {
            !commandLine.isCorrectSoFar -> emptySequence()
            commandLine.toComplete.suggests(TESTS) -> sequenceOf(TESTS)
            commandLine.toComplete.isEmpty() && commandLine.completedParameters.lastOrNull() != TESTS -> {
                sequenceOf(TESTS)
            }
            else -> sequenceOf()
        }
    }

    private fun String.suggests(another: String): Boolean =
        isNotEmpty() && another.startsWith(this) && another != this

    private val List<String>.isCorrectParametersList: Boolean
        get() = withIndex().all { (index, value) -> index.isOdd || value == TESTS }

    private val CommandLine.isCorrectSoFar: Boolean
        get() = completedParameters.isCorrectParametersList

    private val CommandLine.isCorrectCompletely: Boolean
        get() = parameters.isCorrectParametersList && parameters.size.isEven

    private val Int.isEven get() = this and 1 == 0
    private val Int.isOdd get() = !isEven

    override fun run(dataContext: DataContext, commandLine: CommandLine): Boolean {
        val project = RunAnythingUtil.fetchProject(dataContext)

        if (!commandLine.isCorrectCompletely) {
            return failure("This is not a valid `$COMMAND` call", project)
        }

        val workingDirectory = project.basePath
            ?: return failure("Failed to get the project base path and set up the working directory", project)

        val testFiltersFqNames = commandLine.parameters
            .filterIndexed { index, _ -> index.isOdd }
            .map { it.removeQuotesIfNeeded().removeJUnitDisplayNameIfNeeded() }

        val javaPsiFacade = JavaPsiFacade.getInstance(project)
        val globalSearchScope = GlobalSearchScope.allScope(project)

        val methods = testFiltersFqNames
            .mapNotNull { filter ->
                val parts = filter.replace("$", ".").split(".")
                javaPsiFacade.findClass(parts.dropLast(1).joinToString(separator = "."), globalSearchScope)
                    ?.methods?.find { it.name == parts.last() }
            }
            .filter { it.hasAnnotation("org.jetbrains.kotlin.test.TestMetadata") }
            .map { TestDescription.ExistingTest(it) }

        val executor = EXECUTOR_KEY.getData(dataContext)
        val gradleCommand = computeGradleCommandLine(methods)
        GradleExecuteTaskAction.runGradle(project, executor, workingDirectory, gradleCommand)
        return true
    }

    private fun String.removeQuotesIfNeeded(): String {
        val firstIndex = if (firstOrNull() in QUOTES) 1 else 0
        val lastIndex = if (lastOrNull() in QUOTES) lastIndex else length

        return when {
            firstIndex != 0 || lastIndex != length -> substring(firstIndex, lastIndex)
            else -> this
        }
    }

    /**
     * A countermeasure for functions like: `my.package.path.MyTest.testSomething()(some random commend)`.
     * Despite `@DisplayName` is a JUnit annotation, passing this to `--tests` will produce "No tests found
     * for given includes".
     */
    private fun String.removeJUnitDisplayNameIfNeeded(): String {
        val parenthesesIndex = indexOf('(')

        return when {
            parenthesesIndex != -1 -> substring(0, parenthesesIndex)
            else -> this
        }
    }

    private fun failure(error: String, project: Project) = false.also {
        // There's a bug that the error dialog is partially covered with the
        // Run Anything window, so you can't see it.
        // Let's try closing it eagerly.
        WindowManager.getInstance().getFocusedComponent(project)?.parentOfType<JWindow>()?.dispose()
        Messages.showErrorDialog(error, "Could Not Run Command")
    }

    private inline fun <reified C> Component.parentOfType(): C? {
        var result = parent

        while (result !is C && result != null) {
            result = result.parent
        }

        return result as? C
    }
}
