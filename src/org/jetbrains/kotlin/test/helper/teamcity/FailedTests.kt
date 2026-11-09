package org.jetbrains.kotlin.test.helper.teamcity

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.serialization.Serializable
import org.jetbrains.kotlin.test.helper.SingleObjectAsListSerializer
import org.jetbrains.kotlin.test.helper.decodeTeamCityJsonOrNull

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

@Serializable
internal data class FailedTestsResponse(
    val count: Int? = null,
    @Serializable(with = OccurrencesListSerializer::class)
    val testOccurrence: List<TestOccurrenceJson>? = null,
)

private object OccurrencesListSerializer :
    SingleObjectAsListSerializer<TestOccurrenceJson>(TestOccurrenceJson.serializer())

@Serializable
internal data class TestOccurrenceJson(
    val name: String? = null,
    val muted: Boolean? = null,
    val test: TestJson? = null,
    val build: OccurrenceBuildJson? = null,
)

@Serializable
internal data class TestJson(val parsedTestName: ParsedTestNameJson? = null)

@Serializable
internal data class ParsedTestNameJson(
    val testPackage: String? = null,
    val testClass: String? = null,
    val testMethodName: String? = null,
    val testNameWithParameters: String? = null,
)

@Serializable
internal data class OccurrenceBuildJson(val buildType: BuildTypeNameJson? = null)

@Serializable
internal data class BuildTypeNameJson(val name: String? = null)

internal fun parseFailedTests(json: String): FailedTestsResult {
    val response = decodeTeamCityJsonOrNull<FailedTestsResponse>(json)
        ?: return FailedTestsResult(emptyList(), 0)

    val tests = response.testOccurrence
        .orEmpty()
        .take(MAX_FAILED_TESTS)
        .map(::failedTest)

    return FailedTestsResult(tests, response.count?.coerceAtLeast(tests.size) ?: tests.size)
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

private fun failedTest(occurrence: TestOccurrenceJson): FailedTest {
    val parsedName = occurrence.test?.parsedTestName

    return FailedTest(
        name = occurrence.name,
        packageName = parsedName?.testPackage,
        className = parsedName?.testClass,
        methodName = parsedName?.testMethodName,
        displayName = parsedName?.testNameWithParameters
            ?: occurrence.name
            ?: parsedName?.testMethodName
            ?: parsedName?.testClass
            ?: "Unknown test",
        isMuted = occurrence.muted ?: false,
        buildTypeName = occurrence.build?.buildType?.name,
    )
}