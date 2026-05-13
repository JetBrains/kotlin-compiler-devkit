package org.jetbrains.kotlin.test.helper.codeinsight

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.injected.editor.VirtualFileWindow
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.test.helper.TestDataPathsConfiguration
import org.jetbrains.kotlin.test.helper.isTestDataFile

class TestDataFileHighlightErrorFilter : HighlightErrorFilter() {
    override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean {
        return !(element.containingFile.isInjectionInTestFile() && element.isLanguageIgnored())
    }

    private fun PsiErrorElement.isLanguageIgnored(): Boolean {
        val languageId = this.language.id
        return TestDataPathsConfiguration.getInstance(this.project).ignoredLanguagesForInjection.any {
            it.equals(languageId, ignoreCase = true)
        }
    }
}

class TestDataFileHighlightInfoFilter : HighlightInfoFilter {
    override fun accept(highlightInfo: HighlightInfo, psiFile: PsiFile?): Boolean {
        return psiFile == null || !psiFile.isInjectionInTestFile()
    }
}

private fun PsiFile.isInjectionInTestFile(): Boolean {
    val virtualFile = this.virtualFile
    return virtualFile is VirtualFileWindow && virtualFile.delegate.isTestDataFile(project)
}