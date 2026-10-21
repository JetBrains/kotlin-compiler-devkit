package org.jetbrains.kotlin.test.helper.codeinsight

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ui.components.JBCheckBox
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.test.helper.TestDataPathsConfiguration
import org.jetbrains.kotlin.test.helper.TestDataPathsConfigurable
import java.awt.Container

@Suppress("UnstableApiUsage")
class TestFederationLineMarkerProviderTest : BasePlatformTestCase() {
    override fun getProjectDescriptor() = federationProjectDescriptor

    private val provider = TestFederationLineMarkerProvider()

    fun testDirectAndMetaAnnotatedTestFunctionsReceiveMarkers() {
        val file = parse(
            """
            package sample

            import org.jetbrains.kotlin.testFederation.MustRunAlways
            import org.junit.jupiter.api.Tag
            import org.junit.jupiter.api.Test

            @Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.FUNCTION)
            @Test
            @MustRunAlways
            annotation class SmokeTest

            class SampleTest {
                @Test
                @Tag("contract:Js")
                fun directTest() {}

                @SmokeTest
                @Tag("contract:Native")
                @Tag("contract:Jvm")
                fun composedTest() {}

                @Tag("smoke")
                fun scheduledButNotATest() {}

                @Test
                fun testWithoutSchedule() {}
            }
            """.trimIndent(),
        )

        val directMarker = marker(file.function("directTest"))
        assertMarkerIcon(directMarker, "/icons/test_federation_contract.svg")
        assertEquals("Test Federation: when this test runs", directMarker!!.lineMarkerTooltip)

        val composedMarker = marker(file.function("composedTest"))
        assertMarkerIcon(composedMarker, "/icons/test_federation_smoke.svg")
        assertEquals(
            listOf(
                "Smoke test: required regardless of what changed. Other test filters still apply.",
                "Contract test for Jvm: required when Jvm changes or when this test's own domain needs testing.",
                "Contract test for Native: required when Native changes or when this test's own domain needs testing.",
            ),
            formatTestFederationSchedule(getTestFederationSchedule(file.function("composedTest"))),
        )
        assertNull(marker(file.function("scheduledButNotATest")))
        assertNull(marker(file.function("testWithoutSchedule")))
    }

    fun testOnlyConcreteTestClassesReceiveClassMarkers() {
        val file = parse(
            """
            package sample

            import org.jetbrains.kotlin.testFederation.MustRunAlways
            import org.junit.jupiter.api.Tag
            import org.junit.jupiter.api.Test

            @MustRunAlways
            abstract class AbstractTestBase {
                @Test
                fun inheritedTest() {}
            }

            class ConcreteInheritedTest : AbstractTestBase()

            @Tag("contract:Jvm")
            abstract class AbstractTaggedTest {
                @Test
                fun test() {}
            }

            @Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS)
            @Tag("contract:Js")
            annotation class JsGradlePluginTests

            @Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.FUNCTION)
            @Test
            annotation class ComposedTest

            @JsGradlePluginTests
            @Tag("contract:Native")
            @MustRunAlways
            abstract class ComposedContractBase {
                @ComposedTest
                fun inheritedComposedTest() {}
            }

            class ConcreteComposedContractTest : ComposedContractBase()

            @Tag("contract:Jvm")
            class TaggedButNotTest

            class TestWithoutSchedule {
                @Test
                fun test() {}
            }
            """.trimIndent(),
        )

        assertNull(marker(file.ktClass("AbstractTestBase")))
        assertNotNull(marker(file.ktClass("ConcreteInheritedTest")))
        assertNull(marker(file.ktClass("AbstractTaggedTest")))
        assertNull(marker(file.ktClass("TaggedButNotTest")))
        assertNull(marker(file.ktClass("TestWithoutSchedule")))

        val composedClass = file.ktClass("ConcreteComposedContractTest")
        val composedClassMarker = marker(composedClass)
        assertMarkerIcon(composedClassMarker, "/icons/test_federation_smoke.svg")
        val classSchedule = getTestFederationSchedule(composedClass)
        assertEquals(TestFederationSchedule(smoke = true, contractDomains = setOf("Js", "Native")), classSchedule)
        assertEquals(
            listOf(
                "Smoke test: required regardless of what changed. Other test filters still apply.",
                "Contract test for Js: required when Js changes or when this test's own domain needs testing.",
                "Contract test for Native: required when Native changes or when this test's own domain needs testing.",
            ),
            formatTestFederationSchedule(classSchedule),
        )
    }

