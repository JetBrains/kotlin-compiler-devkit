package org.jetbrains.kotlin.test.helper.actions.git

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportProgressScope
import git4idea.GitBranch
import git4idea.GitNotificationIdsHolder
import git4idea.repo.GitRepository
import org.jetbrains.kotlin.test.helper.MyBundle
import org.jetbrains.kotlin.test.helper.git.Git

class ResetToGreenMasterAction : KotlinMasterAction() {
    @Suppress("UnstableApiUsage")
    override suspend fun actionPerformedOnMaster(e: AnActionEvent, project: Project, repository: GitRepository, reference: GitBranch) {
        with(project) {
            withBackgroundProgress(project, MyBundle.message("resetting.on.green.master.revision"), cancellable = true) {
                reportProgressScope(4) { reporter ->
                    val fetchSucceeded = reporter.sizedStep(1, MyBundle.message("fetching.master.updates")) {
                        Git.performFetch(repository, DEFAULT_ORIGIN, MAIN_BRANCH)
                    }

                    if (!fetchSucceeded) {
                        Git.notifyError(GitNotificationIdsHolder.FETCH_ERROR, "", MyBundle.message("failed.to.fetch", "$DEFAULT_ORIGIN/$MAIN_BRANCH"))
                        return@reportProgressScope
                    }

                    val latestGreenCommit = reporter.sizedStep(1, MyBundle.message("fetching.latest.green.master.revision")) {
                        Git.fetchLatestGreenCommit()
                    }

                    if (latestGreenCommit == null) {
                        Git.notifyError(GitNotificationIdsHolder.FETCH_ERROR, "", MyBundle.message("failed.to.fetch.latest.green.master.revision"))
                        return@reportProgressScope
                    }

                    val resetSucceeded = reporter.sizedStep(1, MyBundle.message("executing.reset.to.green.master.revision")) {
                        Git.performUpdateRef(repository, latestGreenCommit, reference.fullName)
                    }

                    if (!resetSucceeded) {
                        Git.notifyError(
                            GitNotificationIdsHolder.RESET_FAILED,
                            "",
                            MyBundle.message("failed.to.reset.on", latestGreenCommit))
                        return@reportProgressScope
                    }

                    Git.notifyInfo(
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
    }
}

