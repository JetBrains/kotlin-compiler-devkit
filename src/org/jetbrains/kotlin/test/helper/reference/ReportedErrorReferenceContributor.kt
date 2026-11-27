package org.jetbrains.kotlin.test.helper.reference

import com.intellij.injected.editor.VirtualFileWindow
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.util.ProcessingContext
import org.jetbrains.kotlin.test.helper.isTestDataFile
import org.jetbrains.kotlin.test.helper.lang.MultifileTestDataTextFileImpl

/**
 * [PsiReferenceContributor] that embeds references to `FirErrors` into the reported test data diagnostics.
 *
 * The contributor supports various cases:
 * - Single diagnostics `<!TYPE_MISMATCH!>`
 * - Multiple diagnostics on the same element `<!TYPE_MISMATCH, UNSUPPORTED_FEATURE!>`
 * - Diagnostics with additional metadata `<!UNSUPPORTED_FEATURE("gotcha")!>`.
 *   In such cases, the reference is embedded into the diagnostic name (`UNSUPPORTED_FEATURE`).
 * - Deprecation diagnostics with a severity suffix (`_ERROR`, `_WARNING`)
 *
 * ### Example:
 * ```kotlin
 * fun main() {
 *   throw <!TYPE_MISMATCH!>"str"<!>
 * //        ^^^^^^^^^^^^^
 * //   Navigates to `FirErrors.TYPE_MISMATCH`
 * }
 * ```
 */
class ReportedErrorReferenceContributor : PsiReferenceContributor() {
    private val groupRegex = Regex("<![A-Z].*?!>")
    private val entriesRegex = Regex("(?<=<!|, )([A-Z_]+)(?=,|!>|\\()")

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(MultifileTestDataTextFileImpl::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    ctx: ProcessingContext,
                ): Array<PsiReference> {
                    element.containingFile
                        ?.virtualFile
                        ?.let { (it as? VirtualFileWindow)?.delegate ?: it }
                        ?.takeIf { it.isTestDataFile(element.project) }
                        ?: return PsiReference.EMPTY_ARRAY

                    val text = element.text
                    val refs = mutableListOf<PsiReference>()

                    // Search for individual <!...!> groups
                    groupRegex.findAll(text).forEach { groupMatch ->

                        val (groupContent, groupMatchRange) = groupMatch.groups[0] ?: return@forEach
                        val groupStartOffset = groupMatchRange.first

                        // Search for diagnostic entries inside one group
                        entriesRegex.findAll(groupContent).forEach { errorEntry ->
                            val (errorContent, errorMatchRange) = errorEntry.groups[1] ?: return@forEach
                            val startOffset = groupStartOffset + errorMatchRange.first
                            val endOffset = groupStartOffset + errorMatchRange.last + 1
                            val range = TextRange(startOffset, endOffset)
                            refs.add(
                                SingleMemberReference(
                                    element,
                                    range,
                                    errorContent,
                                    ::getFirErrorClasses,
                                    resolveDeprecationSuffixes = true,
                                )
                            )
                        }
                    }

                    return refs.toTypedArray()
                }
            },
        )
    }
}