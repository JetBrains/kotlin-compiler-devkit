package org.jetbrains.kotlin.test.helper.inspections

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.test.helper.codeinsight.addTestFederationAnnotations
import org.jetbrains.kotlin.test.helper.codeinsight.federationProjectDescriptor

class TestFederationScheduleInspectionsTest : BasePlatformTestCase() {
    override fun getProjectDescriptor() = federationProjectDescriptor

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(
            TestFederationRedundantContractInspection(),
            TestFederationNightlyConflictInspection(),
        )
        myFixture.addTestFederationAnnotations()
        myFixture.addFileToProject(
            "org/junit/jupiter/api/Nested.java",
            """
            package org.junit.jupiter.api;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Nested {}
            """.trimIndent(),
        )
    }

    fun testContractTagsOnSmokeTestsAreRedundant() {
        highlight(
            """
            package sample

            import org.jetbrains.kotlin.testFederation.MustRunAlways
            import org.jetbrains.kotlin.testFederation.MustRunOnChangesInJs
            import org.junit.jupiter.api.Tag
            import org.junit.jupiter.api.Test

            class SampleTest {
                @Test
                @MustRunAlways
                @Tag("contract:Native")
                @MustRunOnChangesInJs
                fun smokeAndContract() {}

                @Test
                @MustRunAlways
                fun smokeOnly() {}

                @Test
                @MustRunOnChangesInJs
                fun contractOnly() {}
            }

            @MustRunAlways
            class SmokeClassTest {
                @Test
                @MustRunOnChangesInJs
                fun contractInSmokeClass() {}

                @Test
                fun unscheduled() {}
            }

            @MustRunAlways
            @MustRunOnChangesInJs
            class SmokeAndContractClassTest {
                @Test
                fun test() {}
            }
            """,
        )

        assertEquals(
            listOf(
                "smokeAndContract" to "Redundant contract:Js, contract:Native: smoke tests already run regardless of what changed",
                "contractInSmokeClass" to "Redundant contract:Js: smoke tests already run regardless of what changed",
                "SmokeAndContractClassTest" to "Redundant contract:Js: smoke tests already run regardless of what changed",
            ),
            warnings(),
        )
    }

    fun testNightlyTagConflictsWithSmokeAndContractTags() {
        highlight(
            """
            package sample

            import org.jetbrains.kotlin.testFederation.MustRunAlways
            import org.jetbrains.kotlin.testFederation.MustRunOnChangesInJs
            import org.jetbrains.kotlin.testFederation.NightlyTest
            import org.junit.jupiter.api.Nested
            import org.junit.jupiter.api.Test

            class SampleTest {
                @Test
                @NightlyTest
                @MustRunAlways
                fun nightlySmoke() {}

                @Test
                @NightlyTest
                @MustRunOnChangesInJs
                fun nightlyContract() {}

                @Test
                @NightlyTest
                fun nightlyOnly() {}
            }

            @NightlyTest
            class NightlyClassTest {
                @Test
                @MustRunAlways
                fun smokeInNightlyClass() {}

                @Test
                fun unscheduled() {}

                @Nested
                @MustRunOnChangesInJs
                inner class NestedContractTest {
                    @Test
                    fun test() {}
                }

                @Nested
                inner class NestedUnscheduledTest {
                    @Test
                    fun test() {}
                }
            }
            """,
        )

        val nightlyConflict = "Nightly tag conflicts with %s: nightly tests are skipped in remote and safe-merge runs"
        assertEquals(
            listOf(
                "nightlySmoke" to nightlyConflict.format("smoke"),
                "nightlyContract" to nightlyConflict.format("contract:Js"),
                "smokeInNightlyClass" to nightlyConflict.format("smoke"),
                "NestedContractTest" to nightlyConflict.format("contract:Js"),
            ),
            warnings(),
        )
    }

    fun testNightlySmokeContractTestReportsBothProblemsOnce() {
        highlight(
            """
            package sample

            import org.jetbrains.kotlin.testFederation.MustRunAlways
            import org.jetbrains.kotlin.testFederation.MustRunOnChangesInJs
            import org.jetbrains.kotlin.testFederation.NightlyTest
            import org.junit.jupiter.api.Test

            @MustRunAlways
            @MustRunOnChangesInJs
            class SmokeAndContractClassTest {
                @Test
                @NightlyTest
                fun nightlyInConflictingClass() {}
            }
            """,
        )

        assertEquals(
            listOf(
                "SmokeAndContractClassTest" to
                    "Redundant contract:Js: smoke tests already run regardless of what changed",
                "nightlyInConflictingClass" to
                    "Nightly tag conflicts with smoke, contract:Js: nightly tests are skipped in remote and safe-merge runs",
            ),
            warnings(),
        )
    }

    fun testConflictsAreNotReportedOutsideJupiterTests() {
        highlight(
            """
            package sample

            import org.jetbrains.kotlin.testFederation.MustRunAlways
            import org.jetbrains.kotlin.testFederation.MustRunOnChangesInJs
            import org.jetbrains.kotlin.testFederation.NightlyTest

            class NotATest {
                @NightlyTest
                @MustRunAlways
                @MustRunOnChangesInJs
                fun helper() {}
            }

            @NightlyTest
            @MustRunAlways
            abstract class AbstractBase
            """,
        )

        assertEquals(emptyList<Pair<String, String>>(), warnings())
    }

    fun testWarningsCanBeSuppressed() {
        highlight(
            """
            @file:Suppress("TestFederationNightlyConflict")

            package sample

            import org.jetbrains.kotlin.testFederation.MustRunAlways
            import org.jetbrains.kotlin.testFederation.MustRunOnChangesInJs
            import org.jetbrains.kotlin.testFederation.NightlyTest
            import org.junit.jupiter.api.Test

            class SampleTest {
                @Test
                @NightlyTest
                @MustRunAlways
                @MustRunOnChangesInJs
                @Suppress("TestFederationRedundantContract")
                fun suppressedOnFunction() {}

                @Test
                @MustRunAlways
                @MustRunOnChangesInJs
                fun reported() {}
            }

            @Suppress("TestFederationRedundantContract")
            class SuppressedClassTest {
                @Test
                @MustRunAlways
                @MustRunOnChangesInJs
                fun suppressedOnClass() {}
            }
            """,
        )

        assertEquals(
            listOf("reported" to "Redundant contract:Js: smoke tests already run regardless of what changed"),
            warnings(),
        )
    }

    fun testSuppressActionsAreOffered() {
        highlight(
            """
            package sample

            import org.jetbrains.kotlin.testFederation.MustRunAlways
            import org.jetbrains.kotlin.testFederation.NightlyTest
            import org.junit.jupiter.api.Test

            class SampleTest {
                @Test
                @NightlyTest
                @MustRunAlways
                fun <caret>conflicting() {}
            }
            """,
        )

        val suppressAction = myFixture.availableIntentions
            .single { it.text == "Suppress 'TestFederationNightlyConflict' for fun conflicting" }
        myFixture.launchAction(suppressAction)
        assertTrue(myFixture.file.text.contains("@Suppress(\"TestFederationNightlyConflict\")"))
        assertEquals(emptyList<Pair<String, String>>(), warnings())
    }

    private fun highlight(text: String) {
        myFixture.configureByText("SampleTest.kt", text.trimIndent())
    }

    private fun warnings(): List<Pair<String, String>> = myFixture.doHighlighting(HighlightSeverity.WARNING)
        .filter { it.isTestFederationProblem() }
        .sortedBy { it.startOffset }
        .map { it.text to it.description }

    private fun HighlightInfo.isTestFederationProblem(): Boolean =
        description.orEmpty().let { it.startsWith("Redundant contract:") || it.startsWith("Nightly tag conflicts") }
}