    fun testKotlinExtensionRegistrationCreatesSingleGutterForEachScheduledDeclaration() {
        addTestAnnotations()
        myFixture.configureByText(
            "RegisteredScheduleTest.kt",
            """
            package sample

            import org.junit.jupiter.api.Tag
            import org.junit.jupiter.api.Test

            @Tag("smoke")
            class RegisteredScheduleTest {
                @Test
                @Tag("contract:Js")
                fun scheduled() {}

                @Test
                fun withoutSchedule() {}
            }
            """.trimIndent(),
        )

        myFixture.doHighlighting()
        val federationGutters = myFixture.findAllGutters()
            .filter { it.tooltipText == "Test Federation: when this test runs" }

        // The test without its own tags is still scheduled by its smoke class.
        assertEquals(3, federationGutters.size)
        assertTrue(federationGutters.all { it.icon == icon("/icons/test_federation_smoke.svg") })

        val lineMarkers = DaemonCodeAnalyzerImpl.getLineMarkers(myFixture.editor.document, project)
            .filter { it.lineMarkerTooltip == "Test Federation: when this test runs" }
            .sortedBy { it.startOffset }
        assertEquals(3, lineMarkers.size)
        assertEquals(listOf(6, 9, 12), lineMarkers.map { myFixture.editor.document.getLineNumber(it.startOffset) })
        assertEquals(
            List(3) { icon("/icons/test_federation_smoke.svg") },
            lineMarkers.map { it.createGutterRenderer()!!.icon },
        )
    }

    fun testChangingSettingRefreshesMarkersInOpenEditor() {
        addTestAnnotations()
        myFixture.configureByText(
            "RefreshScheduleTest.kt",
            """
            package sample

            import org.junit.jupiter.api.Tag
            import org.junit.jupiter.api.Test

            @Tag("smoke")
            class RefreshScheduleTest {
                @Test
                @Tag("contract:Js")
                fun scheduled() {}
            }
            """.trimIndent(),
        )

        val configuration = TestDataPathsConfiguration.getInstance(project)
        val configurable = TestDataPathsConfigurable(project)
        val checkbox = configurable.createComponent().findCheckBox("Show Test Federation icons in the gutter")!!
        assertTrue(checkbox.isSelected)

        myFixture.doHighlighting()
        assertEquals(2, federationLineMarkers().size)

        checkbox.isSelected = false
        assertTrue(configurable.isModified)
        configurable.apply()
        assertFalse(configuration.testFederationGutterMarkersEnabled)
        myFixture.doHighlighting()
        assertTrue(federationLineMarkers().isEmpty())

        checkbox.isSelected = true
        configurable.apply()
        assertTrue(configuration.testFederationGutterMarkersEnabled)
        myFixture.doHighlighting()
        assertEquals(2, federationLineMarkers().size)
        configurable.disposeUIResources()
    }

    fun testGutterActionDisablesMarkersAndSettingsCanReenableThem() {
        addTestAnnotations()
        val file = myFixture.configureByText(
            "GutterActionScheduleTest.kt",
            """
            package sample

            import org.junit.jupiter.api.Tag
            import org.junit.jupiter.api.Test

            @Tag("smoke")
            class GutterActionScheduleTest {
                @Test
                @Tag("contract:Js")
                fun scheduled() {}
            }
            """.trimIndent(),
        ) as KtFile

        myFixture.doHighlighting()
        assertEquals(2, federationLineMarkers().size)

        val marker = provider.getLineMarkerInfo(file.function("scheduled").nameIdentifier!!)!!
        val actionGroup = marker.createGutterRenderer()!!.popupMenuActions as DefaultActionGroup
        val disableAction = actionGroup.childActionsOrStubs.single()
        assertEquals("Hide Test Federation icons in this project", disableAction.templatePresentation.text)
        ActionManager.getInstance().tryToExecute(disableAction, null, null, ActionPlaces.UNKNOWN, true)

        val configuration = TestDataPathsConfiguration.getInstance(project)
        assertFalse(configuration.testFederationGutterMarkersEnabled)
        myFixture.doHighlighting()
        assertTrue(federationLineMarkers().isEmpty())

        val configurable = TestDataPathsConfigurable(project)
        val checkbox = configurable.createComponent().findCheckBox("Show Test Federation icons in the gutter")!!
        assertFalse(checkbox.isSelected)
        checkbox.isSelected = true
        configurable.apply()
        assertTrue(configuration.testFederationGutterMarkersEnabled)
        myFixture.doHighlighting()
        assertEquals(2, federationLineMarkers().size)
        configurable.disposeUIResources()
    }

