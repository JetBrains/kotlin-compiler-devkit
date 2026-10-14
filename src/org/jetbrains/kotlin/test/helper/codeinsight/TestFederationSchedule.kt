package org.jetbrains.kotlin.test.helper.codeinsight

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.CommonClassNames.JAVA_LANG_ANNOTATION_REPEATABLE
import com.intellij.psi.CommonClassNames.JAVA_LANG_OBJECT
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.util.JavaPsiAnnotationUtil
import com.intellij.psi.util.PsiUtil
import java.lang.annotation.RetentionPolicy
import com.siyeh.ig.junit.JUnitCommonClassNames.ORG_JUNIT_JUPITER_API_NESTED as JUNIT_NESTED
import com.intellij.psi.PsiAnnotation.DEFAULT_REFERENCED_METHOD_NAME as ANNOTATION_VALUE

private const val JUNIT_TAG = "org.junit.jupiter.api.Tag"
private const val JUNIT_TAGS = "org.junit.jupiter.api.Tags"
private const val SMOKE_TAG = "smoke"
private const val NIGHTLY_TAG = "nightly"
private const val CONTRACT_TAG_PREFIX = "contract:"
private const val RESERVED_TAG_CHARACTERS = ",()&|!"
private val standardAnnotationPackages = listOf("java.lang.annotation.", "kotlin.annotation.", "kotlin.jvm.")

internal data class TestFederationSchedule(
    val smoke: Boolean = false,
    val contractDomains: Set<String> = emptySet(),
    val nightly: Boolean = false,
)

internal fun TestFederationSchedule.isEmpty(): Boolean = !smoke && !nightly && contractDomains.isEmpty()

internal operator fun TestFederationSchedule.plus(other: TestFederationSchedule): TestFederationSchedule =
    TestFederationSchedule(
        smoke = smoke || other.smoke,
        contractDomains = contractDomains + other.contractDomains,
        nightly = nightly || other.nightly,
    )

internal fun PsiModifierListOwner.directTestFederationSchedule(): TestFederationSchedule =
    declaredFederationTags(hashSetOf()).toSchedule()

internal fun PsiClass.isJupiterNestedClass(): Boolean =
    !hasModifierProperty(PsiModifier.STATIC) && hasRuntimeAnnotation(JUNIT_NESTED)

internal fun PsiModifierListOwner.hasRuntimeAnnotation(vararg names: String): Boolean {
    val visited = hashSetOf<String>()
    return modifierList?.annotations.orEmpty().any { it.isRuntimeAnnotation(names, visited) }
}

private fun PsiAnnotation.isRuntimeAnnotation(names: Array<out String>, visited: MutableSet<String>): Boolean {
    val name = qualifiedName ?: return false
    if (!visited.add(name)) return false
    val annotationClass = runtimeAnnotationClass() ?: return false
    return name in names || annotationClass.modifierList?.annotations.orEmpty()
        .any { it.isRuntimeAnnotation(names, visited) }
}

internal fun PsiClass.testFederationSchedule(): TestFederationSchedule {
    val schedule = hierarchyFederationTags(hashSetOf(), hashSetOf()).toSchedule()
    val parentSchedule = if (isJupiterNestedClass()) containingClass?.testFederationSchedule() else null
    return schedule + (parentSchedule ?: TestFederationSchedule())
}

private fun PsiClass.hierarchyFederationTags(
    classes: MutableSet<PsiClass>,
    annotations: MutableSet<String>,
): List<String> {
    ProgressManager.checkCanceled()
    if (!classes.add(this) || qualifiedName == JAVA_LANG_OBJECT) return emptyList()
    return supers.flatMap { it.hierarchyFederationTags(classes, annotations) } + declaredFederationTags(annotations)
}

private fun PsiModifierListOwner.declaredFederationTags(visited: MutableSet<String>): List<String> =
    modifierList?.annotations.orEmpty().flatMap { it.federationTagValues(visited) }

private fun PsiAnnotation.federationTagValues(visited: MutableSet<String>): List<String> {
    ProgressManager.checkCanceled()
    val name = qualifiedName ?: return emptyList()
    val annotationClass = runtimeAnnotationClass() ?: return emptyList()
    return when (name) {
        JUNIT_TAG -> listOfNotNull(stringValue())
        JUNIT_TAGS -> findAttributeValue(ANNOTATION_VALUE).annotationValues().mapNotNull { it.stringValue() }
        else -> {
            val repeated = annotationClass.containedRepeatableAnnotation()
            if (repeated != null) {
                if (findAttributeValue(ANNOTATION_VALUE).annotationValues().isEmpty()) return emptyList()
                if (!visited.add(repeated.qualifiedName ?: return emptyList())) return emptyList()
                repeated.declaredFederationTags(visited)
            } else {
                if (!visited.add(name)) return emptyList()
                annotationClass.declaredFederationTags(visited)
            }
        }
    }
}

// Kotlin light annotations need resolved PSI here; MetaAnnotationUtil can miss their meta-annotation chains.
private fun PsiAnnotation.runtimeAnnotationClass(): PsiClass? {
    val name = qualifiedName ?: return null
    if (standardAnnotationPackages.any(name::startsWith)) return null
    val annotationClass = resolveAnnotationType()
        ?: JavaPsiFacade.getInstance(project).findClass(name, resolveScope)
        ?: return null
    return annotationClass.takeIf { JavaPsiAnnotationUtil.getRetentionPolicy(it) == RetentionPolicy.RUNTIME }
}

private fun PsiClass.containedRepeatableAnnotation(): PsiClass? {
    val arrayType =
        findMethodsByName(ANNOTATION_VALUE, false).singleOrNull()?.returnType as? PsiArrayType ?: return null
    val repeated = PsiUtil.resolveClassInClassTypeOnly(arrayType.componentType) ?: return null
    val container = repeated.modifierList?.findAnnotation(JAVA_LANG_ANNOTATION_REPEATABLE)
        ?.findAttributeValue(ANNOTATION_VALUE) as? PsiClassObjectAccessExpression ?: return null
    val containerClass = PsiUtil.resolveClassInClassTypeOnly(container.operand.type) ?: return null
    return repeated.takeIf { manager.areElementsEquivalent(this, containerClass) }
}

private fun PsiAnnotationMemberValue?.annotationValues(): List<PsiAnnotation> = when (this) {
    is PsiAnnotation -> listOf(this)
    is PsiArrayInitializerMemberValue -> initializers.filterIsInstance<PsiAnnotation>()
    else -> emptyList()
}

private fun PsiAnnotation.stringValue(): String? {
    val value = findAttributeValue(ANNOTATION_VALUE) ?: return null
    return JavaPsiFacade.getInstance(project).constantEvaluationHelper.computeConstantExpression(value) as? String
}

private fun List<String>.toSchedule(): TestFederationSchedule {
    // Match JUnit's String.trim(), which only removes leading/trailing characters up to ASCII space.
    val tags = map { it.trim { character -> character <= ' ' } }.filter { tag ->
        tag.isNotEmpty() && tag.none {
            Character.isWhitespace(it) || Character.isISOControl(it) || it in RESERVED_TAG_CHARACTERS
        }
    }
    return TestFederationSchedule(
        smoke = SMOKE_TAG in tags,
        contractDomains = tags.filter { it.startsWith(CONTRACT_TAG_PREFIX) }
            .map { it.removePrefix(CONTRACT_TAG_PREFIX) }.filter { it.isNotEmpty() }.toSet(),
        nightly = NIGHTLY_TAG in tags,
    )
}
