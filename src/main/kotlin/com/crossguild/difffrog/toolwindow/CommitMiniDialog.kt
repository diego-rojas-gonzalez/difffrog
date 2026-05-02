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
import javax.swing.JComponent
import javax.swing.JPanel

class CommitMiniDialog(
    private val project: Project,
    private val selectedChanges: List<Change>
) : DialogWrapper(project, true) {

    private val textArea = JBTextArea(5, 40)

    init {
        title = "be careful with your changes!!! 🐸"
        init()
        textArea.emptyText.text = "write your fuking commit message here..."
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(10)

        val scrollPane = JBScrollPane(textArea)
        scrollPane.preferredSize = Dimension(400, 100)
        panel.add(scrollPane, BorderLayout.CENTER)
        
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent {
        return textArea
    }

    fun prefillMessage(text: String) {
        if (text.isNotBlank()) {
            textArea.text = text
        }
    }

    fun executeCommit() {
        val message = textArea.text.trim()
        if (message.isEmpty()) return

        val changeListManager = ChangeListManager.getInstance(project)
        val defaultList: LocalChangeList = changeListManager.defaultChangeList

        // Para ejecutar el commit usamos CommitHelper u otras APIs internas si es necesario.
        // Una manera sencilla es delegar al servicio de Checkin de IntelliJ.
        val commitHelper = CommitHelper(
            project,
            defaultList,
            selectedChanges,
            title,
            message,
            emptyList(),
            false,
            false,
            com.intellij.util.NullableFunction<Any, Any> { null },
            null
        )
        
        // Ejecutar commit de forma asíncrona o sincrona dependiendo de la versión
        commitHelper.doCommit()
    }
}
