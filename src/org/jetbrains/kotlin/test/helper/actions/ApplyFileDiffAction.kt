package org.jetbrains.kotlin.test.helper.actions

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.comparison.MergeResolveUtil
import com.intellij.diff.util.ThreeSide
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.rd.util.lifetime
import com.intellij.openapi.util.text.StringUtilRt
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.findDocument
import com.intellij.psi.PsiDocumentManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.kotlin.test.helper.collectDiffsRecursively
import org.jetbrains.kotlin.test.helper.isPassedOrIgnored
import java.nio.file.Paths
import kotlin.coroutines.resume

internal class ApplyFileDiffAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val presentation = e.presentation
        if (e.project == null) {
            presentation.isEnabledAndVisible = false
        } else {
            val context = e.dataContext
            val tests = AbstractTestProxy.DATA_KEYS.getData(context).orEmpty()
            presentation.isEnabledAndVisible = tests.any { it.leafDiffViewerProvider != null }
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val tests = AbstractTestProxy.DATA_KEYS.getData(e.dataContext) ?: return
        val project = e.project ?: return
        project.lifetime.coroutineScope.launch {
            applyDiffs(tests, project)
        }
    }
}

typealias CancellationCallback = () -> Unit

object NoopCancellationCallback : CancellationCallback {
    override fun invoke() {}
}

context(scope: CoroutineScope)
suspend fun runTestAndApplyDiffLoop(
    project: Project,
    isInteractive: Boolean = true,
    runTests: suspend () -> CancellationCallback?,
): SMTestProxy.SMRootTestProxy {
    while (true) {
        val cb = runTests()

        val testProxy = awaitTestRun(project, cb)
        if (testProxy.isPassedOrIgnored) return testProxy

        val result = applyDiffs(arrayOf(testProxy), project)

        if (result != ApplyDiffResult.SUCCESS) {
            val shouldContinue = !isInteractive || suspendCancellableCoroutine {
                val notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup("Kotlin Compiler DevKit Run Apply")
                    .createNotification(
                        when (result) {
                            ApplyDiffResult.HAS_CONFLICT -> "Applying diffs produced a conflict. Resolve conflicts to continue."
                            ApplyDiffResult.NO_DIFFS -> "Tests failed without a diff. Address test failures to continue."
                            ApplyDiffResult.SUCCESS -> error("Not possible")
                        }, NotificationType.ERROR
                    )
                    .setImportant(true)
                    .setRemoveWhenExpired(true)

                notification.addAction(object : AnAction("Continue") {
                    override fun actionPerformed(p0: AnActionEvent) {
                        it.resume(true)
                        notification.expire()
                    }
                })

                notification.addAction(object : AnAction("Cancel") {
                    override fun actionPerformed(p0: AnActionEvent) {
                        it.resume(false)
                        notification.expire()
                    }
                })

                notification.notify(project)

                it.invokeOnCancellation { notification.expire() }
            }

            if (!shouldContinue) return testProxy
        }
    }
}

context(scope: CoroutineScope)
suspend fun awaitTestRun(project: Project, onCancel: CancellationCallback?): SMTestProxy.SMRootTestProxy {
    return suspendCancellableCoroutine {
        val connection = project.messageBus.connect(scope)
        connection
            .subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsAdapter() {
                override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
                    connection.disconnect()
                    it.resume(testsRoot)
                }
            })

        it.invokeOnCancellation {
            connection.disconnect()
            onCancel?.invoke()
        }
    }
}

enum class ApplyDiffResult {
    SUCCESS, HAS_CONFLICT, NO_DIFFS
}

