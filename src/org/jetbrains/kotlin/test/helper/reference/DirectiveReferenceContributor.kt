package org.jetbrains.kotlin.test.helper.reference

import com.intellij.injected.editor.VirtualFileWindow
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.util.ProcessingContext
import org.jetbrains.kotlin.test.helper.getTestDataType

class DirectiveReferenceContributor : PsiReferenceContributor() {
    private val regex = Regex("// ([A-Z0-9_]+)(:.*)?")
    private val regexValue = Regex("[+-]?([A-Za-z0-9_]+)")

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(PsiComment::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext
                ): Array<PsiReference> {
                    element.containingFile?.virtualFile
                        ?.let { (it as? VirtualFileWindow)?.delegate ?: it }
                        ?.takeIf { it.getTestDataType(element.project) != null }
                        ?: return PsiReference.EMPTY_ARRAY

                    val text = element.text
                    val refs = mutableListOf<PsiReference>()

                    val regex = regex
                    regex.matchAt(text, 0)?.let { match ->
                        val (name, matchRange) = match.groups[1] ?: return@let
                        val range = TextRange(matchRange.first, matchRange.last + 1)
                        refs.add(TestDirectiveReference(element, range, name))

                        val (features, featuresRange) = match.groups[2] ?: return@let
                        regexValue.findAll(features).forEach {
                            val (value, valueRange) = it.groups[1] ?: return@forEach
                            val valueTextRange = TextRange(
                                featuresRange.first + valueRange.first,
                                featuresRange.first + valueRange.last + 1,
                            )
                            val enumType: EnumClassProvider = if (name == "LANGUAGE") {
                                ::getLanguageFeatureClasses
                            } else {
                                { getEnumClassesByDirective(name, it) }
                            }
                            val reference = EnumValueReference(element, valueTextRange, value, enumType)
                            refs.add(reference)
                        }
                    }

                    return refs.toTypedArray()
                }
            }
        )
    }
}
