package com.crossguild.difffrog

import com.crossguild.difffrog.config.DiffFrogConfigService
import com.crossguild.difffrog.presentation.DiffTextRenderer
import com.crossguild.difffrog.presentation.RenderContext
import com.crossguild.difffrog.state.DiffDataService
import com.crossguild.difffrog.state.DiffUpdateListener
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.ui.AnimatedIcon
import com.intellij.util.ui.JBUI
import com.crossguild.difffrog.presentation.DisplayFormat
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.components.ActionLink
import java.nio.file.Paths
import kotlin.io.path.writeText
import kotlin.io.path.readText
import javax.swing.border.TitledBorder
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*
import javax.swing.Timer

class GitDiffToolbarAction : AnAction(), CustomComponentAction {

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent { return DiffFrogToolbarPanel() }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
            .showSettingsDialog(e.project, com.crossguild.difffrog.config.DiffFrogConfigurable::class.java)
    }

    private class DiffFrogToolbarPanel : JPanel(GridBagLayout()), Disposable {
        private var config = DiffFrogConfigService.getInstance().loadConfig()
        private var project: Project? = null

        private var displayedAdded = 0
        private var displayedDeleted = 0
        private var targetAdded = 0
        private var targetDeleted = 0

        private val labelStats = JBLabel("").apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            horizontalAlignment = SwingConstants.LEFT
        }

        private val animationTimer = Timer(25) {
            var changed = false

            if (displayedAdded != targetAdded) {
                val diff = targetAdded - displayedAdded
                val step = (diff / 4).coerceIn(-20, 20).let { if (it == 0) (if (diff > 0) 1 else -1) else it }
                displayedAdded = if (Math.abs(diff) <= Math.abs(step)) targetAdded else displayedAdded + step
                changed = true
            }

            if (displayedDeleted != targetDeleted) {
                val diff = targetDeleted - displayedDeleted
                val step = (diff / 4).coerceIn(-20, 20).let { if (it == 0) (if (diff > 0) 1 else -1) else it }
                displayedDeleted = if (Math.abs(diff) <= Math.abs(step)) targetDeleted else displayedDeleted + step
                changed = true
            }

            if (changed) {
                updateLabelText(displayedAdded, displayedDeleted)
            } else {
                (it.source as javax.swing.Timer).stop()
            }
        }

        init {
            isOpaque = false
            val gbc = GridBagConstraints().apply {
                gridy = 0
                insets = JBUI.insets(0, 4)
            }
            gbc.gridx = 0
            gbc.weightx = 1.0
            add(labelStats, gbc)

            labelStats.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = showConfigPopup(labelStats)
            })

            updateLabelText(0, 0)
        }

        override fun addNotify() {
            super.addNotify()
            project = CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(this))
            val p = project ?: return
            val connection = p.messageBus.connect(this)
            connection.subscribe(DiffUpdateListener.TOPIC, object : DiffUpdateListener {
                override fun onDiffUpdated(added: Int, deleted: Int) {
                    targetAdded = added
                    targetDeleted = deleted
                    ApplicationManager.getApplication().invokeLater {
                        animationTimer.start()
                    }
                }
            })

            val appConnection = ApplicationManager.getApplication().messageBus.connect(this)
            appConnection.subscribe(
                com.crossguild.difffrog.config.DiffFrogConfigListener.TOPIC,
                object : com.crossguild.difffrog.config.DiffFrogConfigListener {
                    override fun onConfigChanged(newConfig: com.crossguild.difffrog.config.DiffFrogConfig) {
                        config = newConfig
                        updateLabelText(targetAdded, targetDeleted)
                        com.crossguild.difffrog.state.DiffDataService.getInstance(p).triggerUpdate()
                    }
                })
            // Initial sync
            val dataService = DiffDataService.getInstance(p)
            targetAdded = dataService.targetAdded
            targetDeleted = dataService.targetDeleted
            displayedAdded = targetAdded
            displayedDeleted = targetDeleted
            updateLabelText(targetAdded, targetDeleted)
            Disposer.register(p, this)
        }

        override fun removeNotify() {
            super.removeNotify()
            Disposer.dispose(this)
        }

        private fun updateLabelText(added: Int, deleted: Int) {
            labelStats.text = DiffTextRenderer.render(added, deleted, config, RenderContext.TOOLBAR)
        }

        private fun showConfigPopup(anchor: JComponent) {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, com.crossguild.difffrog.config.DiffFrogConfigurable::class.java)
        }

        override fun dispose() {
            animationTimer.stop()
        }
    }
}
