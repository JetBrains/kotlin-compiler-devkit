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

    val KOTLIN_MONOREPO_REMOTES = setOf(
        "git@github.com:JetBrains/kotlin.git",
        "github.com/JetBrains/kotlin.git",
        "git@git.jetbrains.team/kt/kotlin.git",
        "git.jetbrains.team/kt/kotlin.git",
    )

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