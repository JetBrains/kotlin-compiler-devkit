package org.jetbrains.kotlin.test.helper.teamcity

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.util.text.VersionComparatorUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.kotlin.test.helper.MyBundle
import org.jetbrains.kotlin.test.helper.decodeTeamCityJsonOrNull
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal const val TEAMCITY_SERVER_URL: String = "https://buildserver.labs.intellij.net"

/**
 * The same build configuration a GitHub /dry-run comment and scripts/teamcity-run.sh trigger.
 */
internal const val AGGREGATE_TRIGGER_BUILD_TYPE_ID: String = "Kotlin_KotlinDev_AggregateTrigger"

/**
 * The first version `run start --json --no-input --no-push` was verified with.
 */
internal const val MINIMUM_TEAMCITY_CLI_VERSION: String = "1.6.1"

private val buildUrlPattern = Regex("""https://buildserver\.labs\.intellij\.net/[^\s"'<>]+""")

private val buildIdQueryPattern = Regex("""[?&]buildId=(\d+)""")

/**
 * Install locations of the CLI that may be missing from the IDE's PATH (e.g. when launched from the Dock).
 */
private val fallbackCliLocations = listOf("/opt/homebrew/bin/teamcity", "/usr/local/bin/teamcity")

private val ansiEscapePattern = Regex("""\u001B\[[0-9;]*[A-Za-z]""")

private val cliVersionPattern = Regex("""\b\d+\.\d+\.\d+\b""")

/**
 * Builds the command line that triggers [buildTypeId] on TeamCity for [branchName].
 *
 * `--no-push` is always passed: pushing must never happen as a side effect of launching tests from the IDE.
 * `--json` makes the launched build parseable (see [parseLaunchedBuild]).
 */
internal fun runStartCommandLine(
    cliPath: String,
    buildTypeId: String,
    branchName: String,
    workingDirectory: File?,
): GeneralCommandLine =
    teamCityCommandLine(cliPath, workingDirectory)
        .withParameters("run", "start", buildTypeId, "--branch", branchName, "--no-push", "--json", "--no-input")

internal fun runCancelCommandLine(cliPath: String, buildId: String, workingDirectory: File?): GeneralCommandLine =
    teamCityCommandLine(cliPath, workingDirectory)
        .withParameters("run", "cancel", buildId, "--yes", "--comment", "Stopped from Kotlin Compiler DevKit", "--no-input")

internal fun authStatusCommandLine(cliPath: String, workingDirectory: File?): GeneralCommandLine =
    teamCityCommandLine(cliPath, workingDirectory)
        .withParameters("auth", "status", "--json", "--no-input")

/**
 * Login stays interactive on purpose: the CLI opens a browser login flow on first use.
 */
internal fun authLoginCommandLine(cliPath: String, workingDirectory: File?): GeneralCommandLine =
    teamCityCommandLine(cliPath, workingDirectory)
        .withParameters("auth", "login", "--server", "$TEAMCITY_SERVER_URL/")

internal fun versionCommandLine(cliPath: String, workingDirectory: File?): GeneralCommandLine =
    teamCityCommandLine(cliPath, workingDirectory)
        .withParameters("--version")

@Serializable
internal data class AuthStatusEntry(val server: String? = null, val status: String? = null)

/**
 * Whether the CLI is already authenticated for our server.
 *
 * `teamcity auth status --json --no-input` prints an array with one entry per known server,
 * e.g. [{"server": "https://buildserver.labs.intellij.net", ..., "status": "authenticated"}].
 * Anything unreadable counts as not authenticated, so callers fall back to an explicit login.
 */
internal fun parseAuthStatus(output: String): Boolean =
    decodeTeamCityJsonOrNull<List<AuthStatusEntry>>(stripAnsiEscapes(output))
        ?.any { entry -> entry.server?.trimEnd('/') == TEAMCITY_SERVER_URL && entry.status == "authenticated" }
        ?: false

internal data class LaunchedBuild(val buildId: String, val webUrl: String)

/**
 * The build printed by `run start --json`; the id is a primitive because the CLI reports it
 * either as a number or as a string.
 */
