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
import javax.swing.JProgressBar
import javax.swing.Action

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

    private val progressBar = JProgressBar(1, 4).apply {
        isStringPainted = false
        preferredSize = Dimension(Int.MAX_VALUE, 4)
        isOpaque = false
        border = null
    }

    private lateinit var wizard: ConventionalWizardPanel

    init {
        title = "be careful with your changes!!! 🐸"
        isModal = false
        init()
    }

    override fun createActions(): Array<Action> = emptyArray()

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(10)
        panel.preferredSize = Dimension(460, 420)

        // ── Top: Progress bar + Preview ──────────────────────────────────────
        val previewScroll = JBScrollPane(previewArea).apply {
            preferredSize = Dimension(0, 80)
            border = null
        }
        val topRow = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
            add(progressBar, BorderLayout.NORTH)
            add(previewScroll, BorderLayout.CENTER)
        }

        // ── Middle: wizard ───────────────────────────────────────────────────
        wizard = ConventionalWizardPanel(
            project, 
            selectedChanges,
            onStateChanged = { state -> 
                if (state.step < 4) previewArea.text = state.buildMessage() 
            },
            onStepChanged = { step -> 
                progressBar.value = step
                previewArea.isEditable = (step == 4)
                if (step == 4) {
                    previewArea.text = wizard.getCurrentMessage()
                }
            },
            onFinish = {
                executeCommit()
                wizard.clearDraft()
                close(OK_EXIT_CODE)
            }
        )

        panel.add(topRow, BorderLayout.NORTH)
        panel.add(wizard, BorderLayout.CENTER)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = wizard

    override fun show() {
        window?.addWindowFocusListener(object : java.awt.event.WindowAdapter() {
            override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                close(CANCEL_EXIT_CODE)
            }
        })
        super.show()
        wizard.requestInitialFocus()
    }

    fun prefillMessage(text: String) {
        if (text.isNotBlank()) previewArea.text = text
    }

    fun executeCommit() {
        val message = previewArea.text.trim()
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
