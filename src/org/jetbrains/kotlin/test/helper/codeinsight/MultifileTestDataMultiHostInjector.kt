package org.jetbrains.kotlin.test.helper.codeinsight

import com.intellij.lang.Language
import com.intellij.lang.injection.*
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.impl.source.tree.PsiCommentImpl
import org.jetbrains.annotations.Unmodifiable
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.test.helper.TestDataPathsConfiguration
import org.jetbrains.kotlin.test.helper.lang.MultifileTestDataFileContent
import org.jetbrains.kotlin.test.helper.lang.MultifileTestDataTextBlock

class MultifileTestDataMultiHostInjector: MultiHostInjector {
    private val supportedElementTypes: List<Class<out PsiElement>> =
        listOf(MultifileTestDataFileContent::class.java, PsiCommentImpl::class.java)

    private val ignoredLanguagesForInjection: Set<Language> =
        setOf(KotlinLanguage.INSTANCE, JavaLanguage.INSTANCE)

    override fun getLanguagesToInject(
        registrar: MultiHostRegistrar,
        context: PsiElement
    ) {
        if (context is PsiCommentImpl) {
            injectCommentsAsKotlin(registrar, context)
        }

        val textBlock = (context as? MultifileTestDataTextBlock)?.takeIf { it.textLength != 0 } ?: return
        val language = when {
            textBlock.fileContent != null -> textBlock.fileContent?.entry?.fileHeader?.injectedLanguage
            textBlock.preamble != null -> KotlinLanguage.INSTANCE
            else -> null
        } ?: return

        if (textBlock.fileContent != null) {
            if (language in ignoredLanguagesForInjection) {
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