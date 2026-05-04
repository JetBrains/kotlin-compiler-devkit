package org.jetbrains.kotlin.test.helper.actions.test.data.manager

enum class TestDataManagerMode(val cliValue: String) {
    CHECK("check"),
    UPDATE("update"),
}

/**
 * Property prefix used by the `updateTestData` task to receive options.
 *
 * Defined in the Kotlin repo at `repo/gradle-build-conventions/test-data-manager-convention/src/main/kotlin/TestDataManagerConstants.kt`.
 */
private const val UPDATE_OPTIONS_PREFIX = "org.jetbrains.kotlin.testDataManager.options"

/**
 * Builds a Gradle command for either `manageTestDataGlobally` (the default) or `updateTestData`
 * (when [updateTestDataIsAvailable] is `true` and [mode] is [TestDataManagerMode.UPDATE]).
 *
 * `updateTestData` is a configuration-cache-friendly alternative to
 * `manageTestDataGlobally --mode=update`: its options are passed as `-P` Gradle properties read
 * at execution time, so changing values between runs does not invalidate Gradle's configuration
 * cache. The task only handles the update mode, so it is selected only for
 * [TestDataManagerMode.UPDATE]; other modes always fall back to `manageTestDataGlobally`.
 *
 * See `repo/gradle-build-conventions/test-data-manager-convention` in the Kotlin repo for more details.
 */
class TestDataManagerCommandBuilder {
    var mode: TestDataManagerMode? = null
    var testDataPaths: List<String> = emptyList()
    var testClassPattern: String? = null
    var goldenOnly: Boolean? = null
    var incremental: Boolean? = false

    /**
     * Whether the linked Gradle project ships the dedicated `updateTestData` task. When `true`
     * and [mode] is [TestDataManagerMode.UPDATE], the builder emits `updateTestData` with `-P`
     * properties; otherwise it emits `manageTestDataGlobally` with `--option` CLI flags.
     */
    var updateTestDataIsAvailable: Boolean = false

    private val isUpdateTask: Boolean
        get() = updateTestDataIsAvailable && mode == TestDataManagerMode.UPDATE

    fun build(): String = buildString {
        append(buildTaskPart())
        appendOption(
            cliKey = "test-data-path",
            propKey = "testDataPath",
            value = testDataPaths.takeIf { it.isNotEmpty() }?.joinToString(","),
        )

        appendOption(cliKey = "test-class-pattern", propKey = "testClassPattern", value = testClassPattern)
        appendBooleanFlag(cliKey = "golden-only", propKey = "goldenOnly", value = goldenOnly)
        appendBooleanFlag(cliKey = "incremental", propKey = "incremental", value = incremental)
        append(" --continue")
    }

    private fun buildTaskPart(): String =
        if (isUpdateTask) {
            "updateTestData"
        } else {
            buildString {
                append("manageTestDataGlobally")
                mode?.let { append(" --mode=${it.cliValue}") }
            }
        }

    private fun StringBuilder.appendOption(cliKey: String, propKey: String, value: String?) {
        if (value == null) return
        append(' ')
        if (isUpdateTask) {
            append("-P$UPDATE_OPTIONS_PREFIX.$propKey=$value")
        } else {
            append("--$cliKey=$value")
        }
    }

    private fun StringBuilder.appendBooleanFlag(cliKey: String, propKey: String, value: Boolean?) {
        if (value != true) return
        append(' ')
        if (isUpdateTask) {
            append("-P$UPDATE_OPTIONS_PREFIX.$propKey=true")
        } else {
            append("--$cliKey")
        }
    }

    fun asTitle(): String = buildString {
        when {
            isUpdateTask || mode == TestDataManagerMode.UPDATE -> append("Update")
            mode == TestDataManagerMode.CHECK -> append("Check")
            else -> append("Manage")
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
    updateTestDataIsAvailable: Boolean = false,
    configure: TestDataManagerCommandBuilder.() -> Unit = {},
): String = TestDataManagerCommandBuilder().apply {
    this.updateTestDataIsAvailable = updateTestDataIsAvailable
}.apply(configure).build()
