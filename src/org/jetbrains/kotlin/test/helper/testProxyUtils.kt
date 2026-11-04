package org.jetbrains.kotlin.test.helper

import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.stacktrace.DiffHyperlink

fun AbstractTestProxy.getLeafs(list: MutableList<AbstractTestProxy> = mutableListOf()): List<AbstractTestProxy> {
    if (isLeaf) {
        list.add(this)
    } else {
        for (child in children) {
            child.getLeafs(list)
        }
    }
    return list
}

fun AbstractTestProxy.collectDiffsRecursively(): List<DiffHyperlink> {
    return getLeafs().flatMap { it.diffViewerProviders }
}

val AbstractTestProxy.isPassedOrIgnored: Boolean
    get() = isPassed || isIgnored

