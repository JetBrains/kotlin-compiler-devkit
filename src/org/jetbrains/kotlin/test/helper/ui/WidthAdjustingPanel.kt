// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.test.helper.ui

import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.ui.ComponentUtil
import java.awt.Dimension
import javax.swing.JPanel

class WidthAdjustingPanel : JPanel() {

  override fun getPreferredSize(): Dimension {
    val size = super.getPreferredSize()
    val topPanel = ComponentUtil.getParentOfType(ActionToolbar::class.java, this)?.component ?: return size
    val parent = parent ?: return size
    val parentInsets = parent.insets?.run { left + right } ?: 0
    val availableWidth = topPanel.width / 2 - parentInsets
    val otherComponentsWidth = parent.components.asSequence().filter { it !== this }.sumOf { it.preferredSize.width }
    val maxAllowedWidth = (availableWidth - otherComponentsWidth).coerceAtLeast(minimumSize.width)
    size.width = size.width.coerceAtMost(maxAllowedWidth)
    return size
  }

}
