package org.jetbrains.kotlin.test.helper.actions.git

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.rd.util.lifetime
import git4idea.GitBranch
import git4idea.actions.branch.GitSingleBranchAction
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.test.helper.git.Git

abstract class KotlinMasterAction : GitSingleBranchAction() {
    protected fun chooseRepository(repositories: List<GitRepository>): GitRepository? = repositories.firstOrNull()

    override val disabledForRemote = true

    override fun isEnabledForRef(ref: GitBranch, repositories: List<GitRepository>) =
        repositories.all { it.isOnBranch }
            && chooseRepository(repositories)?.isPublicKotlinMonorepo() == true
            && ref.name == Git.MAIN_BRANCH
            && super.isEnabledForRef(ref, repositories)

    override fun actionPerformed(
        e: AnActionEvent,
        project: Project,
        repositories: List<GitRepository>,
        reference: GitBranch
    ) {
        project.lifetime.coroutineScope.launch {
            val repository = chooseRepository(repositories) ?: return@launch
            actionPerformedOnMaster(e, project, repository, reference)
        }
    }

    abstract suspend fun actionPerformedOnMaster(e: AnActionEvent, project: Project, repository: GitRepository, reference: GitBranch)

    private fun GitRepository.isPublicKotlinMonorepo() =
        remotes.any { it.isPublicKotlinMonorepo() }

    private fun GitRemote.isPublicKotlinMonorepo() =
        this.urls.any { it.removePrefix("ssh://").removePrefix("https://") in Git.KOTLIN_MONOREPO_REMOTES }
}