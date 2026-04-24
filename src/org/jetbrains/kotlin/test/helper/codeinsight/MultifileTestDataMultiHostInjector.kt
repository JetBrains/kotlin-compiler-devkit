package org.jetbrains.kotlin.test.helper.codeinsight

import com.intellij.lang.Language
import com.intellij.lang.injection.*
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import org.jetbrains.annotations.Unmodifiable
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.test.helper.lang.*
import java.util.regex.Pattern

class MultifileTestDataMultiHostInjector: MultiHostInjector {
    private val pattern = Pattern.compile("(//.*)\\R")
    private val supportedElementTypes: List<Class<out PsiElement>> =
        listOf(MultifileTestDataFileContent::class.java, MultifileTestDataPreamble::class.java)

    private val ignoredLanguagesForInjection: Set<Language> =
        setOf(KotlinLanguage.INSTANCE, JavaLanguage.INSTANCE)

    override fun getLanguagesToInject(
        registrar: MultiHostRegistrar,
        context: PsiElement
    ) {
        if (context is MultifileTestDataPreamble) {
            injectCommentsAsKotlin(registrar, context)
            return
        }

        val content =
            (context as? MultifileTestDataFileContent)?.takeIf { it.textLength != 0 } ?: return
        val language = content.entry?.fileHeader?.injectedLanguage ?: return

        if (language in ignoredLanguagesForInjection) {
            injectCommentsAsKotlin(registrar, context)
            return
        }

        registrar.startInjecting(language)
        registrar.addPlace(null, null, content, TextRange(0, content.textLength))
        registrar.doneInjecting()
    }

    private fun injectCommentsAsKotlin(
        registrar: MultiHostRegistrar,
        context: PsiLanguageInjectionHost
    ) {
        val fileContent = context.text ?: return
        val matcher = pattern.matcher(fileContent)
        while (matcher.find()) {
            registrar.startInjecting(KotlinLanguage.INSTANCE)
            val textRange = TextRange(matcher.start(1), matcher.end(1))
            registrar.addPlace(null, null, context, textRange)
            registrar.doneInjecting()
        }
    }

    override fun elementsToInjectIn(): @Unmodifiable List<Class<out PsiElement>> =
        supportedElementTypes
}