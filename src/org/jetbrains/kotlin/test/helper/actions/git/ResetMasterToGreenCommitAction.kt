package org.jetbrains.kotlin.test.helper.actions.git

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import git4idea.GitBranch
import git4idea.repo.GitRepository
import org.jetbrains.kotlin.test.helper.git.Git

class ResetMasterToGreenCommitAction : KotlinMasterAction() {
    override suspend fun actionPerformedOnMaster(e: AnActionEvent, project: Project, repository: GitRepository, reference: GitBranch) {
        with(project) {
            Git.resetMasterToGreenCommit(repository, reference)
        }
    }
}

