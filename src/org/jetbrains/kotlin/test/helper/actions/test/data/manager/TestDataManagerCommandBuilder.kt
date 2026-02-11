package org.jetbrains.kotlin.test.helper.actions.test.data.manager

enum class TestDataManagerMode(val cliValue: String) {
    CHECK("check"),
    UPDATE("update"),
}

/**
 * Represents a command for the `manageTestDataGlobally` gradle task.
 *
 * See `repo/gradle-build-conventions/test-data-manager-convention` in the Kotlin repo for more details.
 */
class TestDataManagerCommandBuilder {
    var mode: TestDataManagerMode? = null
    var testDataPaths: List<String> = emptyList()
    var testClassPattern: String? = null
    var goldenOnly: Boolean? = null

    fun build(): String = buildString {
        append("manageTestDataGlobally")
        mode?.let { append(" --mode=${it.cliValue}") }
        if (testDataPaths.isNotEmpty()) {
            append(" --test-data-path=${testDataPaths.joinToString(",")}")
        }

        testClassPattern?.let { append(" --test-class-pattern=$it") }
        goldenOnly?.let { if (it) append(" --golden-only") }
        append(" --continue")
    }

    fun asTitle(): String = buildString {
        when (mode) {
            TestDataManagerMode.CHECK -> append("Check")
            TestDataManagerMode.UPDATE -> append("Update")
            null -> append("Manage")
        }
        append(" Test Data")
        if (goldenOnly == true) {
            append(" (Golden Only)")
        }
        if (testDataPaths.isNotEmpty()) {
            append(": ")
            append(testDataPaths.joinToString { it.substringAfterLast('/') })
        }
    }
}

fun buildTestDataManagerCommand(configure: TestDataManagerCommandBuilder.() -> Unit = {}): String {
    return TestDataManagerCommandBuilder().apply(configure).build()
}