@Serializable
internal data class LaunchedBuildJson(val id: JsonPrimitive? = null, val webUrl: String? = null)

/**
 * The build started by `run start --json`, which prints the queued build as one JSON object
 * (e.g. {"id": 1111002160, "webUrl": "https://.../buildConfiguration/<build type>/1111002160", ...}).
 * Falls back to the first build link in plain text output; null when no build can be identified.
 */
internal fun parseLaunchedBuild(stdout: String): LaunchedBuild? {
    val plainOutput = stripAnsiEscapes(stdout)
    val buildJson = decodeTeamCityJsonOrNull<LaunchedBuildJson>(plainOutput)
    val buildId = buildJson?.id?.content?.takeIf(::isBuildId)
        ?: return launchedBuildFromPlainText(plainOutput)

    return LaunchedBuild(buildId, buildJson.webUrl ?: "$TEAMCITY_SERVER_URL/build/$buildId")
}

private fun launchedBuildFromPlainText(output: String): LaunchedBuild? =
    buildUrlPattern.findAll(output)
        .map { match -> match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}') }
        .firstNotNullOfOrNull { url -> buildIdFromBuildUrl(url)?.let { buildId -> LaunchedBuild(buildId, url) } }

@Serializable
internal data class CliErrorEnvelope(val error: CliErrorJson? = null)

@Serializable
internal data class CliErrorJson(val message: String? = null, val suggestion: String? = null)

/**
 * The message of the `{"error": {"message": ..., "suggestion": ...}}` envelope the CLI prints to stderr with `--json`.
 */
internal fun parseCliErrorMessage(stderr: String): String? {
    val error = decodeTeamCityJsonOrNull<CliErrorEnvelope>(stripAnsiEscapes(stderr))?.error ?: return null
    val message = error.message?.takeIf(String::isNotBlank) ?: return null
    val suggestion = error.suggestion?.takeIf(String::isNotBlank)

    return if (suggestion == null) message else "$message\n$suggestion"
}

/**
 * TeamCity web URLs end with the numeric build id, both for queued builds (/build/<id>)
 * and started ones (/buildConfiguration/<build type>/<id>); legacy ones carry it as `buildId=<id>`.
 * Query and fragment are ignored. Anything else (e.g. a bare host) counts as "no build id".
 */
internal fun buildIdFromBuildUrl(url: String): String? {
    buildIdQueryPattern.find(url)?.let { match -> return match.groupValues[1] }

    return url.substringBefore('#').substringBefore('?').trimEnd('/')
        .substringAfterLast('/')
        .takeIf(::isBuildId)
}

/**
 * The TeamCity page listing the builds of [buildTypeId] for [branchName].
 */
internal fun buildConfigurationUrl(buildTypeId: String, branchName: String): String =
    "$TEAMCITY_SERVER_URL/buildConfiguration/$buildTypeId?branch=" +
        URLEncoder.encode(branchName, StandardCharsets.UTF_8) + "&mode=builds"

/**
 * The `teamcity` executable on the PATH or in a well-known install location.
 */
internal fun findCliExecutable(): File? =
    PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("teamcity")
        ?: fallbackCliLocations.map(::File).firstOrNull(File::canExecute)

internal fun parseCliVersion(output: String): String? =
    cliVersionPattern.find(stripAnsiEscapes(output))?.value

internal fun isVersionAtLeast(version: String?, minimum: String): Boolean =
    version != null && VersionComparatorUtil.compare(version, minimum) >= 0

/**
 * Mirrors the install hint printed by scripts/teamcity-run.sh.
 */
internal fun cliInstallHint(): String = MyBundle.message("teamcity.cli.install.hint")

private fun teamCityCommandLine(cliPath: String, workingDirectory: File?): GeneralCommandLine =
    GeneralCommandLine(cliPath)
        .withWorkingDirectory(workingDirectory?.toPath())

private fun stripAnsiEscapes(output: String): String =
    ansiEscapePattern.replace(output, "")

private fun isBuildId(text: String): Boolean =
    text.isNotEmpty() && text.all(Char::isDigit)