    fun testOnlyJupiterMethodsInValidClassesReceiveMarkers() {
        myFixture.addFileToProject("org/junit/Test.java", "package org.junit; public @interface Test {}")
        myFixture.addFileToProject("kotlin/test/Test.kt", "package kotlin.test; annotation class Test")
        myFixture.addFileToProject(
            "org/junit/jupiter/api/TestFactory.java",
            """
            package org.junit.jupiter.api;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
            @org.junit.platform.commons.annotation.Testable
            public @interface TestFactory {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "org/junit/jupiter/api/DynamicNode.java",
            "package org.junit.jupiter.api; public abstract class DynamicNode {}",
        )
        val file = parse(
            """
            package sample
            import org.junit.jupiter.api.Tag
            import org.junit.jupiter.api.Test

            @Tag("smoke")
            class MixedTests {
                @org.junit.Test @Tag("smoke") fun vintage() {}
                @kotlin.test.Test @Tag("smoke") fun unknownBackend() {}
                @Test @Tag("smoke") private fun privateTest() {}
                @Test @Tag("smoke") fun nonVoidTest(): Int = 42
                @org.junit.jupiter.api.TestFactory @Tag("smoke") fun voidFactory() {}
                @org.junit.jupiter.api.TestFactory @Tag("smoke")
                fun factory(): java.util.stream.Stream<org.junit.jupiter.api.DynamicNode> = java.util.stream.Stream.empty()
                @Test @Tag("smoke") fun jupiter() {}
            }
            class Enclosing {
                @Tag("smoke")
                private class PrivateTests {
                    @Test @Tag("smoke") fun inPrivateClass() {}
                }
            }
            @Tag("smoke")
            abstract class AbstractTests {
                @Test @Tag("smoke") abstract fun abstractTest()
            }
            @Tag("smoke")
            class VintageOnly {
                @org.junit.Test fun vintageOnly() {}
            }
            @Tag("smoke")
            class InvalidOnly {
                @Test private fun invalidOnly() {}
            }
            @Test @Tag("smoke") fun topLevel() {}
            """.trimIndent(),
        )

        for (name in listOf("vintage", "unknownBackend", "privateTest", "nonVoidTest", "voidFactory", "inPrivateClass", "abstractTest", "topLevel")) {
            assertNull(name, marker(file.function(name)))
        }
        for (name in listOf("PrivateTests", "AbstractTests", "VintageOnly", "InvalidOnly")) {
            assertNull(name, marker(file.ktClass(name)))
        }
        assertNotNull(marker(file.function("jupiter")))
        assertNotNull(marker(file.function("factory")))
        assertNotNull(marker(file.ktClass("MixedTests")))
    }

    fun testEligibilityCacheInvalidatesWhenBaseClassChanges() {
        addTestAnnotations()
        val base = myFixture.addFileToProject(
            "sample/Base.kt",
            """
            package sample
            import org.junit.jupiter.api.Tag
            import org.junit.jupiter.api.Test
            @Tag("smoke")
            open class Base {
                fun inherited() {}
            }
            """.trimIndent(),
        )
        val file = myFixture.addFileToProject("sample/Derived.kt", "package sample; class Derived : Base()") as KtFile
        repeat(2) { assertNull(marker(file.ktClass("Derived"))) }

        val document = PsiDocumentManager.getInstance(project).getDocument(base)!!
        val original = document.text
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(0, document.textLength, original.replace("fun inherited", "@Test fun inherited"))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        repeat(2) { assertNotNull(marker(file.ktClass("Derived"))) }

        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(0, document.textLength, original)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        assertNull(marker(file.ktClass("Derived")))
    }

    fun testMarkerScheduleCacheIsReusedAndInvalidatesAfterMetaAnnotationEdit() {
        val file = parse(
            """
            package sample
            import org.jetbrains.kotlin.testFederation.MustRunAlways
            import org.junit.jupiter.api.Test
            class Sample { @Test @MustRunAlways fun scheduled() {} }
            """.trimIndent(),
        )
        val function = file.function("scheduled")
        val schedule = getTestFederationMarkerSchedule(function)!!
        assertTrue(schedule.smoke)
        assertSame(schedule, getTestFederationMarkerSchedule(function))
        val annotations = myFixture.findFileInTempDir("org/jetbrains/kotlin/testFederation/annotations.kt")!!
        val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(annotations)!!
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)!!
        val original = document.text
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(0, document.textLength, original.replace("Tag(\"smoke\")", "Tag(\"contract:Js\")"))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        assertEquals(TestFederationSchedule(contractDomains = setOf("Js")), getTestFederationMarkerSchedule(function))
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(0, document.textLength, original.replace("Tag(\"smoke\")", "Tag(\"unrelated\")"))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        repeat(2) { assertNull(getTestFederationMarkerSchedule(function)) }
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(0, document.textLength, original)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        assertEquals(schedule, getTestFederationMarkerSchedule(function))
    }

    fun testNestedContainersAndTheirTestsReceiveEffectiveSchedules() {
        myFixture.addFileToProject(
            "org/junit/jupiter/api/Nested.java",
            """
            package org.junit.jupiter.api;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
            public @interface Nested {}
            """.trimIndent(),
        )
        val file = parse(
            """
            package sample
            import org.junit.jupiter.api.*
            @Tag("smoke")
            class Outer {
                @Nested inner class Inner {
                    @Test @Tag("contract:Js") fun scheduled() {}
                    @Test fun classScheduleOnly() {}
                }
                inner class NotNested {
                    @Test @Tag("smoke") fun invalidInnerTest() {}
                }
            }
            """.trimIndent(),
        )
        assertNotNull(marker(file.ktClass("Outer")))
        assertNotNull(marker(file.ktClass("Inner")))
        assertMarkerIcon(marker(file.function("scheduled")), "/icons/test_federation_smoke.svg")
        assertEquals(
            TestFederationSchedule(smoke = true, contractDomains = setOf("Js")),
            getTestFederationMarkerSchedule(file.function("scheduled")),
        )
        val classScheduleOnly = file.function("classScheduleOnly")
        assertMarkerIcon(marker(classScheduleOnly), "/icons/test_federation_smoke.svg")
        assertEquals(TestFederationSchedule(smoke = true), getTestFederationMarkerSchedule(classScheduleOnly))
        assertNull(marker(file.ktClass("NotNested")))
        assertNull(marker(file.function("invalidInnerTest")))
    }

    fun testNightlyTestFunctionsAndClassesReceiveNightlyMarkers() {
        val file = parse(
            """
            package sample

            import org.jetbrains.kotlin.testFederation.MustRunAlways
            import org.jetbrains.kotlin.testFederation.NightlyTest
            import org.junit.jupiter.api.Tag
            import org.junit.jupiter.api.Test

            @Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS)
            @NightlyTest
            annotation class SlowNightly

            class FunctionLevelTest {
                @Test
                @NightlyTest
                fun nightlyFunction() {}

                @Test
                @Tag("nightly")
                fun directlyTaggedFunction() {}

                @NightlyTest
                fun nightlyButNotATest() {}
            }

            @NightlyTest
            class ClassLevelTest {
                @Test
                fun classScheduleOnly() {}

                fun helper() {}
            }

            @MustRunAlways
            class SmokeClassLevelTest {
                @Test
                fun smokeClassScheduleOnly() {}
            }

            @SlowNightly
            abstract class AbstractNightlyBase {
                @Test
                fun inheritedTest() {}
            }

            class ConcreteNightlyTest : AbstractNightlyBase()
            """.trimIndent(),
        )

        val nightlyFunction = file.function("nightlyFunction")
        assertMarkerIcon(marker(nightlyFunction), "/icons/test_federation_nightly.svg")
        assertEquals(TestFederationSchedule(nightly = true), getTestFederationMarkerSchedule(nightlyFunction))
        assertEquals(
            listOf(
                "Nightly test: skipped in remote and safe-merge runs; runs in nightly builds and local Gradle runs " +
                    "by default. Not required for merging. Other test filters still apply.",
            ),
            formatTestFederationSchedule(getTestFederationSchedule(nightlyFunction)),
        )
        assertMarkerIcon(marker(file.function("directlyTaggedFunction")), "/icons/test_federation_nightly.svg")
        assertNull(marker(file.function("nightlyButNotATest")))
        assertNull(marker(file.ktClass("FunctionLevelTest")))

        assertMarkerIcon(marker(file.ktClass("ClassLevelTest")), "/icons/test_federation_nightly.svg")
        // Tests in a scheduled class get the class icon too.
        val classScheduleOnly = file.function("classScheduleOnly")
        assertMarkerIcon(marker(classScheduleOnly), "/icons/test_federation_nightly.svg")
        assertEquals(TestFederationSchedule(nightly = true), getTestFederationMarkerSchedule(classScheduleOnly))
        assertNull(marker(file.function("helper")))
        assertMarkerIcon(marker(file.ktClass("SmokeClassLevelTest")), "/icons/test_federation_smoke.svg")
        val smokeClassScheduleOnly = file.function("smokeClassScheduleOnly")
        assertMarkerIcon(marker(smokeClassScheduleOnly), "/icons/test_federation_smoke.svg")
        assertEquals(TestFederationSchedule(smoke = true), getTestFederationMarkerSchedule(smokeClassScheduleOnly))

        assertNull(marker(file.ktClass("AbstractNightlyBase")))
        val concreteClass = file.ktClass("ConcreteNightlyTest")
        assertMarkerIcon(marker(concreteClass), "/icons/test_federation_nightly.svg")
        assertEquals(TestFederationSchedule(nightly = true), getTestFederationMarkerSchedule(concreteClass))
    }

    fun testNightlyIconTakesPriorityAndPopupListsNightlyFirst() {
        val file = parse(
            """
            package sample

            import org.jetbrains.kotlin.testFederation.MustRunAlways
            import org.jetbrains.kotlin.testFederation.NightlyTest
            import org.junit.jupiter.api.Tag
            import org.junit.jupiter.api.Test

            @NightlyTest
            class NightlyClassTest {
                @Test
                @MustRunAlways
                @Tag("contract:Native")
                @Tag("contract:Js")
                fun everything() {}

                @Test
                @Tag("contract:Jvm")
                fun contractInNightlyClass() {}
            }

            @MustRunAlways
            class SmokeClassTest {
                @Test
                @NightlyTest
                fun nightlyInSmokeClass() {}

                @Test
                @Tag("contract:Js")
                fun contractInSmokeClass() {}
            }
            """.trimIndent(),
        )

        val nightlyLine = "Nightly test: skipped in remote and safe-merge runs; runs in nightly builds and local Gradle " +
            "runs by default. Not required for merging. Other test filters still apply."
        val smokeLine = "Smoke test: required regardless of what changed. Other test filters still apply."

        val everything = file.function("everything")
        assertMarkerIcon(marker(everything), "/icons/test_federation_nightly.svg")
        val everythingSchedule = getTestFederationMarkerSchedule(everything)!!
        assertEquals(
            TestFederationSchedule(smoke = true, contractDomains = setOf("Js", "Native"), nightly = true),
            everythingSchedule,
        )
        assertEquals(
            listOf(
                nightlyLine,
                smokeLine,
                "Contract test for Js: required when Js changes or when this test's own domain needs testing.",
                "Contract test for Native: required when Native changes or when this test's own domain needs testing.",
            ),
            formatTestFederationSchedule(everythingSchedule),
        )

        val contractInNightlyClass = file.function("contractInNightlyClass")
        assertMarkerIcon(marker(contractInNightlyClass), "/icons/test_federation_nightly.svg")
        assertEquals(
            listOf(
                nightlyLine,
                "Contract test for Jvm: required when Jvm changes or when this test's own domain needs testing.",
            ),
            formatTestFederationSchedule(getTestFederationMarkerSchedule(contractInNightlyClass)!!),
        )
        assertMarkerIcon(marker(file.ktClass("NightlyClassTest")), "/icons/test_federation_nightly.svg")

        val nightlyInSmokeClass = file.function("nightlyInSmokeClass")
        assertMarkerIcon(marker(nightlyInSmokeClass), "/icons/test_federation_nightly.svg")
        assertEquals(
            listOf(nightlyLine, smokeLine),
            formatTestFederationSchedule(getTestFederationMarkerSchedule(nightlyInSmokeClass)!!),
        )
        assertMarkerIcon(marker(file.function("contractInSmokeClass")), "/icons/test_federation_smoke.svg")
        assertMarkerIcon(marker(file.ktClass("SmokeClassTest")), "/icons/test_federation_smoke.svg")
    }

    fun testHidingIconsSuppressesNightlyMarkersUntilReenabled() {
        addTestAnnotations()
        val file = myFixture.configureByText(
            "NightlyScheduleTest.kt",
            """
            package sample

            import org.jetbrains.kotlin.testFederation.NightlyTest
            import org.junit.jupiter.api.Tag
            import org.junit.jupiter.api.Test

            @NightlyTest
            class NightlyScheduleTest {
                @Test
                @Tag("smoke")
                fun scheduled() {}

                @Test
                fun classScheduleOnly() {}
            }
            """.trimIndent(),
        ) as KtFile

        myFixture.doHighlighting()
        val lineMarkers = federationLineMarkers().sortedBy { it.startOffset }
        assertEquals(listOf(7, 10, 13), lineMarkers.map { myFixture.editor.document.getLineNumber(it.startOffset) })
        assertEquals(
            List(3) { icon("/icons/test_federation_nightly.svg") },
            lineMarkers.map { it.createGutterRenderer()!!.icon },
        )

        val classMarker = marker(file.ktClass("NightlyScheduleTest"))!!
        val actionGroup = classMarker.createGutterRenderer()!!.popupMenuActions as DefaultActionGroup
        val disableAction = actionGroup.childActionsOrStubs.single()
        ActionManager.getInstance().tryToExecute(disableAction, null, null, ActionPlaces.UNKNOWN, true)

        val configuration = TestDataPathsConfiguration.getInstance(project)
        assertFalse(configuration.testFederationGutterMarkersEnabled)
        myFixture.doHighlighting()
        assertTrue(federationLineMarkers().isEmpty())
        assertNull(marker(file.function("scheduled")))

        val configurable = TestDataPathsConfigurable(project)
        val checkbox = configurable.createComponent().findCheckBox("Show Test Federation icons in the gutter")!!
        assertFalse(checkbox.isSelected)
        checkbox.isSelected = true
        configurable.apply()
        assertTrue(configuration.testFederationGutterMarkersEnabled)
        myFixture.doHighlighting()
        assertEquals(3, federationLineMarkers().size)
        configurable.disposeUIResources()
    }

    fun testJavaSubclassOfKotlinContractBaseReceivesMarkers() {
        addNestedAnnotation()
        myFixture.addFileToProject(
            "sample/AbstractAnalysisApiBasedTest.kt",
            """
            package sample
            import org.junit.jupiter.api.Tag

            @Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS)
            @Tag("contract:AnalysisApi")
            annotation class MustRunOnChangesInAnalysisApi

