package org.jetbrains.kotlin.test.helper.git

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsNotifier
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
                    if (autoStash) addParameters("--autosquash")
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