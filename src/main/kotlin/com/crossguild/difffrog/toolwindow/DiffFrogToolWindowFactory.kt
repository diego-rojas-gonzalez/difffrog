package com.crossguild.difffrog.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class DiffFrogToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val diffFrogToolWindow = DiffFrogToolWindow(project)
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(diffFrogToolWindow.content, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
