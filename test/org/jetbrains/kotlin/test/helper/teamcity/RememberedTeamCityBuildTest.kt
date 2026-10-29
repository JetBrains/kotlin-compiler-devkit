package org.jetbrains.kotlin.test.helper.teamcity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RememberedTeamCityBuildTest {
    @Test
    fun roundTripsBuildThroughTheBean() {
        val build = RememberedTeamCityBuild(
            buildId = "1110990014",
            webUrl = "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_AggregateTrigger/1110990014",
            branchName = "feature/kt-123456",
            launchTimestamp = 1763718423000,
            isAggregateTrigger = true,
        )

        assertEquals(build, toRememberedTeamCityBuild(toRememberedTeamCityBuildBean(build)))
    }

    @Test
    fun rejectsIncompleteBeans() {
        assertNull(toRememberedTeamCityBuild(RememberedTeamCityBuildBean()))
        assertNull(
            toRememberedTeamCityBuild(
                RememberedTeamCityBuildBean().apply {
                    buildId = "1"
                },
            ),
        )
    }
}
