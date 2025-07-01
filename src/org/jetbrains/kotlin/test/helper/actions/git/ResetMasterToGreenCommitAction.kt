package org.jetbrains.kotlin.test.helper.actions.git

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import git4idea.GitBranch
import git4idea.repo.GitRepository
import git4idea.ui.branch.GitBranchPopupActions.addTooltipText
import org.jetbrains.kotlin.test.helper.git.Git

class ResetMasterToGreenCommitAction : KotlinMasterAction() {
    override fun updateIfEnabledAndVisible(
        e: AnActionEvent,
        project: Project,
        repositories: List<GitRepository>,
        reference: GitBranch
    ) {
        super.updateIfEnabledAndVisible(e, project, repositories, reference)
        with(e.presentation) {
            addTooltipText(this, description)
        }
    }

    override suspend fun actionPerformedOnMaster(e: AnActionEvent, project: Project, repository: GitRepository, reference: GitBranch) {
        with(project) {
            Git.resetMasterToGreenCommit(repository, reference)
        }
    }
}

