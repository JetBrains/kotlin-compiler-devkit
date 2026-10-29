package org.jetbrains.kotlin.test.helper.teamcity

import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteRunBranchTreeTest {
    private val branches = listOf("master", "sellmair/fed/test-2", "rr/sellmair/KT-123", "backup/old")

    @Test
    fun blankFilterKeepsAllBranches() {
        assertEquals(branches, filterRemoteRunBranchNames(branches, ""))
        assertEquals(branches, filterRemoteRunBranchNames(branches, "   "))
        assertEquals(branches, filterRemoteRunBranchNames(branches, "/"))
    }

    @Test
    fun filterUsesGitBranchesFuzzyMatchingCaseInsensitively() {
        assertEquals(listOf("sellmair/fed/test-2"), filterRemoteRunBranchNames(branches, "sfed"))
        assertEquals(listOf("rr/sellmair/KT-123"), filterRemoteRunBranchNames(branches, "kt-"))
    }

    @Test
    fun filterWithoutMatchIsEmpty() {
        assertEquals(emptyList(), filterRemoteRunBranchNames(branches, "nothing"))
    }

    @Test
    fun bestMatchPrefersHighestScoreAndThenEarliestMatch() {
        val candidates = listOf("sellmair/fed/test-2", "rr/fed/test-2")

        assertEquals("rr/fed/test-2", bestMatchingRemoteRunBranchName(candidates, "fed"))
        assertEquals("sellmair/fed/test-2", bestMatchingRemoteRunBranchName(branches, "sfed"))
    }

    @Test
    fun pendingLaunchIsInProgress() {
        assertEquals(BranchRunIndicator.IN_PROGRESS, branchRunIndicator(null, null, isPendingLaunch = true))
    }

    @Test
    fun branchWithoutBuildOrStatusHasNoIndicator() {
        assertEquals(BranchRunIndicator.NONE, branchRunIndicator(null, null, isPendingLaunch = false))
        assertEquals(BranchRunIndicator.NONE, branchRunIndicator(build(), null, isPendingLaunch = false))
    }

    @Test
    fun schedulingQueuedAndRunningAreInProgress() {
        val trigger = build().copy(isAggregateTrigger = true)

        assertEquals(BranchRunIndicator.IN_PROGRESS, branchRunIndicator(trigger, status(BuildState.RUNNING, null), false))
        assertEquals(BranchRunIndicator.IN_PROGRESS, branchRunIndicator(build(), status(BuildState.QUEUED, null), false))
        assertEquals(BranchRunIndicator.IN_PROGRESS, branchRunIndicator(build(), status(BuildState.RUNNING, true), false))
    }

    @Test
    fun finishedBuildsAreSuccessOrFailure() {
        assertEquals(BranchRunIndicator.SUCCESS, branchRunIndicator(build(), status(BuildState.FINISHED, true), false))
        assertEquals(BranchRunIndicator.FAILURE, branchRunIndicator(build(), status(BuildState.FINISHED, false), false))
        assertEquals(BranchRunIndicator.FAILURE, branchRunIndicator(build(), status(BuildState.FINISHED, null), false))
    }

    private fun build() =
        RememberedTeamCityBuild(
            buildId = "1",
            webUrl = "https://buildserver.example/build/1",
            branchName = "master",
            launchTimestamp = 1,
        )

    private fun status(state: BuildState, isSuccessful: Boolean?) =
        TeamCityBuildStatus(
            state = state,
            isSuccessful = isSuccessful,
            statusText = null,
            webUrl = "https://buildserver.example/build/1",
            branchName = null,
            buildTypeId = null,
            buildTypeName = null,
        )
}
