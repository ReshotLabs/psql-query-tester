package com.psqlquerytester.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class QueryTesterToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val queryTesterWindow = QueryTesterToolWindow(project)
        val content = ContentFactory.getInstance().createContent(
            queryTesterWindow.getContent(),
            "",
            false
        )
        toolWindow.contentManager.addContent(content)

        // Store reference for access from action
        project.putUserData(QueryTesterToolWindow.KEY, queryTesterWindow)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