            @MustRunOnChangesInAnalysisApi
            abstract class AbstractAnalysisApiBasedTest
            """.trimIndent(),
        )
        val file = parseJava(
            "SwiftExportInIdeTestGenerated",
            """
            package sample;

            import org.junit.jupiter.api.Nested;
            import org.junit.jupiter.api.Test;

            public class SwiftExportInIdeTestGenerated extends AbstractAnalysisApiBasedTest {
                @Test
                public void testFirst() {}

                @Test
                public void testSecond() {}

                public void helper() {}

                @Nested
                public class NestedGroup {
                    @Test
                    public void testNested() {}
                }
            }
            """.trimIndent(),
        )

        val contractSchedule = TestFederationSchedule(contractDomains = setOf("AnalysisApi"))
        val testClass = file.psiClass("SwiftExportInIdeTestGenerated")
        assertMarkerIcon(marker(testClass), "/icons/test_federation_contract.svg")
        assertEquals(contractSchedule, getTestFederationMarkerSchedule(testClass))
        for (name in listOf("testFirst", "testSecond", "testNested")) {
            val method = file.psiMethod(name)
            assertMarkerIcon(marker(method), "/icons/test_federation_contract.svg")
            assertEquals(name, contractSchedule, getTestFederationMarkerSchedule(method))
        }
        assertEquals(
            listOf(
                "Contract test for AnalysisApi: required when AnalysisApi changes or when this test's own domain " +
                    "needs testing.",
            ),
            formatTestFederationSchedule(getTestFederationMarkerSchedule(file.psiMethod("testFirst"))!!),
        )

        val nestedClass = file.psiClass("NestedGroup")
        assertMarkerIcon(marker(nestedClass), "/icons/test_federation_contract.svg")
        assertEquals(contractSchedule, getTestFederationMarkerSchedule(nestedClass))
        assertNull(marker(file.psiMethod("helper")))
    }

    fun testJavaTestsWithOwnSchedulesAndInvalidJavaDeclarations() {
        val file = parseJava(
            "JavaScheduleTest",
            """
            package sample;

