package org.jetbrains.kotlin.test.helper.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportProgressScope
import git4idea.GitBranch
import git4idea.GitNotificationIdsHolder
import git4idea.repo.GitRepository
import git4idea.ui.branch.GitBranchPopupActions.addTooltipText
import git4idea.ui.branch.GitBranchPopupActions.getCurrentBranchTruncatedPresentation
import org.jetbrains.kotlin.test.helper.MyBundle
import org.jetbrains.kotlin.test.helper.git.Git

class GitRebaseOnGreenMasterAction : GitKotlinMasterAction() {
    override fun isEnabledForRef(ref: GitBranch, repositories: List<GitRepository>): Boolean {
        // Disable self-rebasing `master` on `master`
        return chooseRepository(repositories)?.currentBranch?.name != MAIN_BRANCH
            && super.isEnabledForRef(ref, repositories)
    }

    // Suppressed because the analyzer still complains against 'action.GitRebaseOnGreenMasterAction.text'
    // for some reason.
    // The capitalization is kept in sync with the Git plugin one.
    @Suppress("DialogTitleCapitalization")
    override fun updateIfEnabledAndVisible(
        e: AnActionEvent,
        project: Project,
        repositories: List<GitRepository>,
        reference: GitBranch
    ) {
        with(e.presentation) {
            text = MyBundle.message(
                "action.GitRebaseOnGreenMasterAction.text",
                getCurrentBranchTruncatedPresentation(project, repositories))
            description = MyBundle.message("action.GitRebaseOnGreenMasterAction.description")
            addTooltipText(this, description)
        }
    }

    @Suppress("UnstableApiUsage")
    override suspend fun actionPerformedOnMaster(e: AnActionEvent, project: Project, repository: GitRepository, reference: GitBranch) {
        with(project) {
            withBackgroundProgress(project, MyBundle.message("rebasing.on.green.master.revision"), cancellable = true) {
                reportProgressScope(4) { reporter ->
                    val fetchSucceeded = reporter.sizedStep(1, MyBundle.message("fetching.master.updates")) {
                        Git.performFetch(repository, DEFAULT_ORIGIN, MAIN_BRANCH)
                    }

                    if (!fetchSucceeded) {
                        Git.notifyError(
                            GitNotificationIdsHolder.FETCH_ERROR,
                            "",
                            MyBundle.message("failed.to.fetch", "$DEFAULT_ORIGIN/$MAIN_BRANCH")
                        )
                        return@reportProgressScope
                    }

                    val latestGreenCommit = reporter.sizedStep(1, MyBundle.message("fetching.latest.green.master.revision")) {
                        Git.fetchLatestGreenCommit()
                    }

                    if (latestGreenCommit == null) {
                        Git.notifyError(
                            GitNotificationIdsHolder.FETCH_ERROR,
                            "",
                            MyBundle.message("failed.to.fetch.latest.green.master.revision")
                        )
                        return@reportProgressScope
                    }

                    val rebaseSucceeded = reporter.sizedStep(1, MyBundle.message("executing.rebase.on.green.master.revision")) {
                        Git.performRebase(repository, latestGreenCommit, autoStash = true)
                    }

                    if (!rebaseSucceeded) {
                        Git.notifyError(
                            GitNotificationIdsHolder.REBASE_FAILED,
                            "",
                            MyBundle.message("failed.to.rebase.on", latestGreenCommit)
                        )
                        return@reportProgressScope
                    }

                    Git.notifyInfo(
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
    }
}