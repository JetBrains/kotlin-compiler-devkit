package org.jetbrains.kotlin.test.helper.domain

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.ui.LayeredIcon
import org.jetbrains.kotlin.test.helper.MyBundle
import javax.swing.Icon
import javax.swing.SwingConstants

private val ignoredBadgeIcon: Icon =
    IconLoader.getIcon("/icons/test_federation_ignored.svg", TestFederationIgnoreProjectViewNodeDecorator::class.java)

/*
 * Badges files ignored by `repo/domains.yaml`.
 * Also covers Domain pane nodes: the platform runs decorators from PSI-based node updates,
 * which `DomainFileNode` and the `PsiDirectoryNode` delegate of `DomainDirectoryNode` perform.
 */
internal class TestFederationIgnoreProjectViewNodeDecorator : ProjectViewNodeDecorator, DumbAware {
    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val file = when (val value = node.value) {
            is VirtualFile -> value
            is PsiFileSystemItem -> value.virtualFile ?: return
            else -> return
        }
        val project = node.project ?: return
        if (isIgnoredByTestFederation(project, file)) data.markFederationIgnored()
    }
}

/* Idempotent, so the YAML decorator and the dump-based Domain badge can both apply to one node. */
internal fun PresentationData.markFederationIgnored() {
    getIcon(false)?.let { setIcon(federationIgnoredIcon(it)) }
    val ignoredTooltip = MyBundle.message("test.federation.ignored.tooltip")
    val currentTooltip = tooltip?.takeIf { it.isNotBlank() }
    if (currentTooltip == null) {
        tooltip = ignoredTooltip
    } else if (ignoredTooltip !in currentTooltip) {
        tooltip = "$currentTooltip\n$ignoredTooltip"
    }
}

private fun federationIgnoredIcon(baseIcon: Icon): Icon {
    if (baseIcon is LayeredIcon && baseIcon.iconCount == 2 && baseIcon.getIcon(1) === ignoredBadgeIcon) return baseIcon
    return LayeredIcon(2).apply {
        setIcon(baseIcon, 0)
        setIcon(ignoredBadgeIcon, 1, SwingConstants.SOUTH_EAST)
    }
}
