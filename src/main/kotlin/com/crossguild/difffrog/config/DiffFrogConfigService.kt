package com.crossguild.difffrog.config

import com.crossguild.difffrog.presentation.DisplayFormat
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.google.gson.Gson
import com.google.gson.GsonBuilder

@Service(Service.Level.APP)
class DiffFrogConfigService {

    companion object {
        fun getInstance(): DiffFrogConfigService = service()

        private const val KEY_TARGET_BRANCH = "com.crossguild.difffrog.targetBranch"
        private const val KEY_MAX_LINES = "com.crossguild.difffrog.maxLines"
        private const val KEY_DELAY_LEVEL = "com.crossguild.difffrog.delayLevel"
        private const val KEY_DISPLAY_FORMAT = "com.crossguild.difffrog.displayFormat"
        private const val KEY_SHOW_STATUS_ICONS = "com.crossguild.difffrog.showStatusIcons"
        private const val KEY_DIFF_SCOPE = "com.crossguild.difffrog.diffScope"
        private const val KEY_INCLUDE_UNTRACKED = "com.crossguild.difffrog.includeUntracked"
        private const val KEY_FONT_SIZE = "com.crossguild.difffrog.fontSize"
        private const val KEY_EXCLUDED_PATTERNS = "com.crossguild.difffrog.excludedPatterns"


        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    }

    fun loadConfig(): DiffFrogConfig {
        val props = PropertiesComponent.getInstance()
        val config = DiffFrogConfig()

        config.targetBranch = props.getValue(KEY_TARGET_BRANCH, "develop")
        config.maxLines = props.getInt(KEY_MAX_LINES, 420)
        config.delayLevel = props.getInt(KEY_DELAY_LEVEL, 1)
        
        val formatStr = props.getValue(KEY_DISPLAY_FORMAT, DisplayFormat.COMPACT.name)
        config.displayFormat = try {
            DisplayFormat.valueOf(formatStr)
        } catch (e: IllegalArgumentException) {
            DisplayFormat.LABELED
        }

        config.showStatusIcons = props.getBoolean(KEY_SHOW_STATUS_ICONS, true)
        config.diffScope = props.getValue(KEY_DIFF_SCOPE, "Working directory")
        config.includeUntrackedFiles = props.getBoolean(KEY_INCLUDE_UNTRACKED, true)
        config.fontSize = props.getInt(KEY_FONT_SIZE, 12)
        
        val excludedStr = props.getValue(KEY_EXCLUDED_PATTERNS, "")
        config.excludedPatterns = if (excludedStr.isEmpty()) emptyList() else excludedStr.split(";")

        return config
    }

    fun saveConfig(config: DiffFrogConfig) {
        val props = PropertiesComponent.getInstance()
        props.setValue(KEY_TARGET_BRANCH, config.targetBranch)
        props.setValue(KEY_MAX_LINES, config.maxLines, 420)
        props.setValue(KEY_DELAY_LEVEL, config.delayLevel, 1)
        props.setValue(KEY_DISPLAY_FORMAT, config.displayFormat.name)
        props.setValue(KEY_SHOW_STATUS_ICONS, config.showStatusIcons, true)
        props.setValue(KEY_DIFF_SCOPE, config.diffScope)
        props.setValue(KEY_INCLUDE_UNTRACKED, config.includeUntrackedFiles, true)
        props.setValue(KEY_FONT_SIZE, config.fontSize, 12)
        props.setValue(KEY_EXCLUDED_PATTERNS, config.excludedPatterns.joinToString(";"))

        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus
            .syncPublisher(DiffFrogConfigListener.TOPIC)
            .onConfigChanged(config)
    }

}
