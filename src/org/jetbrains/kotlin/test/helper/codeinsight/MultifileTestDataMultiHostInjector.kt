package org.jetbrains.kotlin.test.helper.codeinsight

import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.jetbrains.annotations.Unmodifiable
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.test.helper.lang.MultifileTestDataFileContent

class MultifileTestDataMultiHostInjector: MultiHostInjector {
    private val supportedElementTypes: List<Class<out PsiElement>> =
        listOf(MultifileTestDataFileContent::class.java)

    private val ignoredLanguagesForInjection: Set<Language> =
        setOf(KotlinLanguage.INSTANCE, JavaLanguage.INSTANCE)

    override fun getLanguagesToInject(
        registrar: MultiHostRegistrar,
        context: PsiElement
    ) {
        val content =
            (context as? MultifileTestDataFileContent)?.takeIf { it.textLength != 0 } ?: return
        val language = content.entry?.fileHeader?.injectedLanguage ?: return
        if (language in ignoredLanguagesForInjection) return

        registrar.startInjecting(language)
        registrar.addPlace(null, null, content, TextRange(0, content.textLength))
        registrar.doneInjecting()
    }

    override fun elementsToInjectIn(): @Unmodifiable List<Class<out PsiElement>> =
        supportedElementTypes
}