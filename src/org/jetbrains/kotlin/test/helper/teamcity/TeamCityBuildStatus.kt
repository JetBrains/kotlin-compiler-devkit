package org.jetbrains.kotlin.test.helper.teamcity

import com.intellij.icons.AllIcons
import com.intellij.ide.nls.NlsMessages
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.AnimatedIcon
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.swing.Icon

internal enum class BuildState { QUEUED, RUNNING, FINISHED }

internal data class TestCounts(
    val passed: Int,
    val failed: Int,
    val ignored: Int,
    val muted: Int,
)

/**
 * The live status of a TeamCity build, fetched via guest REST (see [buildStatusUrl]).
 *
 * [isSuccessful] mirrors the server `status` field, which is preliminary while a build is running;
 * treat it as final only when [state] is [BuildState.FINISHED].
 */
internal data class TeamCityBuildStatus(
    val state: BuildState,
    val isSuccessful: Boolean?,
    val statusText: String?,
    val webUrl: String,
    val branchName: String?,
    val buildTypeId: String?,
    val buildTypeName: String?,
    val testCounts: TestCounts? = null,
    val startTimestamp: Long? = null,
)

internal fun isSchedulingInProgress(build: RememberedTeamCityBuild, status: TeamCityBuildStatus?): Boolean =
    (build.isAggregateTrigger || status?.buildTypeId == AGGREGATE_TRIGGER_BUILD_TYPE_ID) && status?.state != BuildState.FINISHED

internal fun isBuildUnfinished(build: RememberedTeamCityBuild, status: TeamCityBuildStatus?): Boolean =
    isSchedulingInProgress(build, status) || status?.state == BuildState.QUEUED || status?.state == BuildState.RUNNING

internal fun unfinishedBuildsOnBranch(
    builds: List<RememberedTeamCityBuild>,
    statusOf: (String) -> TeamCityBuildStatus?,
    branchName: String,
): List<RememberedTeamCityBuild> = builds.filter { build ->
    build.branchName == branchName && isBuildUnfinished(build, statusOf(build.buildId))
}

internal fun scheduledBuildFromTrigger(
    status: TeamCityBuildStatus,
    isAggregateTrigger: Boolean = false,
): LaunchedBuild? {
    if (status.state != BuildState.FINISHED) return null
    if (!isAggregateTrigger && status.buildTypeId != AGGREGATE_TRIGGER_BUILD_TYPE_ID) return null

    val buildId = status.statusText?.let(scheduledBuildUrlPattern::matchEntire)?.groupValues?.get(1) ?: return null
    return LaunchedBuild(buildId, "$TEAMCITY_SERVER_URL/build/$buildId")
}

private val scheduledBuildUrlPattern =
    Regex("""https://buildserver\.labs\.intellij\.net/build/(\d+)""")

/**
 * No credentials are needed for guest REST queries, only network access to the server (e.g. VPN).
 */
internal fun buildStatusUrl(buildId: String): String =
    "$TEAMCITY_SERVER_URL/guestAuth/app/rest/builds/id:$buildId" +
        "?fields=id,state,status,statusText,webUrl,branchName,startDate,buildType(id,name),testOccurrences(count,passed,failed,ignored,muted)"

internal fun elapsedStartTimestamp(
    build: RememberedTeamCityBuild,
    status: TeamCityBuildStatus?,
    isScheduling: Boolean,
): Long? = when {
    isScheduling -> build.launchTimestamp
    status?.state == BuildState.RUNNING -> status.startTimestamp ?: build.launchTimestamp
    else -> null
}

/**
 * Whole seconds only (at least one), so the label never shows milliseconds.
 */
internal fun formatElapsed(millis: Long): String =
    NlsMessages.formatDurationApproximateNarrow((millis / 1_000).coerceAtLeast(1) * 1_000)

internal val buildSuccessIcon: Icon =
    IconLoader.getIcon("/icons/test_federation_success.svg", TeamCityBuildStatus::class.java)

internal val buildFailureIcon: Icon =
    IconLoader.getIcon("/icons/test_federation_failure.svg", TeamCityBuildStatus::class.java)

/**
 * A grey "nothing to report yet" marker for queued builds.
 */
internal val buildQueuedIcon: Icon = AllIcons.RunConfigurations.TestNotRan

internal val buildRunningIcon: Icon = AnimatedIcon.Default.INSTANCE

/**
 * Neutral fallback for unreadable or ambiguous statuses: the plain federation mark.
 */
internal val buildUnknownIcon: Icon =
    IconLoader.getIcon("/icons/test_federation.svg", TeamCityBuildStatus::class.java)

internal fun buildStatusIcon(status: TeamCityBuildStatus?): Icon = when {
    status == null -> buildUnknownIcon
    status.state == BuildState.QUEUED -> buildQueuedIcon
    status.state == BuildState.RUNNING -> buildRunningIcon
    status.state == BuildState.FINISHED && status.isSuccessful == true -> buildSuccessIcon
    status.state == BuildState.FINISHED && status.isSuccessful == false -> buildFailureIcon
    else -> buildUnknownIcon
}

/**
 * Parses a single-build REST response.
 *
 * Missing optional fields (status, statusText, branchName, buildType) are tolerated — queued builds have none of them.
 * Returns null if [json] is not readable, so callers can keep the last known status.
 */
internal fun parseBuildStatus(json: String, fallbackUrl: String): TeamCityBuildStatus? {
    val root = try {
        Json.parseToJsonElement(json)
    } catch (_: SerializationException) {
        return null
    }

    if (root !is JsonObject) return null

    val state = when (root.stringOrNull("state")) {
        "queued" -> BuildState.QUEUED
        "running" -> BuildState.RUNNING
        "finished" -> BuildState.FINISHED
        else -> return null
    }

    val isSuccessful = when (root.stringOrNull("status")) {
        "SUCCESS" -> true
        "FAILURE" -> false
        else -> null
    }

    val buildType = root["buildType"] as? JsonObject
    val testOccurrences = root["testOccurrences"] as? JsonObject

    return TeamCityBuildStatus(
        state = state,
        isSuccessful = isSuccessful,
        statusText = root.stringOrNull("statusText"),
        webUrl = root.stringOrNull("webUrl") ?: fallbackUrl,
        branchName = root.stringOrNull("branchName"),
        buildTypeId = buildType?.stringOrNull("id"),
        buildTypeName = buildType?.stringOrNull("name"),
        testCounts = testOccurrences?.let { occurrences ->
            TestCounts(
                passed = occurrences.intOrZero("passed"),
                failed = occurrences.intOrZero("failed"),
                ignored = occurrences.intOrZero("ignored"),
                muted = occurrences.intOrZero("muted"),
            )
        },
        startTimestamp = root.stringOrNull("startDate")?.let(::parseTeamCityDate),
    )
}

private fun JsonObject.stringOrNull(key: String): String? {
    val value = get(key) as? JsonPrimitive ?: return null
    return if (value.isString) value.content else null
}

private fun JsonObject.intOrZero(key: String): Int =
    (get(key) as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

private val teamCityDateFormatter = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssZ", Locale.ROOT)

private fun parseTeamCityDate(value: String): Long? = try {
    OffsetDateTime.parse(value, teamCityDateFormatter).toInstant().toEpochMilli()
} catch (_: DateTimeParseException) {
    null
}
