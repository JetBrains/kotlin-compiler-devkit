package org.jetbrains.kotlin.test.helper.teamcity

import kotlin.test.Test
import kotlin.test.assertEquals

class FailedTestsTest {
    @Test
    fun buildsFailedTestsUrl() {
        assertEquals(
            "https://buildserver.labs.intellij.net/guestAuth/app/rest/testOccurrences" +
                "?locator=build:(id:123),status:FAILURE,count:$MAX_FAILED_TESTS" +
                "&fields=count,testOccurrence(name,muted,test(parsedTestName(testPackage,testClass,testMethodName,testNameWithParameters)),build(buildType(name)))",
            failedTestsUrl("123"),
        )
    }

    @Test
    fun parsesFailedTestNamesAndMutedBuildMetadata() {
        val result = parseFailedTests(
            """
            {
              "count": 2,
              "testOccurrence": [
                {
                  "name": "testFails",
                  "muted": false,
                  "test": {"parsedTestName": {
                    "testPackage": "org.example",
                    "testClass": "Outer\u0024Inner",
                    "testMethodName": "testFails",
                    "testNameWithParameters": "testFails(value)"
                  }},
                  "build": {"buildType": {"name": "Compiler Tests"}}
                },
                {"name": "mutedTest", "muted": true}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(2, result.totalCount)
        assertEquals(0, result.remainingCount)
        val test = result.tests.first()
        assertEquals("testFails", test.name)
        assertEquals("org.example", test.packageName)
        assertEquals("testFails", test.methodName)
        assertEquals("testFails(value)", test.displayName)
        assertEquals(false, test.isMuted)
        assertEquals("Compiler Tests", test.buildTypeName)
        assertEquals("org.example.Outer.Inner", failedTestClassFqName(test))
        assertEquals(true, result.tests.last().isMuted)
        assertEquals("mutedTest", result.tests.last().displayName)
    }

    @Test
    fun toleratesMissingFieldsAndInvalidBodies() {
        assertEquals(
            FailedTestsResult(
                tests = listOf(
                    FailedTest(
                        name = null,
                        packageName = null,
                        className = null,
                        methodName = null,
                        displayName = "Unknown test",
                        isMuted = false,
                        buildTypeName = null,
                    ),
                ),
                totalCount = 1,
            ),
            parseFailedTests("""{"testOccurrence":[{}]}"""),
        )
        assertEquals(FailedTestsResult(emptyList(), 0), parseFailedTests("not JSON"))
    }
}