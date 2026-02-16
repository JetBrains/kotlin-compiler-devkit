package org.jetbrains.kotlin.test.helper.test.data.manager

import org.jetbrains.kotlin.test.helper.actions.test.data.manager.TestDataManagerCommandBuilder
import org.jetbrains.kotlin.test.helper.actions.test.data.manager.TestDataManagerMode
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
    }

    @Test
    fun `default with no configuration`() {
        assertBuilder(
            expectedCommand = "manageTestDataGlobally --continue",
            expectedTitle = "Manage Test Data",
        )
    }

    @Test
    fun `CHECK mode`() {
        assertBuilder(
            expectedCommand = "manageTestDataGlobally --mode=check --continue",
            expectedTitle = "Check Test Data",
        ) {
            mode = TestDataManagerMode.CHECK
        }
    }

    @Test
    fun `UPDATE mode`() {
        assertBuilder(
            expectedCommand = "manageTestDataGlobally --mode=update --continue",
            expectedTitle = "Update Test Data",
        ) {
            mode = TestDataManagerMode.UPDATE
        }
    }

    @Test
    fun `single path without mode`() {
        assertBuilder(
            expectedCommand = "manageTestDataGlobally --test-data-path=path/to/data --continue",
            expectedTitle = "Manage Test Data: data",
        ) {
            testDataPaths = listOf("path/to/data")
        }
    }

    @Test
    fun `multiple paths without mode`() {
        assertBuilder(
            expectedCommand = "manageTestDataGlobally --test-data-path=path/one,path/two --continue",
            expectedTitle = "Manage Test Data: one, two",
        ) {
            testDataPaths = listOf("path/one", "path/two")
        }
    }

    @Test
    fun `duplicated paths without mode`() {
        assertBuilder(
            expectedCommand = "manageTestDataGlobally --test-data-path=path/one,path/one --continue",
            expectedTitle = "Manage Test Data: one, one",
        ) {
            testDataPaths = listOf("path/one", "path/one")
        }
    }

    @Test
    fun `duplicated file name in paths without mode`() {
        assertBuilder(
            expectedCommand = "manageTestDataGlobally --test-data-path=path/one/a.kt,path/two/a.kt --continue",
            expectedTitle = "Manage Test Data: a.kt, a.kt",
        ) {
            testDataPaths = listOf("path/one/a.kt", "path/two/a.kt")
        }
    }

    @Test
    fun `test class pattern`() {
        assertBuilder(
            expectedCommand = "manageTestDataGlobally --test-class-pattern=.*MyTest.* --continue",
            expectedTitle = "Manage Test Data",
        ) {
            testClassPattern = ".*MyTest.*"
        }
    }

    @Test
    fun `goldenOnly true without mode`() {
        assertBuilder(
            expectedCommand = "manageTestDataGlobally --golden-only --continue",
            expectedTitle = "Manage Test Data (Golden Only)",
        ) {
            goldenOnly = true
        }
    }

    @Test
    fun `goldenOnly false without mode`() {
        assertBuilder(
            expectedCommand = "manageTestDataGlobally --continue",
            expectedTitle = "Manage Test Data",
        ) {
            goldenOnly = false
        }
    }

    @Test
    fun `UPDATE mode with single path`() {
        assertBuilder(
            expectedCommand = "manageTestDataGlobally --mode=update --test-data-path=path/to/data.txt --continue",
            expectedTitle = "Update Test Data: data.txt",
        ) {
            mode = TestDataManagerMode.UPDATE
            testDataPaths = listOf("path/to/data.txt")
        }
    }

    @Test
    fun `UPDATE mode with multiple paths`() {
        assertBuilder(
            expectedCommand = "manageTestDataGlobally --mode=update --test-data-path=path/to/a.txt,other/b.kt --continue",
            expectedTitle = "Update Test Data: a.txt, b.kt",
        ) {
            mode = TestDataManagerMode.UPDATE
            testDataPaths = listOf("path/to/a.txt", "other/b.kt")
        }
    }

    @Test
    fun `CHECK mode with paths`() {
        assertBuilder(
            expectedCommand = "manageTestDataGlobally --mode=check --test-data-path=a/b.txt,c/d.kt --continue",
            expectedTitle = "Check Test Data: b.txt, d.kt",
        ) {
            mode = TestDataManagerMode.CHECK
            testDataPaths = listOf("a/b.txt", "c/d.kt")
        }
    }

    @Test
    fun `CHECK mode with goldenOnly`() {
        assertBuilder(
            expectedCommand = "manageTestDataGlobally --mode=check --golden-only --continue",
            expectedTitle = "Check Test Data (Golden Only)",
        ) {
            mode = TestDataManagerMode.CHECK
            goldenOnly = true
        }
    }

    @Test
    fun `UPDATE mode with goldenOnly and path`() {
        assertBuilder(
            expectedCommand = "manageTestDataGlobally --mode=update --test-data-path=a/b.txt --golden-only --continue",
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
            expectedCommand = "manageTestDataGlobally --mode=update --test-data-path=a/b.txt --continue",
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
            expectedCommand = "manageTestDataGlobally --mode=update --test-data-path=a/b.txt --incremental --continue",
            expectedTitle = "Update Test Data (Incremental): b.txt",
        ) {
            mode = TestDataManagerMode.UPDATE
            incremental = true
            testDataPaths = listOf("a/b.txt")
        }
    }

    @Test
    fun `all parameters`() {
        assertBuilder(
            expectedCommand =
                "manageTestDataGlobally --mode=check --test-data-path=path/one,path/two " +
                    "--test-class-pattern=.*MyTest.* --golden-only --continue",
            expectedTitle = "Check Test Data (Golden Only): one, two",
        ) {
            mode = TestDataManagerMode.CHECK
            testDataPaths = listOf("path/one", "path/two")
            testClassPattern = ".*MyTest.*"
            goldenOnly = true
        }
    }
}
