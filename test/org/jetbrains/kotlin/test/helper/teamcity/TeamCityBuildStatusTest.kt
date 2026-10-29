package org.jetbrains.kotlin.test.helper.teamcity

import com.intellij.ide.nls.NlsMessages
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class TeamCityBuildStatusTest {
    @Test
    fun buildsGuestStatusUrlByBuildId() {
        assertEquals(
            "https://buildserver.labs.intellij.net/guestAuth/app/rest/builds/id:123" +
                "?fields=id,state,status,statusText,webUrl,branchName,startDate,buildType(id,name),testOccurrences(count,passed,failed,ignored,muted)",
            buildStatusUrl("123"),
        )
    }

    @Test
    fun parsesFinishedSuccessfulBuild() {
        val status = parseBuildStatus(FINISHED_SUCCESS_RESPONSE, fallbackUrl = "https://fallback/1")!!

        assertEquals(BuildState.FINISHED, status.state)
        assertEquals(true, status.isSuccessful)
        assertEquals("Tests passed: 90491, ignored: 246; Build chain finished (success: 8)", status.statusText)
        assertEquals("https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_Domain_AnalysisApi/1110990014", status.webUrl)
        assertEquals("master", status.branchName)
        assertEquals("Kotlin_KotlinDev_Domain_AnalysisApi", status.buildTypeId)
        assertEquals("Domain (AnalysisApi)", status.buildTypeName)
        assertEquals(TestCounts(passed = 90491, failed = 0, ignored = 246, muted = 0), status.testCounts)
    }

    @Test
    fun parsesFinishedFailedBuildWithoutStatusText() {
        val status = parseBuildStatus(
            """
            {"id": 100, "status": "FAILURE", "state": "finished", "branchName": "master",
             "webUrl": "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_Aggregate/100"}
            """.trimIndent(),
            fallbackUrl = "https://fallback/1",
        )!!

        assertEquals(BuildState.FINISHED, status.state)
        assertEquals(false, status.isSuccessful)
        assertEquals(null, status.statusText)
        assertEquals(null, status.buildTypeId)
        assertEquals(null, status.buildTypeName)
        assertNull(status.testCounts)
    }

    @Test
    fun parsesRunningBuildWithPreliminaryStatus() {
        val status = parseBuildStatus(RUNNING_RESPONSE, fallbackUrl = "https://fallback/1")!!

        assertEquals(BuildState.RUNNING, status.state)
        assertEquals(true, status.isSuccessful)
        assertEquals("Running", status.statusText)
        assertEquals("Safe-Merge Coordinator", status.buildTypeName)
        assertEquals(Instant.parse("2026-10-29T15:04:00Z").toEpochMilli(), status.startTimestamp)
    }

    @Test
    fun formatsElapsedTime() {
        assertEquals(NlsMessages.formatDurationApproximateNarrow(192_000), formatElapsed(192_499))
        assertEquals(NlsMessages.formatDurationApproximateNarrow(1_000), formatElapsed(-1))
    }

    @Test
    fun choosesElapsedTimeStartByBuildState() {
        val build = rememberedBuildForStatusTest().copy(launchTimestamp = 100)
        val runningStatus = status(
            state = BuildState.RUNNING,
            isSuccessful = null,
            startTimestamp = 200,
        )

        assertEquals(100L, elapsedStartTimestamp(build, runningStatus, isScheduling = true))
        assertEquals(200L, elapsedStartTimestamp(build, runningStatus, isScheduling = false))
        assertEquals(
            100L,
            elapsedStartTimestamp(build, status(BuildState.RUNNING, null), isScheduling = false),
        )
        assertNull(elapsedStartTimestamp(build, status(BuildState.QUEUED, null), isScheduling = false))
        assertNull(elapsedStartTimestamp(build, status(BuildState.FINISHED, true), isScheduling = false))
    }

    @Test
    fun ignoresInvalidTeamCityStartDate() {
        val status = parseBuildStatus(
            """{"state":"running","startDate":"not-a-date"}""",
            fallbackUrl = "https://fallback/1",
        )!!

        assertNull(status.startTimestamp)
    }

    @Test
    fun defaultsMissingTestOccurrenceCountsToZero() {
        val status = parseBuildStatus(
            """{"state":"running","testOccurrences":{"passed":3}}""",
            fallbackUrl = "https://fallback/1",
        )!!

        assertEquals(TestCounts(passed = 3, failed = 0, ignored = 0, muted = 0), status.testCounts)
    }

    @Test
    fun extractsScheduledAggregateBuildFromFinishedTrigger() {
        val triggerStatus = status(
            state = BuildState.FINISHED,
            isSuccessful = true,
            statusText = "https://buildserver.labs.intellij.net/build/123456",
            buildTypeId = AGGREGATE_TRIGGER_BUILD_TYPE_ID,
        )

        assertEquals(
            LaunchedBuild("123456", "https://buildserver.labs.intellij.net/build/123456"),
            scheduledBuildFromTrigger(triggerStatus),
        )
        assertEquals(
            true,
            isSchedulingInProgress(
                RememberedTeamCityBuild(
                    buildId = "123",
                    webUrl = "https://buildserver.labs.intellij.net/build/123",
                    branchName = "master",
                    launchTimestamp = 1,
                    isAggregateTrigger = true,
                ),
                status(state = BuildState.RUNNING, isSuccessful = null),
            ),
        )
        assertEquals(
            true,
            isSchedulingInProgress(
                rememberedBuildForStatusTest(),
                status(
                    state = BuildState.RUNNING,
                    isSuccessful = null,
                    buildTypeId = AGGREGATE_TRIGGER_BUILD_TYPE_ID,
                ),
            ),
        )
        assertEquals(false, isSchedulingInProgress(rememberedBuildForStatusTest(), triggerStatus))
    }

    @Test
    fun identifiesUnfinishedBuildsOnTheRequestedBranch() {
        val queuedBuild = rememberedBuildForStatusTest().copy(buildId = "queued", branchName = "feature/test")
        val runningBuild = rememberedBuildForStatusTest().copy(buildId = "running", branchName = "feature/test")
        val schedulingBuild = rememberedBuildForStatusTest().copy(
            buildId = "scheduling",
            branchName = "feature/test",
            isAggregateTrigger = true,
        )
        val finishedBuild = rememberedBuildForStatusTest().copy(buildId = "finished", branchName = "feature/test")
        val otherBranchBuild = rememberedBuildForStatusTest().copy(buildId = "other", branchName = "other")
        val statuses = mapOf(
            "queued" to status(BuildState.QUEUED, null),
            "running" to status(BuildState.RUNNING, null),
            "finished" to status(BuildState.FINISHED, true),
        )

        assertEquals(
            listOf(queuedBuild, runningBuild, schedulingBuild),
            unfinishedBuildsOnBranch(
                listOf(queuedBuild, runningBuild, schedulingBuild, finishedBuild, otherBranchBuild),
                statusOf = statuses::get,
                branchName = "feature/test",
            ),
        )
        assertEquals(false, isBuildUnfinished(finishedBuild, statuses["finished"]))
        assertEquals(false, isBuildUnfinished(finishedBuild, status(BuildState.FINISHED, null)))
        assertEquals(false, isBuildUnfinished(finishedBuild, null))
    }

    @Test
    fun ignoresNonTriggerRunningAndUnlinkedBuildStatuses() {
        assertNull(
            scheduledBuildFromTrigger(
                status(
                    state = BuildState.FINISHED,
                    isSuccessful = true,
                    statusText = "https://buildserver.labs.intellij.net/build/123456",
                    buildTypeId = "Kotlin_KotlinDev_Aggregate",
                ),
            ),
        )
        assertNull(
            scheduledBuildFromTrigger(
                status(
                    state = BuildState.RUNNING,
                    isSuccessful = null,
                    statusText = "https://buildserver.labs.intellij.net/build/123456",
                    buildTypeId = AGGREGATE_TRIGGER_BUILD_TYPE_ID,
                ),
            ),
        )
        assertNull(
            scheduledBuildFromTrigger(
                status(
                    state = BuildState.FINISHED,
                    isSuccessful = false,
                    buildTypeId = AGGREGATE_TRIGGER_BUILD_TYPE_ID,
                ),
            ),
        )
    }

    @Test
    fun parsesQueuedBuildWithoutStatusAndFallsBackToRememberedUrl() {
        val status = parseBuildStatus(
            """
            {"id": 1111002160, "state": "queued", "branchName": "refs/merge/GITHUB-9387/safe-merge",
             "webUrl": "https://buildserver.labs.intellij.net/build/1111002160"}
            """.trimIndent(),
            fallbackUrl = "https://fallback/1111002160",
        )!!

        assertEquals(BuildState.QUEUED, status.state)
        assertEquals(null, status.isSuccessful)
        assertEquals(null, status.statusText)
        assertEquals("https://buildserver.labs.intellij.net/build/1111002160", status.webUrl)

        val withoutUrl = parseBuildStatus(
            """{"id": 1111002160, "state": "queued"}""",
            fallbackUrl = "https://fallback/1111002160",
        )!!

        assertEquals("https://fallback/1111002160", withoutUrl.webUrl)
    }

    @Test
    fun distinguishesUnusableResponsesFromValidOnes() {
        assertNull(parseBuildStatus("<html>Login required</html>", "https://fallback/1"))
        assertNull(parseBuildStatus("[]", "https://fallback/1"))
        assertNull(parseBuildStatus("{}", "https://fallback/1"))
        assertNull(parseBuildStatus("""{"id": 1, "status": "SUCCESS"}""", "https://fallback/1"))

        assertEquals(BuildState.FINISHED, parseBuildStatus(FINISHED_SUCCESS_RESPONSE, "https://fallback/1")?.state)
    }

    @Test
    fun selectsIconByStatus() {
        val finishedSuccess = status(state = BuildState.FINISHED, isSuccessful = true)
        val finishedFailure = status(state = BuildState.FINISHED, isSuccessful = false)
        val finishedUnknown = status(state = BuildState.FINISHED, isSuccessful = null)
        val running = status(state = BuildState.RUNNING, isSuccessful = null)
        val queued = status(state = BuildState.QUEUED, isSuccessful = null)

        assertSame(buildSuccessIcon, buildStatusIcon(finishedSuccess))
        assertSame(buildFailureIcon, buildStatusIcon(finishedFailure))
        assertSame(buildUnknownIcon, buildStatusIcon(finishedUnknown))
        assertSame(buildRunningIcon, buildStatusIcon(running))
        assertSame(buildQueuedIcon, buildStatusIcon(queued))
        assertSame(buildUnknownIcon, buildStatusIcon(null))
    }
}

