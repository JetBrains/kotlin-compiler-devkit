package org.jetbrains.kotlin.test.helper.gradle

import andel.intervals.toReversedList
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.test.helper.actions.TestDescription
import org.jetbrains.kotlin.test.helper.actions.normalizedName
import org.jetbrains.plugins.gradle.execution.test.runner.GradleTestRunConfigurationProducer
import org.jetbrains.plugins.gradle.util.createTestFilterFrom

fun computeGradleCommandLine(testDeclarations: List<TestDescription>): String = buildString {
    val singleTest = testDeclarations.size == 1

    testDeclarations
        .flatMap { description ->
            val psi = description.psi
            val parentClass = psi as? PsiClass ?: psi.parentOfType<PsiClass>() ?: return@flatMap emptyList()
            val taskArguments = toTaskArguments(parentClass, psi, description)

            val virtualFile = psi.containingFile?.virtualFile ?: return@flatMap emptyList()
            val allTasks = GradleTestRunConfigurationProducer.findAllTestsTaskToRun(virtualFile, psi.project)
                .flatMap { it.tasks }
            allTasks
                .map {
                    val group = it.substringBeforeLast(":")
                    val name = it.substringAfterLast(":")
                    group to name
                }
                .groupBy(keySelector = { it.first }, valueTransform = { it.second })
                .map { (group, names) -> Triple(group, names.minByOrNull { it.length }!!, taskArguments) }
        }
        .groupBy({ (group, name) -> group to name }) { it.third }
        .forEach { (group, name), taskArguments ->
            if (!singleTest) append("$group:cleanTest ")
            append("$group:$name ")
            for (taskArgument in taskArguments) {
                append(taskArgument)
                append(' ')
            }
        }

    if (!singleTest) append("--continue")
}

private fun toTaskArguments(
    parentClass: PsiClass,
    psi: PsiNameIdentifierOwner,
    description: TestDescription
): String {
    // format is  "--tests \"$escaped\""
    val taskArguments = createTestFilterFrom(parentClass, (psi as? PsiMethod)?.name)

    if (description.isOnTheFly()) {
        var current = description.file
        val parent = description.fileOfParentPsi

        return buildString {
            append(taskArguments)

            val missingPart = sequence {
                if (!description.file.isDirectory) {
                    yield(".test" + current.normalizedName())
                    current = current.parent
                }

                while (current != parent) {
                    yield("$" + current.normalizedName())
                    current = current.parent
                }
            }.toReversedList().joinToString("")

            insert(length - 1, missingPart)
        }
    }

    return taskArguments
}
