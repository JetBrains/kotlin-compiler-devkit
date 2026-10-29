package org.jetbrains.kotlin.test.helper.teamcity

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val MAX_FAILED_TESTS = 200

internal data class FailedTest(
    val name: String?,
    val packageName: String?,
    val className: String?,
    val methodName: String?,
    val displayName: String,
    val isMuted: Boolean,
    val buildTypeName: String?,
)

internal data class FailedTestsResult(
    val tests: List<FailedTest>,
    val totalCount: Int,
) {
    val remainingCount: Int
        get() = (totalCount - tests.size).coerceAtLeast(0)
}

internal fun failedTestsUrl(buildId: String): String =
    "$TEAMCITY_SERVER_URL/guestAuth/app/rest/testOccurrences" +
        "?locator=build:(id:$buildId),status:FAILURE,count:$MAX_FAILED_TESTS" +
        "&fields=count,testOccurrence(name,muted,test(parsedTestName(testPackage,testClass,testMethodName,testNameWithParameters)),build(buildType(name)))"

internal fun parseFailedTests(json: String): FailedTestsResult {
    val root = try {
        Json.parseToJsonElement(json) as? JsonObject ?: return FailedTestsResult(emptyList(), 0)
    } catch (_: SerializationException) {
        return FailedTestsResult(emptyList(), 0)
    }

    val occurrences = when (val value = root["testOccurrence"]) {
        is JsonArray -> value.filterIsInstance<JsonObject>()
        is JsonObject -> listOf(value)
        else -> emptyList()
    }
    val tests = occurrences.take(MAX_FAILED_TESTS).map(::parseFailedTest)
    val totalCount = root.intOrNull("count")?.coerceAtLeast(tests.size) ?: tests.size

    return FailedTestsResult(tests, totalCount)
}

internal fun failedTestClassFqName(test: FailedTest): String? {
    val className = test.className?.takeIf(String::isNotBlank) ?: return null
    val qualifiedName = when {
        '.' in className || test.packageName.isNullOrBlank() -> className
        else -> "${test.packageName}.$className"
    }
    return qualifiedName.replace('$', '.')
}

internal fun findFailedTestTarget(project: Project, test: FailedTest): NavigatablePsiElement? {
    val classFqName = failedTestClassFqName(test) ?: return null
    val psiClass = JavaPsiFacade.getInstance(project)
        .findClass(classFqName, GlobalSearchScope.allScope(project)) ?: return null
    val methodName = test.methodName ?: return psiClass

    return psiClass.findMethodsByName(methodName, true).firstOrNull() ?: psiClass
}

private fun parseFailedTest(occurrence: JsonObject): FailedTest {
    val test = occurrence["test"] as? JsonObject
    val parsedName = test?.get("parsedTestName") as? JsonObject
    val name = occurrence.stringOrNull("name")
    val packageName = parsedName?.stringOrNull("testPackage")
    val className = parsedName?.stringOrNull("testClass")
    val methodName = parsedName?.stringOrNull("testMethodName")
    val parameterizedName = parsedName?.stringOrNull("testNameWithParameters")
    val buildType = (occurrence["build"] as? JsonObject)
        ?.get("buildType") as? JsonObject

    return FailedTest(
        name = name,
        packageName = packageName,
        className = className,
        methodName = methodName,
        displayName = parameterizedName ?: name ?: methodName ?: className ?: "Unknown test",
        isMuted = occurrence.booleanOrFalse("muted"),
        buildTypeName = buildType?.stringOrNull("name"),
    )
}

private fun JsonObject.stringOrNull(key: String): String? {
    val value = get(key) as? JsonPrimitive ?: return null
    return if (value.isString) value.content else null
}

private fun JsonObject.intOrNull(key: String): Int? =
    (get(key) as? JsonPrimitive)?.content?.toIntOrNull()

private fun JsonObject.booleanOrFalse(key: String): Boolean =
    (get(key) as? JsonPrimitive)?.content == "true"