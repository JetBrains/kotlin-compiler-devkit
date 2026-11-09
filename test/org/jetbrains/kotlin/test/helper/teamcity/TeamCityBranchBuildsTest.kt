package org.jetbrains.kotlin.test.helper.teamcity

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking

class TeamCityBranchBuildsTest {
    @Test
    fun masterQueriesTheDefaultBranchInsteadOfItsName() {
        /* state:any,canceled:any lift TeamCity's defaults that hide running and canceled remote-run chains. */
        assertEquals(
            "https://buildserver.labs.intellij.net/guestAuth/app/rest/builds" +
                "?locator=buildType:Kotlin_KotlinDev_Aggregate,branch:(default:true),state:any,canceled:any,count:5" +
                "&fields=build(id,state,status,statusText,webUrl,branchName,startDate,buildType(id,name)" +
                ",testOccurrences(count,passed,failed,ignored,muted))",
            aggregateBuildsUrl("master"),
        )
    }

    @Test
    fun featureBranchNamesAreEncodedForTheLocator() {
        assertEquals(
            "https://buildserver.labs.intellij.net/guestAuth/app/rest/builds" +
                "?locator=buildType:Kotlin_KotlinDev_Aggregate,branch:sellmair%2Ffeature%20branch,state:any,canceled:any,count:5" +
                "&fields=build(id,state,status,statusText,webUrl,branchName,startDate,buildType(id,name)" +
                ",testOccurrences(count,passed,failed,ignored,muted))",
            aggregateBuildsUrl("sellmair/feature branch"),
        )
    }

    @Test
    fun theQueueIsQueriedOnceForAllBranches() {
        assertEquals(
            "https://buildserver.labs.intellij.net/guestAuth/app/rest/buildQueue" +
                "?locator=buildType:Kotlin_KotlinDev_Aggregate" +
                "&fields=build(id,state,branchName,queuedDate,webUrl,buildType(id,name))",
            aggregateBuildQueueUrl(),
        )
    }

    @Test
    fun serverBranchNamesReadBackAsLocalBranchNames() {
        assertEquals("master", localBranchNameOfServerBranchName("<default>"))
        assertEquals("sellmair/feature", localBranchNameOfServerBranchName("refs/heads/sellmair/feature"))
        assertEquals("sellmair/feature", localBranchNameOfServerBranchName("sellmair/feature"))
        assertNull(localBranchNameOfServerBranchName(""))
    }

    @Test
    fun parsesFetchedBuildsWithTheirStatuses() {
        val fetched = parseFetchedBranchBuilds(branchBuildsJson, localBranchName = "master")

        assertEquals(2, fetched?.size)

        val (finished, running) = fetched.orEmpty()
        val finishedStatus = assertNotNull(finished.status)
        val runningStatus = assertNotNull(running.status)
        assertEquals("1123096733", finished.build.buildId)
        /* The server reports the default branch as `<default>`; the local name the query was for wins. */
        assertEquals("master", finished.build.branchName)
        assertEquals(
            "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_Aggregate/1123096733",
            finished.build.webUrl,
        )
        assertEquals(teamCityDateMillis("20261109T010609+0000"), finished.build.launchTimestamp)
        assertEquals(finishedStatus.startTimestamp, finished.build.launchTimestamp)

        assertEquals(BuildState.FINISHED, finishedStatus.state)
        assertEquals(false, finishedStatus.isSuccessful)
        assertEquals(AGGREGATE_BUILD_TYPE_ID, finishedStatus.buildTypeId)
        assertEquals("Aggregate (master)", finishedStatus.buildTypeName)
        assertEquals(TestCounts(passed = 397439, failed = 2, ignored = 48145, muted = 11), finishedStatus.testCounts)

        assertEquals("1123179064", running.build.buildId)
        assertEquals(BuildState.RUNNING, runningStatus.state)
        assertEquals(null, runningStatus.isSuccessful)
        assertEquals(null, runningStatus.testCounts)
        assertEquals(teamCityDateMillis("20261109T064630+0000"), running.build.launchTimestamp)
    }

