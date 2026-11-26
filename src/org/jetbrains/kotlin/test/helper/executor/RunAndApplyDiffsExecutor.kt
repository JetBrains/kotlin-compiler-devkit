package org.jetbrains.kotlin.test.helper.executor

import com.intellij.execution.Executor
import com.intellij.execution.ExecutorRegistry
import com.intellij.icons.AllIcons
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import org.jetbrains.kotlin.test.helper.actions.ActionIcons
import javax.swing.Icon

class RunAndApplyDiffsExecutor : Executor() {
    companion object {
        const val EXECUTOR_ID = "RunAndApplyDiffs"

        init {
            ExternalSystemUtil.registerRunnerId(EXECUTOR_ID, RunAndApplyDiffsProgramRunner.RUNNER_ID)
        }

        fun getInstance(): Executor {
            return ExecutorRegistry.getInstance().getExecutorById(EXECUTOR_ID)!!
        }
    }

    override fun getToolWindowId(): String = "Run"
    override fun getToolWindowIcon(): Icon = ActionIcons.RunAndApply
    override fun getIcon(): Icon = ActionIcons.RunAndApply
    override fun getDisabledIcon(): Icon = ActionIcons.RunAndApply
    override fun getDescription(): String = "Run and apply diffs on test failures"
    override fun getActionName(): String = "Run and Apply Diffs"
    override fun getId(): String = EXECUTOR_ID
    override fun getStartActionText(): String = "Run and Apply Diffs"
    override fun getContextActionId(): String = "RunAndApplyDiffsContext"
    override fun getHelpId(): String? = null
}
