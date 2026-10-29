package org.jetbrains.kotlin.test.helper.teamcity

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/**
 * A TeamCity build launched from the IDE.
 *
 * [branchName] is the exact `--branch` value passed to the CLI — the local branch name at launch time.
 * It is deliberately not the server-side branch name, which TeamCity reports as `<default>` or a full ref in special cases.
 */
internal data class RememberedTeamCityBuild(
    val buildId: String,
    val webUrl: String,
    val branchName: String,
    val launchTimestamp: Long,
    val isAggregateTrigger: Boolean = false,
)

internal fun interface RememberedBuildsListener {
    fun rememberedBuildsChanged()
}

/**
 * Remembers the TeamCity builds launched from the IDE, per project.
 *
 * Stored in the personal (non-shared) workspace file, so the memory is never committed.
 * Only the [MAX_REMEMBERED_BUILDS] most recently launched builds are kept.
 * Every change publishes [CHANGED_TOPIC] on the project message bus.
 */
@Service(Service.Level.PROJECT)
@State(name = "KotlinDevKitTeamCityBuilds", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class RememberedTeamCityBuildStore(private val project: Project) :
    PersistentStateComponent<RememberedBuildsState> {

    companion object {
        const val MAX_REMEMBERED_BUILDS: Int = 50

        val CHANGED_TOPIC: Topic<RememberedBuildsListener> =
            Topic("Remembered TeamCity builds changed", RememberedBuildsListener::class.java)

        fun getInstance(project: Project): RememberedTeamCityBuildStore = project.service()
    }

    /**
     * Guarded by `this`.
     */
    private var state = RememberedBuildsState()

    fun builds(): List<RememberedTeamCityBuild> =
        synchronized(this) {
            state.builds.mapNotNull(::toRememberedTeamCityBuild)
        }

    fun add(build: RememberedTeamCityBuild) {
        val changed = synchronized(this) {
            val alreadyRemembered = state.builds.any { bean -> bean.buildId == build.buildId }
            if (!alreadyRemembered) {
                state.builds += toRememberedTeamCityBuildBean(build)
                state.builds = newestBuilds(state.builds)
            }

            !alreadyRemembered
        }

        if (changed) publishChanged()
    }

    fun replace(oldBuildId: String, newBuild: RememberedTeamCityBuild) {
        val changed = synchronized(this) {
            if (state.builds.none { bean -> bean.buildId == oldBuildId }) return@synchronized false

            val replacementAlreadyRemembered = state.builds.any { bean ->
                bean.buildId == newBuild.buildId && bean.buildId != oldBuildId
            }
            state.builds.removeAll { bean -> bean.buildId == oldBuildId }
            if (!replacementAlreadyRemembered) state.builds += toRememberedTeamCityBuildBean(newBuild)
            state.builds = newestBuilds(state.builds)
            true
        }

        if (changed) publishChanged()
    }

    fun remove(buildId: String) {
        val changed = synchronized(this) {
            state.builds.removeAll { bean -> bean.buildId == buildId }
        }

        if (changed) publishChanged()
    }

    /**
     * A copy: the state is serialized on a background thread while builds may be added or removed.
     */
    override fun getState(): RememberedBuildsState = synchronized(this) { state.copy() }

    override fun loadState(state: RememberedBuildsState) {
        synchronized(this) {
            this.state = state.copy()
            this.state.builds = newestBuilds(this.state.builds)
        }

        publishChanged()
    }

    private fun publishChanged() {
        if (project.isDisposed) return
        project.messageBus.syncPublisher(CHANGED_TOPIC).rememberedBuildsChanged()
    }
}

private fun newestBuilds(builds: List<RememberedTeamCityBuildBean>): MutableList<RememberedTeamCityBuildBean> =
    builds.sortedByDescending { bean -> bean.launchTimestamp }
        .take(RememberedTeamCityBuildStore.MAX_REMEMBERED_BUILDS)
        .toMutableList()

internal class RememberedBuildsState {
    var builds: MutableList<RememberedTeamCityBuildBean> = mutableListOf()

    fun copy(): RememberedBuildsState =
        RememberedBuildsState().also { stateCopy ->
            stateCopy.builds = builds.mapTo(mutableListOf()) { bean -> bean.copy() }
        }
}

/**
 * XmlSerializer bean of one remembered build.
 */
internal class RememberedTeamCityBuildBean {
    var buildId: String? = null
    var webUrl: String? = null
    var branchName: String? = null
    var launchTimestamp: Long = 0
    var isAggregateTrigger: Boolean = false

    fun copy(): RememberedTeamCityBuildBean =
        RememberedTeamCityBuildBean().also { beanCopy ->
            beanCopy.buildId = buildId
            beanCopy.webUrl = webUrl
            beanCopy.branchName = branchName
            beanCopy.launchTimestamp = launchTimestamp
            beanCopy.isAggregateTrigger = isAggregateTrigger
        }
}

internal fun toRememberedTeamCityBuildBean(build: RememberedTeamCityBuild): RememberedTeamCityBuildBean =
    RememberedTeamCityBuildBean().apply {
        buildId = build.buildId
        webUrl = build.webUrl
        branchName = build.branchName
        launchTimestamp = build.launchTimestamp
        isAggregateTrigger = build.isAggregateTrigger
    }

internal fun toRememberedTeamCityBuild(bean: RememberedTeamCityBuildBean): RememberedTeamCityBuild? =
    RememberedTeamCityBuild(
        buildId = bean.buildId ?: return null,
        webUrl = bean.webUrl ?: return null,
        branchName = bean.branchName ?: return null,
        launchTimestamp = bean.launchTimestamp,
        isAggregateTrigger = bean.isAggregateTrigger,
    )