    @Test
    fun parsesCanceledRemoteRunChains() {
        val fetched = parseFetchedBranchBuilds(canceledChainJson, localBranchName = "sellmair/codeowners")

        assertEquals(listOf("1123184420"), fetched.orEmpty().map { fetchedBuild -> fetchedBuild.build.buildId })

        val canceled = fetched.orEmpty().single()
        val canceledStatus = assertNotNull(canceled.status)
        assertEquals("sellmair/codeowners", canceled.build.branchName)
        assertEquals(BuildState.FINISHED, canceledStatus.state)

        /* Canceled chains report status UNKNOWN, which is neither success nor failure. */
        assertEquals(null, canceledStatus.isSuccessful)
        assertEquals(
            "Canceled (Tests passed: 25400, ignored: 441; Build chain interrupted (success: 141, canceled: 31))",
            canceledStatus.statusText,
        )
        assertEquals(TestCounts(passed = 26225, failed = 0, ignored = 447, muted = 0), canceledStatus.testCounts)
        assertEquals(teamCityDateMillis("20261109T065056+0000"), canceled.build.launchTimestamp)
    }

    @Test
    fun aBlankBodyMeansTheBranchHasNoBuilds() {
        assertEquals(emptyList(), parseFetchedBranchBuilds("", localBranchName = "master"))
        assertEquals(emptyList(), parseFetchedBranchBuilds("   ", localBranchName = "master"))
    }

    @Test
    fun anUnreadableBodyMeansAFailedRequest() {
        assertNull(parseFetchedBranchBuilds("<html>Login required</html>", localBranchName = "master"))
        assertNull(parseFetchedBranchBuilds("{\"build\": [", localBranchName = "master"))
    }

    @Test
    fun skipsElementsThatAreNotReadableBuilds() {
        val fetched = parseFetchedBranchBuilds(
            """
            {"build": [
              {"id": 7, "state": "finished", "startDate": "20261109T010609+0000"},
              "not an object",
              {"state": "finished", "startDate": "20261109T010609+0000"},
              {"id": "not-a-number", "state": "finished"},
              {"id": 8, "state": "unfinished", "startDate": "20261109T010609+0000"}
            ]}
            """.trimIndent(),
            localBranchName = "master",
        )

        assertEquals(listOf("7"), fetched.orEmpty().map { fetchedBuild -> fetchedBuild.build.buildId })
    }

    @Test
    fun parsesQueuedBuildsOfLocalBranchesOnly() {
        val fetched = parseQueuedBranchBuilds(queueJson, localBranchNames = setOf("master", "sellmair/codeowners"))

        assertEquals(listOf("1123184420", "1123184421"), fetched.orEmpty().map { it.build.buildId })

        val (queuedFeature, queuedMaster) = fetched.orEmpty()
        val queuedFeatureStatus = assertNotNull(queuedFeature.status)
        assertEquals("sellmair/codeowners", queuedFeature.build.branchName)
        assertEquals(teamCityDateMillis("20261109T064732+0000"), queuedFeature.build.launchTimestamp)
        assertEquals(BuildState.QUEUED, queuedFeatureStatus.state)
        assertEquals(null, queuedFeatureStatus.isSuccessful)

        /* `<default>` reads back as the local 'master' branch. */
        assertEquals("master", queuedMaster.build.branchName)
    }

    @Test
    fun anUnreadableQueueBodyMeansAFailedRequest() {
        assertNull(parseQueuedBranchBuilds("<html>Login required</html>", localBranchNames = setOf("master")))
    }

    @Test
    fun safeMergeCoordinatorBuildsAreQueriedPerBranch() {
        assertEquals(
            "https://buildserver.labs.intellij.net/guestAuth/app/rest/builds" +
                "?locator=buildType:Kotlin_KotlinDev_SafeMergeCoordinator,branch:sellmair%2Ffeature%20branch" +
                ",state:any,canceled:any,count:5" +
                "&fields=build(id,state,status,statusText,branchName,startDate,queuedDate,webUrl" +
                ",comment(text),triggered(type,date,user(id,username)))",
            safeMergeCoordinatorBuildsUrl("sellmair/feature branch"),
        )
    }

