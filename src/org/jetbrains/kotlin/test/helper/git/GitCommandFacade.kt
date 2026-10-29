package org.jetbrains.kotlin.test.helper.git

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsNotifier
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportProgressScope
import git4idea.GitNotificationIdsHolder
import git4idea.GitReference
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import kotlinx.coroutines.future.await
import org.jetbrains.kotlin.test.helper.MyBundle
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@Suppress("UnstableApiUsage")
object Git {
    private val git by lazy { Git.getInstance() }
    private val logger by lazy { logger<Git>() }
    private const val GREEN_BRANCH_FETCH_URL = "https://kotl.in/green-dev-kotlin-aggregate"
    const val MAIN_BRANCH = "master"
    const val DEFAULT_ORIGIN = "origin"

    val KOTLIN_MONOREPO_REMOTE = Regex(
        "(ssh://|https://)?(git@)?" +
        "((github\\.com[:/]JetBrains/kotlin)|" +
        "(git\\.jetbrains\\.team/kt/kotlin))" +
        "(\\.git)?")


    context(project: Project)
    suspend fun rebaseOnGreenMasterCommit(repository: GitRepository) {
        withBackgroundProgress(project, MyBundle.message("rebasing.on.green.master.revision"), cancellable = true) {
            reportProgressScope(4) { reporter ->
                val fetchSucceeded = reporter.sizedStep(1, MyBundle.message("fetching.master.updates")) {
                    performFetch(repository, DEFAULT_ORIGIN, MAIN_BRANCH)
                }

                if (!fetchSucceeded) {
                    notifyError(
                        GitNotificationIdsHolder.FETCH_ERROR,
                        "",
                        MyBundle.message("failed.to.fetch", "$DEFAULT_ORIGIN/$MAIN_BRANCH")
                    )
                    return@reportProgressScope
                }

                val latestGreenCommit = reporter.sizedStep(1, MyBundle.message("fetching.latest.green.master.revision")) {
                    fetchLatestGreenCommit()
                }

                if (latestGreenCommit == null) {
                    notifyError(
                        GitNotificationIdsHolder.FETCH_ERROR,
                        "",
                        MyBundle.message("failed.to.fetch.latest.green.master.revision")
                    )
                    return@reportProgressScope
                }

                val rebaseSucceeded = reporter.sizedStep(1, MyBundle.message("executing.rebase.on.green.master.revision")) {
                    performRebase(repository, latestGreenCommit, autoStash = true)
                }

                if (!rebaseSucceeded) {
                    notifyError(
                        GitNotificationIdsHolder.REBASE_FAILED,
                        "",
                        MyBundle.message("failed.to.rebase.on", latestGreenCommit)
                    )
                    return@reportProgressScope
                }

                notifyInfo(
                    GitNotificationIdsHolder.REBASE_SUCCESSFUL,
                    "",
                    MyBundle.message("rebase.successful")
                )

                reporter.sizedStep(1, MyBundle.message("updating.project")) {
                    repository.update()
                }
            }
        }
    }

    context(project: Project)
    suspend fun resetMasterToGreenCommit(repository: GitRepository, masterBranch: GitReference) {
        withBackgroundProgress(project, MyBundle.message("resetting.on.green.master.revision"), cancellable = true) {
            reportProgressScope(4) { reporter ->
                val fetchSucceeded = reporter.sizedStep(1, MyBundle.message("fetching.master.updates")) {
                    performFetch(repository, DEFAULT_ORIGIN, MAIN_BRANCH)
                }

                if (!fetchSucceeded) {
                    notifyError(GitNotificationIdsHolder.FETCH_ERROR, "", MyBundle.message("failed.to.fetch", "$DEFAULT_ORIGIN/$MAIN_BRANCH"))
                    return@reportProgressScope
                }

                val latestGreenCommit = reporter.sizedStep(1, MyBundle.message("fetching.latest.green.master.revision")) {
                    fetchLatestGreenCommit()
                }

                if (latestGreenCommit == null) {
                    notifyError(GitNotificationIdsHolder.FETCH_ERROR, "", MyBundle.message("failed.to.fetch.latest.green.master.revision"))
                    return@reportProgressScope
                }

                val resetSucceeded = reporter.sizedStep(1, MyBundle.message("executing.reset.to.green.master.revision")) {
                    performUpdateRef(repository, latestGreenCommit, masterBranch.fullName)
                }

                if (!resetSucceeded) {
                    notifyError(
                        GitNotificationIdsHolder.RESET_FAILED,
                        "",
                        MyBundle.message("failed.to.reset.on", latestGreenCommit))
                    return@reportProgressScope
                }

                notifyInfo(
                    GitNotificationIdsHolder.REBASE_SUCCESSFUL,
                    "",
                    MyBundle.message("reset.successful")
                )

                reporter.sizedStep(1, MyBundle.message("updating.project")) {
                    repository.update()
                }
            }
        }
    }

    context(project: Project)
    suspend fun performFetch(repository: GitRepository, originName: String, branchName: String): Boolean =
        coroutineToIndicator {
            git.runCommand {
                GitLineHandler(project, repository.root, GitCommand.FETCH).apply {
                    addParameters(originName, branchName)
                }
            }.success()
        }

