package org.jetbrains.kotlin.test.helper.codeinsight

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.CustomFoldingBuilder
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

internal class MultifileTestDataFoldingBuilder: CustomFoldingBuilder(), DumbAware {

    override fun isCustomFoldingCandidate(node: ASTNode): Boolean {
        if (node.psi.containingFile?.multiTestDataFile == null) return false

        return MODULE_FILE_PATTERN.matcher(node.text).find()
    }

    override fun buildLanguageFoldRegions(
        descriptors: MutableList<FoldingDescriptor>,
        root: PsiElement,
        document: Document,
        quick: Boolean,
    ) {
        val file = (root as? PsiFile)?.multiTestDataFile ?: return
        val text = file.text
        val matcher = MODULE_FILE_PATTERN.matcher(text)

        var previousDirective: String? = null
        var previousStart: Int? = null

        fun addDescriptor(end: Int, name: String?) {
            val textRange = TextRange(previousStart ?: 0, end)
            val descriptor = FoldingDescriptor(root, textRange)
            descriptor.placeholderText = name + Typography.ellipsis.toString()
            descriptors += descriptor
        }

        while (matcher.find()) {
            val start = matcher.start()
            val directive = matcher.group(0).trim()

            if (previousStart != null) {
                addDescriptor(start - 1, previousDirective)
            }
            previousStart = start
            previousDirective = directive
        }

        addDescriptor(text.length, previousDirective)
    }

    override fun getLanguagePlaceholderText(node: ASTNode, range: TextRange): String =
        Typography.ellipsis.toString()

    override fun isRegionCollapsedByDefault(node: ASTNode): Boolean = false

}