    @Test
    fun parsesSafeMergeDryRunsByFollowingTheirGateBuilds() {
        val safeMerges = parseSafeMergeCoordinatorBuilds(
            safeMergeDryRunJson,
            localBranchName = "krakhman/rich_errors_kmp",
        )

        val safeMerge = safeMerges.orEmpty().single()

        /* The row becomes the gate build the comment names, marked as a dry-run safe merge. */
        assertEquals("1121083833", safeMerge.build.buildId)
        assertEquals("1121083833", safeMerge.gateBuildId)
        assertEquals("https://buildserver.labs.intellij.net/build/1121083833", safeMerge.build.webUrl)
        assertEquals("krakhman/rich_errors_kmp", safeMerge.build.branchName)
        assertTrue(safeMerge.build.isSafeMerge)
        assertTrue(safeMerge.build.isSafeMergeDryRun)
        assertEquals(teamCityDateMillis("20261106T135832+0000"), safeMerge.build.launchTimestamp)

        /* The coordinator's own status stays available, e.g. while no gate is known yet. */
        assertEquals(BuildState.FINISHED, safeMerge.coordinatorStatus.state)
        assertEquals(false, safeMerge.coordinatorStatus.isSuccessful)
        assertEquals(SAFE_MERGE_COORDINATOR_BUILD_TYPE_ID, safeMerge.coordinatorStatus.buildTypeId)
    }

    @Test
    fun parsesRealSafeMergesWhoseCoordinatorIsStillRunning() {
        val safeMerges = parseSafeMergeCoordinatorBuilds(realMergeJson, localBranchName = "rr/kobzar/KT-90626")

        val safeMerge = safeMerges.orEmpty().single()

        /* A comment without the 'DRY RUN' prefix is a real merge. */
        assertEquals("1123518276", safeMerge.build.buildId)
        assertEquals("1123518276", safeMerge.gateBuildId)
        assertTrue(safeMerge.build.isSafeMerge)
        assertFalse(safeMerge.build.isSafeMergeDryRun)
        assertEquals(BuildState.RUNNING, safeMerge.coordinatorStatus.state)
    }

    @Test
    fun parsesSafeMergesWhoseRebaseFailedWithoutAGate() {
        val safeMerges = parseSafeMergeCoordinatorBuilds(
            rebaseFailureJson,
            localBranchName = "nkasyanov/stdlib-jvm-use-removeIf",
        )

        /* The newest entry failed to rebase, so no gate exists and the row stays the coordinator build. */
        val rebaseFailure = safeMerges.orEmpty().first()
        assertEquals("1123421832", rebaseFailure.build.buildId)
        assertNull(rebaseFailure.gateBuildId)
        assertEquals(
            "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_SafeMergeCoordinator/1123421832",
            rebaseFailure.build.webUrl,
        )
        assertTrue(rebaseFailure.build.isSafeMerge)
        assertFalse(rebaseFailure.build.isSafeMergeDryRun)
        assertEquals(BuildState.FINISHED, rebaseFailure.coordinatorStatus.state)
        assertEquals(false, rebaseFailure.coordinatorStatus.isSuccessful)

        /* The older dry runs of the same branch follow their gates. */
        assertEquals(
            listOf("1121185415", "1119474474"),
            safeMerges.orEmpty().drop(1).map { safeMerge -> safeMerge.build.buildId },
        )
    }

    @Test
    fun skipsCoordinatorBuildsThatAreChainCleanupSteps() {
        /* The default branch's coordinator builds are snapshot-dependency triggered cleanups. */
        val safeMerges = parseSafeMergeCoordinatorBuilds(
            chainCleanupJson,
            localBranchName = "nkasyanov/stdlib-jvm-use-removeIf",
        )

        assertEquals(listOf("1123421832"), safeMerges.orEmpty().map { safeMerge -> safeMerge.build.buildId })
    }

    @Test
    fun aBlankCoordinatorBodyMeansTheBranchHasNoSafeMerges() {
        assertEquals(emptyList(), parseSafeMergeCoordinatorBuilds("", localBranchName = "feature"))
        assertEquals(emptyList(), parseSafeMergeCoordinatorBuilds("   ", localBranchName = "feature"))
    }

    @Test
    fun anUnreadableCoordinatorBodyMeansAFailedRequest() {
        assertNull(parseSafeMergeCoordinatorBuilds("<html>Login required</html>", localBranchName = "feature"))
        assertNull(parseSafeMergeCoordinatorBuilds("{\"build\": [", localBranchName = "feature"))
    }

