package org.jetbrains.kotlin.test.helper.actions

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "FavoriteTestRunners", storages = [Storage("favoriteTestRunners.xml")])
class FavoriteTestRunnersService : PersistentStateComponent<FavoriteTestRunnersService> {
    companion object {
        const val FAVORITE_PREFIX = "★ "

        fun getInstance(): FavoriteTestRunnersService = service()

        fun formatRunnerName(runnerName: String, isFavorite: Boolean): String =
            if (isFavorite) "$FAVORITE_PREFIX$runnerName" else runnerName
    }

    var favoriteRunners: MutableSet<String> = mutableSetOf()

    override fun getState(): FavoriteTestRunnersService = this

    override fun loadState(state: FavoriteTestRunnersService) {
        favoriteRunners.clear()
        favoriteRunners.addAll(state.favoriteRunners)
    }

    fun isFavorite(runnerName: String): Boolean = runnerName in favoriteRunners

    fun toggleFavorite(runnerName: String) {
        if (runnerName in favoriteRunners) {
            favoriteRunners.remove(runnerName)
        } else {
            favoriteRunners.add(runnerName)
        }
    }
}
