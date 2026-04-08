package com.crossguild.difffrog.config

import com.crossguild.difffrog.presentation.DisplayFormat

data class DiffFrogConfig(
    var targetBranch: String = "develop",
    var maxLines: Int = 420,
    var delayLevel: Int = 1,
    var displayFormat: DisplayFormat = DisplayFormat.LABELED,
    var diffScope: String = "Working directory", // Future-proofing from ASCII mockup
    var includeUntrackedFiles: Boolean = true, // Future-proofing
    var showStatusIcons: Boolean = true, // Future-proofing
    var fontSize: Int = 12 // Future-proofing
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
            fontSize
        )
    }
}
