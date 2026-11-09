package org.jetbrains.kotlin.test.helper.teamcity

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        assertEquals("1123096733", finished.build.buildId)
        /* The server reports the default branch as `<default>`; the local name the query was for wins. */
        assertEquals("master", finished.build.branchName)
        assertEquals(
            "https://buildserver.labs.intellij.net/buildConfiguration/Kotlin_KotlinDev_Aggregate/1123096733",
            finished.build.webUrl,
        )
        assertEquals(teamCityDateMillis("20261109T010609+0000"), finished.build.launchTimestamp)
        assertEquals(finished.status.startTimestamp, finished.build.launchTimestamp)

        assertEquals(BuildState.FINISHED, finished.status.state)
        assertEquals(false, finished.status.isSuccessful)
        assertEquals(AGGREGATE_BUILD_TYPE_ID, finished.status.buildTypeId)
        assertEquals("Aggregate (master)", finished.status.buildTypeName)
        assertEquals(TestCounts(passed = 397439, failed = 2, ignored = 48145, muted = 11), finished.status.testCounts)

        assertEquals("1123179064", running.build.buildId)
        assertEquals(BuildState.RUNNING, running.status.state)
        assertEquals(null, running.status.isSuccessful)
        assertEquals(null, running.status.testCounts)
        assertEquals(teamCityDateMillis("20261109T064630+0000"), running.build.launchTimestamp)
    }

    @Test
    fun parsesCanceledRemoteRunChains() {
        val fetched = parseFetchedBranchBuilds(canceledChainJson, localBranchName = "sellmair/codeowners")

        assertEquals(listOf("1123184420"), fetched.orEmpty().map { fetchedBuild -> fetchedBuild.build.buildId })

        val canceled = fetched.orEmpty().single()
        assertEquals("sellmair/codeowners", canceled.build.branchName)
        assertEquals(BuildState.FINISHED, canceled.status.state)

        /* Canceled chains report status UNKNOWN, which is neither success nor failure. */
        assertEquals(null, canceled.status.isSuccessful)
        assertEquals(
            "Canceled (Tests passed: 25400, ignored: 441; Build chain interrupted (success: 141, canceled: 31))",
            canceled.status.statusText,
        )
        assertEquals(TestCounts(passed = 26225, failed = 0, ignored = 447, muted = 0), canceled.status.testCounts)
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
        assertEquals("sellmair/codeowners", queuedFeature.build.branchName)
        assertEquals(teamCityDateMillis("20261109T064732+0000"), queuedFeature.build.launchTimestamp)
        assertEquals(BuildState.QUEUED, queuedFeature.status.state)
        assertEquals(null, queuedFeature.status.isSuccessful)

        /* `<default>` reads back as the local 'master' branch. */
        assertEquals("master", queuedMaster.build.branchName)
    }

    @Test
    fun anUnreadableQueueBodyMeansAFailedRequest() {
        assertNull(parseQueuedBranchBuilds("<html>Login required</html>", localBranchNames = setOf("master")))
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

private fun teamCityDateMillis(value: String): Long =
    OffsetDateTime.parse(value, DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssZ", Locale.ROOT))
        .toInstant()
        .toEpochMilli()
