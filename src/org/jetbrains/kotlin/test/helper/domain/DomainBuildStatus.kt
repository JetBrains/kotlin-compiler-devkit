package org.jetbrains.kotlin.test.helper.domain

import com.intellij.openapi.util.IconLoader
import kotlinx.serialization.Serializable
import org.jetbrains.kotlin.test.helper.SingleObjectAsListSerializer
import org.jetbrains.kotlin.test.helper.decodeTeamCityJsonOrNull
import javax.swing.Icon

/**
 * Latest finished build on the default branch for every build type in Kotlin_KotlinDev (guest access).
 */
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

@Serializable
internal data class DomainBuildTypesResponse(val buildType: List<DomainBuildTypeJson>? = null)

@Serializable
internal data class DomainBuildTypeJson(
    val id: String? = null,
    val builds: DomainBuildsJson? = null,
)

@Serializable
internal data class DomainBuildsJson(
    @Serializable(with = DomainBuildListSerializer::class)
    val build: List<DomainBuildJson>? = null,
)

private object DomainBuildListSerializer :
    SingleObjectAsListSerializer<DomainBuildJson>(DomainBuildJson.serializer())

@Serializable
internal data class DomainBuildJson(
    val status: String? = null,
    val statusText: String? = null,
    val webUrl: String? = null,
)

/**
 * Keys are domains named by the build type id without [DOMAIN_BUILD_TYPE_ID_PREFIX].
 * Returns null if [json] is not a readable response, so callers can tell it apart from a valid response without builds.
 */
internal fun parseDomainBuildStatuses(json: String): Map<Domain, DomainBuildStatus>? {
    val response = decodeTeamCityJsonOrNull<DomainBuildTypesResponse>(json) ?: return null

    return response.buildType
        .orEmpty()
        .mapNotNull(::domainBuildStatusEntry)
        .toMap()
}

private fun domainBuildStatusEntry(buildType: DomainBuildTypeJson): Pair<Domain, DomainBuildStatus>? {
    val buildTypeId = buildType.id ?: return null
    val domainName = buildTypeId.removePrefix(DOMAIN_BUILD_TYPE_ID_PREFIX)
    if (domainName == buildTypeId || domainName.isEmpty()) return null

    val latestBuild = buildType.builds?.build?.firstOrNull() ?: return null
    val status = latestBuild.status ?: return null
    val webUrl = latestBuild.webUrl ?: return null

    return Domain.of(domainName) to DomainBuildStatus(
        isSuccess = status == "SUCCESS",
        statusText = latestBuild.statusText,
        webUrl = webUrl,
    )
}
