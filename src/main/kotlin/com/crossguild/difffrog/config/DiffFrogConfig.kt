package com.crossguild.difffrog.config

import com.crossguild.difffrog.presentation.DisplayFormat

data class DiffFrogConfig(
    var targetBranch: String = "",
    var maxLines: Int = 420,
    var delayLevel: Int = 1,
    var displayFormat: DisplayFormat = DisplayFormat.LABELED,
    var diffScope: String = "Working directory",
    var includeUntrackedFiles: Boolean = true,
    var showStatusIcons: Boolean = true,
    var fontSize: Int = 12,
    var excludedPatterns: List<String> = emptyList()
) {
    fun copy(): DiffFrogConfig {
        return DiffFrogConfig(
            targetBranch,
            maxLines,
            delayLevel,
            displayFormat,
            diffScope,
            includeUntrackedFiles,
            showStatusIcons,
            fontSize,
            excludedPatterns.toList()
        )
    }
}
