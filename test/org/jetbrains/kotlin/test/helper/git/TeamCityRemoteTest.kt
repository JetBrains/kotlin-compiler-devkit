package org.jetbrains.kotlin.test.helper.git

import kotlin.test.Test
import kotlin.test.assertEquals

class TeamCityRemoteTest {
    private val gitHub = RemoteCandidate("github", listOf("git@github.com:JetBrains/kotlin.git"))
    private val mirrorOrigin = RemoteCandidate("origin", listOf("ssh://git@git.jetbrains.team/kt/kotlin.git"))
    private val fork = RemoteCandidate("fork", listOf("git@github.com:Stream29/kotlin.git"))

    @Test
    fun prefersGitHubOverTheMirrorRegardlessOfTheUpstream() {
        val remotes = listOf(mirrorOrigin, gitHub, fork)

        assertEquals(TeamCityRemoteChoice.GitHubSource("github"), chooseTeamCityRemote(remotes, upstreamRemoteName = "github"))
        assertEquals(TeamCityRemoteChoice.GitHubSource("github"), chooseTeamCityRemote(remotes, upstreamRemoteName = "origin"))
        assertEquals(TeamCityRemoteChoice.GitHubSource("github"), chooseTeamCityRemote(remotes, upstreamRemoteName = "fork"))
        assertEquals(TeamCityRemoteChoice.GitHubSource("github"), chooseTeamCityRemote(remotes, upstreamRemoteName = null))
    }

    @Test
    fun fallsBackToTheMirrorOnlyWithoutGitHub() {
        assertEquals(
            TeamCityRemoteChoice.MirrorOnly("origin"),
            chooseTeamCityRemote(listOf(fork, mirrorOrigin), upstreamRemoteName = "fork"),
        )
    }

    @Test
    fun refusesForksAndUnrelatedRemotes() {
        val lookalikes = listOf(
            fork,
            RemoteCandidate("ide", listOf("git@github.com:JetBrains/kotlin-ide.git")),
            RemoteCandidate("prefixed", listOf("https://github.com/JetBrains/kotlin/tree/master")),
            RemoteCandidate("other-host", listOf("https://gitlab.com/JetBrains/kotlin.git")),
            RemoteCandidate("other-space", listOf("ssh://git@git.jetbrains.team/kti/kotlin.git")),
            RemoteCandidate("no-urls", emptyList()),
        )

        assertEquals(TeamCityRemoteChoice.None, chooseTeamCityRemote(lookalikes, upstreamRemoteName = "fork"))
        assertEquals(TeamCityRemoteChoice.None, chooseTeamCityRemote(emptyList(), upstreamRemoteName = null))
    }

    @Test
    fun rejectsRemotesThatPushElsewhere() {
        val pushesToFork = RemoteCandidate(
            "origin",
            listOf("https://github.com/JetBrains/kotlin.git", "git@github.com:Stream29/kotlin.git"),
        )

        assertEquals(TeamCityRemoteChoice.None, chooseTeamCityRemote(listOf(pushesToFork), upstreamRemoteName = "origin"))
        assertEquals(
            TeamCityRemoteChoice.MirrorOnly("mirror"),
            chooseTeamCityRemote(listOf(pushesToFork, mirrorOrigin.copy(name = "mirror")), upstreamRemoteName = "origin"),
        )
    }

    @Test
    fun breaksTiesByUpstreamThenOriginThenName() {
        val first = RemoteCandidate("b-github", listOf("https://github.com/JetBrains/kotlin"))
        val second = RemoteCandidate("a-github", listOf("https://github.com/JetBrains/kotlin.git"))
        val origin = RemoteCandidate("origin", listOf("git@github.com:JetBrains/kotlin.git"))

        assertEquals(
            TeamCityRemoteChoice.GitHubSource("b-github"),
            chooseTeamCityRemote(listOf(first, second, origin), upstreamRemoteName = "b-github"),
        )
        assertEquals(
            TeamCityRemoteChoice.GitHubSource("origin"),
            chooseTeamCityRemote(listOf(first, second, origin), upstreamRemoteName = "elsewhere"),
        )
        assertEquals(
            TeamCityRemoteChoice.GitHubSource("a-github"),
            chooseTeamCityRemote(listOf(first, second), upstreamRemoteName = null),
        )
        assertEquals(
            TeamCityRemoteChoice.MirrorOnly("origin"),
            chooseTeamCityRemote(listOf(mirrorOrigin.copy(name = "z-mirror"), mirrorOrigin), upstreamRemoteName = null),
        )
    }

    @Test
    fun acceptsAllUrlSpellingsOfTheRepositories() {
        val gitHubUrls = listOf(
            "git@github.com:JetBrains/kotlin.git",
            "git@github.com:JetBrains/kotlin",
            "ssh://git@github.com/JetBrains/kotlin.git",
            "https://github.com/JetBrains/kotlin.git",
            "https://github.com/JetBrains/kotlin",
            "https://github.com/JetBrains/kotlin/",
            "https://github.com/jetbrains/kotlin.git",
            "github.com/JetBrains/kotlin",
        )
        for (url in gitHubUrls) {
            assertEquals(
                TeamCityRemoteChoice.GitHubSource("remote"),
                chooseTeamCityRemote(listOf(RemoteCandidate("remote", listOf(url))), upstreamRemoteName = null),
                url,
            )
        }

        val mirrorUrls = listOf(
            "ssh://git@git.jetbrains.team/kt/kotlin.git",
            "git@git.jetbrains.team:kt/kotlin.git",
            "https://git.jetbrains.team/kt/kotlin.git",
            "https://git.jetbrains.team/kt/kotlin",
        )
        for (url in mirrorUrls) {
            assertEquals(
                TeamCityRemoteChoice.MirrorOnly("remote"),
                chooseTeamCityRemote(listOf(RemoteCandidate("remote", listOf(url))), upstreamRemoteName = null),
                url,
            )
        }
    }
}
