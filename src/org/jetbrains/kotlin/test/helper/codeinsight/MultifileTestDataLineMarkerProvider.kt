package org.jetbrains.kotlin.test.helper.codeinsight

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.SeparatorPlacement
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.test.helper.TestDataPathsConfiguration
import org.jetbrains.kotlin.test.helper.lang.MultifileTestDataEntry

/**
 * Multifile test data file describes several files in a single `.test` data file,
 * where files are separated by `// FILE: filename` directive.
 */
internal class MultifileTestDataLineMarkerProvider: LineMarkerProvider, DumbAware {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val entry = element as? MultifileTestDataEntry ?: return null
        if (!TestDataPathsConfiguration.getInstance(entry.project).multifilePartsSeparator) return null

        val globalScheme = EditorColorsManager.getInstance().globalScheme
        val anchor = entry.moduleHeader ?: entry.fileHeader ?: return null
        return LineMarkerInfo(anchor, anchor.textRange).apply {
            separatorPlacement = SeparatorPlacement.TOP
            separatorColor = globalScheme.getColor(CodeInsightColors.METHOD_SEPARATORS_COLOR)
        }
    }
}
