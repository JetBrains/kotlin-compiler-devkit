package org.jetbrains.kotlin.test.helper.domain

import com.intellij.openapi.util.IconLoader
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.swing.Icon

/* Latest finished build on the default branch for every build type in Kotlin_KotlinDev (guest access). */
internal const val DOMAIN_BUILD_STATUSES_URL: String =
    "https://buildserver.labs.intellij.net/guestAuth/app/rest/buildTypes" +
        "?locator=project:(id:Kotlin_KotlinDev)" +
        "&fields=buildType(id,builds(\$locator(branch:(default:true),state:finished,count:1),build(id,status,statusText,webUrl)))"

internal const val DOMAIN_BUILD_TYPE_ID_PREFIX: String = "Kotlin_KotlinDev_Domain_"

internal val domainBuildSuccessIcon: Icon =
    IconLoader.getIcon("/icons/test_federation_success.svg", DomainBuildStatus::class.java)

internal val domainBuildFailureIcon: Icon =
    IconLoader.getIcon("/icons/test_federation_failure.svg", DomainBuildStatus::class.java)

internal data class DomainBuildStatus(
    val isSuccess: Boolean,
    val statusText: String?,
    val webUrl: String,
)

internal fun domainBuildStatusIcon(status: DomainBuildStatus?): Icon = when {
    status == null -> domainIcon
    status.isSuccess -> domainBuildSuccessIcon
    else -> domainBuildFailureIcon
}

/*
Keys are domains named by the build type id without [DOMAIN_BUILD_TYPE_ID_PREFIX].
Returns null if [json] is not a readable response, so callers can tell it apart from a valid response without builds.
 */
internal fun parseDomainBuildStatuses(json: String): Map<Domain, DomainBuildStatus>? {
    val root = try {
        Json.parseToJsonElement(json)
    } catch (_: SerializationException) {
        return null
    }

    if (root !is JsonObject) return null

    val buildTypes = root["buildType"] ?: return emptyMap()
    if (buildTypes !is JsonArray) return null

    return buildTypes
        .mapNotNull { buildType -> parseDomainBuildType(buildType) }
        .toMap()
}

private fun parseDomainBuildType(buildType: JsonElement): Pair<Domain, DomainBuildStatus>? {
    if (buildType !is JsonObject) return null

    val buildTypeId = buildType.stringOrNull("id") ?: return null
    val domainName = buildTypeId.removePrefix(DOMAIN_BUILD_TYPE_ID_PREFIX)
    if (domainName == buildTypeId || domainName.isEmpty()) return null

    val builds = (buildType["builds"] as? JsonObject)?.get("build") as? JsonArray ?: return null
    val latestBuild = builds.firstOrNull() as? JsonObject ?: return null

    val status = latestBuild.stringOrNull("status") ?: return null
    val webUrl = latestBuild.stringOrNull("webUrl") ?: return null

    return Domain.of(domainName) to DomainBuildStatus(
        isSuccess = status == "SUCCESS",
        statusText = latestBuild.stringOrNull("statusText"),
        webUrl = webUrl,
    )
}

private fun JsonObject.stringOrNull(key: String): String? {
    val value = get(key) as? JsonPrimitive ?: return null
    return if (value.isString) value.content else null
}
