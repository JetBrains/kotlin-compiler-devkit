package org.jetbrains.kotlin.test.helper.actions

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "FavoriteTestRunners", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class FavoriteTestRunnersService
    : SerializablePersistentStateComponent<FavoriteTestRunnersService.State>(State()) {
    companion object {
        const val FAVORITE_PREFIX = "★ "

        fun getInstance(project: Project): FavoriteTestRunnersService = project.service<FavoriteTestRunnersService>()

        fun formatRunnerName(runnerName: String, isFavorite: Boolean): String =
            if (isFavorite) "$FAVORITE_PREFIX$runnerName" else runnerName
    }

    private var favoriteRunners: Set<String>
        get() = state.favoriteRunners
        set(value) {
            updateState {
                State(value)
            }
        }

    fun isFavorite(runnerName: String): Boolean {
        return runnerName in state.favoriteRunners
    }

    fun toggleFavorite(runnerName: String) {
        if (runnerName in favoriteRunners) {
            favoriteRunners -= runnerName
        } else {
            favoriteRunners += runnerName
        }
    }

    data class State(
        @JvmField val favoriteRunners: Set<String> = setOf()
    )
}