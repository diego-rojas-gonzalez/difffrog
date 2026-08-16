package com.crossguild.difffrog.config

import com.crossguild.difffrog.presentation.DisplayFormat
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import git4idea.repo.GitRepositoryManager
import java.awt.Dimension
import java.util.*
import javax.swing.*

class DiffFrogSettingsPanel(private val project: Project? = null) {
    val mainPanel: JPanel
    private val branchCombo = ComboBox<String>().apply {
        isEditable = true
    }
    private val txtMaxLines = JBTextField()
    private val delaySlider = JSlider(0, 2, 1).apply {
        val labels = Hashtable<Int, JLabel>()
        labels[0] = JLabel("slow 🐢")
        labels[1] = JLabel("medium 🐸")
        labels[2] = JLabel("fast 🐰")
        labelTable = labels
        paintLabels = true
        snapToTicks = true
    }
    private val formatCombo = ComboBox(DisplayFormat.values())
    private val chkShowIcons = JCheckBox("Show status icons (⚠️, 🌿)")
    private val chkIncludeUntracked = JCheckBox("Include untracked files")

    private val excludedListModel = CollectionListModel<String>()
    private val excludedList = JBList(excludedListModel)

    private val previewLabel = JBLabel().apply {
        horizontalAlignment = SwingConstants.CENTER
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("📺 LIVE PREVIEW"),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)
        )
    }

    init {
        // Cargar ramas si el proyecto está disponible
        project?.let { p ->
            val repo = GitRepositoryManager.getInstance(p).repositories.firstOrNull()
            repo?.let { r ->
                val localBranches = r.branches.localBranches.map { it.name }
                val remoteBranches = r.branches.remoteBranches.map { it.name }
                val allBranches = (localBranches + remoteBranches).distinct().sorted()
                allBranches.forEach { branchCombo.addItem(it) }
            }
        }

        val decorator = ToolbarDecorator.createDecorator(excludedList)
            .setAddAction {
                val pattern = Messages.showInputDialog(
                    "Enter pattern to exclude (e.g. *Test*, screens/, .xml):",
                    "Add Exclusion Pattern",
                    null
                )
                if (!pattern.isNullOrBlank()) {
                    excludedListModel.add(pattern)
                }
            }
            .setRemoveAction {
                val index = excludedList.selectedIndex
                if (index != -1) {
                    excludedListModel.remove(index)
                }
            }
            .disableUpDownActions()

        val excludedPanel = decorator.createPanel()
        excludedPanel.preferredSize = Dimension(0, 150)

        val updatePreview = {
            val tempConfig = getConfig()
            previewLabel.text = com.crossguild.difffrog.presentation.DiffTextRenderer.render(
                125, 45, tempConfig, com.crossguild.difffrog.presentation.RenderContext.TOOLBAR
            )
        }

        excludedListModel.addListDataListener(object : javax.swing.event.ListDataListener {
            override fun intervalAdded(e: javax.swing.event.ListDataEvent) = updatePreview()
            override fun intervalRemoved(e: javax.swing.event.ListDataEvent) = updatePreview()
            override fun contentsChanged(e: javax.swing.event.ListDataEvent) = updatePreview()
        })

        mainPanel = FormBuilder.createFormBuilder()
            .addVerticalGap(10)
            .addLabeledComponent(JBLabel("Target branch:"), branchCombo, 1, false)
            .addLabeledComponent(JBLabel("Max lines threshold:"), txtMaxLines, 1, false)
            .addLabeledComponent(JBLabel("Refresh time:"), delaySlider, 1, false)
            .addSeparator()
            .addComponent(previewLabel)
            .addLabeledComponent(JBLabel("Display format:"), formatCombo, 1, false)
            .addComponent(chkShowIcons)
            .addComponent(chkIncludeUntracked)
            .addVerticalGap(10)
            .addLabeledComponent(JBLabel("Excluded patterns on counting (Git syntax):"), excludedPanel, 1, true)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        // Listener para el editor del ComboBox
        val editorComponent = branchCombo.editor.editorComponent
        if (editorComponent is JTextField) {
            editorComponent.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
                override fun textChanged(e: javax.swing.event.DocumentEvent) = updatePreview()
            })
        }
        
        branchCombo.addActionListener { updatePreview() }
        txtMaxLines.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = updatePreview()
        })
        formatCombo.addActionListener { updatePreview() }
        chkShowIcons.addActionListener { updatePreview() }
        delaySlider.addChangeListener { updatePreview() }

        updatePreview()
    }

    fun applyConfig(config: DiffFrogConfig) {
        branchCombo.setSelectedItem(config.targetBranch)
        txtMaxLines.text = config.maxLines.toString()
        delaySlider.value = config.delayLevel
        formatCombo.selectedItem = config.displayFormat
        chkShowIcons.isSelected = config.showStatusIcons
        chkIncludeUntracked.isSelected = config.includeUntrackedFiles
        excludedListModel.removeAll()
        config.excludedPatterns.forEach { excludedListModel.add(it) }
    }

    fun getConfig(): DiffFrogConfig {
        val target = branchCombo.editor.item?.toString()?.trim() ?: "develop"
        return DiffFrogConfig(
            targetBranch = target,
            maxLines = txtMaxLines.text.toIntOrNull() ?: 420,
            delayLevel = delaySlider.value,
            displayFormat = formatCombo.selectedItem as? DisplayFormat ?: DisplayFormat.LABELED,
            showStatusIcons = chkShowIcons.isSelected,
            includeUntrackedFiles = chkIncludeUntracked.isSelected,
            excludedPatterns = (0 until excludedListModel.size).map { excludedListModel.getElementAt(it) }
        )
    }

    fun isModified(config: DiffFrogConfig): Boolean {
        val target = branchCombo.editor.item?.toString()?.trim() ?: "develop"
        return target != config.targetBranch ||
                txtMaxLines.text != config.maxLines.toString() ||
                delaySlider.value != config.delayLevel ||
                formatCombo.selectedItem != config.displayFormat ||
                chkShowIcons.isSelected != config.showStatusIcons ||
                chkIncludeUntracked.isSelected != config.includeUntrackedFiles ||
                (0 until excludedListModel.size).map { excludedListModel.getElementAt(it) } != config.excludedPatterns
    }

    fun getPreferredFocusedComponent(): JComponent = branchCombo
}
