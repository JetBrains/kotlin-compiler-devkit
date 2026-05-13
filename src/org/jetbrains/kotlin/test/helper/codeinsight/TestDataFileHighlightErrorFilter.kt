package org.jetbrains.kotlin.test.helper.codeinsight

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.injected.editor.VirtualFileWindow
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.test.helper.TestDataPathsConfiguration
import org.jetbrains.kotlin.test.helper.isTestDataFile

class TestDataFileHighlightErrorFilter : HighlightErrorFilter() {
    override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean {
        return !(isInjectionInTestFile(element.containingFile, element) && element.isLanguageIgnored())
    }

    private fun isInjectionInTestFile(
        containingFile: PsiFile,
        element: PsiErrorElement
    ): Boolean {
        val virtualFile = containingFile.virtualFile
        return virtualFile is VirtualFileWindow && virtualFile.delegate.isTestDataFile(element.project)
    }

    private fun PsiErrorElement.isLanguageIgnored(): Boolean {
        val languageId = this.language.id
        return TestDataPathsConfiguration.getInstance(this.project).ignoredLanguagesForInjection.any {
            it.equals(languageId, ignoreCase = true)
        }
    }
}