suspend fun applyDiffs(tests: Array<out AbstractTestProxy>, project: Project): ApplyDiffResult {
    val fileDocumentManager = FileDocumentManager.getInstance()
    val psiDocumentManager = PsiDocumentManager.getInstance(project)

    val diffsByFile = tests
        .flatMap { it.collectDiffsRecursively() }
        .groupBy { it.filePath }
        .mapValues { it.value.distinctBy { diff -> diff.right } }

    var result = if (diffsByFile.any { it.key != null }) ApplyDiffResult.SUCCESS else ApplyDiffResult.NO_DIFFS

    @Suppress("UnstableApiUsage")
    writeCommandAction(project, "Applying Diffs") {
        for ((filePath, diffs) in diffsByFile) {
            if (filePath == null) continue
            val virtualFile = VfsUtil.findFile(Paths.get(filePath), true) ?: continue
            val document = virtualFile.findDocument() ?: continue

            // Get base before refreshing VFS so that direct modifications on disk aren't picked up.
            val base = document.text
            virtualFile.refresh(false, false)
            fileDocumentManager.reloadFromDisk(document)

            val result =  if (diffs.size == 1) {
                val diff = diffs.single()
                autoMerge(
                    left = StringUtilRt.convertLineSeparators(diff.left, "\n"),
                    base = StringUtilRt.convertLineSeparators(base, "\n"),
                    right = StringUtilRt.convertLineSeparators(diff.right, "\n"),
                    onConflict = {
                        result = ApplyDiffResult.HAS_CONFLICT
                    }
                )
            } else {
                diffs
                    .windowed(2)
                    .fold(diffs.first().right) { acc, (_, right) ->
                        autoMerge(
                            left = StringUtilRt.convertLineSeparators(acc, "\n"),
                            base = StringUtilRt.convertLineSeparators(base, "\n"),
                            right = StringUtilRt.convertLineSeparators(right.right, "\n"),
                            onConflict = {
                                result = ApplyDiffResult.HAS_CONFLICT
                            }
                        )
                    }
            }

            document.replaceString(0, document.textLength, result)
            psiDocumentManager.commitDocument(document)
            fileDocumentManager.saveDocument(document)
        }
    }

    return result
}

private fun autoMerge(left: String, base: String, right: String, onConflict: () -> Unit): String {
    val leftLines = left.lines()
    val baseLines = base.lines()
    val rightLines = right.lines()

    val fragments = ComparisonManager.getInstance().mergeLines(
        left,
        base,
        right,
        ComparisonPolicy.DEFAULT,
        EmptyProgressIndicator()
    )

    val result = mutableListOf<String>()
    var currentBaseLine = 0

    for (fragment in fragments) {
        val baseStart = fragment.getStartLine(ThreeSide.BASE)
        val baseEnd = fragment.getEndLine(ThreeSide.BASE)
        val leftStart = fragment.getStartLine(ThreeSide.LEFT)
        val leftEnd = fragment.getEndLine(ThreeSide.LEFT)
        val rightStart = fragment.getStartLine(ThreeSide.RIGHT)
        val rightEnd = fragment.getEndLine(ThreeSide.RIGHT)

        // Copy unchanged lines from base
        while (currentBaseLine < baseStart) {
            result += baseLines[currentBaseLine]
            currentBaseLine++
        }

        // Extract chunks
        val leftChunk = leftLines.subList(leftStart, leftEnd)
        val rightChunk = rightLines.subList(rightStart, rightEnd)
        val baseChunk = baseLines.subList(baseStart, baseEnd)

        // Auto-resolve
        when {
            leftChunk == rightChunk -> result += leftChunk
            leftChunk == baseChunk -> result += rightChunk
            rightChunk == baseChunk -> result += leftChunk
            else -> {
                val greedyResolution = MergeResolveUtil.tryResolve(
                    leftChunk.joinToString("\n"),
                    baseChunk.joinToString("\n"),
                    rightChunk.joinToString("\n")
                )
                if (greedyResolution != null) {
                    result += greedyResolution.lines()
                } else {
                    // Conflict
                    result += "<<<<<<< LEFT"
                    result += leftChunk
                    result += "======="
                    result += rightChunk
                    result += ">>>>>>> RIGHT"
                    onConflict()
                }
            }
        }

        currentBaseLine = baseEnd
    }

    // Add remaining lines from base
    while (currentBaseLine < baseLines.size) {
        result += baseLines[currentBaseLine]
        currentBaseLine++
    }

    return result.joinToString("\n")
}
