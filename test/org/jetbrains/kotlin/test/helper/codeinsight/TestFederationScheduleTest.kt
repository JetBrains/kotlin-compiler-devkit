package org.jetbrains.kotlin.test.helper.codeinsight

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

class TestFederationScheduleTest : BasePlatformTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = federationProjectDescriptor

    fun testMustRunAlwaysMetaAnnotationProvidesSmokeSchedule() {
        val file = parse(
            """
            package sample

            import org.jetbrains.kotlin.testFederation.MustRunAlways

            class SampleTest {
                @MustRunAlways
                fun testSmoke() {}
            }
            """.trimIndent(),
        )

        assertEquals(TestFederationSchedule(smoke = true), getTestFederationSchedule(file.function("testSmoke")))
    }

    fun testComposedAnnotationCollectsScheduleAlongsideItsUnrelatedTag() {
        val file = parse(
            """
            package sample

            import org.jetbrains.kotlin.testFederation.MustRunOnChangesInJs
            import org.junit.jupiter.api.Tag

            @Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
            @Tag("JsKGP")
            @MustRunOnChangesInJs
            annotation class JsGradlePluginTests

            @JsGradlePluginTests
            abstract class JsGradlePluginBaseTest

            class InheritsJsGradlePluginTest : JsGradlePluginBaseTest()

            class SampleTest {
                @JsGradlePluginTests
                fun testJs() {}
            }
            """.trimIndent(),
        )

        assertEquals(
            TestFederationSchedule(contractDomains = setOf("Js")),
            getTestFederationSchedule(file.function("testJs")),
        )
        assertEquals(
            TestFederationSchedule(contractDomains = setOf("Js")),
            getTestFederationSchedule(file.ktClass("InheritsJsGradlePluginTest")),
        )
    }

    fun testTwoLevelComposedAnnotationProvidesSchedule() {
        val file = parse(
            """
            package sample

            import org.jetbrains.kotlin.testFederation.MustRunAlways

            @Target(AnnotationTarget.ANNOTATION_CLASS)
            @MustRunAlways
            annotation class FirstLevel

            @Target(AnnotationTarget.FUNCTION)
            @FirstLevel
            annotation class SecondLevel

            class SampleTest {
                @SecondLevel
                fun testNestedSmoke() {}
            }
            """.trimIndent(),
        )

        assertEquals(TestFederationSchedule(smoke = true), getTestFederationSchedule(file.function("testNestedSmoke")))
    }

    fun testRepeatedAndContainerTagsAreAllCollected() {
        val file = parse(
            """
            package sample

            import org.junit.jupiter.api.Tag
            import org.junit.jupiter.api.Tags

            @Target(AnnotationTarget.FUNCTION)
            @Tag("smoke")
            @Tag("contract:Jvm")
            annotation class RepeatedTags

            @Target(AnnotationTarget.FUNCTION)
            @Tags(value = [Tag("contract:Native"), Tag("contract:Jvm")])
            annotation class ContainerTags

            class SampleTest {
                @RepeatedTags
                @ContainerTags
                fun testSeveralTags() {}
            }
            """.trimIndent(),
        )

        assertEquals(
            TestFederationSchedule(smoke = true, contractDomains = setOf("Jvm", "Native")),
            getTestFederationSchedule(file.function("testSeveralTags")),
        )
    }

    fun testClassScheduleIncludesSmokeAndDirectTagsFromAbstractBaseClass() {
        val file = parse(
            """
            package sample

            import org.jetbrains.kotlin.testFederation.MustRunAlways
            import org.junit.jupiter.api.Tag

            @MustRunAlways
            abstract class SmokeBaseTest

            @Tag("contract:Jvm")
            abstract class ContractBaseTest

            class InheritsSmokeTest : SmokeBaseTest()
            class InheritsContractTest : ContractBaseTest()
            """.trimIndent(),
        )

        assertEquals(
            TestFederationSchedule(smoke = true),
            getTestFederationSchedule(file.ktClass("InheritsSmokeTest")),
        )
        assertEquals(
            TestFederationSchedule(contractDomains = setOf("Jvm")),
            getTestFederationSchedule(file.ktClass("InheritsContractTest")),
        )
    }

    fun testUnrelatedTagsDoNotCreateSchedule() {
        val file = parse(
            """
            package sample

            import org.junit.jupiter.api.Tag

            @Target(AnnotationTarget.FUNCTION)
            @Tag("other")
            annotation class UnrelatedMetadata

            class SampleTest {
                @UnrelatedMetadata
                fun testUnrelated() {}

                @Tag("other")
                fun testOtherTag() {}

                fun testWithoutTags() {}
            }
            """.trimIndent(),
        )

        assertEquals(TestFederationSchedule(), getTestFederationSchedule(file.function("testUnrelated")))
        assertEquals(TestFederationSchedule(), getTestFederationSchedule(file.function("testOtherTag")))
        assertEquals(TestFederationSchedule(), getTestFederationSchedule(file.function("testWithoutTags")))
    }

    fun testDirectFunctionTagsCreateSchedule() {
        val file = parse(
            """
            package sample

            import org.junit.jupiter.api.Tag

            class SampleTest {
                @Tag("smoke")
                @Tag("contract:Native")
                fun testDirectTags() {}
            }
            """.trimIndent(),
        )

        assertEquals(
            TestFederationSchedule(smoke = true, contractDomains = setOf("Native")),
            getTestFederationSchedule(file.function("testDirectTags")),
        )
    }

    fun testConcreteClassTagCreatesSchedule() {
        val file = parse(
            """
            package sample

            import org.junit.jupiter.api.Tag

            @Tag("contract:Wasm")
            class ConcreteTest
            """.trimIndent(),
        )

        assertEquals(
            TestFederationSchedule(contractDomains = setOf("Wasm")),
            getTestFederationSchedule(file.ktClass("ConcreteTest")),
        )
    }

    fun testMethodScheduleIncludesClassAndNestedContainers() {
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
            import org.junit.jupiter.api.Tag
            import org.junit.jupiter.api.Nested
            @Tag("contract:Jvm") interface TaggedInterface
            @Tag("smoke")
            class Outer : TaggedInterface {
                @Tag("contract:Js") fun direct() {}
                @Nested @Tag("contract:Native")
                inner class Inner {
                    @Tag("contract:Wasm") fun nested() {}
                }
                class Independent {
                    @Tag("contract:Js") fun independent() {}
                }
            }
            """.trimIndent(),
        )
        assertEquals(
            TestFederationSchedule(smoke = true, contractDomains = setOf("Jvm", "Js")),
            getTestFederationSchedule(file.function("direct")),
        )
        assertEquals(
            TestFederationSchedule(smoke = true, contractDomains = setOf("Jvm", "Native", "Wasm")),
            getTestFederationSchedule(file.function("nested")),
        )
        assertEquals(
            TestFederationSchedule(smoke = true, contractDomains = setOf("Jvm", "Native")),
            getTestFederationSchedule(file.ktClass("Inner")),
        )
        assertEquals(
            TestFederationSchedule(contractDomains = setOf("Js")),
            getTestFederationSchedule(file.function("independent")),
        )
    }

    fun testNonRuntimeAnnotationsDoNotContributeTags() {
        val file = parse(
            """
            package sample
            import org.junit.jupiter.api.Tag
            @Retention(AnnotationRetention.SOURCE) @Tag("smoke") annotation class SourceSmoke
            @Retention(AnnotationRetention.BINARY) @Tag("contract:Js") annotation class BinaryContract
            @SourceSmoke annotation class RuntimeWrapper
            class Sample {
                @RuntimeWrapper @BinaryContract fun noRuntimeTags() {}
                @Tag("contract:Jvm") fun runtimeTags() {}
            }
            """.trimIndent(),
        )
        assertEquals(TestFederationSchedule(), getTestFederationSchedule(file.function("noRuntimeTags")))
        assertEquals(
            TestFederationSchedule(contractDomains = setOf("Jvm")),
            getTestFederationSchedule(file.function("runtimeTags")),
        )
    }

    fun testRepeatableComposedContainerIsNotAnArbitraryAnnotationArray() {
        addJUnitAnnotations()
        myFixture.addFileToProject(
            "sample/Smoke.java",
            """
            package sample;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Repeatable(Smokes.class)
            @org.junit.jupiter.api.Tag("smoke")
            public @interface Smoke { String value(); }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "sample/Smokes.java",
            """
            package sample;
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
            public @interface Smokes { Smoke[] value(); }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "sample/NotAContainer.java",
            """
            package sample;
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
            public @interface NotAContainer { Smoke[] value(); }
            """.trimIndent(),
        )
        val file = myFixture.addFileToProject(
            "sample/Repeated.kt",
            """
            package sample
            class Sample {
                @Smokes(value = [Smoke("a"), Smoke("b")]) fun repeated() {}
                @NotAContainer(value = [Smoke("a")]) fun unrelated() {}
                @Smokes(value = []) fun empty() {}
            }
            """.trimIndent(),
        ) as KtFile
        assertEquals(TestFederationSchedule(smoke = true), getTestFederationSchedule(file.function("repeated")))
        assertEquals(TestFederationSchedule(), getTestFederationSchedule(file.function("unrelated")))
        assertEquals(TestFederationSchedule(), getTestFederationSchedule(file.function("empty")))
    }

    fun testTagsAreTrimmedAndValidatedLikeJupiter() {
        val file = parse(
            """
            package sample
            import org.junit.jupiter.api.Tag
            class Sample {
                @Tag(" smoke ")
                @Tag(" nightly ")
                @Tag(" contract:Jvm ")
                @Tag("contract:Js,Native")
                @Tag("contract:has space")
                @Tag("contract:(Wasm)")
                @Tag("contract:!")
                @Tag("contract:a|b")
                @Tag("contract:a&b")
                @Tag("contract:")
                fun tagged() {}
            }
            """.trimIndent(),
        )
        assertEquals(
            TestFederationSchedule(smoke = true, contractDomains = setOf("Jvm"), nightly = true),
            getTestFederationSchedule(file.function("tagged")),
        )
    }

    fun testNightlyTestAnnotationProvidesNightlySchedule() {
        val file = parse(
            """
            package sample
            import org.jetbrains.kotlin.testFederation.NightlyTest
            import org.junit.jupiter.api.Tag
            class Sample {
                @NightlyTest fun annotated() {}
                @Tag("nightly") fun direct() {}
                @Tag("nightly:Jvm") fun notNightly() {}
            }
            """.trimIndent(),
        )
        assertEquals(TestFederationSchedule(nightly = true), getTestFederationSchedule(file.function("annotated")))
        assertEquals(TestFederationSchedule(nightly = true), getTestFederationSchedule(file.function("direct")))
        assertEquals(TestFederationSchedule(), getTestFederationSchedule(file.function("notNightly")))
    }

    fun testNightlyThroughComposedAnnotationAndInheritedBaseClass() {
        val file = parse(
            """
            package sample
            import org.jetbrains.kotlin.testFederation.NightlyTest
            import org.junit.jupiter.api.Tag
            @Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
            @Tag("Slow") @NightlyTest
            annotation class SlowNightly
            @SlowNightly abstract class NightlyBaseTest
            class InheritsNightlyTest : NightlyBaseTest()
            class Sample {
                @SlowNightly fun composed() {}
            }
            """.trimIndent(),
        )
        assertEquals(TestFederationSchedule(nightly = true), getTestFederationSchedule(file.function("composed")))
        assertEquals(
            TestFederationSchedule(nightly = true),
            getTestFederationSchedule(file.ktClass("InheritsNightlyTest")),
        )
    }

    fun testNightlyCombinesWithSmokeAndContractsIncludingContainerTags() {
        val file = parse(
            """
            package sample
            import org.jetbrains.kotlin.testFederation.MustRunAlways
            import org.jetbrains.kotlin.testFederation.MustRunOnChangesInJs
            import org.jetbrains.kotlin.testFederation.NightlyTest
            import org.junit.jupiter.api.Tag
            import org.junit.jupiter.api.Tags
            @NightlyTest
            class NightlyClassTest {
                @MustRunAlways @MustRunOnChangesInJs fun combined() {}
            }
            class Sample {
                @Tags(value = [Tag("nightly"), Tag("contract:Native")]) fun container() {}
                @Tag("nightly") @Tag("smoke") fun repeated() {}
            }
            """.trimIndent(),
        )
        assertEquals(
            TestFederationSchedule(smoke = true, contractDomains = setOf("Js"), nightly = true),
            getTestFederationSchedule(file.function("combined")),
        )
        assertEquals(
            TestFederationSchedule(contractDomains = setOf("Native"), nightly = true),
            getTestFederationSchedule(file.function("container")),
        )
        assertEquals(
            TestFederationSchedule(smoke = true, nightly = true),
            getTestFederationSchedule(file.function("repeated")),
        )
    }

    fun testNestedClassInheritsNightlyFromOuterClass() {
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
            import org.jetbrains.kotlin.testFederation.NightlyTest
            import org.junit.jupiter.api.Nested
            import org.junit.jupiter.api.Tag
            @NightlyTest
            class Outer {
                @Nested inner class Inner {
                    @Tag("contract:Jvm") fun nested() {}
                }
                class Independent {
                    @Tag("contract:Js") fun independent() {}
                }
            }
            """.trimIndent(),
        )
        assertEquals(TestFederationSchedule(nightly = true), getTestFederationSchedule(file.ktClass("Inner")))
        assertEquals(
            TestFederationSchedule(contractDomains = setOf("Jvm"), nightly = true),
            getTestFederationSchedule(file.function("nested")),
        )
        assertEquals(
            TestFederationSchedule(contractDomains = setOf("Js")),
            getTestFederationSchedule(file.function("independent")),
        )
    }

    private fun parse(text: String): KtFile {
        addJUnitAnnotations()
        myFixture.addFileToProject(
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
        return myFixture.addFileToProject("sample/SchedulingTest.kt", text) as KtFile
    }

    private fun addJUnitAnnotations() {
        myFixture.addFileToProject(
            "org/junit/jupiter/api/Tags.java",
            """
            package org.junit.jupiter.api;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Inherited;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Target({ElementType.ANNOTATION_TYPE, ElementType.METHOD, ElementType.TYPE})
            @Retention(RetentionPolicy.RUNTIME)
            @Inherited
            public @interface Tags {
                Tag[] value();
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "org/junit/jupiter/api/Tag.java",
            """
            package org.junit.jupiter.api;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Inherited;
            import java.lang.annotation.Repeatable;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Target({ElementType.ANNOTATION_TYPE, ElementType.METHOD, ElementType.TYPE})
            @Retention(RetentionPolicy.RUNTIME)
            @Repeatable(Tags.class)
            @Inherited
            public @interface Tag {
                String value();
            }
            """.trimIndent(),
        )
    }

    private fun KtFile.function(name: String): KtNamedFunction =
        PsiTreeUtil.findChildrenOfType(this, KtNamedFunction::class.java).single { it.name == name }

    private fun KtFile.ktClass(name: String): KtClass =
        PsiTreeUtil.findChildrenOfType(this, KtClass::class.java).single { it.name == name }
}

internal val federationProjectDescriptor = object : LightProjectDescriptor() {
    override fun getSdk(): Sdk =
        JavaSdk.getInstance().createJdk("Federation test JDK", System.getProperty("java.home"), false)
}

internal fun getTestFederationSchedule(function: KtNamedFunction): TestFederationSchedule {
    val method = function.toLightMethods().single()
    return method.directTestFederationSchedule() + method.containingClass!!.testFederationSchedule()
}

internal fun getTestFederationSchedule(ktClass: KtClass): TestFederationSchedule =
    ktClass.toLightClass()!!.testFederationSchedule()
