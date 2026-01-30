package org.jetbrains.kotlin.test.helper.reference

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.stubindex.KotlinPropertyShortNameIndex
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDeclarationWithReturnType

const val LANGUAGE_FEATURE_FQ_NAME = "org.jetbrains.kotlin.config.LanguageFeature"

private val VALUE_DIRECTIVE_CLASS_ID =
    ClassId.topLevel(FqName("org.jetbrains.kotlin.test.directives.model.ValueDirective"))

typealias EnumClassProvider = (Project) -> List<PsiClass>

class EnumValueReference(
    element: PsiElement,
    range: TextRange,
    private val key: String,
    private val enumType: EnumClassProvider
) : PsiReferenceBase<PsiElement>(element, range), PsiPolyVariantReference {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val project = myElement.project
        val classes = enumType(project)

        return classes.mapNotNull { clazz ->
            clazz.fields
                .filterIsInstance<PsiEnumConstant>()
                .firstOrNull { it.name == key }
                ?.let { PsiElementResolveResult(it) }
        }.toTypedArray()
    }

    override fun resolve(): PsiElement? {
        return multiResolve(false).singleOrNull()?.element
    }

    override fun getVariants(): Array<Any> = emptyArray()
}

fun getLanguageFeatureClasses(project: Project): List<PsiClass> {
    val psiFacade = JavaPsiFacade.getInstance(project)

    return resolvePreferringProjectScope(project) {
        psiFacade.findClasses(LANGUAGE_FEATURE_FQ_NAME, it).toList()
    }
}

fun getEnumClassesByDirective(key: String, project: Project): List<KtLightClass> {
    return resolvePreferringProjectScope(project) {
        val declarations =
            KotlinPropertyShortNameIndex.Helper[key, project, GlobalSearchScope.allScope(project)]

        declarations.mapNotNull {
            if (it !is KtDeclarationWithReturnType) return@mapNotNull null
            analyze(it) {
                if (it.returnType.isClassType(VALUE_DIRECTIVE_CLASS_ID)) {
                    ((it.returnType as KaClassType).typeArguments.firstOrNull()?.type?.expandedSymbol?.psi as? KtClass)?.toLightClass()
                } else {
                    null
                }
            }
        }
    }
}
