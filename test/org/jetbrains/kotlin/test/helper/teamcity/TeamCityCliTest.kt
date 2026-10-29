package org.jetbrains.kotlin.test.helper.teamcity

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeamCityCliTest {
    @Test
    fun buildsRunStartCommandForExplicitBranchWithoutPushing() {
        val workingDirectory = File("/checkout/kotlin")

        val commandLine = runStartCommandLine(
            cliPath = "/usr/local/bin/teamcity",
            buildTypeId = AGGREGATE_TRIGGER_BUILD_TYPE_ID,
            branchName = "feature/kt-123456",
            workingDirectory = workingDirectory,
        )

        assertEquals("/usr/local/bin/teamcity", commandLine.exePath)
        assertEquals(
            listOf("run", "start", AGGREGATE_TRIGGER_BUILD_TYPE_ID, "--branch", "feature/kt-123456", "--no-push", "--json", "--no-input"),
            commandLine.parametersList.parameters,
        )
        assertEquals(workingDirectory.toPath(), commandLine.workingDirectory)
    }

    @Test
    fun buildsRunCancelCommandWithoutPrompting() {
        val workingDirectory = File("/checkout/kotlin")
        val commandLine = runCancelCommandLine("/usr/local/bin/teamcity", "12345", workingDirectory)

        assertEquals(
            listOf("run", "cancel", "12345", "--yes", "--comment", "Stopped from Kotlin Compiler DevKit", "--no-input"),
            commandLine.parametersList.parameters,
        )
        assertEquals(workingDirectory.toPath(), commandLine.workingDirectory)
    }

    @Test
    fun buildsAuthAndVersionCommands() {
        val statusCommandLine = authStatusCommandLine("/usr/bin/teamcity", null)
        assertEquals(
            listOf("auth", "status", "--json", "--no-input"),
            statusCommandLine.parametersList.parameters,
        )
        assertNull(statusCommandLine.workingDirectory)

        val loginCommandLine = authLoginCommandLine("/usr/bin/teamcity", null)
        assertEquals(
            listOf("auth", "login", "--server", "$TEAMCITY_SERVER_URL/"),
            loginCommandLine.parametersList.parameters,
        )

        val versionCommandLine = versionCommandLine("/usr/bin/teamcity", null)
        assertEquals(listOf("--version"), versionCommandLine.parametersList.parameters)
    }

    @Test
    fun parsesAuthenticatedStatusForOurServerOnly() {
        assertTrue(parseAuthStatus(AUTHENTICATED_STATUS_RESPONSE))
        assertTrue(
            parseAuthStatus(
                """
                [
                  {"server": "https://buildserver.labs.intellij.net/", "status": "authenticated"}
                ]
                """.trimIndent(),
            ),
        )

        assertFalse(
            parseAuthStatus(
                """
                [{"server": "https://buildserver.labs.intellij.net", "status": "unauthenticated"}]
                """.trimIndent(),
            ),
        )
        assertFalse(parseAuthStatus("""[{"server": "https://other.server", "status": "authenticated"}]"""))

        assertFalse(parseAuthStatus("<html>Login required</html>"))
        assertFalse(parseAuthStatus("["))
        assertFalse(parseAuthStatus("{}"))
        assertFalse(parseAuthStatus("[]"))
        assertFalse(parseAuthStatus("""[{"wrong": "shape"}]"""))
    }

    @Test
    fun parsesAuthenticatedStatusWrappedInAnsiColors() {
        val coloredStatus = AUTHENTICATED_STATUS_RESPONSE
            .replace("[", "\u001B[32m[\u001B[0m")
            .replace("authenticated", "\u001B[32mauthenticated\u001B[0m")

        assertTrue(parseAuthStatus(coloredStatus))
    }

    @Test
    fun parsesLaunchedBuildFromJsonOutput() {
        assertEquals(
            LaunchedBuild("1111002160", "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_AggregateTrigger/1111002160"),
            parseLaunchedBuild(
                """
                {
                  "id": 1111002160,
                  "buildTypeId": "Kotlin_KotlinDev_AggregateTrigger",
                  "state": "queued",
                  "branchName": "feature/kt-123456",
                  "webUrl": "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_AggregateTrigger/1111002160"
                }
                """.trimIndent(),
            ),
        )

        /* Without a webUrl, the build page is derived from the id. */
        assertEquals(
            LaunchedBuild("42", "$TEAMCITY_SERVER_URL/build/42"),
            parseLaunchedBuild("""{"id": 42, "state": "queued"}"""),
        )

        assertNull(parseLaunchedBuild("""{"id": "not-a-number"}"""))
        assertNull(parseLaunchedBuild("""{"state": "queued"}"""))
        assertNull(parseLaunchedBuild(""))
    }

    @Test
    fun parsesLaunchedBuildFromPlainTextOutput() {
        val plainOutput = """
            Queued run 12345678  #1234 for Kotlin_KotlinDev_AggregateTrigger
              URL: https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_AggregateTrigger/12345678
        """.trimIndent()

        assertEquals(
            LaunchedBuild("12345678", "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_AggregateTrigger/12345678"),
            parseLaunchedBuild(plainOutput),
        )

        /* Links that don't point to a build are skipped. */
        assertEquals(
            LaunchedBuild("1111002160", "https://buildserver.labs.intellij.net/build/1111002160"),
            parseLaunchedBuild(
                "see https://buildserver.labs.intellij.net/project/Kotlin, then https://buildserver.labs.intellij.net/build/1111002160.",
            ),
        )

        assertEquals(
            LaunchedBuild("1", "https://buildserver.labs.intellij.net/build/1"),
            parseLaunchedBuild("\u001B[32mhttps://buildserver.labs.intellij.net/build/1\u001B[0m"),
        )

        assertNull(parseLaunchedBuild("Run started successfully, no link here."))
    }

    @Test
    fun parsesCliErrorEnvelope() {
        assertEquals(
            "branch not found\nPush the branch first",
            parseCliErrorMessage("""{"error": {"code": "not_found", "message": "branch not found", "suggestion": "Push the branch first"}}"""),
        )
        assertEquals("unauthorized", parseCliErrorMessage("""{"error": {"code": "auth", "message": "unauthorized"}}"""))

        assertNull(parseCliErrorMessage("Error: something went wrong"))
        assertNull(parseCliErrorMessage("""{"error": {"message": ""}}"""))
        assertNull(parseCliErrorMessage(""))
    }

    @Test
    fun extractsBuildIdFromBuildUrl() {
        assertEquals("1110600602", buildIdFromBuildUrl("https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_Aggregate/1110600602"))
        assertEquals("1111002160", buildIdFromBuildUrl("https://buildserver.labs.intellij.net/build/1111002160"))
        assertEquals("1111002160", buildIdFromBuildUrl("https://buildserver.labs.intellij.net/build/1111002160/"))
        assertEquals("1110600602", buildIdFromBuildUrl("https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_Aggregate/1110600602?buildTab=log#top"))
        assertEquals("123", buildIdFromBuildUrl("https://buildserver.labs.intellij.net/viewLog.html?buildId=123&tab=buildLog"))

        assertNull(buildIdFromBuildUrl("https://buildserver.labs.intellij.net"))
        assertNull(buildIdFromBuildUrl("https://buildserver.labs.intellij.net/viewType.html?buildTypeId=Kotlin_KotlinDev_AggregateTrigger"))
    }

    @Test
    fun buildsBuildConfigurationUrlWithEncodedBranch() {
        assertEquals(
            "$TEAMCITY_SERVER_URL/buildConfiguration/Kotlin_KotlinDev_AggregateTrigger?branch=feature%2Fkt-1+x%26y&mode=builds",
            buildConfigurationUrl(AGGREGATE_TRIGGER_BUILD_TYPE_ID, "feature/kt-1 x&y"),
        )
    }

    @Test
    fun parsesAndComparesCliVersions() {
        assertEquals("1.6.1", parseCliVersion("teamcity version 1.6.1"))
        assertEquals("1.6.1", parseCliVersion("teamcity version 1.6.1-rc1"))
        assertNull(parseCliVersion("teamcity version unknown"))

        assertEquals("1.6.1", MINIMUM_TEAMCITY_CLI_VERSION)
        assertTrue(isVersionAtLeast("1.6.1", MINIMUM_TEAMCITY_CLI_VERSION))
        assertTrue(isVersionAtLeast("1.10.0", MINIMUM_TEAMCITY_CLI_VERSION))
        assertTrue(isVersionAtLeast("2.0.0", MINIMUM_TEAMCITY_CLI_VERSION))
        assertTrue(isVersionAtLeast("1.7", MINIMUM_TEAMCITY_CLI_VERSION))
        assertTrue(isVersionAtLeast("1.3", "1.3.0"))

        assertFalse(isVersionAtLeast("1.6.0", MINIMUM_TEAMCITY_CLI_VERSION))
        assertFalse(isVersionAtLeast("1.6", MINIMUM_TEAMCITY_CLI_VERSION))
        assertFalse(isVersionAtLeast("1.3.0", MINIMUM_TEAMCITY_CLI_VERSION))
        assertFalse(isVersionAtLeast("0.9.0", MINIMUM_TEAMCITY_CLI_VERSION))
        assertFalse(isVersionAtLeast(null, MINIMUM_TEAMCITY_CLI_VERSION))
        assertFalse(isVersionAtLeast("garbage", MINIMUM_TEAMCITY_CLI_VERSION))
    }

    @Test
    fun installHintMirrorsTheScriptHint() {
        val hint = cliInstallHint()

        assertTrue("brew install jetbrains/utils/teamcity" in hint)
        assertTrue("curl -fsSL https://jb.gg/tc/install | bash" in hint)
        assertTrue("winget install JetBrains.TeamCityCLI" in hint)
        assertTrue("https://www.jetbrains.com/help/teamcity/teamcity-cli-get-started.html#install" in hint)
    }
}

private val AUTHENTICATED_STATUS_RESPONSE = """
    [
      {
        "server": "https://buildserver.labs.intellij.net",
        "auth_method": "token",
        "token_source": "keyring",
        "user": {"id": 0, "username": "some.user", "name": "Some User"},
        "server_info": {"version_major": 2026, "version_minor": 3, "build_number": "266911"},
        "token_expiry": "2026-11-28T12:33:09.628Z",
        "status": "authenticated"
      }
    ]
""".trimIndent()