    context(project: Project)
    suspend fun performUpdateRef(repository: GitRepository, revision: String, fullBranchName: String): Boolean =
        coroutineToIndicator {
            git.runCommand {
                GitLineHandler(project, repository.root, GitCommand.UPDATE_REF).apply {
                    addParameters(fullBranchName, revision)
                }
            }.success()
        }

    context(project: Project)
    suspend fun performRebase(repository: GitRepository, revision: String, autoStash: Boolean = false): Boolean =
        coroutineToIndicator {
            git.runCommand {
                GitLineHandler(project, repository.root, GitCommand.REBASE).apply {
                    if (autoStash) addParameters("--autostash")
                    addParameters(revision)
                }
            }.success()
        }

    suspend fun fetchLatestGreenCommit(): String? {
        try {
            val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()

            val request = HttpRequest.newBuilder()
                .uri(URI.create(GREEN_BRANCH_FETCH_URL))
                .build()

            val result = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            val responseBody: String? = result.body()

            return responseBody?.trim()
        } catch (e: Throwable) {
            logger.error(MyBundle.message("failed.to.fetch.latest.green.master.revision"), e)
            return null
        }
    }

    /**
     * Whether the repository points to the public Kotlin monorepo.
     * Shared by the TeamCity test launch and the master actions.
     */
    fun isPublicKotlinMonorepo(repository: GitRepository): Boolean =
        repository.remotes.any { remote -> remote.urls.any { it.matches(KOTLIN_MONOREPO_REMOTE) } }

    /**
     * Which remote the TeamCity test launch checks [branchName] against and pushes it to (see [chooseTeamCityRemote]).
     * This is deliberately not simply the branch upstream, which may point to a fork or the mirror.
     */
    internal fun teamCityRemoteChoice(repository: GitRepository, branchName: String): TeamCityRemoteChoice {
        val remotes = repository.remotes.map { remote -> RemoteCandidate(remote.name, remote.urls + remote.pushUrls) }
        val upstreamRemoteName = repository.getBranchTrackInfo(branchName)?.remote?.name

        return chooseTeamCityRemote(remotes, upstreamRemoteName)
    }

    /**
     * How the local branch [branchName] relates to the same-named branch on [remote],
     * based on the remote-tracking ref (as fresh as the last fetch or push).
     */
    context(project: Project)
    internal suspend fun branchPushState(repository: GitRepository, remote: GitRemote, branchName: String): BranchPushState {
        val localRef = "refs/heads/$branchName"
        val remoteRef = "refs/remotes/${remote.name}/$branchName"

        val hasLocalBranch = repository.branches.findLocalBranch(branchName) != null
        val hasRemoteBranch = repository.branches.findRemoteBranch("${remote.name}/$branchName") != null

        val aheadCount = if (hasLocalBranch && hasRemoteBranch) revListCount(repository, "$remoteRef..$localRef") else null
        val behindCount = if (aheadCount != null) revListCount(repository, "$localRef..$remoteRef") else null

        return branchPushState(hasLocalBranch, hasRemoteBranch, aheadCount, behindCount)
    }

    /**
     * Pushes the local branch [branchName] to the same-named branch on [remote] and waits for the result.
     * Never force-pushes. Registers [remote] as upstream only if the branch has no upstream yet.
     * The repository state is refreshed afterwards. Returns false when the push failed.
     */
    suspend fun pushBranchAndWait(repository: GitRepository, remote: GitRemote, branchName: String): Boolean {
        val pushUrl = remote.pushUrls.firstOrNull() ?: remote.firstUrl
        if (pushUrl == null) {
            logger.warn("Remote ${remote.name} has no URL to push $branchName to")
            return false
        }

        val setUpstream = repository.getBranchTrackInfo(branchName) == null
        val result = coroutineToIndicator {
            git.push(repository, remote.name, pushUrl, "refs/heads/$branchName:refs/heads/$branchName", setUpstream)
        }

        repository.update()

        if (!result.success()) {
            logger.warn("Failed to push $branchName to ${remote.name}: ${result.errorOutputAsJoinedString}")
            return false
        }

        return true
    }

    /**
     * The number of commits in [revisionRange] (e.g. "origin/main..main"), or null when git couldn't answer.
     */
    context(project: Project)
    private suspend fun revListCount(repository: GitRepository, revisionRange: String): Int? =
        coroutineToIndicator {
            val result = git.runCommand {
                GitLineHandler(project, repository.root, GitCommand.REV_LIST).apply {
                    addParameters("--count", revisionRange)
                }
            }

            if (!result.success()) {
                logger.warn("git rev-list --count failed for $revisionRange")
                null
            } else {
                result.output.firstOrNull()?.trim()?.toIntOrNull()
            }
        }

    context(project: Project)
    fun notifyError(displayId: String, title: String, message: String) {
        VcsNotifier.getInstance(project).notifyError(
            displayId,
            title,
            message
        )
    }

