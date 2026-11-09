package org.jetbrains.kotlin.test.helper.teamcity

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The build configuration the Aggregate Trigger schedules; its builds are the aggregate runs the tool window fetches.
 */
internal const val AGGREGATE_BUILD_TYPE_ID: String = "Kotlin_KotlinDev_Aggregate"

/**
 * How many of the newest aggregate builds are fetched per branch.
 */
internal const val FETCHED_BUILDS_PER_BRANCH: Int = 5

/**
 * TeamCity reports the default branch as `<default>`; the default branch of the Kotlin monorepo is 'master'.
 */
internal const val DEFAULT_SERVER_BRANCH_NAME: String = "<default>"

internal const val MASTER_BRANCH_NAME: String = "master"

/**
 * One aggregate build fetched from TeamCity: the tree row and its live status.
 */
internal data class FetchedTeamCityBuild(
    val build: RememberedTeamCityBuild,
    val status: TeamCityBuildStatus,
)

/**
 * The outcome of one branch-builds fetch round: the fetched builds and whether TeamCity was reachable at all.
 * [isFailed] is only true when every request of the round failed, so partial outages stay silent.
 */
internal data class FetchedBranchBuildsResult(
    val builds: List<FetchedTeamCityBuild>,
    val isFailed: Boolean,
)

/**
 * The newest [FETCHED_BUILDS_PER_BRANCH] aggregate builds of [branchName] (guest access, no login).
 *
 * 'master' is the build configuration's default branch, which TeamCity matches with `branch:(default:true)`
 * instead of its display name. The `any` flags lift TeamCity's locator defaults, which hide running (`state`)
 * and canceled builds — remote-run chains are running first and usually end canceled. Queued builds are not
 * served by this endpoint; they come from [aggregateBuildQueueUrl].
 */
internal fun aggregateBuildsUrl(branchName: String): String {
    val branchLocator = if (branchName == MASTER_BRANCH_NAME) {
        "branch:(default:true)"
    } else {
        "branch:${encodeLocatorValue(branchName)}"
    }

    return "$TEAMCITY_SERVER_URL/guestAuth/app/rest/builds" +
        "?locator=buildType:$AGGREGATE_BUILD_TYPE_ID,$branchLocator,state:any,canceled:any,count:$FETCHED_BUILDS_PER_BRANCH" +
        "&fields=build(id,state,status,statusText,webUrl,branchName,startDate,buildType(id,name)" +
        ",testOccurrences(count,passed,failed,ignored,muted))"
}

/**
 * The aggregate build queue across all branches: its locator cannot filter by branch,
 * so entries are matched against the local branches after parsing.
 */
internal fun aggregateBuildQueueUrl(): String =
    "$TEAMCITY_SERVER_URL/guestAuth/app/rest/buildQueue" +
        "?locator=buildType:$AGGREGATE_BUILD_TYPE_ID" +
        "&fields=build(id,state,branchName,queuedDate,webUrl,buildType(id,name))"

/**
 * Reads a build back into the local branch namespace: the default branch is 'master' locally,
 * and branch display names are local branch names except for the full-ref special cases.
 * Returns null when [serverBranchName] matches no local branch naming scheme.
 */
internal fun localBranchNameOfServerBranchName(serverBranchName: String): String? = when {
    serverBranchName == DEFAULT_SERVER_BRANCH_NAME -> MASTER_BRANCH_NAME
    serverBranchName.startsWith("refs/heads/") -> serverBranchName.removePrefix("refs/heads/")
    serverBranchName.isBlank() -> null
    else -> serverBranchName
}

/**
 * Parses the per-branch builds response. Every build is attributed to [localBranchName] — the branch the
 * query was for — because the server reports the default branch as `<default>`.
 *
 * A blank body means the branch is unknown to TeamCity and reads as "no builds".
 * Returns null if [json] is not readable, so callers can tell a failed request apart from an empty branch.
 */
internal fun parseFetchedBranchBuilds(json: String, localBranchName: String): List<FetchedTeamCityBuild>? {
    if (json.isBlank()) return emptyList()

    val root = parseJsonObjectOrNull(json) ?: return null
    val builds = root["build"] ?: return emptyList()
    if (builds !is JsonArray) return null

    return builds.mapNotNull { element ->
        fetchedBuildFromJson(element as? JsonObject ?: return@mapNotNull null, localBranchName)
    }
}

/**
 * Parses the aggregate build queue, keeping only entries of [localBranchNames].
 * Returns null if [json] is not readable.
 */
internal fun parseQueuedBranchBuilds(json: String, localBranchNames: Set<String>): List<FetchedTeamCityBuild>? {
    val root = parseJsonObjectOrNull(json) ?: return null
    val builds = root["build"] ?: return emptyList()
    if (builds !is JsonArray) return null

    return builds.mapNotNull { element ->
        val buildJson = element as? JsonObject ?: return@mapNotNull null
        val serverBranchName = buildJson.stringOrNull("branchName") ?: return@mapNotNull null
        val localBranchName = localBranchNameOfServerBranchName(serverBranchName) ?: return@mapNotNull null
        if (localBranchName !in localBranchNames) return@mapNotNull null

        fetchedBuildFromJson(buildJson, localBranchName)
    }
}

private fun fetchedBuildFromJson(buildJson: JsonObject, localBranchName: String): FetchedTeamCityBuild? {
    val buildId = (buildJson["id"] as? JsonPrimitive)?.content?.takeIf(::isBuildId) ?: return null

    val status = buildStatusFromJson(buildJson, "$TEAMCITY_SERVER_URL/build/$buildId") ?: return null

    /* Started builds carry startDate, queued ones only queuedDate; a build with neither cannot be ordered. */
    val launchTimestamp = status.startTimestamp
        ?: buildJson.stringOrNull("queuedDate")?.let(::parseTeamCityDate)
        ?: return null

    return FetchedTeamCityBuild(
        build = RememberedTeamCityBuild(
            buildId = buildId,
            webUrl = status.webUrl,
            branchName = localBranchName,
            launchTimestamp = launchTimestamp,
        ),
        status = status,
    )
}

private fun encodeLocatorValue(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

private fun parseJsonObjectOrNull(json: String): JsonObject? =
    try {
        Json.parseToJsonElement(json) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

private fun JsonObject.stringOrNull(key: String): String? {
    val value = get(key) as? JsonPrimitive ?: return null
    return if (value.isString) value.content else null
}
