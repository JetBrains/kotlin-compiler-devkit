package org.jetbrains.kotlin.test.helper.domain

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.project.DumbAwareAction

/**
 * Opens the latest known TeamCity build of the domain selected in the Domain pane.
 */
class OpenDomainBuildInTeamCityAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val domainNode = selectedDomainNode(e)

        e.presentation.isVisible = domainNode != null
        e.presentation.isEnabled = domainNode != null && buildStatus(e, domainNode) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val domainNode = selectedDomainNode(e) ?: return
        val status = buildStatus(e, domainNode) ?: return

        BrowserUtil.browse(status.webUrl)
    }
}

/**
 * The selected items are a snapshot of the pane's selected tree paths, so this is safe on BGT.
 * Domain group nodes exist only in the Domain pane.
 */
private fun selectedDomainNode(e: AnActionEvent): DomainGroupNode? =
    e.getData(PlatformCoreDataKeys.SELECTED_ITEMS)?.singleOrNull() as? DomainGroupNode

private fun buildStatus(e: AnActionEvent, domainNode: DomainGroupNode): DomainBuildStatus? =
    e.project?.getServiceIfCreated(DomainBuildStatusService::class.java)?.status(domainNode.domain)
