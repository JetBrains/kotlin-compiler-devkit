package org.jetbrains.kotlin.test.helper.reference

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.idea.stubindex.KotlinPropertyShortNameIndex
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclarationWithReturnType
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtProperty

private const val LANGUAGE_FEATURE_FQ_NAME = "org.jetbrains.kotlin.config.LanguageFeature"

fun getLanguageFeatureClasses(project: Project): List<KtClassOrObject> {
    val psiFacade = JavaPsiFacade.getInstance(project)

    return resolvePreferringProjectScope(project) {
        psiFacade.findClasses(LANGUAGE_FEATURE_FQ_NAME, it).mapNotNull(PsiClass::kotlinClassOrigin)
    }
}

private val VALUE_DIRECTIVE_CLASS_ID =
    ClassId.topLevel(FqName("org.jetbrains.kotlin.test.directives.model.ValueDirective"))

fun getEnumClassesByDirective(key: String, project: Project): List<KtClass> {
    return resolvePreferringProjectScope(project) {
        val declarations =
            KotlinPropertyShortNameIndex.Helper[key, project, GlobalSearchScope.allScope(project)]

        declarations.mapNotNull {
            if (it !is KtDeclarationWithReturnType) return@mapNotNull null
            analyze(it) {
                if (it.returnType.isClassType(VALUE_DIRECTIVE_CLASS_ID)) {
                    (it.returnType as KaClassType).typeArguments.firstOrNull()?.type?.expandedSymbol?.psi as? KtClass
                } else {
                    null
                }
            }
        }
    }
}

private const val KT_DIAGNOSTIC_CONTAINER_FQ_NAME = "org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer"

fun getFirErrorClasses(project: Project): Collection<KtClassOrObject> {
    return getInheritors(project, KT_DIAGNOSTIC_CONTAINER_FQ_NAME)
}

fun getInheritors(project: Project, fqName: String): Collection<KtClassOrObject> {
    val scope = GlobalSearchScope.allScope(project)

    val directiveContainer = JavaPsiFacade.getInstance(project)
        .findClass(fqName, scope)
        ?: return emptyList()

    return resolvePreferringProjectScope(project) {
        ClassInheritorsSearch.search(directiveContainer, it, true).findAll().mapNotNull(PsiClass::kotlinClassOrigin)
    }
}

fun <T : PsiElement> resolvePreferringProjectScope(project: Project, resolve: (GlobalSearchScope) -> List<T>): List<T> {
    return resolve(GlobalSearchScope.projectScope(project))
        .ifEmpty { resolve(GlobalSearchScope.allScope(project)) }
}

private fun PsiClass.kotlinClassOrigin(): KtClassOrObject? = (this as? KtLightClass)?.kotlinOrigin

val KtClassOrObject.fieldLikeMembers: List<KtNamedDeclaration>
    get() = declarations.filterIsInstance<KtNamedDeclaration>().filter { it is KtProperty || it is KtEnumEntry }
