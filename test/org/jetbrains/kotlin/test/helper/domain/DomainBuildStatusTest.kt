package org.jetbrains.kotlin.test.helper.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class DomainBuildStatusTest {
    @Test
    fun parsesRecordedSampleResponse() {
        val statuses = parseDomainBuildStatuses(SAMPLE_RESPONSE)

        assertEquals(
            mapOf(
                Domain.of("AnalysisApi") to DomainBuildStatus(
                    isSuccess = true,
                    statusText = "Tests passed: 94190, ignored: 148",
                    webUrl = "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_Domain_AnalysisApi/100",
                ),
                Domain.of("CompilerInfrastructure") to DomainBuildStatus(
                    isSuccess = false,
                    statusText = "Tests failed: 3 (1 new), passed: 5000",
                    webUrl = "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_Domain_CompilerInfrastructure/101",
                ),
                Domain.of("Gradle") to DomainBuildStatus(
                    isSuccess = false,
                    statusText = null,
                    webUrl = "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_Domain_Gradle/102",
                ),
            ),
            statuses,
        )
    }

    @Test
    fun skipsEntriesWithoutBuildsOrWebUrl() {
        val statuses = parseDomainBuildStatuses(
            """
            {"buildType": [
              {"id": "Kotlin_KotlinDev_Domain_EmptyBuilds", "builds": {"build": []}},
              {"id": "Kotlin_KotlinDev_Domain_NoBuilds"},
              {"id": "Kotlin_KotlinDev_Domain_NoWebUrl", "builds": {"build": [{"id": 1, "status": "SUCCESS"}]}},
              {"id": "Kotlin_KotlinDev_Domain_", "builds": {"build": [{"id": 2, "status": "SUCCESS", "webUrl": "https://x"}]}}
            ]}
            """.trimIndent(),
        )

        assertEquals(emptyMap(), statuses)
    }

    @Test
    fun distinguishesMalformedJsonFromValidEmptyResponse() {
        assertNull(parseDomainBuildStatuses("<html>Login required</html>"))
        assertNull(parseDomainBuildStatuses("{\"buildType\": ["))
        assertNull(parseDomainBuildStatuses("[]"))
        assertNull(parseDomainBuildStatuses("{\"buildType\": 42}"))

        assertEquals(emptyMap(), parseDomainBuildStatuses("{}"))
        assertEquals(emptyMap(), parseDomainBuildStatuses("{\"buildType\": []}"))
    }

    @Test
    fun selectsIconByStatus() {
        val success = DomainBuildStatus(isSuccess = true, statusText = null, webUrl = "https://x")
        val failure = DomainBuildStatus(isSuccess = false, statusText = null, webUrl = "https://x")

        assertSame(domainBuildSuccessIcon, domainBuildStatusIcon(success))
        assertSame(domainBuildFailureIcon, domainBuildStatusIcon(failure))
        assertSame(domainIcon, domainBuildStatusIcon(null))
    }
}

private val SAMPLE_RESPONSE = """
{
  "buildType": [
    {
      "id": "Kotlin_KotlinDev_Aggregate",
      "builds": {"build": [{"id": 1, "status": "SUCCESS", "statusText": "Success", "webUrl": "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_Aggregate/1"}]}
    },
    {
      "id": "Kotlin_KotlinDev_Domain_AnalysisApi",
      "builds": {"build": [{"id": 100, "status": "SUCCESS", "statusText": "Tests passed: 94190, ignored: 148", "webUrl": "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_Domain_AnalysisApi/100"}]}
    },
    {
      "id": "Kotlin_KotlinDev_Domain_CompilerInfrastructure",
      "builds": {"build": [{"id": 101, "status": "FAILURE", "statusText": "Tests failed: 3 (1 new), passed: 5000", "webUrl": "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_Domain_CompilerInfrastructure/101"}]}
    },
    {
      "id": "Kotlin_KotlinDev_Domain_Gradle",
      "builds": {"build": [{"id": 102, "status": "ERROR", "webUrl": "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_Domain_Gradle/102"}]}
    }
  ]
}
""".trimIndent()
