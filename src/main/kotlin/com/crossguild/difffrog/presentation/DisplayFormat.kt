package com.crossguild.difffrog.presentation

enum class DisplayFormat(val displayName: String) {
    COMPACT("Compact"),
    LABELED("Labeled");

    override fun toString(): String = displayName
}

enum class RenderContext {
    TOOLBAR,
    STATUS_BAR,
    TOOLTIP
}
