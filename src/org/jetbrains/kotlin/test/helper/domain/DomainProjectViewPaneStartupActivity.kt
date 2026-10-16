package org.jetbrains.kotlin.test.helper.domain

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DomainProjectViewPaneStartupActivity : ProjectActivity, DumbAware {
    override suspend fun execute(project: Project) {
        if (DomainProjectService.hasDumpMarker(project)) return

        withContext(Dispatchers.EDT) {
            if (project.isDisposed) return@withContext

            val projectView = ProjectView.getInstance(project)
            val pane = projectView.getProjectViewPaneById(DomainProjectViewPane.VIEW_ID) ?: return@withContext
            projectView.removeProjectPane(pane)
        }
    }
}