            import org.jetbrains.kotlin.testFederation.MustRunAlways;
            import org.junit.jupiter.api.Tag;
            import org.junit.jupiter.api.Test;

            public class JavaScheduleTest {
                @Test
                @Tag("smoke")
                public void smokeTest() {}

                @Test
                @MustRunAlways
                public void metaAnnotatedSmokeTest() {}

                @Test
                @Tag("contract:Js")
                public void contractTest() {}

                @Tag("smoke")
                public void scheduledButNotATest() {}

                @Test
                public void testWithoutSchedule() {}

                @Test
                @Tag("smoke")
                private void privateTest() {}

                @Tag("smoke")
                public static abstract class AbstractNested {
                    @Test
                    public void inAbstractClass() {}
                }

                @Tag("smoke")
                private static class PrivateNested {
                    @Test
                    public void inPrivateClass() {}
                }

                @Tag("smoke")
                public interface TaggedInterface {
                    @Test
                    default void inInterface() {}
                }
            }

            class JavaTestWithoutSchedule {
                @Test
                public void test() {}
            }
            """.trimIndent(),
        )

        assertMarkerIcon(marker(file.psiMethod("smokeTest")), "/icons/test_federation_smoke.svg")
        assertMarkerIcon(marker(file.psiMethod("metaAnnotatedSmokeTest")), "/icons/test_federation_smoke.svg")
        assertEquals(
            TestFederationSchedule(smoke = true),
            getTestFederationMarkerSchedule(file.psiMethod("metaAnnotatedSmokeTest")),
        )
        assertMarkerIcon(marker(file.psiMethod("contractTest")), "/icons/test_federation_contract.svg")

        // The class itself has no schedule; only its methods do.
        assertNull(marker(file.psiClass("JavaScheduleTest")))
        val unscheduledMethods = listOf(
            "scheduledButNotATest", "testWithoutSchedule", "privateTest",
            "inAbstractClass", "inPrivateClass", "inInterface", "test",
        )
        for (name in unscheduledMethods) {
            assertNull(name, marker(file.psiMethod(name)))
        }
        for (name in listOf("AbstractNested", "PrivateNested", "TaggedInterface", "JavaTestWithoutSchedule")) {
            assertNull(name, marker(file.psiClass(name)))
        }
    }

    fun testJavaFactoryAndTemplateMethodsInheritSchedulesFromJavaSuperclass() {
        myFixture.addFileToProject(
            "org/junit/jupiter/api/TestFactory.java",
            """
            package org.junit.jupiter.api;
            import java.lang.annotation.*;
            @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
            @org.junit.platform.commons.annotation.Testable
            public @interface TestFactory {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "org/junit/jupiter/api/TestTemplate.java",
            """
            package org.junit.jupiter.api;
            import java.lang.annotation.*;
            @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
            @org.junit.platform.commons.annotation.Testable
            public @interface TestTemplate {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "org/junit/jupiter/api/DynamicNode.java",
            "package org.junit.jupiter.api; public abstract class DynamicNode {}",
        )
        val file = parseJava(
            "JavaInheritedScheduleTest",
            """
            package sample;

