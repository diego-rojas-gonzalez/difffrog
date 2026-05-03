package com.crossguild.difffrog.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.LocalChangeList
import com.intellij.openapi.vcs.changes.ui.CommitHelper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class CommitMiniDialog(
    private val project: Project,
    private val selectedChanges: List<Change>
) : DialogWrapper(project, true) {

    private val previewArea = JBTextArea(4, 40).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = font.deriveFont(Font.PLAIN, 12f)
        emptyText.text = "commit preview will appear here..."
    }

    private lateinit var wizard: ConventionalWizardPanel

    init {
        title = "be careful with your changes!!! 🐸"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(10)
        panel.preferredSize = Dimension(460, 420)

        // ── Top: preview + clear button ──────────────────────────────────────
        val clearBtn = JButton("🗑").apply {
            toolTipText = "Clear draft"
            isBorderPainted = false; isContentAreaFilled = false
            font = font.deriveFont(16f)
            addActionListener { wizard.clearDraft() }
        }
        val previewScroll = JBScrollPane(previewArea).apply {
            preferredSize = Dimension(0, 80)
            border = JBUI.Borders.customLine(com.intellij.ui.JBColor.border(), 1)
        }
        val topRow = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
            add(previewScroll, BorderLayout.CENTER)
            add(clearBtn, BorderLayout.EAST)
        }

        // ── Middle: wizard ───────────────────────────────────────────────────
        wizard = ConventionalWizardPanel(project, selectedChanges) { state ->
            previewArea.text = state.buildMessage()
        }

        panel.add(topRow, BorderLayout.NORTH)
        panel.add(wizard, BorderLayout.CENTER)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = wizard

    fun prefillMessage(text: String) {
        if (text.isNotBlank()) previewArea.text = text
    }

    fun executeCommit() {
        val message = wizard.getCurrentMessage().trim()
        if (message.isEmpty()) return
        val changeListManager = ChangeListManager.getInstance(project)
        val defaultList: LocalChangeList = changeListManager.defaultChangeList
        val commitHelper = CommitHelper(
            project, defaultList, selectedChanges, title, message,
            emptyList(), false, false,
            com.intellij.util.NullableFunction<Any, Any> { null }, null
        )
        commitHelper.doCommit()
    }
}
