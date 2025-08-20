package org.jetbrains.kotlin.test.helper.inspections

import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.idea.base.psi.KotlinPsiHeuristics
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtAnnotated

/**
 * Suppresses the "unused declaration" inspection for declarations annotated with one of the [entryPoints] annotations.
 */
class UnusedDeclarationSuppressor : InspectionSuppressor {
    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean =
        toolId.lowercase() == "unused" &&
                element is KtAnnotated &&
                entryPoints.any { KotlinPsiHeuristics.hasAnnotation(element, it) }

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<out SuppressQuickFix?> =
        SuppressQuickFix.EMPTY_ARRAY
}

private val entryPoints: List<FqName> = listOf(
    // Compiler
    "org.jetbrains.kotlin.utils.IDEAPluginsCompatibilityAPI",

    // Standard library
    "kotlin.internal.UsedFromCompilerGeneratedCode",
    "kotlin.js.JsIntrinsic",
    "kotlin.wasm.internal.ExcludedFromCodegen",
).map(::FqName)
