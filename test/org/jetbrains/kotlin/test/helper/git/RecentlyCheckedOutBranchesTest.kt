package org.jetbrains.kotlin.test.helper.git

import kotlin.test.Test
import kotlin.test.assertEquals

class RecentlyCheckedOutBranchesTest {
    @Test
    fun extractsDestinationsOfCheckoutEntriesInReflogOrder() {
        assertEquals(
            listOf("sellmair/fix", "master", "master~0"),
            parseCheckedOutBranchNames(
                listOf(
                    "b176b6c HEAD@{0}: checkout: moving from master to sellmair/fix",
                    "5fe00d5 HEAD@{1}: checkout: moving from 5fe00d5f9567460e7bfe0484cae4ebcfd1c2c594 to master",
                    "5fe00d5 HEAD@{2}: checkout: moving from feature to master~0",
                    "b176b6c HEAD@{6}: commit: fix the parser to handle edge cases",
                    "085857b HEAD@{9}: commit (initial): a",
                )
            ),
        )
    }

    @Test
    fun skipsCheckoutEntriesWithoutDestination() {
        assertEquals(
            emptyList(),
            parseCheckedOutBranchNames(listOf("1a2b3c4 HEAD@{0}: checkout: moving from master")),
        )
    }

    @Test
    fun keepsTheCurrentBranchFirstAndCapsTheList() {
        assertEquals(
            listOf("fix", "master", "feature"),
            recentBranchNames(
                currentBranchName = "fix",
                reflogCheckoutNames = listOf("fix", "master", "feature", "cleanup", "master"),
                rememberedBranchName = null,
                localBranchNames = setOf("fix", "master", "feature", "cleanup"),
                limit = 3,
            ),
        )
    }

    @Test
    fun alwaysIncludesTheCurrentBranch() {
        assertEquals(
            listOf("master"),
            recentBranchNames(
                currentBranchName = "master",
                reflogCheckoutNames = emptyList(),
                rememberedBranchName = null,
                localBranchNames = setOf("master", "feature"),
                limit = 10,
            ),
        )
    }

    @Test
    fun dropsNamesThatAreNoLocalBranchAnymore() {
        assertEquals(
            listOf("master"),
            recentBranchNames(
                currentBranchName = null,
                reflogCheckoutNames = listOf("5fe00d5f9567460e7bfe0484cae4ebcfd1c2c594", "deleted-branch", "master"),
                rememberedBranchName = "also-deleted",
                localBranchNames = setOf("master"),
                limit = 10,
            ),
        )
    }

    @Test
    fun appendsTheRememberedBranchOfAFreshClone() {
        assertEquals(
            listOf("feature", "master"),
            recentBranchNames(
                currentBranchName = "feature",
                reflogCheckoutNames = listOf("feature"),
                rememberedBranchName = "master",
                localBranchNames = setOf("master", "feature"),
                limit = 10,
            ),
        )
    }

    @Test
    fun fallsBackToCurrentAndRememberedBranchWhenTheReflogIsUnreadable() {
        assertEquals(
            listOf("feature", "master"),
            recentBranchNames(
                currentBranchName = "feature",
                reflogCheckoutNames = emptyList(),
                rememberedBranchName = "master",
                localBranchNames = setOf("master", "feature"),
                limit = 10,
            ),
        )
    }
}
