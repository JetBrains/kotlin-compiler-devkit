package org.jetbrains.kotlin.test.helper.teamcity

import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FailedTestNavigationTest : BasePlatformTestCase() {
    fun testFindsFailedTestClass() {
        val psiClass = addSampleTestClass()

        assertSame(psiClass, findFailedTestClass(project, "org.example.SampleTest"))
    }

    fun testFindsMethodAndFallsBackToClassWhenMethodIsMissing() {
        val psiClass = addSampleTestClass()
        val test = FailedTest(
            name = "testFails",
            packageName = "org.example",
            className = "SampleTest",
            methodName = "testFails",
            displayName = "testFails",
            isMuted = false,
            buildTypeName = null,
        )

        assertSame(psiClass.findMethodsByName("testFails", true).single(), findFailedTestTarget(project, test))
        assertSame(psiClass, findFailedTestTarget(project, test.copy(methodName = "missing")))
        assertEquals("org.example.SampleTest", failedTestClassFqName(test))
    }

    fun testMissingClassHasNoNavigationTarget() {
        assertNull(findFailedTestClass(project, "org.example.MissingTest"))
    }

    private fun addSampleTestClass() =
        (myFixture.addFileToProject(
            "org/example/SampleTest.java",
            """
            package org.example;

            public class SampleTest {
                public void testFails() {}
            }
            """.trimIndent(),
        ) as PsiJavaFile).classes.single()
}
