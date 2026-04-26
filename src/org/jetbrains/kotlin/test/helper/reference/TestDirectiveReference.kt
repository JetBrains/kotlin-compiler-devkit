package org.jetbrains.kotlin.test.helper.reference

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.stubindex.KotlinPropertyShortNameIndex
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtDeclarationWithReturnType
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.test.helper.TestDataPathsConfiguration

class TestDirectiveReference(
    element: PsiElement,
    range: TextRange,
    private val key: String
) : PsiReferenceBase<PsiElement>(element, range), PsiPolyVariantReference {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val project = myElement.project
        return resolvePreferringProjectScope(project) { scope ->
            val declarations = KotlinPropertyShortNameIndex.Helper[key, project, scope]
            declarations.filter { it.isDirective() }
        }.map { PsiElementResolveResult(it) }
            .toTypedArray()
    }

    override fun resolve(): PsiElement? {
        return multiResolve(false).singleOrNull()?.element
    }

    override fun getVariants(): Array<Any> = emptyArray()
}

fun KtNamedDeclaration.isDirective(): Boolean {
    val directiveClassId = TestDataPathsConfiguration.getInstance(project).directiveClassId
        .takeIf(String::isNotBlank)
        ?.let(::runCatchingClassId)
        ?: return false

    return this is KtDeclarationWithReturnType && analyze(this) {
        returnType.isSubtypeOf(directiveClassId)
    }
}

private fun runCatchingClassId(fqName: String): ClassId? =
    runCatching { ClassId.topLevel(FqName(fqName)) }.getOrNull()

fun <T : PsiElement> resolvePreferringProjectScope(project: Project, resolve: (GlobalSearchScope) -> List<T>): List<T> {
    return resolve(GlobalSearchScope.projectScope(project))
        .ifEmpty { resolve(GlobalSearchScope.allScope(project)) }
}
