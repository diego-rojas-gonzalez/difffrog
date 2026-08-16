package com.crossguild.difffrog.config

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.ProjectManager
import javax.swing.JComponent

class DiffFrogConfigurable : SearchableConfigurable {
    private var settingsPanel: DiffFrogSettingsPanel? = null

    override fun getId(): String = "com.crossguild.difffrog.settings"
    override fun getDisplayName(): String = "DiffFrog configuration"

    override fun createComponent(): JComponent? {
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        settingsPanel = DiffFrogSettingsPanel(project)
        return settingsPanel?.mainPanel
    }

    override fun isModified(): Boolean {
        val config = DiffFrogConfigService.getInstance().loadConfig()
        return settingsPanel?.isModified(config) ?: false
    }

    override fun apply() {
        val config = settingsPanel?.getConfig() ?: return
        DiffFrogConfigService.getInstance().saveConfig(config)
        
        // Notify all projects to update
        for (project in ProjectManager.getInstance().openProjects) {
            com.crossguild.difffrog.state.DiffDataService.getInstance(project).triggerUpdate()
        }
    }

    override fun reset() {
        val config = DiffFrogConfigService.getInstance().loadConfig()
        settingsPanel?.applyConfig(config)
    }

    override fun disposeUIResources() {
        settingsPanel = null
    }
}
