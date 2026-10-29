package org.jetbrains.kotlin.test.helper.git

import kotlin.test.Test
import kotlin.test.assertEquals

class BranchPushStateTest {
    @Test
    fun decidesFromBranchExistenceFirst() {
        assertEquals(
            BranchPushState.Missing,
            branchPushState(hasLocalBranch = false, hasRemoteBranch = false, aheadCount = null, behindCount = null),
        )
        assertEquals(
            BranchPushState.RemoteOnly,
            branchPushState(hasLocalBranch = false, hasRemoteBranch = true, aheadCount = null, behindCount = null),
        )
        assertEquals(
            BranchPushState.NotOnRemote,
            branchPushState(hasLocalBranch = true, hasRemoteBranch = false, aheadCount = null, behindCount = null),
        )
    }

    @Test
    fun decidesFromAheadAndBehindCounts() {
        assertEquals(
            BranchPushState.Pushed,
            branchPushState(hasLocalBranch = true, hasRemoteBranch = true, aheadCount = 0, behindCount = 0),
        )
        assertEquals(
            BranchPushState.Pushed,
            branchPushState(hasLocalBranch = true, hasRemoteBranch = true, aheadCount = 0, behindCount = 7),
        )
        assertEquals(
            BranchPushState.Unpushed,
            branchPushState(hasLocalBranch = true, hasRemoteBranch = true, aheadCount = 3, behindCount = 0),
        )
        assertEquals(
            BranchPushState.Diverged,
            branchPushState(hasLocalBranch = true, hasRemoteBranch = true, aheadCount = 2, behindCount = 1),
        )
    }

    @Test
    fun unreadableCountsAreUnknown() {
        assertEquals(
            BranchPushState.Unknown,
            branchPushState(hasLocalBranch = true, hasRemoteBranch = true, aheadCount = null, behindCount = null),
        )
        assertEquals(
            BranchPushState.Unknown,
            branchPushState(hasLocalBranch = true, hasRemoteBranch = true, aheadCount = 1, behindCount = null),
        )
    }
}