    context(project: Project)
    fun notifyInfo(displayId: String, title: String, message: String) {
        VcsNotifier.getInstance(project).notifyInfo(
            displayId,
            title,
            message
        )
    }
}

/**
 * How a branch relates to the same-named branch on the TeamCity remote.
 */
internal enum class BranchPushState {
    /**
     * The branch exists locally only.
     */
    NotOnRemote,

    /**
     * Local and remote diverged; a plain push cannot fix this.
     */
    Diverged,

    /**
     * The local branch has commits that are not on the remote yet.
     */
    Unpushed,

    /**
     * Everything local is on the remote (the remote may still be ahead).
     */
    Pushed,

    /**
     * The branch exists on the remote only, so there is nothing to push.
     */
    RemoteOnly,

    /**
     * The branch exists neither locally nor on the remote.
     */
    Missing,

    /**
     * The ahead/behind counts could not be determined; callers should refuse to launch.
     */
    Unknown,
}

/**
 * Pure decision core of [Git.branchPushState]; the counts are null when they could not be read.
 */
internal fun branchPushState(
    hasLocalBranch: Boolean,
    hasRemoteBranch: Boolean,
    aheadCount: Int?,
    behindCount: Int?,
): BranchPushState = when {
    !hasLocalBranch && !hasRemoteBranch -> BranchPushState.Missing
    !hasLocalBranch -> BranchPushState.RemoteOnly
    !hasRemoteBranch -> BranchPushState.NotOnRemote
    aheadCount == null || behindCount == null -> BranchPushState.Unknown
    aheadCount > 0 && behindCount > 0 -> BranchPushState.Diverged
    aheadCount > 0 -> BranchPushState.Unpushed
    else -> BranchPushState.Pushed
}

/**
 * The repository the TeamCity Kotlin VCS root builds branches from
 * (verified via the read-only TeamCity REST API: VCS root `Kotlin_KotlinDev_Kotlin`, git@github.com:JetBrains/kotlin.git).
 */
private val kotlinGitHubUrl = Regex("(ssh://|https://)?(git@)?github\\.com[:/]JetBrains/kotlin(\\.git)?/?", RegexOption.IGNORE_CASE)

/**
 * The JetBrains Git server copy of the monorepo; TeamCity sees its branches only once they are mirrored to GitHub.
 */
private val kotlinMirrorUrl = Regex("(ssh://|https://)?(git@)?git\\.jetbrains\\.team[:/]kt/kotlin(\\.git)?/?", RegexOption.IGNORE_CASE)

/**
 * A git remote reduced to what [chooseTeamCityRemote] needs: its name and all its fetch and push URLs.
 */
internal data class RemoteCandidate(val name: String, val urls: List<String>)

/**
 * The remote a TeamCity test launch checks and pushes the branch against.
 */
internal sealed interface TeamCityRemoteChoice {
    /**
     * A github.com/JetBrains/kotlin remote: the repository TeamCity builds from.
     */
    data class GitHubSource(val remoteName: String) : TeamCityRemoteChoice

    /**
     * Only the git.jetbrains.team mirror is configured; launching needs an explicit confirmation.
     */
    data class MirrorOnly(val remoteName: String) : TeamCityRemoteChoice

    /**
     * Neither (e.g. only a personal fork): launching is refused.
     */
    data object None : TeamCityRemoteChoice
}

/**
 * Prefers a github.com/JetBrains/kotlin remote over the git.jetbrains.team mirror.
 * A remote qualifies only if all of its URLs point to the same repository, so a remote pushing to a fork never does.
 * Among several qualifying remotes, the branch's upstream remote wins, then `origin`, then the first by name.
 */
internal fun chooseTeamCityRemote(remotes: List<RemoteCandidate>, upstreamRemoteName: String?): TeamCityRemoteChoice {
    val gitHubRemotes = remotes.filter { remote -> remote.hasOnlyUrlsMatching(kotlinGitHubUrl) }
    if (gitHubRemotes.isNotEmpty()) {
        return TeamCityRemoteChoice.GitHubSource(preferredRemoteName(gitHubRemotes, upstreamRemoteName))
    }

    val mirrorRemotes = remotes.filter { remote -> remote.hasOnlyUrlsMatching(kotlinMirrorUrl) }
    if (mirrorRemotes.isNotEmpty()) {
        return TeamCityRemoteChoice.MirrorOnly(preferredRemoteName(mirrorRemotes, upstreamRemoteName))
    }

    return TeamCityRemoteChoice.None
}

private fun RemoteCandidate.hasOnlyUrlsMatching(pattern: Regex): Boolean =
    urls.isNotEmpty() && urls.all { url -> url.trim().matches(pattern) }

private fun preferredRemoteName(candidates: List<RemoteCandidate>, upstreamRemoteName: String?): String {
    val names = candidates.map { candidate -> candidate.name }

    /* Spelled out: at top level, `Git` resolves to the imported git4idea class. */
    val origin = org.jetbrains.kotlin.test.helper.git.Git.DEFAULT_ORIGIN

    return when {
        upstreamRemoteName != null && upstreamRemoteName in names -> upstreamRemoteName
        origin in names -> origin
        else -> names.min()
    }
}