    @Test
    fun finishedGateStatusesAreReusedWithoutRefetching() = runBlocking {
        val safeMerge = parseSafeMergeCoordinatorBuilds(
            safeMergeDryRunJson,
            localBranchName = "krakhman/rich_errors_kmp",
        ).orEmpty().single()
        val cachedStatus = finishedGateStatus()

        assertEquals(
            cachedStatus,
            gateStatusOrNull(safeMerge, cachedStatus) { fail("A finished gate must not be fetched again") },
        )
    }

    @Test
    fun gateStatusesOfUnfinishedGatesAreFetchedByGateBuildId() = runBlocking {
        val safeMerge = parseSafeMergeCoordinatorBuilds(
            safeMergeDryRunJson,
            localBranchName = "krakhman/rich_errors_kmp",
        ).orEmpty().single()
        val gateStatus = runningGateStatus()
        val fetchedGateBuildIds = mutableListOf<String>()

        /* No cached status yet: the gate is fetched by its build id. */
        assertEquals(gateStatus, gateStatusOrNull(safeMerge, null) { gateBuildId ->
            fetchedGateBuildIds += gateBuildId
            gateStatus
        })

        /* A cached running status is not final, so the gate keeps being fetched. */
        assertEquals(gateStatus, gateStatusOrNull(safeMerge, runningGateStatus()) { gateBuildId ->
            fetchedGateBuildIds += gateBuildId
            gateStatus
        })

        assertEquals(listOf("1121083833", "1121083833"), fetchedGateBuildIds)
    }

    @Test
    fun safeMergesWithoutAGateHaveNoGateStatus() = runBlocking {
        val rebaseFailure = parseSafeMergeCoordinatorBuilds(
            rebaseFailureJson,
            localBranchName = "nkasyanov/stdlib-jvm-use-removeIf",
        ).orEmpty().first()

        assertNull(gateStatusOrNull(rebaseFailure, finishedGateStatus()) { fail("No gate to fetch") })
    }

    @Test
    fun aFailedGateStatusFetchReadsAsUnknown() = runBlocking {
        val safeMerge = parseSafeMergeCoordinatorBuilds(
            safeMergeDryRunJson,
            localBranchName = "krakhman/rich_errors_kmp",
        ).orEmpty().single()

        assertNull(gateStatusOrNull(safeMerge, null) { null })
    }
}

private val branchBuildsJson = """
    {"build": [
      {
        "id": 1123096733,
        "status": "FAILURE",
        "state": "finished",
        "branchName": "<default>",
        "webUrl": "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_Aggregate/1123096733",
        "statusText": "Tests failed: 2, passed: 397439, ignored: 48145, muted: 11",
        "buildType": {"id": "Kotlin_KotlinDev_Aggregate", "name": "Aggregate (master)"},
        "startDate": "20261109T010609+0000",
        "testOccurrences": {"count": 445597, "ignored": 48145, "failed": 2, "passed": 397439, "muted": 11}
      },
      {
        "id": 1123179064,
        "state": "running",
        "branchName": "<default>",
        "webUrl": "https://buildserver.labs.intellij.net/build/1123179064",
        "buildType": {"id": "Kotlin_KotlinDev_Aggregate", "name": "Aggregate (master)"},
        "startDate": "20261109T064630+0000"
      }
    ]}
""".trimIndent()

private val canceledChainJson = """
    {"build": [
      {
        "id": 1123184420,
        "status": "UNKNOWN",
        "state": "finished",
        "branchName": "sellmair/codeowners",
        "webUrl": "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_Aggregate/1123184420",
        "statusText": "Canceled (Tests passed: 25400, ignored: 441; Build chain interrupted (success: 141, canceled: 31))",
        "buildType": {"id": "Kotlin_KotlinDev_Aggregate", "name": "Aggregate (master)"},
        "startDate": "20261109T065056+0000",
        "testOccurrences": {"count": 26672, "ignored": 447, "failed": 0, "passed": 26225, "muted": 0}
      }
    ]}
""".trimIndent()