            import java.util.stream.Stream;
            import org.junit.jupiter.api.DynamicNode;
            import org.junit.jupiter.api.Tag;
            import org.junit.jupiter.api.TestFactory;
            import org.junit.jupiter.api.TestTemplate;

            @Tag("contract:Native")
            abstract class JavaContractBase {}

            public class JavaInheritedScheduleTest extends JavaContractBase {
                @TestFactory
                @Tag("smoke")
                public Stream<DynamicNode> factory() { return null; }

                @TestTemplate
                @Tag("contract:Js")
                public void template() {}
            }
            """.trimIndent(),
        )

        val inheritedSchedule = TestFederationSchedule(contractDomains = setOf("Native"))
        val testClass = file.psiClass("JavaInheritedScheduleTest")
        assertMarkerIcon(marker(testClass), "/icons/test_federation_contract.svg")
        assertEquals(inheritedSchedule, getTestFederationMarkerSchedule(testClass))

        val factory = file.psiMethod("factory")
        assertMarkerIcon(marker(factory), "/icons/test_federation_smoke.svg")
        assertEquals(
            TestFederationSchedule(smoke = true, contractDomains = setOf("Native")),
            getTestFederationMarkerSchedule(factory),
        )

        val template = file.psiMethod("template")
        assertMarkerIcon(marker(template), "/icons/test_federation_contract.svg")
        assertEquals(
            TestFederationSchedule(contractDomains = setOf("Js", "Native")),
            getTestFederationMarkerSchedule(template),
        )
        assertNull(marker(file.psiClass("JavaContractBase")))
    }

    fun testJavaExtensionRegistrationRespectsSetting() {
        addTestAnnotations()
        myFixture.configureByText(
            "RegisteredJavaScheduleTest.java",
            """
            package sample;

