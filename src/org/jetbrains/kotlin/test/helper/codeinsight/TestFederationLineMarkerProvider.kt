package org.jetbrains.kotlin.test.helper.codeinsight

import com.intellij.codeInsight.TestFrameworks
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiTypes
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.awt.RelativePoint
import com.siyeh.ig.junit.JUnitCommonClassNames.ORG_JUNIT_JUPITER_API_TEST as JUNIT_TEST
import com.siyeh.ig.junit.JUnitCommonClassNames.ORG_JUNIT_JUPITER_API_TEST_FACTORY as JUNIT_TEST_FACTORY
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.test.helper.MyBundle
import org.jetbrains.kotlin.test.helper.TestDataPathsConfiguration
import java.awt.event.MouseEvent

private const val JUNIT5_FRAMEWORK_NAME = "JUnit5"
private const val JUNIT_TEST_TEMPLATE = "org.junit.jupiter.api.TestTemplate"

private val smokeIcon = IconLoader.getIcon(
    "/icons/test_federation_smoke.svg",
    TestFederationLineMarkerProvider::class.java,
)
private val contractIcon = IconLoader.getIcon(
    "/icons/test_federation_contract.svg",
    TestFederationLineMarkerProvider::class.java,
)

internal class TestFederationLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val declaration = element.parent as? KtNamedDeclaration ?: return null
        if (declaration !is KtClass && declaration !is KtNamedFunction) return null
        if (declaration.nameIdentifier !== element) return null
        if (!TestDataPathsConfiguration.getInstance(element.project).testFederationGutterMarkersEnabled) return null
        val schedule = getTestFederationMarkerSchedule(declaration) ?: return null
        val icon = if (schedule.smoke) smokeIcon else contractIcon
        val tooltip = MyBundle.message("test.federation.marker.tooltip")
        return object : LineMarkerInfo<PsiElement>(
            element,
            element.textRange,
            icon,
            { tooltip },
            { event, _ -> showSchedulePopup(event, schedule) },
            GutterIconRenderer.Alignment.LEFT,
            { tooltip },
        ) {
            override fun createGutterRenderer(): GutterIconRenderer =
                object : LineMarkerGutterIconRenderer<PsiElement>(this) {
                    override fun getPopupMenuActions(): ActionGroup = DefaultActionGroup(
                        object : AnAction(MyBundle.message("test.federation.marker.disable")) {
                            override fun actionPerformed(event: AnActionEvent) {
                                val project = element.project
                                TestDataPathsConfiguration.getInstance(project).testFederationGutterMarkersEnabled =
                                    false
                                DaemonCodeAnalyzer.getInstance(project)
                                    .restart("test-federation gutter markers disabled from gutter")
                            }
                        },
                    )
                }
        }
    }
}

internal fun formatTestFederationSchedule(schedule: TestFederationSchedule): List<String> = buildList {
    if (schedule.smoke) {
        add(MyBundle.message("test.federation.schedule.smoke"))
    }
    schedule.contractDomains.sorted().forEach { domain ->
        add(MyBundle.message("test.federation.schedule.contract", domain))
    }
}

private fun showSchedulePopup(event: MouseEvent, schedule: TestFederationSchedule) {
    val details = formatTestFederationSchedule(schedule)
        .joinToString("<br>") { StringUtil.escapeXmlEntities(it) }
    val content =
        "<b>${StringUtil.escapeXmlEntities(MyBundle.message("test.federation.schedule.title"))}</b><br>$details"
    JBPopupFactory.getInstance()
        .createHtmlTextBalloonBuilder(content, MessageType.INFO, null)
        .createBalloon()
        .show(RelativePoint(event), Balloon.Position.above)
}

internal fun getTestFederationMarkerSchedule(declaration: KtNamedDeclaration): TestFederationSchedule? =
    CachedValuesManager.getCachedValue(declaration) {
        CachedValueProvider.Result.create(
            computeTestFederationMarkerSchedule(declaration),
            PsiModificationTracker.MODIFICATION_COUNT,
            ProjectRootModificationTracker.getInstance(declaration.project),
        )
    }

private fun computeTestFederationMarkerSchedule(declaration: KtNamedDeclaration): TestFederationSchedule? {
    val schedule = when (declaration) {
        is KtNamedFunction -> {
            if (declaration.annotationEntries.isEmpty() || declaration.parent !is KtClassBody) return null
            val method = declaration.toLightMethods().firstOrNull() ?: return null
            val testClass = method.containingClass ?: return null
            if (!testClass.isJupiterTestContainer() || !testClass.hasJupiterFramework()) return null
            if (!method.isJupiterTestMethod()) return null
            val direct = method.directTestFederationSchedule()
            // Class-only schedules already have a class marker; method popups still include them.
            if (direct.isEmpty()) return null
            direct + testClass.testFederationSchedule()
        }

        is KtClass -> {
            val testClass = declaration.toLightClass() ?: return null
            if (!testClass.isJupiterTestClass()) return null
            testClass.testFederationSchedule()
        }

        else -> return null
    }
    return schedule.takeUnless { it.isEmpty() }
}

// This public API already caches framework discovery, including Kotlin support.
private fun PsiClass.hasJupiterFramework(): Boolean =
    TestFrameworks.detectApplicableFrameworks(this).any { it.name == JUNIT5_FRAMEWORK_NAME }

private fun PsiClass.isJupiterTestClass(): Boolean =
    isJupiterTestContainer() && hasJupiterFramework() &&
        (allMethods.any(PsiMethod::isJupiterTestMethod) ||
            innerClasses.any { it.isJupiterNestedClass() && it.isJupiterTestClass() })

private fun PsiClass.isJupiterTestContainer(): Boolean {
    if (isInterface || isAnnotationType || isEnum || qualifiedName == null ||
        hasModifierProperty(PsiModifier.PRIVATE) || hasModifierProperty(PsiModifier.ABSTRACT)
    ) return false
    val outer = containingClass ?: return true
    return hasModifierProperty(PsiModifier.STATIC) ||
        (isJupiterNestedClass() && outer.isJupiterTestContainer())
}

// Framework discovery also accepts incomplete code and kotlin.test; confirm an executable Jupiter method.
private fun PsiMethod.isJupiterTestMethod(): Boolean =
    !isConstructor && !hasModifierProperty(PsiModifier.PRIVATE) &&
        !hasModifierProperty(PsiModifier.STATIC) && !hasModifierProperty(PsiModifier.ABSTRACT) &&
        when (returnType) {
            null -> false
            PsiTypes.voidType() -> hasRuntimeAnnotation(JUNIT_TEST, JUNIT_TEST_TEMPLATE)
            else -> hasRuntimeAnnotation(JUNIT_TEST_FACTORY)
        }
