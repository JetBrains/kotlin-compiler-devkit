package org.jetbrains.kotlin.test.helper.codeinsight

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzerSettings
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.SeparatorPlacement
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.test.helper.getTestDataType
import java.util.regex.Pattern

/**
 * Multifile test data file describes several files in a single `.test` data file,
 * where files are separated by `// FILE: filename` directive.
 */
internal class MultifileTestDataLineMarkerProvider: LineMarkerProvider, DumbAware {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        if (!DaemonCodeAnalyzerSettings.getInstance().SHOW_METHOD_SEPARATORS) return

        val file = elements.firstOrNull()?.multiTestDataFile ?: return

        val matcher = MODULE_FILE_PATTERN.matcher(file.text)
        val globalScheme = EditorColorsManager.getInstance().globalScheme

        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()

            val info = LineMarkerInfo(file, TextRange(start, end))
            info.separatorColor = globalScheme.getColor(CodeInsightColors.METHOD_SEPARATORS_COLOR)
            info.separatorPlacement = SeparatorPlacement.TOP

            result.add(info)
        }

    }
}

internal val MODULE_FILE_PATTERN = Pattern.compile("(//\\s*MODULE:\\s*(.*)\\R)?//\\s*FILE:\\s*(.*)\\R")

internal val PsiElement.multiTestDataFile: PsiFile?
    get() = containingFile?.takeIf { it.virtualFile.getTestDataType(project) != null }