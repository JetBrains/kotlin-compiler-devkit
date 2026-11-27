package org.jetbrains.kotlin.test.helper.reference

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.util.ui.EDT
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.idea.base.analysis.api.utils.analyzeInModalWindow
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtProperty

typealias ClassProvider = (Project) -> Collection<KtClassOrObject>

/**
 * A PSI reference for resolution to a single member declaration inside some Kotlin class
 *
 * @param element The host of the reference
 * @param range Reference text range inside [element]
 * @property memberShortName Short name of the target member
 * @property containingClassProvider Provider for the enclosing class of the target member
 * @property resolveDeprecationSuffixes Whether to also resolve the name without an `_ERROR`/`_WARNING`
 */
class SingleMemberReference(
    element: PsiElement,
    range: TextRange,
    private val memberShortName: String,
    private val containingClassProvider: ClassProvider,
    private val resolveDeprecationSuffixes: Boolean = false,
) : PsiReferenceBase<PsiElement>(element, range), PsiPolyVariantReference {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val project = myElement.project
        val classes = containingClassProvider(project)

        val targets = resolveMember(classes, memberShortName).ifEmpty {
            memberShortName.takeIf { resolveDeprecationSuffixes }?.removeDeprecationSuffix()
                ?.let { baseName ->
                    resolveMember(classes, baseName).filter(PsiElement::isDeprecationDiagnostic)
                }.orEmpty()
        }
        return targets.map { PsiElementResolveResult(it) }.toTypedArray()
    }

    override fun resolve(): PsiElement? {
        return multiResolve(false).singleOrNull()?.element
    }
}

private fun resolveMember(classes: Collection<KtClassOrObject>, name: String): List<PsiElement> =
    classes.mapNotNull { clazz ->
        clazz.fieldLikeMembers.firstOrNull { it.name == name }
    }

private val DEPRECATION_SUFFIXES = listOf("_ERROR", "_WARNING")

private fun String.removeDeprecationSuffix(): String? {
    val suffix = DEPRECATION_SUFFIXES.firstOrNull(::endsWith) ?: return null
    return removeSuffix(suffix).takeIf(String::isNotEmpty)
}

private val DIAGNOSTICS_PACKAGE = FqName("org.jetbrains.kotlin.diagnostics")

private fun PsiElement.isDeprecationDiagnostic(): Boolean {
    val property = this as? KtProperty ?: return false
    return if (EDT.isCurrentThreadEdt()) {
        analyzeInModalWindow(property, "Resolving") { isDeprecationDiagnosticType(property) }
    } else {
        analyze(property) { isDeprecationDiagnosticType(property) }
    }
}

private fun KaSession.isDeprecationDiagnosticType(property: KtProperty): Boolean {
    val classId = (property.returnType as? KaClassType)?.classId ?: return false
    return classId.packageFqName == DIAGNOSTICS_PACKAGE &&
            classId.shortClassName.asString().startsWith("KtDiagnosticFactoryForDeprecation")
}
