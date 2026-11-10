package org.jetbrains.kotlin.test.helper.teamcity

import kotlin.test.Test
import kotlin.test.assertEquals

class TeamCitySafeMergeTrackingTest {
    @Test
    fun replacesTheLaunchedCoordinatorRowWithItsAggregateGate() {
        val launchedCoordinator = RememberedTeamCityBuild(
            buildId = "coordinator-42",
            webUrl = "$TEAMCITY_SERVER_URL/build/coordinator-42",
            branchName = "feature/kt-123456",
            launchTimestamp = 1_700_000_000_000,
            isSafeMerge = true,
            isSafeMergeDryRun = true,
        )
        val fetchedGate = RememberedTeamCityBuild(
            buildId = "gate-99",
            webUrl = "$TEAMCITY_SERVER_URL/build/gate-99",
            branchName = "feature/kt-123456",
            launchTimestamp = 1_700_000_100_000,
            isSafeMerge = true,
        )

        val replacements = launchedSafeMergeBuildReplacements(
            rememberedBuilds = listOf(launchedCoordinator),
            fetchedBuilds = listOf(
                FetchedTeamCityBuild(
                    build = fetchedGate,
                    status = null,
                    coordinatorBuildId = "coordinator-42",
                ),
            ),
        )

        assertEquals(
            listOf(
                "coordinator-42" to fetchedGate.copy(
                    launchTimestamp = launchedCoordinator.launchTimestamp,
                    isSafeMergeDryRun = true,
                ),
            ),
            replacements,
        )
    }

    @Test
    fun keepsTheLaunchedCoordinatorUntilItsGateIsReported() {
        val launchedCoordinator = RememberedTeamCityBuild(
            buildId = "coordinator-42",
            webUrl = "$TEAMCITY_SERVER_URL/build/coordinator-42",
            branchName = "feature/kt-123456",
            launchTimestamp = 1_700_000_000_000,
            isSafeMerge = true,
            isSafeMergeDryRun = true,
        )

        assertEquals(
            listOf(
                "coordinator-42" to launchedCoordinator,
            ),
            launchedSafeMergeBuildReplacements(
                rememberedBuilds = listOf(launchedCoordinator),
                fetchedBuilds = listOf(
                    FetchedTeamCityBuild(
                        build = launchedCoordinator.copy(isSafeMergeDryRun = false),
                        status = null,
                        coordinatorBuildId = "coordinator-42",
                    ),
                ),
            ),
        )
    }

    @Test
    fun keepsBranchesWithUnfinishedSafeMergesInPeriodicRefreshes() {
        val runningSafeMerge = rememberedBuild("coordinator-running", "older/running")
            .copy(isSafeMerge = true)
        val finishedSafeMerge = rememberedBuild("gate-finished", "older/finished")
            .copy(isSafeMerge = true)
        val unknownSafeMerge = rememberedBuild("coordinator-unknown", "older/unknown")
            .copy(isSafeMerge = true)
        val regularBuild = rememberedBuild("aggregate-running", "older/aggregate")
        val finishedStatus = TeamCityBuildStatus(
            state = BuildState.FINISHED,
            isSuccessful = true,
            statusText = "Success",
            webUrl = "$TEAMCITY_SERVER_URL/build/gate-finished",
            branchName = null,
            buildTypeId = null,
            buildTypeName = null,
        )

        assertEquals(
            listOf("older/running", "older/unknown"),
            branchesWithUnfinishedSafeMerges(
                rememberedBuilds = listOf(runningSafeMerge, finishedSafeMerge, unknownSafeMerge, regularBuild),
                statuses = mapOf(
                    "coordinator-running" to finishedStatus.copy(state = BuildState.RUNNING),
                    "gate-finished" to finishedStatus
                ),
            ),
        )
    }
}

private fun rememberedBuild(buildId: String, branchName: String) =
    RememberedTeamCityBuild(
        buildId = buildId,
        webUrl = "$TEAMCITY_SERVER_URL/build/$buildId",
        branchName = branchName,
        launchTimestamp = 1_700_000_000_000,
    )