private val queueJson = """
    {"build": [
      {
        "id": 1123184420,
        "state": "queued",
        "branchName": "sellmair/codeowners",
        "queuedDate": "20261109T064732+0000",
        "webUrl": "https://buildserver.labs.intellij.net/build/1123184420",
        "buildType": {"id": "Kotlin_KotlinDev_Aggregate", "name": "Aggregate (master)"}
      },
      {
        "id": 1123184421,
        "state": "queued",
        "branchName": "<default>",
        "queuedDate": "20261109T070000+0000",
        "webUrl": "https://buildserver.labs.intellij.net/build/1123184421",
        "buildType": {"id": "Kotlin_KotlinDev_Aggregate"}
      },
      {
        "id": 1123184422,
        "state": "queued",
        "branchName": "other/branch",
        "queuedDate": "20261109T070001+0000",
        "webUrl": "https://buildserver.labs.intellij.net/build/1123184422"
      }
    ]}
""".trimIndent()

/* Recorded coordinator responses of the build server (2026-11-09). */

private val safeMergeDryRunJson = """
    {"build": [
      {
        "id": 1121074402,
        "status": "FAILURE",
        "state": "finished",
        "branchName": "krakhman/rich_errors_kmp",
        "webUrl": "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_SafeMergeCoordinator/1121074402",
        "statusText": "Quality gate failed ❌. See https://buildserver.labs.intellij.net/build/1121083833 to get full insight. (new)",
        "buildType": {"id": "Kotlin_KotlinDev_SafeMergeCoordinator", "name": "\uD83D\uDC27 Safe-Merge Coordinator"},
        "comment": {"text": "DRY RUN Started by cypressious in review https://github.com/JetBrains/kotlin/pull/9617\nMain quality gate triggered at https://buildserver.labs.intellij.net/build/1121083833"},
        "queuedDate": "20261106T135727+0000",
        "startDate": "20261106T135832+0000",
        "triggered": {"type": "user", "date": "20261106T135727+0000", "user": {"username": "kotlin", "id": 1826}}
      }
    ]}
""".trimIndent()

private val realMergeJson = """
    {"build": [
      {
        "id": 1123466254,
        "status": "SUCCESS",
        "state": "running",
        "branchName": "rr/kobzar/KT-90626",
        "webUrl": "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_SafeMergeCoordinator/1123466254",
        "statusText": "Step 10/14",
        "buildType": {"id": "Kotlin_KotlinDev_SafeMergeCoordinator", "name": "\uD83D\uDC27 Safe-Merge Coordinator"},
        "comment": {"text": "Started by JSMonk in review https://github.com/JetBrains/kotlin/pull/9608\nMain quality gate triggered at https://buildserver.labs.intellij.net/build/1123518276"},
        "queuedDate": "20261109T120144+0000",
        "startDate": "20261109T120300+0000",
        "triggered": {"type": "user", "date": "20261109T120144+0000", "user": {"username": "kotlin", "id": 1826}}
      }
    ]}
""".trimIndent()

