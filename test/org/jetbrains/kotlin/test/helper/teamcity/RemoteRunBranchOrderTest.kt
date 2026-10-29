package org.jetbrains.kotlin.test.helper.teamcity

import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteRunBranchOrderTest {
    @Test
    fun keepsTheCheckedOutBranchFirstRegardlessOfRunTime() {
        val orderedBranches = orderRemoteRunBranchNames(
            currentBranchName = "current",
            localBranchNames = listOf("current", "recent"),
            builds = listOf(
                rememberedBuild("current", 10),
                rememberedBuild("recent", 20),
            ),
            pendingLaunchBranchName = null,
        )

        assertEquals(listOf("current", "recent"), orderedBranches)
    }

    @Test
    fun sortsPreviouslyLaunchedBranchesByTheirLatestRun() {
        val orderedBranches = orderRemoteRunBranchNames(
            currentBranchName = null,
            localBranchNames = emptyList(),
            builds = listOf(
                rememberedBuild("feature/older", 20),
                rememberedBuild("feature/latest", 80),
                rememberedBuild("feature/older", 90),
            ),
            pendingLaunchBranchName = null,
        )

        assertEquals(listOf("feature/older", "feature/latest"), orderedBranches)
    }

    @Test
    fun placesThePendingLaunchAfterTheCurrentBranchAndBeforePreviousRuns() {
        val orderedBranches = orderRemoteRunBranchNames(
            currentBranchName = "current",
            localBranchNames = listOf("current", "pending", "recent"),
            builds = listOf(rememberedBuild("recent", 100)),
            pendingLaunchBranchName = "pending",
        )

        assertEquals(listOf("current", "pending", "recent"), orderedBranches)
    }

    @Test
    fun includesUnlaunchedLocalBranchesAlphabeticallyAndKeepsNonlocalBuildBranches() {
        val orderedBranches = orderRemoteRunBranchNames(
            currentBranchName = null,
            localBranchNames = listOf("z-local", "a-local"),
            builds = listOf(rememberedBuild("remote-only", 1)),
            pendingLaunchBranchName = null,
        )

        assertEquals(listOf("remote-only", "a-local", "z-local"), orderedBranches)
    }

    @Test
    fun detachedHeadAndDuplicateNamesStillProduceUniqueBranches() {
        val orderedBranches = orderRemoteRunBranchNames(
            currentBranchName = null,
            localBranchNames = listOf("b", "a", "a"),
            builds = listOf(rememberedBuild("b", 1), rememberedBuild("outside-local", 2)),
            pendingLaunchBranchName = "b",
        )

        assertEquals(listOf("b", "outside-local", "a"), orderedBranches)
    }

    private fun rememberedBuild(branchName: String, launchTimestamp: Long) =
        RememberedTeamCityBuild(
            buildId = "$branchName-$launchTimestamp",
            webUrl = "https://buildserver.example/build/$launchTimestamp",
            branchName = branchName,
            launchTimestamp = launchTimestamp,
        )
}