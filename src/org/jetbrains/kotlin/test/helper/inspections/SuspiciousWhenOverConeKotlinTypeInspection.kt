package org.jetbrains.kotlin.test.helper.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.symbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.kotlin.psi.KtWhenConditionIsPattern
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.types.Variance

private val PACKAGE_FIR_TYPES = FqName("org.jetbrains.kotlin.fir.types")

private fun String.coneTypeClassId(): ClassId = ClassId(PACKAGE_FIR_TYPES, Name.identifier(this))

private val CLASS_ID_CONE_KOTLIN_TYPE = "ConeKotlinType".coneTypeClassId()

private val REQUIRED_BRANCHES = listOf(
    "ConeFlexibleType",
    "ConeDefinitelyNotNullType",
    "ConeCapturedType",
    "ConeIntersectionType",
).map { it.coneTypeClassId() }

private val CLASS_ID_CLASS_LIKE_TYPE = "ConeClassLikeType".coneTypeClassId()
private val CLASS_ID_TYPE_PARAMETER_TYPE = "ConeTypeParameterType".coneTypeClassId()
private val CLASS_ID_TYPE_LOOKUP_TAP_BASED = "ConeLookupTagBasedType".coneTypeClassId()

private fun KaSession.buildMissingTypeList(): MutableList<KaType> {
    return mutableListOf<KaType>().apply {
        REQUIRED_BRANCHES.mapTo(this) { classId -> buildClassType(classId) }
        val typeParameterType = buildClassType(CLASS_ID_TYPE_PARAMETER_TYPE)
        if (typeParameterType.symbol != null) {
            add(typeParameterType)
            add(buildClassType(CLASS_ID_CLASS_LIKE_TYPE))
        } else {
            add(buildClassType(CLASS_ID_TYPE_LOOKUP_TAP_BASED))
        }
    }
}

class SuspiciousWhenOverConeKotlinTypeInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : KtVisitorVoid() {
            override fun visitWhenExpression(expression: KtWhenExpression) {
                val elseExpression = expression.elseExpression ?: return
                val subjectExpression = (expression.subjectExpression)?.let {
                    if (it is KtProperty) it.initializer else it
                } ?: return
                if (expression.entries.none { entry -> entry.conditions.any { it is KtWhenConditionIsPattern } }) return

                analyze(expression) {
                    val subjectType = subjectExpression.expressionType
                    if (subjectType?.isSubtypeOf(CLASS_ID_CONE_KOTLIN_TYPE) != true) return@analyze
                    if (elseExpression !is KtReturnExpression && elseExpression.expressionType?.isNothingType == true) return@analyze

                    val missingBranches = buildMissingTypeList()
                    missingBranches.removeIf { !it.isSubtypeOf(subjectType) }

                    for (entry in expression.entries) {
                        for (condition in entry.conditions) {
                            if (condition is KtWhenConditionIsPattern && !condition.isNegated) {
                                val type = condition.typeReference?.type ?: continue
                                missingBranches.removeIf { it.isSubtypeOf(type) }
                            }
                        }
                    }

                    if (missingBranches.isNotEmpty()) {
                        @OptIn(KaExperimentalApi::class)
                        val renderedMissing = missingBranches.joinToString {
                            it.render(
                                KaTypeRendererForSource.WITH_SHORT_NAMES,
                                Variance.INVARIANT
                            )
                        }

                        holder.registerProblem(
                            subjectExpression,
                            "When over 'ConeKotlinType' doesn't handle [$renderedMissing]."
                        )
                    }
                }
            }
        }
    }
}
