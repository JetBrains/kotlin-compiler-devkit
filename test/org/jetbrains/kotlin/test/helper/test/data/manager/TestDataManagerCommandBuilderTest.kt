package org.jetbrains.kotlin.test.helper.test.data.manager

import org.jetbrains.kotlin.test.helper.actions.test.data.manager.TestDataManagerCommandBuilder
import org.jetbrains.kotlin.test.helper.actions.test.data.manager.TestDataManagerMode
import org.jetbrains.kotlin.test.helper.actions.test.data.manager.buildTestDataManagerCommand
import kotlin.test.Test
import kotlin.test.assertEquals

class TestDataManagerCommandBuilderTest {
    private fun assertBuilder(
        expectedCommand: String,
        expectedTitle: String,
        configure: TestDataManagerCommandBuilder.() -> Unit = {},
    ) {
        val builder = TestDataManagerCommandBuilder().apply(configure)
        assertEquals(expectedCommand, builder.build())
        assertEquals(expectedTitle, builder.asTitle())

        // Cross-check that the public helper produces the same command.
        assertEquals(expectedCommand, buildTestDataManagerCommand(configure))
    }

    @Test
    fun `default with no configuration`() {
        assertBuilder(
            expectedCommand = "checkTestData --continue",
            expectedTitle = "Check Test Data",
        )
    }

    @Test
    fun `CHECK mode`() {
        assertBuilder(
            expectedCommand = "checkTestData --continue",
            expectedTitle = "Check Test Data",
        ) {
            mode = TestDataManagerMode.CHECK
        }
    }

    @Test
    fun `UPDATE mode`() {
        assertBuilder(
            expectedCommand = "updateTestData --continue",
            expectedTitle = "Update Test Data",
        ) {
            mode = TestDataManagerMode.UPDATE
        }
    }

    @Test
    fun `single path without mode`() {
        assertBuilder(
            expectedCommand = "checkTestData -Porg.jetbrains.kotlin.testDataManager.options.testDataPath=path/to/data --continue",
            expectedTitle = "Check Test Data: data",
        ) {
            testDataPaths = listOf("path/to/data")
        }
    }

    @Test
    fun `multiple paths without mode`() {
        assertBuilder(
            expectedCommand =
                "checkTestData -Porg.jetbrains.kotlin.testDataManager.options.testDataPath=path/one,path/two --continue",
            expectedTitle = "Check Test Data: one, two",
        ) {
            testDataPaths = listOf("path/one", "path/two")
        }
    }

    @Test
    fun `duplicated paths without mode`() {
        assertBuilder(
            expectedCommand =
                "checkTestData -Porg.jetbrains.kotlin.testDataManager.options.testDataPath=path/one,path/one --continue",
            expectedTitle = "Check Test Data: one, one",
        ) {
            testDataPaths = listOf("path/one", "path/one")
        }
    }

    @Test
    fun `duplicated file name in paths without mode`() {
        assertBuilder(
            expectedCommand =
                "checkTestData -Porg.jetbrains.kotlin.testDataManager.options.testDataPath=path/one/a.kt,path/two/a.kt --continue",
            expectedTitle = "Check Test Data: a.kt, a.kt",
        ) {
            testDataPaths = listOf("path/one/a.kt", "path/two/a.kt")
        }
    }

    @Test
    fun `test class pattern`() {
        assertBuilder(
            expectedCommand =
                "checkTestData -Porg.jetbrains.kotlin.testDataManager.options.testClassPattern=.*MyTest.* --continue",
            expectedTitle = "Check Test Data",
        ) {
            testClassPattern = ".*MyTest.*"
        }
    }

    @Test
    fun `goldenOnly true without mode`() {
        assertBuilder(
            expectedCommand = "checkTestData -Porg.jetbrains.kotlin.testDataManager.options.goldenOnly=true --continue",
            expectedTitle = "Check Test Data (Golden Only)",
        ) {
            goldenOnly = true
        }
    }

    @Test
    fun `goldenOnly false without mode`() {
        assertBuilder(
            expectedCommand = "checkTestData --continue",
            expectedTitle = "Check Test Data",
        ) {
            goldenOnly = false
        }
    }

    @Test
    fun `UPDATE mode with single path`() {
        assertBuilder(
            expectedCommand =
                "updateTestData -Porg.jetbrains.kotlin.testDataManager.options.testDataPath=path/to/data.txt --continue",
            expectedTitle = "Update Test Data: data.txt",
        ) {
            mode = TestDataManagerMode.UPDATE
            testDataPaths = listOf("path/to/data.txt")
        }
    }

