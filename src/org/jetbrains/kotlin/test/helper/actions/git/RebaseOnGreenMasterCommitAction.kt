package org.jetbrains.kotlin.test.helper.actions.git

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import git4idea.GitBranch
import git4idea.repo.GitRepository
import git4idea.ui.branch.GitBranchPopupActions.addTooltipText
import git4idea.ui.branch.GitBranchPopupActions.getCurrentBranchTruncatedPresentation
import org.jetbrains.kotlin.test.helper.MyBundle
import org.jetbrains.kotlin.test.helper.git.Git

class RebaseOnGreenMasterCommitAction : KotlinMasterAction() {
    override fun isEnabledForRef(ref: GitBranch, repositories: List<GitRepository>): Boolean {
        // Disable self-rebasing `master` on `master`
        return chooseRepository(repositories)?.currentBranch?.name != Git.MAIN_BRANCH
            && super.isEnabledForRef(ref, repositories)
    }

    override fun updateIfEnabledAndVisible(
        e: AnActionEvent,
        project: Project,
        repositories: List<GitRepository>,
        reference: GitBranch
    ) {
        with(e.presentation) {
            text = MyBundle.message(
                "action.GitRebaseOnGreenMasterCommitAction.text",
                getCurrentBranchTruncatedPresentation(project, repositories))
            description = MyBundle.message("action.GitRebaseOnGreenMasterCommitAction.description")
            addTooltipText(this, description)
        }
    }

    override suspend fun actionPerformedOnMaster(e: AnActionEvent, project: Project, repository: GitRepository, reference: GitBranch) {
        with(project) {
            Git.rebaseOnGreenMasterCommit(repository)
        }
    }
}