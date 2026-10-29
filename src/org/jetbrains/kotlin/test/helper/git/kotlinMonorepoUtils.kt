package org.jetbrains.kotlin.test.helper.git

import com.intellij.openapi.project.Project
import git4idea.GitUtil
import git4idea.repo.GitRepository

/**
 * The Kotlin monorepo checkout among the project's git roots, if any.
 */
fun Project.kotlinMonorepoRepository(): GitRepository? =
    GitUtil.getRepositoryManager(this).repositories.firstOrNull(Git::isPublicKotlinMonorepo)

/**
 * The name of the currently checked out monorepo branch, or null when HEAD is detached.
 */
fun Project.currentBranchName(): String? =
    kotlinMonorepoRepository()?.currentBranch?.name
