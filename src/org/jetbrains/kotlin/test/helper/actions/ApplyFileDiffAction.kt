package org.jetbrains.kotlin.test.helper.actions

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.util.ThreeSide
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.stacktrace.DiffHyperlink
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.text.StringUtilRt
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.writeText
import java.nio.file.Paths

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
        applyDiffs(tests)
    }
}

enum class ApplyDiffResult {
    SUCCESS, HAS_CONFLICT, NO_DIFFS
}

fun applyDiffs(tests: Array<out AbstractTestProxy>): ApplyDiffResult {
    val diffsByFile = tests
        .flatMap { it.collectChildrenRecursively(mutableListOf()) }
        .groupBy { it.filePath }
        .mapValues { it.value.distinctBy { diff -> diff.right } }

    var result = if (diffsByFile.any { it.key != null }) ApplyDiffResult.SUCCESS else ApplyDiffResult.NO_DIFFS

    WriteAction.run<Throwable> {
        for ((filePath, diffs) in diffsByFile) {
            if (filePath == null) continue
            val file = VfsUtil.findFile(Paths.get(filePath), true) ?: continue
            val lineSeparator = LoadTextUtil.detectLineSeparator(file, true) ?: "\n"

            val result =  if (diffs.size == 1) {
                StringUtilRt.convertLineSeparators(diffs.single().right, lineSeparator)
            } else {
                val first = diffs.first()
                val base = first.left
                diffs
                    .windowed(2)
                    .fold(first.right) { acc, (_, right) ->
                        autoMerge(
                            left = StringUtilRt.convertLineSeparators(acc, lineSeparator),
                            base = StringUtilRt.convertLineSeparators(base, lineSeparator),
                            right = StringUtilRt.convertLineSeparators(right.right, lineSeparator),
                            onConflict = {
                                result = ApplyDiffResult.HAS_CONFLICT
                            }
                        )
                    }
            }

            file.writeText(StringUtilRt.convertLineSeparators(result, lineSeparator))
        }
    }

    return result
}

private fun AbstractTestProxy.collectChildrenRecursively(list: MutableList<DiffHyperlink>): List<DiffHyperlink> {
    if (isLeaf) {
        list.addAll(diffViewerProviders)
    } else {
        for (child in children) {
            child.collectChildrenRecursively(list)
        }
    }
    return list
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
                // Conflict
                result += "<<<<<<< LEFT"
                result += leftChunk
                result += "======="
                result += rightChunk
                result += ">>>>>>> RIGHT"
                onConflict()
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
