package com.crossguild.difffrog.presentation

import com.crossguild.difffrog.config.DiffFrogConfig
import com.intellij.util.ui.UIUtil

object DiffTextRenderer {

    fun render(
        added: Int,
        deleted: Int,
        config: DiffFrogConfig,
        context: RenderContext
    ): String {
        return when (context) {
            RenderContext.TOOLBAR -> renderToolbar(added, deleted, config)
            RenderContext.STATUS_BAR -> renderStatusBar(added, deleted, config)
            RenderContext.TOOLTIP -> renderTooltip(added, deleted, config)
        }
    }

    private fun renderToolbar(added: Int, deleted: Int, config: DiffFrogConfig): String {
        val isDark = UIUtil.isUnderDarcula()
        val addColor = getInterpolatedGreen(added, config.maxLines, isDark)
        val delColor = if (isDark) "#FF5252" else "#D32F2F" // Lighter red for dark theme, darker red for light theme

        val icon = if (config.showStatusIcons) {
            when {
                added >= config.maxLines -> " ⚠️"
                added > 0 && added == deleted -> " 🌿"
                else -> ""
            }
        } else ""

        val rawAdded = "<font color='$addColor'>+$added</font>"
        val rawDeleted = "<font color='$delColor'>-$deleted</font>"

        val text = when (config.displayFormat) {
            DisplayFormat.COMPACT -> "$rawAdded $rawDeleted"
            DisplayFormat.LABELED -> "$rawAdded added, $rawDeleted removed"
        }

        return "<html><nobr>$text$icon</nobr></html>"
    }

    private fun renderStatusBar(added: Int, deleted: Int, config: DiffFrogConfig): String {
        val icon = if (config.showStatusIcons) {
            when {
                added >= config.maxLines -> " ⚠️"
                added > 0 && added == deleted -> " 🌿"
                else -> ""
            }
        } else ""
        
        return when (config.displayFormat) {
            DisplayFormat.COMPACT -> "+$added -$deleted$icon"
            DisplayFormat.LABELED -> "+$added added, -$deleted removed$icon" // Can be truncated by OS or IDE but requested as part of labeled
        }
    }

    private fun renderTooltip(added: Int, deleted: Int, config: DiffFrogConfig): String {
        val net = added - deleted
        val ratio = if (config.maxLines > 0) ((added + deleted).toFloat() / config.maxLines * 100).toInt() else 0
        return "<html>Added: +$added | Removed: -$deleted | Net: ${if (net > 0) "+$net" else net} ($ratio%)<br>Target: ${config.targetBranch}</html>"
    }
    
    // Extracted for testing and general use
    fun getInterpolatedGreen(added: Int, maxLines: Int, isDark: Boolean = true): String {
        if (maxLines <= 0) return if (isDark) "#69F0AE" else "#2E7D32"
        val ratio = (added.toFloat() / maxLines.toFloat()).coerceIn(0f, 1f)
        
        // Base interpolation from Green to Amber/Red
        val r = (255 - (ratio * (255 - (if(isDark) 255 else 198)))).toInt()
        val g = (255 - (ratio * (255 - (if(isDark) 82 else 84)))).toInt()
        val b = (255 - (ratio * (255 - (if(isDark) 82 else 80)))).toInt()

        return String.format("#%02x%02x%02x", r, g, b)
    }
}