private val rebaseFailureJson = """
    {"build": [
      {
        "id": 1123421832,
        "status": "FAILURE",
        "state": "finished",
        "branchName": "nkasyanov/stdlib-jvm-use-removeIf",
        "webUrl": "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_SafeMergeCoordinator/1123421832",
        "statusText": "Command failed with exit code 1: git rebase --autosquash master nkasyanov/stdlib-jvm-use-removeIf\nRebasing (1/10)\rRebasing (2/10)\rRebasing (3/10)\rRebasing (4/10)\rRebasing (5/10)\rRebasing (6/10)\rRebasing (7/10)\rerror: could not apply 256ecb332f86... f...",
        "buildType": {"id": "Kotlin_KotlinDev_SafeMergeCoordinator", "name": "\uD83D\uDC27 Safe-Merge Coordinator"},
        "comment": {"text": "Started by nikolaykasyanov in review https://github.com/JetBrains/kotlin/pull/9568"},
        "queuedDate": "20261109T110727+0000",
        "startDate": "20261109T110752+0000",
        "triggered": {"type": "user", "date": "20261109T110727+0000", "user": {"username": "kotlin", "id": 1826}}
      },
      {
        "id": 1121179693,
        "status": "FAILURE",
        "state": "finished",
        "branchName": "nkasyanov/stdlib-jvm-use-removeIf",
        "webUrl": "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_SafeMergeCoordinator/1121179693",
        "statusText": "Quality gate failed ❌. See https://buildserver.labs.intellij.net/build/1121185415 to get full insight. (new)",
        "buildType": {"id": "Kotlin_KotlinDev_SafeMergeCoordinator", "name": "\uD83D\uDC27 Safe-Merge Coordinator"},
        "comment": {"text": "DRY RUN Started by nikolaykasyanov in review https://github.com/JetBrains/kotlin/pull/9568\nMain quality gate triggered at https://buildserver.labs.intellij.net/build/1121185415"},
        "queuedDate": "20261106T145251+0000",
        "startDate": "20261106T145451+0000",
        "triggered": {"type": "user", "date": "20261106T145251+0000", "user": {"username": "kotlin", "id": 1826}}
      },
      {
        "id": 1119468349,
        "status": "FAILURE",
        "state": "finished",
        "branchName": "nkasyanov/stdlib-jvm-use-removeIf",
        "webUrl": "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_SafeMergeCoordinator/1119468349",
        "statusText": "Quality gate failed ❌. See https://buildserver.labs.intellij.net/build/1119474474 to get full insight. (new)",
        "buildType": {"id": "Kotlin_KotlinDev_SafeMergeCoordinator", "name": "\uD83D\uDC27 Safe-Merge Coordinator"},
        "comment": {"text": "DRY RUN Started by nikolaykasyanov in review https://github.com/JetBrains/kotlin/pull/9568\nMain quality gate triggered at https://buildserver.labs.intellij.net/build/1119474474"},
        "queuedDate": "20261105T115346+0000",
        "startDate": "20261105T115515+0000",
        "triggered": {"type": "user", "date": "20261105T115346+0000", "user": {"username": "kotlin", "id": 1826}}
      }
    ]}
""".trimIndent()

/* A recorded snapshot-dependency cleanup entry — never part of a per-branch response, composed here
   to pin the skip rule. */
private val chainCleanupJson = """
    {"build": [
      {
        "id": 1123523020,
        "status": "SUCCESS",
        "state": "finished",
        "branchName": "<default>",
        "webUrl": "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_SafeMergeCoordinator/1123523020",
        "statusText": "Success",
        "buildType": {"id": "Kotlin_KotlinDev_SafeMergeCoordinator", "name": "\uD83D\uDC27 Safe-Merge Coordinator"},
        "queuedDate": "20261109T120624+0000",
        "startDate": "20261109T121433+0000",
        "triggered": {"type": "snapshotDependency", "date": "20261109T120624+0000"}
      },
      {
        "id": 1123421832,
        "status": "FAILURE",
        "state": "finished",
        "branchName": "nkasyanov/stdlib-jvm-use-removeIf",
        "webUrl": "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_SafeMergeCoordinator/1123421832",
        "statusText": "Command failed with exit code 1: git rebase --autosquash master nkasyanov/stdlib-jvm-use-removeIf",
        "buildType": {"id": "Kotlin_KotlinDev_SafeMergeCoordinator", "name": "\uD83D\uDC27 Safe-Merge Coordinator"},
        "comment": {"text": "Started by nikolaykasyanov in review https://github.com/JetBrains/kotlin/pull/9568"},
        "queuedDate": "20261109T110727+0000",
        "startDate": "20261109T110752+0000",
        "triggered": {"type": "user", "date": "20261109T110727+0000", "user": {"username": "kotlin", "id": 1826}}
      }
    ]}
""".trimIndent()

private fun finishedGateStatus() =
    TeamCityBuildStatus(
        state = BuildState.FINISHED,
        isSuccessful = true,
        statusText = "Success",
        webUrl = "https://buildserver.labs.intellij.net/build/1121083833",
        branchName = null,
        buildTypeId = null,
        buildTypeName = null,
    )

private fun runningGateStatus() =
    TeamCityBuildStatus(
        state = BuildState.RUNNING,
        isSuccessful = null,
        statusText = "Running",
        webUrl = "https://buildserver.labs.intellij.net/build/1121083833",
        branchName = null,
        buildTypeId = null,
        buildTypeName = null,
    )

private fun teamCityDateMillis(value: String): Long =
    OffsetDateTime.parse(value, DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssZ", Locale.ROOT))
        .toInstant()
        .toEpochMilli()