            import org.junit.jupiter.api.Tag;
            import org.junit.jupiter.api.Test;

            @Tag("smoke")
            public class RegisteredJavaScheduleTest {
                @Test
                @Tag("contract:Js")
                public void scheduled() {}

                @Test
                public void classScheduleOnly() {}

                public void helper() {}
            }
            """.trimIndent(),
        )

        myFixture.doHighlighting()
        val lineMarkers = federationLineMarkers().sortedBy { it.startOffset }
        assertEquals(listOf(6, 9, 12), lineMarkers.map { myFixture.editor.document.getLineNumber(it.startOffset) })
        assertEquals(
            List(3) { icon("/icons/test_federation_smoke.svg") },
            lineMarkers.map { it.createGutterRenderer()!!.icon },
        )

        val configuration = TestDataPathsConfiguration.getInstance(project)
        try {
            configuration.testFederationGutterMarkersEnabled = false
            DaemonCodeAnalyzer.getInstance(project).restart("test")
            myFixture.doHighlighting()
            assertTrue(federationLineMarkers().isEmpty())
        } finally {
            configuration.testFederationGutterMarkersEnabled = true
        }
    }

    private fun parseJava(className: String, text: String): PsiJavaFile {
        addTestAnnotations()
        return myFixture.addFileToProject("sample/$className.java", text) as PsiJavaFile
    }

    private fun addNestedAnnotation() {
        myFixture.addFileToProject(
            "org/junit/jupiter/api/Nested.java",
            """
            package org.junit.jupiter.api;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
            public @interface Nested {}
            """.trimIndent(),
        )
    }

    private fun parse(text: String): KtFile {
        addTestAnnotations()
        return myFixture.addFileToProject("sample/ScheduledTest.kt", text) as KtFile
    }

    private fun addTestAnnotations() = myFixture.addTestFederationAnnotations()

    private fun marker(function: KtNamedFunction) = provider.getLineMarkerInfo(function.nameIdentifier!!)

    private fun marker(ktClass: KtClass) = provider.getLineMarkerInfo(ktClass.nameIdentifier!!)

    private fun marker(method: PsiMethod) = provider.getLineMarkerInfo(method.nameIdentifier!!)

    private fun marker(psiClass: PsiClass) = provider.getLineMarkerInfo(psiClass.nameIdentifier!!)

    private fun assertMarkerIcon(
        marker: com.intellij.codeInsight.daemon.LineMarkerInfo<*>?,
        expectedIconPath: String,
    ) {
        assertNotNull(marker)
        assertEquals(icon(expectedIconPath), marker!!.createGutterRenderer()!!.icon)
        assertEquals("Test Federation: when this test runs", marker.lineMarkerTooltip)
    }

    private fun icon(path: String) = IconLoader.getIcon(path, TestFederationLineMarkerProvider::class.java)

    private fun federationLineMarkers() = DaemonCodeAnalyzerImpl.getLineMarkers(myFixture.editor.document, project)
        .filter { it.lineMarkerTooltip == "Test Federation: when this test runs" }

    private fun Container.findCheckBox(text: String): JBCheckBox? {
        if (this is JBCheckBox && this.text == text) return this
        return components.asSequence()
            .filterIsInstance<Container>()
            .firstNotNullOfOrNull { it.findCheckBox(text) }
    }

    private fun KtFile.function(name: String): KtNamedFunction =
        PsiTreeUtil.findChildrenOfType(this, KtNamedFunction::class.java).single { it.name == name }

    private fun KtFile.ktClass(name: String): KtClass =
        PsiTreeUtil.findChildrenOfType(this, KtClass::class.java).single { it.name == name }

    private fun PsiJavaFile.psiMethod(name: String): PsiMethod =
        PsiTreeUtil.findChildrenOfType(this, PsiMethod::class.java).single { it.name == name }

    private fun PsiJavaFile.psiClass(name: String): PsiClass =
        PsiTreeUtil.findChildrenOfType(this, PsiClass::class.java).single { it.name == name }
}

internal fun CodeInsightTestFixture.addTestFederationAnnotations() {
    addFileToProject(
        "org/jetbrains/kotlin/testFederation/annotations.kt",
        """
            package org.jetbrains.kotlin.testFederation

