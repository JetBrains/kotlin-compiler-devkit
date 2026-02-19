package org.jetbrains.kotlin.test.helper

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

private val DIGIT_REGEX = """\d+""".toRegex()

val VirtualFile.simpleNameUntilFirstDot: String
    get() {
        var processingFirst = true
        val parts = buildList {
            for (part in name.split(".")) {
                val isNumber = DIGIT_REGEX.matches(part)
                if (processingFirst) {
                    add(part)
                    processingFirst = false
                    continue
                }
                if (!isNumber) {
                    break
                }
                add(part)
            }
        }
        return parts.joinToString(".")
    }


private val supportedExtensions = listOf("kt", "kts", "args", "can-freeze-ide")

enum class TestDataType {
    File,
    Directory,
    DirectoryOfFiles,
    RelatedFile,
}

fun VirtualFile.getTestDataType(project: Project): TestDataType? {
    val configuration = TestDataPathsConfiguration.getInstance(project)
    if (configuration.testDataDirectories.any { path.startsWith(it) }) return TestDataType.Directory
    if (configuration.testDataFiles.any { path.startsWith(it) }) {
        return when {
            extension in supportedExtensions -> TestDataType.File
            isDirectory -> TestDataType.DirectoryOfFiles
            supportedExtensions.any { parent.findChild("$nameWithoutAllExtensions.$it") != null } -> TestDataType.RelatedFile
            else -> null
        }
    }
    return null
}

val String.asPathWithoutAllExtensions: String
    get() {
        val separatorLastIndex = lastIndexOf(File.separatorChar)
        var dotPreviousIndex: Int
        var dotIndex = length

        do {
            dotPreviousIndex = dotIndex
            dotIndex = lastIndexOf('.', dotPreviousIndex - 1)
        } while (
            dotIndex > separatorLastIndex && // it also handles `-1`
            !subSequence(dotIndex + 1, dotPreviousIndex).let { it.isNotEmpty() && it.all { c -> c.isDigit() } }
        )

        return substring(0, dotPreviousIndex)
    }

val String.allExtensions: String
    get() = substring(asPathWithoutAllExtensions.length)

val VirtualFile.nameWithoutAllExtensions get() = name.asPathWithoutAllExtensions
val VirtualFile.allExtensions get() = name.allExtensions

fun AnActionEvent.toFileNamesString(): String? {
    val project = project ?: return null
    val files = getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
    return files?.toList()?.toFileNamesString(project)
}

fun List<VirtualFile>.toFileNamesString(project: Project): String {
    return this
        .filter { it.getTestDataType(project) != null }
        .map { it.nameWithoutAllExtensions }
        .distinct()
        .joinToString(separator = ", ")
}


fun VirtualFile.getRelatedTestFiles(
    project: Project
): List<VirtualFile> {
    val configuration = TestDataPathsConfiguration.getInstance(project)
    val curFileName = simpleNameUntilFirstDot

    return this.parent.children.filter { it.name.startsWith("$curFileName.") } +
            configuration.findAdditionalRelatedFiles(this, curFileName)
}