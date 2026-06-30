package org.jetbrains.kotlin.test.helper.codeinsight

import com.intellij.injected.editor.VirtualFileWindow
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.test.helper.isTestDataFile

private val EXPECTATIONS_HEADER = Regex("""^//\s*EXPECTATIONS\b""")

/**
 * Folds each `// EXPECTATIONS <BACKEND>` section of a stepping test.
 *
 * This builder runs on the injected Kotlin PSI fragment and folds
 * each backend expectations section (the header comment plus its following step lines) independently.
 */
class KotlinTestDataSteppingFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<out FoldingDescriptor?> {
        if (!root.isInjectionInTestFile()) return FoldingDescriptor.EMPTY_ARRAY

        val comments = PsiTreeUtil.collectElementsOfType(root, PsiComment::class.java)
            .sortedBy { it.textRange.startOffset }

        val descriptors = mutableListOf<FoldingDescriptor>()
        var index = 0
        while (index < comments.size) {
            val header = comments[index]
            if (!header.isExpectationsHeader()) {
                index++
                continue
            }

            var last = header
            var next = index + 1
            while (next < comments.size) {
                val candidate = comments[next]
                if (candidate.isExpectationsHeader()) break
                // Only extend across whitespace-separated comments; real code ends the section.
                val gap = document.getText(TextRange(last.textRange.endOffset, candidate.textRange.startOffset))
                if (!gap.isBlank()) break
                last = candidate
                next++
            }

            if (last !== header) {
                val range = TextRange(header.textRange.startOffset, last.textRange.endOffset)
                descriptors += FoldingDescriptor(header.node, range).apply {
                    placeholderText = header.placeholderText()
                }
            }
            index = next
        }

        return descriptors.toTypedArray()
    }

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false

    override fun getPlaceholderText(node: ASTNode): String? = node.psi?.placeholderText()

    private fun PsiElement.isInjectionInTestFile(): Boolean {
        val virtualFile = containingFile?.virtualFile
        return virtualFile is VirtualFileWindow && virtualFile.delegate.isTestDataFile(project)
    }

    private fun PsiElement.isExpectationsHeader(): Boolean =
        this is PsiComment && EXPECTATIONS_HEADER.containsMatchIn(text)

    private fun PsiElement.placeholderText(): String =
        text.lineSequence().firstOrNull()?.trim().orEmpty() + " " + Typography.ellipsis
}