            import org.junit.jupiter.api.Tag

            @Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
            @Tag("smoke")
            annotation class MustRunAlways

            @Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
            @Tag("contract:Js")
            annotation class MustRunOnChangesInJs

            @Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
            @Tag("nightly")
            annotation class NightlyTest
            """.trimIndent(),
    )
    addJUnitAnnotations()
}

private fun CodeInsightTestFixture.addJUnitAnnotations() {
    addFileToProject(
        "org/junit/platform/commons/annotation/Testable.java",
        """
            package org.junit.platform.commons.annotation;
            import java.lang.annotation.*;
            @Target({ElementType.ANNOTATION_TYPE, ElementType.METHOD, ElementType.TYPE})
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Testable {}
            """.trimIndent(),
    )
    addFileToProject(
        "org/junit/jupiter/api/Tag.java",
        """
            package org.junit.jupiter.api;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Repeatable;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Target({ElementType.ANNOTATION_TYPE, ElementType.METHOD, ElementType.TYPE})
            @Retention(RetentionPolicy.RUNTIME)
            @Repeatable(Tags.class)
            public @interface Tag {
                String value();
            }
            """.trimIndent(),
    )
    addFileToProject(
        "org/junit/jupiter/api/Tags.java",
        """
            package org.junit.jupiter.api;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Target({ElementType.ANNOTATION_TYPE, ElementType.METHOD, ElementType.TYPE})
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Tags {
                Tag[] value();
            }
            """.trimIndent(),
    )
    addFileToProject(
        "org/junit/jupiter/api/Test.java",
        """
            package org.junit.jupiter.api;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Target({ElementType.ANNOTATION_TYPE, ElementType.METHOD})
            @Retention(RetentionPolicy.RUNTIME)
            @org.junit.platform.commons.annotation.Testable
            public @interface Test {}
            """.trimIndent(),
    )
}
