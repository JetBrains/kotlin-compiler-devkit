package org.jetbrains.kotlin.test.helper.codeinsight

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.CustomFoldingBuilder
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.test.helper.lang.MultifileTestDataEntry
import org.jetbrains.kotlin.test.helper.lang.MultifileTestDataTextFileImpl

internal class MultifileTestDataFoldingBuilder: CustomFoldingBuilder(), DumbAware {

    override fun isCustomFoldingCandidate(node: ASTNode): Boolean {
        return node.psi is MultifileTestDataEntry
    }

    override fun buildLanguageFoldRegions(
        descriptors: MutableList<FoldingDescriptor>,
        root: PsiElement,
        document: Document,
        quick: Boolean,
    ) {
        val file = root as? MultifileTestDataTextFileImpl ?: return

        for (entry in file.entries) {
            val content = entry.content
            if (content?.textContains('\n') != true) continue

            val entityRange = entry.textRange
            val range = TextRange(entityRange.startOffset, entityRange.endOffset - 1)
            descriptors +=
                FoldingDescriptor(entry.node, range).apply {
                    placeholderText = entry.placeholderText + " " + Typography.ellipsis
                }

            if (entry.moduleHeader != null) {
                val fileHeader = entry.fileHeader
                if (fileHeader != null) {
                    val textRange = fileHeader.textRange
                    descriptors +=
                        FoldingDescriptor(fileHeader.node, TextRange(textRange.startOffset, range.endOffset)).apply {
                            placeholderText = fileHeader.placeholderText + " " + Typography.ellipsis
                        }
                }
            }
        }
    }

    override fun getLanguagePlaceholderText(node: ASTNode, range: TextRange): String =
        Typography.ellipsis.toString()

    override fun isRegionCollapsedByDefault(node: ASTNode): Boolean = false

}
