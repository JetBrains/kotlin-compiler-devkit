package org.jetbrains.kotlin.test.helper.codeinsight

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.impl.source.tree.PsiCommentImpl
import org.jetbrains.annotations.Unmodifiable
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.test.helper.TestDataPathsConfiguration
import org.jetbrains.kotlin.test.helper.isTestDataFile
import org.jetbrains.kotlin.test.helper.lang.MultifileTestDataFileContent
import org.jetbrains.kotlin.test.helper.lang.MultifileTestDataTextBlock
import org.jetbrains.kotlin.util.capitalizeDecapitalize.toLowerCaseAsciiOnly

class MultifileTestDataMultiHostInjector: MultiHostInjector {
    private val supportedElementTypes: List<Class<out PsiElement>> =
        listOf(MultifileTestDataFileContent::class.java, PsiComment::class.java, MultifileTestDataTextBlock::class.java)

    override fun getLanguagesToInject(
        registrar: MultiHostRegistrar,
        context: PsiElement
    ) {
        if (context is PsiCommentImpl && context.containingFile.virtualFile.isTestDataFile(context.project)) {
            injectCommentsAsKotlin(registrar, context)
            return
        }

        val textBlock = (context as? MultifileTestDataTextBlock)?.takeIf { it.textLength != 0 } ?: return
        val language = when {
            textBlock.fileContent != null -> textBlock.fileContent?.entry?.fileHeader?.injectedLanguage
            textBlock.preamble != null -> KotlinLanguage.INSTANCE
            else -> null
        } ?: return

        if (textBlock.fileContent != null) {
            val ignoredLanguageIds =
                TestDataPathsConfiguration.getInstance(context.project)
                    .ignoredLanguagesForInjection
                    .mapTo(hashSetOf(), String::toLowerCaseAsciiOnly)
            if (language.id.toLowerCaseAsciiOnly() in ignoredLanguageIds) {
                return
            }
        }

        registrar.startInjecting(language)
        registrar.addPlace(null, null, textBlock, TextRange(0, textBlock.textLength))
        registrar.doneInjecting()
    }

    private fun injectCommentsAsKotlin(
        registrar: MultiHostRegistrar,
        context: PsiLanguageInjectionHost
    ) {
        val content = context.text.takeUnless { it.isEmpty() } ?: return
        registrar.startInjecting(KotlinLanguage.INSTANCE)
        val textRange = TextRange(0, content.length)
        registrar.addPlace(null, null, context, textRange)
        registrar.doneInjecting()
    }

    override fun elementsToInjectIn(): @Unmodifiable List<Class<out PsiElement>> =
        supportedElementTypes
}
