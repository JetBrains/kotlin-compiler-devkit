package org.jetbrains.kotlin.test.helper.actions.test.data.manager

enum class TestDataManagerMode {
    CHECK,
    UPDATE,
}

/**
 * Property prefix used by the test data manager tasks to receive options.
 *
 * Defined in the Kotlin repo at `repo/gradle-build-conventions/test-data-manager-convention/src/main/kotlin/TestDataManagerConstants.kt`.
 */
private const val OPTIONS_PREFIX = "org.jetbrains.kotlin.testDataManager.options"

/**
 * Builds a Gradle command for the test data manager tasks: `checkTestData` ([TestDataManagerMode.CHECK])
 * or `updateTestData` ([TestDataManagerMode.UPDATE]).
 *
 * Both are per-module `JavaExec` tasks with a fixed mode; there is no `manageTestDataGlobally`
 * orchestrator and no `--mode` flag anymore. Running a bare task name from the repo root fans out
 * to every module that applies the `test-data-manager` plugin via Gradle task-name matching.
 *
 * Options are passed exclusively as `-P` Gradle properties (read by the tasks at execution time),
 * never as `--option` CLI flags. This keeps Gradle's configuration cache valid when option values
 * change between runs.
 *
 * See `repo/gradle-build-conventions/test-data-manager-convention` in the Kotlin repo for more details.
 */
class TestDataManagerCommandBuilder {
    var mode: TestDataManagerMode = TestDataManagerMode.CHECK
    var testDataPaths: List<String> = emptyList()
    var testClassPattern: String? = null
    var goldenOnly: Boolean? = null
    var incremental: Boolean? = false

    fun build(): String = buildString {
        append(buildTaskPart())
        appendOption(propKey = "testDataPath", value = testDataPaths.takeIf { it.isNotEmpty() }?.joinToString(","))
        appendOption(propKey = "testClassPattern", value = testClassPattern)
        appendBooleanFlag(propKey = "goldenOnly", value = goldenOnly)
        appendBooleanFlag(propKey = "incremental", value = incremental)
        append(" --continue")
    }

    private fun buildTaskPart(): String = when (mode) {
        TestDataManagerMode.UPDATE -> "updateTestData"
        TestDataManagerMode.CHECK -> "checkTestData"
    }

    private fun StringBuilder.appendOption(propKey: String, value: String?) {
        if (value == null) return
        append(" -P$OPTIONS_PREFIX.$propKey=$value")
    }

    private fun StringBuilder.appendBooleanFlag(propKey: String, value: Boolean?) {
        if (value != true) return
        append(" -P$OPTIONS_PREFIX.$propKey=true")
    }

    fun asTitle(): String = buildString {
        when (mode) {
            TestDataManagerMode.UPDATE -> append("Update")
            TestDataManagerMode.CHECK -> append("Check")
        }

        append(" Test Data")
        if (goldenOnly == true) {
            append(" (Golden Only)")
        }

        if (incremental == true) {
            append(" (Incremental)")
        }

        if (testDataPaths.isNotEmpty()) {
            append(": ")
            append(testDataPaths.joinToString { it.substringAfterLast('/') })
        }
    }
}

fun buildTestDataManagerCommand(
    configure: TestDataManagerCommandBuilder.() -> Unit = {},
): String = TestDataManagerCommandBuilder().apply(configure).build()
