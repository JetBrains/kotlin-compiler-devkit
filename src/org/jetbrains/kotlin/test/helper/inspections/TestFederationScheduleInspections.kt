package org.jetbrains.kotlin.test.helper.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.kotlin.test.helper.MyBundle
import org.jetbrains.kotlin.test.helper.codeinsight.TestFederationDeclarationSchedule
import org.jetbrains.kotlin.test.helper.codeinsight.getTestFederationDeclarationSchedule
import org.jetbrains.kotlin.test.helper.codeinsight.hasNightlyConflict
import org.jetbrains.kotlin.test.helper.codeinsight.hasRedundantContracts
import org.jetbrains.kotlin.test.helper.codeinsight.isEmpty
import org.jetbrains.kotlin.test.helper.codeinsight.tags

internal abstract class TestFederationScheduleInspection : LocalInspectionTool() {
    protected abstract fun problemMessage(schedule: TestFederationDeclarationSchedule): String?

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : KtVisitorVoid() {
            override fun visitNamedFunction(function: KtNamedFunction) = check(function)

            override fun visitClass(klass: KtClass) = check(klass)

            private fun check(declaration: KtNamedDeclaration) {
                val nameIdentifier = declaration.nameIdentifier ?: return
                val schedule = getTestFederationDeclarationSchedule(declaration) ?: return
                // Conflicts that come only from the containing class are reported on that class.
                if (schedule.own.isEmpty()) return
                val message = problemMessage(schedule) ?: return
                holder.registerProblem(nameIdentifier, message)
            }
        }
}

internal class TestFederationRedundantContractInspection : TestFederationScheduleInspection() {
    override fun problemMessage(schedule: TestFederationDeclarationSchedule): String? {
        val own = schedule.own
        if (!schedule.effective.hasRedundantContracts() || (!own.smoke && own.contractDomains.isEmpty())) return null
        val contracts = schedule.effective.copy(smoke = false, nightly = false).tags.joinToString()
        return MyBundle.message("inspection.test.federation.redundant.contract.problem", contracts)
    }
}

internal class TestFederationNightlyConflictInspection : TestFederationScheduleInspection() {
    override fun problemMessage(schedule: TestFederationDeclarationSchedule): String? {
        if (!schedule.effective.hasNightlyConflict()) return null
        val skipped = schedule.effective.copy(nightly = false).tags.joinToString()
        return MyBundle.message("inspection.test.federation.nightly.conflict.problem", skipped)
    }
}
