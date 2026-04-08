package com.crossguild.difffrog.presentation

import com.crossguild.difffrog.config.DiffFrogConfigService
import com.crossguild.difffrog.state.DiffDataService
import com.crossguild.difffrog.state.DiffUpdateListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.components.JBLabel
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.SwingConstants

class DiffFrogStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = "DiffFrogStatusBarWidget"
    override fun getDisplayName() = "DiffFrog Git Stats"
    override fun isAvailable(project: Project) = true
    override fun canBeEnabledOn(statusBar: StatusBar) = true

    override fun createWidget(project: Project): StatusBarWidget {
        return DiffFrogStatusBarWidget(project)
    }

    override fun disposeWidget(widget: StatusBarWidget) {
        Disposer.dispose(widget)
    }
}

class DiffFrogStatusBarWidget(private val project: Project) : CustomStatusBarWidget {
    private val label = JBLabel("DiffFrog")
    private var statusBar: StatusBar? = null

    init {
        label.horizontalAlignment = SwingConstants.CENTER
        label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        
        val connection = project.messageBus.connect(this)
        connection.subscribe(DiffUpdateListener.TOPIC, object : DiffUpdateListener {
            override fun onDiffUpdated(added: Int, deleted: Int) {
                ApplicationManager.getApplication().invokeLater {
                    updateUI(added, deleted)
                }
            }
        })
    }

    override fun ID() = "DiffFrogStatusBarWidget"

    override fun getComponent(): JComponent = label

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        // Initial sync
        val dataService = DiffDataService.getInstance(project)
        updateUI(dataService.targetAdded, dataService.targetDeleted)
    }

    private fun updateUI(added: Int, deleted: Int) {
        val config = DiffFrogConfigService.getInstance().loadConfig()
        label.text = DiffTextRenderer.render(added, deleted, config, RenderContext.STATUS_BAR)
        label.toolTipText = DiffTextRenderer.render(added, deleted, config, RenderContext.TOOLTIP)
        statusBar?.updateWidget(ID())
    }

    override fun dispose() {
        statusBar = null
    }
}
