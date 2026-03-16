package org.jetbrains.kotlin.test.helper.executor

import com.intellij.execution.Executor
import com.intellij.icons.AllIcons
import javax.swing.Icon

class RunAndApplyDiffsExecutor : Executor() {
    companion object {
        const val EXECUTOR_ID = "RunAndApplyDiffs"
    }

    override fun getToolWindowId(): String = "Run"
    override fun getToolWindowIcon(): Icon = AllIcons.Diff.ApplyNotConflicts
    override fun getIcon(): Icon = AllIcons.Diff.ApplyNotConflicts
    override fun getDisabledIcon(): Icon = AllIcons.Diff.ApplyNotConflicts
    override fun getDescription(): String = "Run and apply diffs on test failures"
    override fun getActionName(): String = "Run and Apply Diffs"
    override fun getId(): String = EXECUTOR_ID
    override fun getStartActionText(): String = "Run and Apply Diffs"
    override fun getContextActionId(): String = "RunAndApplyDiffsContext"
    override fun getHelpId(): String? = null
}
