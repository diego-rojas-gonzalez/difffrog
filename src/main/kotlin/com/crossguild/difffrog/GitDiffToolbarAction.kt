package com.crossguild.difffrog

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
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
import com.crossguild.difffrog.config.DiffFrogConfigService
import com.crossguild.difffrog.presentation.DiffTextRenderer
import com.crossguild.difffrog.presentation.DisplayFormat
import com.crossguild.difffrog.presentation.RenderContext
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.components.ActionLink
import com.crossguild.difffrog.state.DiffDataService
import com.crossguild.difffrog.state.DiffUpdateListener
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

    private var config = DiffFrogConfigService.getInstance().loadConfig()

    private var displayedAdded = 0
    private var displayedDeleted = 0
    private var targetAdded = 0
    private var targetDeleted = 0

    private val labelStats = JBLabel("").apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        horizontalAlignment = SwingConstants.LEFT
        preferredSize = Dimension(140, 26)
    }

    private val loadingIcon = JBLabel("", AnimatedIcon.Default(), SwingConstants.LEFT).apply { isVisible = false }

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
            (it.source as Timer).stop()
        }
    }

    private var currentProject: Project? = null
    private val isListenerRegistered = AtomicBoolean(false)
    private var configPopup: com.intellij.openapi.ui.popup.JBPopup? = null

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.isOpaque = false
        labelStats.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = showConfigPopup(labelStats)
        })

        val gbc = GridBagConstraints().apply { gridy = 0; insets = JBUI.insets(0, 2) }
        gbc.gridx = 0; panel.add(loadingIcon, gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; panel.add(labelStats, gbc)

        updateLabelText(0, 0)
        return panel
    }

    private fun updateLabelText(added: Int, deleted: Int) {
        labelStats.text = DiffTextRenderer.render(added, deleted, config, RenderContext.TOOLBAR)
    }

    private fun triggerUpdate() {
        val project = currentProject ?: return
        DiffDataService.getInstance(project).triggerUpdate()
    }

    private fun showConfigPopup(anchor: JComponent) {
        // Si ya hay un popup abierto, cerrarlo (toggle)
        configPopup?.let {
            if (it.isVisible) {
                it.cancel()
                return
            }
        }

        val rootPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(15)
        }

        // Header row con botón X para cerrar
        val headerPanel = JPanel(BorderLayout()).apply { isOpaque = false }
        val closeBtn = JButton("✕").apply {
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Cerrar"
            preferredSize = Dimension(24, 24)
        }
        headerPanel.add(closeBtn, BorderLayout.EAST)
        rootPanel.add(headerPanel)
        rootPanel.add(Box.createVerticalStrut(9))


        // Live Preview Panel
        val previewPanel = JPanel(BorderLayout()).apply {
            border = TitledBorder("📺 LIVE PREVIEW")
        }
        val previewLabel = JBLabel(DiffTextRenderer.render(targetAdded, targetDeleted, config, RenderContext.TOOLBAR))
        previewLabel.horizontalAlignment = SwingConstants.CENTER
        previewPanel.add(previewLabel, BorderLayout.CENTER)
        
        rootPanel.add(previewPanel)
        rootPanel.add(Box.createVerticalStrut(10))

        // Basic Settings
        val basicPanel = JPanel(BorderLayout(8, 0)).apply {
            border = TitledBorder("🔧 Basic Settings")
        }
        val fieldsPanel = JPanel(GridLayout(2, 2, 8, 8))
        val txtTarget = JBTextField(config.targetBranch)
        val txtMaxLines = JBTextField(config.maxLines.toString())
        fieldsPanel.add(JBLabel("Target branch:"))
        fieldsPanel.add(txtTarget)
        fieldsPanel.add(JBLabel("Max lines threshold:"))
        fieldsPanel.add(txtMaxLines)
        
        val requiresApplyYourChangesLabel = JBLabel("(*) Requires Apply Changes").apply {
            foreground = com.intellij.ui.JBColor.RED
            isVisible = false
        }
        val applyBasicBtn = JButton("Apply 🐸").apply {
            isVisible = false
            toolTipText = "Apply changes immediately without restarting"
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            alignmentX = java.awt.Component.CENTER_ALIGNMENT
        }
        

        basicPanel.add(fieldsPanel, BorderLayout.CENTER)
        rootPanel.add(basicPanel)
        rootPanel.add(Box.createVerticalStrut(10))

        // Update Triggers
        val triggerPanel = JPanel(BorderLayout()).apply {
            border = TitledBorder("⚡ Update Triggers")
        }
        val delaySlider = JSlider(0, 2, config.delayLevel).apply {
            val labels = Hashtable<Int, JLabel>()
            labels[0] = JLabel("slow 🐢"); labels[1] = JLabel("medium 😐"); labels[2] = JLabel("fast ⚡")
            labelTable = labels; paintLabels = true; snapToTicks = true
        }
        triggerPanel.add(JBLabel("Refresh delay:"), BorderLayout.NORTH)
        triggerPanel.add(delaySlider, BorderLayout.CENTER)
        rootPanel.add(triggerPanel)
        rootPanel.add(Box.createVerticalStrut(10))
        rootPanel.add(requiresApplyYourChangesLabel)
        rootPanel.add(Box.createVerticalStrut(10))
        rootPanel.add(applyBasicBtn)

        // Display Format
        val formatPanel = JPanel(BorderLayout(8, 8)).apply {
            border = TitledBorder("📐 Display Format")
        }
        val formatCombo = ComboBox(DisplayFormat.values())
        formatCombo.selectedItem = config.displayFormat
        
        val formatControls = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
        formatControls.add(JBLabel("Format:"))
        formatControls.add(formatCombo)
        
        val formatWarningLabel = JBLabel("⚠️ Requires IDE restart to apply changes").apply {
            foreground = com.intellij.ui.JBColor.RED
            isVisible = false
        }
        
        
        formatPanel.add(formatControls, BorderLayout.CENTER)
        formatPanel.add(formatWarningLabel, BorderLayout.SOUTH)
        rootPanel.add(formatPanel)
        rootPanel.add(Box.createVerticalStrut(10))
    

        // Import/Export Panel
        val actionsPanel = JPanel(FlowLayout(FlowLayout.CENTER, 10, 0))
        val exportBtn = JButton("📤 Export JSON").apply {
            addActionListener { exportConfig() }
        }
        val importBtn = JButton("📥 Import JSON").apply {
            addActionListener { importConfig() }
        }
        actionsPanel.add(exportBtn)
        actionsPanel.add(importBtn)
        rootPanel.add(actionsPanel)
        rootPanel.add(Box.createVerticalStrut(8))

        // Save Button (fila propia)
        val savePanel = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0))
        val saveBtn = JButton("💾 Save and Reload").apply {
            preferredSize = Dimension(160, 32)
        }
        savePanel.add(saveBtn)
        rootPanel.add(savePanel)

        // Live Preview Updater
        val updatePreview = {
            var hasError = false
            
            // Validate Target Branch
            val branch = txtTarget.text.trim()
            if (branch.isEmpty()) {
                txtTarget.putClientProperty("JComponent.outline", "error")
                txtTarget.toolTipText = "Target branch cannot be empty"
                hasError = true
            } else {
                txtTarget.putClientProperty("JComponent.outline", null)
                txtTarget.toolTipText = null
            }

            // Validate Max Lines
            val parsedLines = txtMaxLines.text.toIntOrNull()
            if (parsedLines == null || parsedLines <= 0) {
                txtMaxLines.putClientProperty("JComponent.outline", "error")
                txtMaxLines.toolTipText = "Max lines must be a positive integer"
                hasError = true
            } else {
                txtMaxLines.putClientProperty("JComponent.outline", null)
                txtMaxLines.toolTipText = null
            }

            if (!hasError) {
                val tempConfig = config.copy()
                tempConfig.targetBranch = branch
                tempConfig.maxLines = parsedLines!!
                tempConfig.delayLevel = delaySlider.value
                tempConfig.displayFormat = formatCombo.selectedItem as DisplayFormat
                
                formatWarningLabel.isVisible = tempConfig.displayFormat != config.displayFormat
                
                val basicChanged = branch != config.targetBranch || parsedLines != config.maxLines || delaySlider.value != config.delayLevel
                applyBasicBtn.isVisible = basicChanged
                
                previewLabel.text = DiffTextRenderer.render(targetAdded, targetDeleted, tempConfig, RenderContext.TOOLBAR)
            }
        }
        
        applyBasicBtn.addActionListener {
            val branch = txtTarget.text.trim()
            val parsedLines = txtMaxLines.text.toIntOrNull() ?: return@addActionListener
            
            config.targetBranch = branch
            config.maxLines = parsedLines
            config.delayLevel = delaySlider.value
            DiffFrogConfigService.getInstance().saveConfig(config)
            
            triggerUpdate()
            updatePreview()
        }

        // Add listeners to trigger live preview
        txtMaxLines.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = updatePreview()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = updatePreview()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = updatePreview()
        })
        txtTarget.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = updatePreview()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = updatePreview()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = updatePreview()
        })
        formatCombo.addActionListener { updatePreview() }
        delaySlider.addChangeListener { updatePreview() }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(rootPanel, txtTarget)
            .setTitle("🐸 DiffFrog Configuration")
            .setRequestFocus(true)
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    // El guardado es explícito vía el botón Guardar
                    configPopup = null
                    triggerUpdate()
                }
            })
            .createPopup()

        configPopup = popup
        closeBtn.addActionListener { popup.cancel() }

        saveBtn.addActionListener {
            // 1. Validar campos
            val branch = txtTarget.text.trim()
            val parsedLines = txtMaxLines.text.toIntOrNull()
            if (branch.isEmpty() || parsedLines == null || parsedLines <= 0) {
                JOptionPane.showMessageDialog(
                    saveBtn,
                    "Por favor corrige los errores antes de guardar.",
                    "Configuración inválida",
                    JOptionPane.ERROR_MESSAGE
                )
                return@addActionListener
            }

            // 2. Modal de confirmación con aviso de reinicio
            val result = JOptionPane.showConfirmDialog(
                saveBtn,
                "Los cambios se guardarán y el IDE se reiniciará para aplicarlos.\n¿Deseas continuar?",
                "Reinicio del IDE requerido",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
            )

            if (result == JOptionPane.OK_OPTION) {
                // 3. Guardar config
                config.targetBranch = branch
                config.maxLines = parsedLines
                config.delayLevel = delaySlider.value
                config.displayFormat = formatCombo.selectedItem as DisplayFormat
                DiffFrogConfigService.getInstance().saveConfig(config)

                // 4. Cerrar popup
                popup.cancel()

                // 5. Reiniciar IDE
                ApplicationManagerEx.getApplicationEx().restart(true)
            }
        }
        popup.showInCenterOf(anchor)
    }

    private fun exportConfig() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        descriptor.title = "Select Folder to Export DiffFrog Config"
        
        val homeDir = VfsUtil.getUserHomeDir()
        
        FileChooser.chooseFile(descriptor, currentProject, homeDir) { file ->
            val exportPath = Paths.get(file.path, "difffrog_config.json")
            val json = DiffFrogConfigService.getInstance().exportToJson(config)
            exportPath.writeText(json)
            
            NotificationGroupManager.getInstance()
                .getNotificationGroup("DiffFrog")
                ?.createNotification("Config exported to ${exportPath.fileName}", NotificationType.INFORMATION)
                ?.notify(currentProject) ?: com.intellij.openapi.ui.Messages.showInfoMessage("Config exported to ${exportPath.fileName}", "DiffFrog")
        }
    }

    private fun importConfig() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
        descriptor.title = "Select DiffFrog Config JSON"
        
        val homeDir = VfsUtil.getUserHomeDir()
        
        FileChooser.chooseFile(descriptor, currentProject, homeDir) { file ->
            try {
                val json = Paths.get(file.path).readText()
                val imported = DiffFrogConfigService.getInstance().importFromJson(json)
                if (imported != null) {
                    config = imported
                    DiffFrogConfigService.getInstance().saveConfig(config)
                    triggerUpdate()
                    
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("DiffFrog")
                        ?.createNotification("Config imported successfully", NotificationType.INFORMATION)
                        ?.notify(currentProject) ?: com.intellij.openapi.ui.Messages.showInfoMessage("Config imported successfully", "DiffFrog")
                } else {
                    com.intellij.openapi.ui.Messages.showErrorDialog("Invalid JSON format", "Import Error")
                }
            } catch (e: Exception) {
                com.intellij.openapi.ui.Messages.showErrorDialog("Error reading file: ${e.message}", "Import Error")
            }
        }
    }



    override fun update(e: AnActionEvent) {
        currentProject = e.project
        val project = currentProject ?: return

        if (isListenerRegistered.compareAndSet(false, true)) {
            val connection = project.messageBus.connect(project as Disposable)
            connection.subscribe(DiffUpdateListener.TOPIC, object : DiffUpdateListener {
                override fun onDiffUpdated(added: Int, deleted: Int) {
                    targetAdded = added
                    targetDeleted = deleted
                    ApplicationManager.getApplication().invokeLater {
                        animationTimer.start()
                    }
                }
            })
            
            // Initial sync
            val dataService = DiffDataService.getInstance(project)
            targetAdded = dataService.targetAdded
            targetDeleted = dataService.targetDeleted
            animationTimer.start()

            Disposer.register(project as Disposable) {
                animationTimer.stop()
                isListenerRegistered.set(false)
            }
        }
    }

    override fun actionPerformed(e: AnActionEvent) = showConfigPopup(labelStats)
}