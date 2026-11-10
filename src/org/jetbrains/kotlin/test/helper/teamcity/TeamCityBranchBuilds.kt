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
 * The build configuration that rebases pull requests and launches their quality gate in
 * [AGGREGATE_BUILD_TYPE_ID]; its builds are the safe merges the tool window follows.
 */
internal const val SAFE_MERGE_COORDINATOR_BUILD_TYPE_ID: String = "Kotlin_KotlinDev_SafeMergeCoordinator"

/**
 * How many of the newest aggregate builds are fetched per branch.
 */
internal const val FETCHED_BUILDS_PER_BRANCH: Int = 5

/**
 * How many of the most recently checked out local branches get their aggregate builds fetched.
 */
internal const val FETCHED_RECENT_BRANCH_COUNT: Int = 10

/**
 * TeamCity reports the default branch as `<default>`; the default branch of the Kotlin monorepo is 'master'.
 */
internal const val DEFAULT_SERVER_BRANCH_NAME: String = "<default>"

internal const val MASTER_BRANCH_NAME: String = "master"

/**
 * One build fetched from TeamCity: the tree row and its live status. The status is null while it is
 * unknown — e.g. a safe-merge gate whose status fetch failed — so the row keeps its last known status.
 */
internal data class FetchedTeamCityBuild(
    val build: RememberedTeamCityBuild,
    val status: TeamCityBuildStatus?,
    val coordinatorBuildId: String? = null,
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
 * One safe merge of a branch: the row it produces — the aggregate gate build once the coordinator's
 * comment names one, else the coordinator build itself, e.g. after a failed rebase — the coordinator's
 * own status, and the id of the gate build it launched.
 */
internal data class FetchedSafeMerge(
    val build: RememberedTeamCityBuild,
    val coordinatorStatus: TeamCityBuildStatus,
    val gateBuildId: String?,
    val coordinatorBuildId: String,
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
 * The newest [FETCHED_BUILDS_PER_BRANCH] Safe-Merge Coordinator builds of [branchName] (guest access, no login).
 *
 * 'master' is never queried: the coordinator's default-branch builds are chain cleanup steps, not safe
 * merges. Every coordinator build's comment names the aggregate gate build it launched, and `triggered`
 * lets the parser drop the chain cleanup entries.
 */
internal fun safeMergeCoordinatorBuildsUrl(branchName: String): String =
    "$TEAMCITY_SERVER_URL/guestAuth/app/rest/builds" +
        "?locator=buildType:$SAFE_MERGE_COORDINATOR_BUILD_TYPE_ID,branch:${encodeLocatorValue(branchName)}" +
        ",state:any,canceled:any,count:$FETCHED_BUILDS_PER_BRANCH" +
        "&fields=build(id,state,status,statusText,branchName,startDate,queuedDate,webUrl" +
        ",comment(text),triggered(type,date,user(id,username)))"

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

/**
 * Parses the Safe-Merge Coordinator builds of [localBranchName], keeping only the user-triggered
 * entries — the rest are chain cleanup steps.
 *
 * A blank body means the branch is unknown to TeamCity and reads as "no builds".
 * Returns null if [json] is not readable, so callers can tell a failed request apart from an empty branch.
 */
internal fun parseSafeMergeCoordinatorBuilds(json: String, localBranchName: String): List<FetchedSafeMerge>? {
    if (json.isBlank()) return emptyList()

    val root = parseJsonObjectOrNull(json) ?: return null
    val builds = root["build"] ?: return emptyList()
    if (builds !is JsonArray) return null

    return builds.mapNotNull { element ->
        safeMergeFromJson(element as? JsonObject ?: return@mapNotNull null, localBranchName)
    }
}

/**
 * The status of a safe merge's gate build: the cached one while it is final — finished gates are not
 * fetched again — else a fresh fetch. Null while the gate status is unknown, e.g. after a failed fetch.
 * Safe merges without a gate (failed rebase) have no gate status; the coordinator status is shown instead.
 */
internal suspend fun gateStatusOrNull(
    safeMerge: FetchedSafeMerge,
    cachedStatus: TeamCityBuildStatus?,
    fetchStatus: suspend (String) -> TeamCityBuildStatus?,
): TeamCityBuildStatus? {
    val gateBuildId = safeMerge.gateBuildId ?: return null
    if (cachedStatus?.state == BuildState.FINISHED) return cachedStatus

    return fetchStatus(gateBuildId)
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

private fun safeMergeFromJson(buildJson: JsonObject, localBranchName: String): FetchedSafeMerge? {
    /* The default branch's coordinator builds are chain cleanup steps, not safe merges. */
    val triggeredType = (buildJson["triggered"] as? JsonObject)?.stringOrNull("type")
    if (triggeredType != "user") return null

    val buildId = (buildJson["id"] as? JsonPrimitive)?.content?.takeIf(::isBuildId) ?: return null
    val coordinatorStatus = buildStatusFromJson(buildJson, "$TEAMCITY_SERVER_URL/build/$buildId") ?: return null

    /* Started builds carry startDate, queued ones only queuedDate; a build with neither cannot be ordered. */
    val launchTimestamp = coordinatorStatus.startTimestamp
        ?: buildJson.stringOrNull("queuedDate")?.let(::parseTeamCityDate)
        ?: return null

    val comment = (buildJson["comment"] as? JsonObject)?.stringOrNull("text")
    val gateBuildId = comment?.let(::gateBuildIdOfComment)

    /* The row follows the gate: once the comment names it, the gate build replaces the coordinator build. */
    return FetchedSafeMerge(
        build = RememberedTeamCityBuild(
            buildId = gateBuildId ?: buildId,
            webUrl = if (gateBuildId == null) {
                coordinatorStatus.webUrl
            } else {
                "$TEAMCITY_SERVER_URL/build/$gateBuildId"
            },
            branchName = localBranchName,
            launchTimestamp = launchTimestamp,
            isSafeMerge = true,
            isSafeMergeDryRun = comment?.startsWith("DRY RUN ") == true,
        ),
        coordinatorStatus = coordinatorStatus,
        gateBuildId = gateBuildId,
        coordinatorBuildId = buildId,
    )
}

/**
 * The gate build id the coordinator's comment names. Retries append a newer gate link, so the last one wins.
 */
private fun gateBuildIdOfComment(comment: String): String? =
    gateBuildUrlPattern.findAll(comment).lastOrNull()?.groupValues?.get(1)?.takeIf(::isBuildId)

private val gateBuildUrlPattern = Regex("""Main quality gate triggered at .*/build/(\d+)""")

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