    @Test
    fun `UPDATE mode with multiple paths`() {
        assertBuilder(
            expectedCommand =
                "updateTestData -Porg.jetbrains.kotlin.testDataManager.options.testDataPath=path/to/a.txt,other/b.kt --continue",
            expectedTitle = "Update Test Data: a.txt, b.kt",
        ) {
            mode = TestDataManagerMode.UPDATE
            testDataPaths = listOf("path/to/a.txt", "other/b.kt")
        }
    }

    @Test
    fun `CHECK mode with paths`() {
        assertBuilder(
            expectedCommand =
                "checkTestData -Porg.jetbrains.kotlin.testDataManager.options.testDataPath=a/b.txt,c/d.kt --continue",
            expectedTitle = "Check Test Data: b.txt, d.kt",
        ) {
            mode = TestDataManagerMode.CHECK
            testDataPaths = listOf("a/b.txt", "c/d.kt")
        }
    }

    @Test
    fun `CHECK mode with goldenOnly`() {
        assertBuilder(
            expectedCommand = "checkTestData -Porg.jetbrains.kotlin.testDataManager.options.goldenOnly=true --continue",
            expectedTitle = "Check Test Data (Golden Only)",
        ) {
            mode = TestDataManagerMode.CHECK
            goldenOnly = true
        }
    }

    @Test
    fun `UPDATE mode with goldenOnly and path`() {
        assertBuilder(
            expectedCommand =
                "updateTestData -Porg.jetbrains.kotlin.testDataManager.options.testDataPath=a/b.txt " +
                    "-Porg.jetbrains.kotlin.testDataManager.options.goldenOnly=true --continue",
            expectedTitle = "Update Test Data (Golden Only): b.txt",
        ) {
            mode = TestDataManagerMode.UPDATE
            goldenOnly = true
            testDataPaths = listOf("a/b.txt")
        }
    }

    @Test
    fun `UPDATE mode with goldenOnly false and path`() {
        assertBuilder(
            expectedCommand =
                "updateTestData -Porg.jetbrains.kotlin.testDataManager.options.testDataPath=a/b.txt --continue",
            expectedTitle = "Update Test Data: b.txt",
        ) {
            mode = TestDataManagerMode.UPDATE
            goldenOnly = false
            testDataPaths = listOf("a/b.txt")
        }
    }

    @Test
    fun `UPDATE mode with incremental`() {
        assertBuilder(
            expectedCommand =
                "updateTestData -Porg.jetbrains.kotlin.testDataManager.options.testDataPath=a/b.txt " +
                    "-Porg.jetbrains.kotlin.testDataManager.options.incremental=true --continue",
            expectedTitle = "Update Test Data (Incremental): b.txt",
        ) {
            mode = TestDataManagerMode.UPDATE
            incremental = true
            testDataPaths = listOf("a/b.txt")
        }
    }

    @Test
    fun `CHECK mode with all parameters`() {
        assertBuilder(
            expectedCommand =
                "checkTestData " +
                    "-Porg.jetbrains.kotlin.testDataManager.options.testDataPath=path/one,path/two " +
                    "-Porg.jetbrains.kotlin.testDataManager.options.testClassPattern=.*MyTest.* " +
                    "-Porg.jetbrains.kotlin.testDataManager.options.goldenOnly=true --continue",
            expectedTitle = "Check Test Data (Golden Only): one, two",
        ) {
            mode = TestDataManagerMode.CHECK
            testDataPaths = listOf("path/one", "path/two")
            testClassPattern = ".*MyTest.*"
            goldenOnly = true
        }
    }

    @Test
    fun `UPDATE mode with all parameters`() {
        assertBuilder(
            expectedCommand =
                "updateTestData " +
                    "-Porg.jetbrains.kotlin.testDataManager.options.testDataPath=path/one,path/two " +
                    "-Porg.jetbrains.kotlin.testDataManager.options.testClassPattern=.*MyTest.* " +
                    "-Porg.jetbrains.kotlin.testDataManager.options.goldenOnly=true " +
                    "-Porg.jetbrains.kotlin.testDataManager.options.incremental=true --continue",
            expectedTitle = "Update Test Data (Golden Only) (Incremental): one, two",
        ) {
            mode = TestDataManagerMode.UPDATE
            testDataPaths = listOf("path/one", "path/two")
            testClassPattern = ".*MyTest.*"
            goldenOnly = true
            incremental = true
        }
    }
}
