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
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiTypes
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.awt.RelativePoint
import com.siyeh.ig.junit.JUnitCommonClassNames.ORG_JUNIT_JUPITER_API_TEST as JUNIT_TEST
import com.siyeh.ig.junit.JUnitCommonClassNames.ORG_JUNIT_JUPITER_API_TEST_FACTORY as JUNIT_TEST_FACTORY
import org.jetbrains.kotlin.asJava.elements.KtLightElement
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

private val nightlyIcon = IconLoader.getIcon(
    "/icons/test_federation_nightly.svg",
    TestFederationLineMarkerProvider::class.java,
)
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
        if (!isDeclarationNameIdentifier(element)) return null
        if (!TestDataPathsConfiguration.getInstance(element.project).testFederationGutterMarkersEnabled) return null
        val schedule = when (val declaration = element.parent) {
            is KtNamedDeclaration -> getTestFederationMarkerSchedule(declaration)
            is PsiMember -> getTestFederationMarkerSchedule(declaration)
            else -> null
        } ?: return null
        // Nightly tests are skipped before merging, even when they are also smoke or contract tests.
        val icon = when {
            schedule.nightly -> nightlyIcon
            schedule.smoke -> smokeIcon
            else -> contractIcon
        }
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

/**
 * Markers are anchored on the name identifier of a Kotlin class/function or a Java class/method.
 */
private fun isDeclarationNameIdentifier(element: PsiElement): Boolean = when (val declaration = element.parent) {
    is KtClass -> declaration.nameIdentifier === element
    is KtNamedFunction -> declaration.nameIdentifier === element
    is KtLightElement<*, *> -> false
    is PsiClass -> element is PsiIdentifier && declaration.nameIdentifier === element
    is PsiMethod -> element is PsiIdentifier && declaration.nameIdentifier === element
    else -> false
}

internal fun formatTestFederationSchedule(schedule: TestFederationSchedule): List<String> = buildList {
    if (schedule.nightly) {
        add(MyBundle.message("test.federation.schedule.nightly"))
    }
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
    getTestFederationDeclarationSchedule(declaration)?.effective

internal fun getTestFederationMarkerSchedule(member: PsiMember): TestFederationSchedule? =
    getTestFederationDeclarationSchedule(member)?.effective

/**
 * [own] is declared on the test function, or on the test class and its supertypes;
 * [inherited] comes from the containing test class, or from the enclosing classes of a `@Nested` class.
 */
internal class TestFederationDeclarationSchedule(
    val own: TestFederationSchedule,
    val inherited: TestFederationSchedule,
) {
    val effective: TestFederationSchedule = own + inherited
}

internal fun getTestFederationDeclarationSchedule(declaration: KtNamedDeclaration): TestFederationDeclarationSchedule? =
    CachedValuesManager.getCachedValue(declaration) {
        CachedValueProvider.Result.create(
            computeTestFederationDeclarationSchedule(declaration),
            PsiModificationTracker.MODIFICATION_COUNT,
            ProjectRootModificationTracker.getInstance(declaration.project),
        )
    }

/**
 * Java [PsiClass] / [PsiMethod] counterpart of the Kotlin overload above.
 */
internal fun getTestFederationDeclarationSchedule(member: PsiMember): TestFederationDeclarationSchedule? =
    CachedValuesManager.getCachedValue(member) {
        CachedValueProvider.Result.create(
            computeJavaDeclarationSchedule(member),
            PsiModificationTracker.MODIFICATION_COUNT,
            ProjectRootModificationTracker.getInstance(member.project),
        )
    }

private fun computeTestFederationDeclarationSchedule(
    declaration: KtNamedDeclaration,
): TestFederationDeclarationSchedule? = when (declaration) {
    is KtNamedFunction -> {
        if (declaration.annotationEntries.isEmpty() || declaration.parent !is KtClassBody) return null
        val method = declaration.toLightMethods().firstOrNull() ?: return null
        computeMethodSchedule(method)
    }

    is KtClass -> {
        val testClass = declaration.toLightClass() ?: return null
        computeClassSchedule(testClass)
    }

    else -> null
}

private fun computeJavaDeclarationSchedule(member: PsiMember): TestFederationDeclarationSchedule? = when (member) {
    is PsiMethod -> {
        if (member.modifierList.annotations.isEmpty()) return null
        computeMethodSchedule(member)
    }

    is PsiClass -> computeClassSchedule(member)

    else -> null
}

private fun computeMethodSchedule(method: PsiMethod): TestFederationDeclarationSchedule? {
    val testClass = method.containingClass ?: return null
    if (!testClass.isJupiterTestContainer() || !testClass.hasJupiterFramework()) return null
    if (!method.isJupiterTestMethod()) return null
    // Tests also get a marker when their schedule comes only from the class.
    val schedule = TestFederationDeclarationSchedule(
        method.directTestFederationSchedule(),
        testClass.cachedTestFederationSchedule(),
    )
    return schedule.takeUnless { it.effective.isEmpty() }
}

private fun computeClassSchedule(testClass: PsiClass): TestFederationDeclarationSchedule? {
    if (!testClass.isJupiterTestClass()) return null
    val schedule = TestFederationDeclarationSchedule(
        testClass.ownTestFederationSchedule(),
        testClass.enclosingTestFederationSchedule(),
    )
    return schedule.takeUnless { it.effective.isEmpty() }
}

// Every test method needs its class schedule, so compute it once per class.
private fun PsiClass.cachedTestFederationSchedule(): TestFederationSchedule =
    CachedValuesManager.getCachedValue(this) {
        CachedValueProvider.Result.create(
            testFederationSchedule(),
            PsiModificationTracker.MODIFICATION_COUNT,
            ProjectRootModificationTracker.getInstance(project),
        )
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