private fun status(
    state: BuildState,
    isSuccessful: Boolean?,
    statusText: String? = null,
    buildTypeId: String? = null,
    startTimestamp: Long? = null,
) =
    TeamCityBuildStatus(
        state = state,
        isSuccessful = isSuccessful,
        statusText = statusText,
        webUrl = "https://buildserver.labs.intellij.net/build/1",
        branchName = null,
        buildTypeId = buildTypeId,
        buildTypeName = null,
        startTimestamp = startTimestamp,
    )

private fun rememberedBuildForStatusTest() =
    RememberedTeamCityBuild(
        buildId = "123",
        webUrl = "https://buildserver.labs.intellij.net/build/123",
        branchName = "master",
        launchTimestamp = 1,
    )

/**
 * Recorded answer of /guestAuth/app/rest/builds/id:<n>?fields=... for a finished build.
 */
private val FINISHED_SUCCESS_RESPONSE = """
    {"id":1110990014,"status":"SUCCESS","state":"finished","branchName":"master",
     "webUrl":"https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_Domain_AnalysisApi/1110990014",
     "statusText":"Tests passed: 90491, ignored: 246; Build chain finished (success: 8)",
     "testOccurrences":{"count":90737,"passed":90491,"failed":0,"ignored":246,"muted":0},
     "buildType":{"id":"Kotlin_KotlinDev_Domain_AnalysisApi","name":"Domain (AnalysisApi)"}}
""".trimIndent()

/**
 * Recorded answer for a running build; TeamCity already reports a preliminary status here.
 */
private val RUNNING_RESPONSE = """
    {"id":1111053292,"status":"SUCCESS","state":"running","branchName":"<default>",
     "webUrl":"https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_SafeMergeCoordinator/1111053292",
     "statusText":"Running",
     "startDate":"20261029T150400+0000",
     "buildType":{"id":"Kotlin_KotlinDev_SafeMergeCoordinator","name":"Safe-Merge Coordinator"}}
""".trimIndent()
