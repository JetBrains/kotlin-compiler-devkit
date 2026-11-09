package org.jetbrains.kotlin.test.helper.teamcity

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Icons for the safe merges shown in the Remote Run tool window, drawn in the outlined style of the
 * Remote Run tool window icon. The `_dark` and `@20x20` variants next to each path are picked up by
 * [IconLoader] automatically.
 */
internal object SafeMergeIcons {
    const val SAFE_MERGE_PATH: String = "/icons/safeMerge.svg"
    const val SAFE_MERGE_DRY_RUN_PATH: String = "/icons/safeMergeDryRun.svg"
    const val SAFE_MERGE_SUCCESS_PATH: String = "/icons/safeMergeSuccess.svg"
    const val SAFE_MERGE_FAILURE_PATH: String = "/icons/safeMergeFailure.svg"
    const val SAFE_MERGE_DRY_RUN_SUCCESS_PATH: String = "/icons/safeMergeDryRunSuccess.svg"
    const val SAFE_MERGE_DRY_RUN_FAILURE_PATH: String = "/icons/safeMergeDryRunFailure.svg"

    /**
     * A merge glyph guarded by a shield: starts a real safe merge.
     */
    @JvmField
    val SafeMerge: Icon = IconLoader.getIcon(SAFE_MERGE_PATH, SafeMergeIcons::class.java)

    /**
     * The [SafeMerge] glyph with a dashed shield, right branch and arrow: runs the safe merge checks without merging.
     */
    @JvmField
    val SafeMergeDryRun: Icon = IconLoader.getIcon(SAFE_MERGE_DRY_RUN_PATH, SafeMergeIcons::class.java)

    /**
     * [SafeMerge] in green: the safe merge succeeded.
     */
    @JvmField
    val SafeMergeSuccess: Icon = IconLoader.getIcon(SAFE_MERGE_SUCCESS_PATH, SafeMergeIcons::class.java)

    /**
     * [SafeMerge] in red, with a cross in the shield: the safe merge failed.
     */
    @JvmField
    val SafeMergeFailure: Icon = IconLoader.getIcon(SAFE_MERGE_FAILURE_PATH, SafeMergeIcons::class.java)

    /**
     * [SafeMergeDryRun] in green: the dry run succeeded.
     */
    @JvmField
    val SafeMergeDryRunSuccess: Icon =
        IconLoader.getIcon(SAFE_MERGE_DRY_RUN_SUCCESS_PATH, SafeMergeIcons::class.java)

    /**
     * [SafeMergeDryRun] in red, with a cross in the shield: the dry run failed.
     */
    @JvmField
    val SafeMergeDryRunFailure: Icon =
        IconLoader.getIcon(SAFE_MERGE_DRY_RUN_FAILURE_PATH, SafeMergeIcons::class.java)
}

internal fun safeMergeBuildIcon(isDryRun: Boolean, status: TeamCityBuildStatus?): Icon {
    val finishedStatus = status?.takeIf { buildStatus -> buildStatus.state == BuildState.FINISHED }

    return when (finishedStatus?.isSuccessful) {
        true -> if (isDryRun) SafeMergeIcons.SafeMergeDryRunSuccess else SafeMergeIcons.SafeMergeSuccess
        false -> if (isDryRun) SafeMergeIcons.SafeMergeDryRunFailure else SafeMergeIcons.SafeMergeFailure
        null -> if (isDryRun) SafeMergeIcons.SafeMergeDryRun else SafeMergeIcons.SafeMerge
    }
}
