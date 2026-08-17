package com.crossguild.difffrog.presentation

import com.crossguild.difffrog.config.DiffFrogConfig
import com.intellij.ui.JBColor

object DiffTextRenderer {

    fun render(
        added: Int,
        deleted: Int,
        config: DiffFrogConfig,
        context: RenderContext
    ): String {
        return when (context) {
            RenderContext.TOOLBAR -> renderColored(added, deleted, config)
            RenderContext.STATUS_BAR -> renderColored(added, deleted, config)
            RenderContext.TOOLTIP -> renderTooltip(added, deleted, config)
        }
    }

    private fun renderColored(added: Int, deleted: Int, config: DiffFrogConfig): String {
        val isDark = !JBColor.isBright()
        val addColor = getInterpolatedGreen(added, config.maxLines, isDark)
        val delColor = if (isDark) "#FF5252" else "#D32F2F"

        val icon = if (config.showStatusIcons) {
            when {
                added == 420 && deleted == 420 -> " 🌿"
                added >= config.maxLines -> " ⚠️"
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

    private fun renderTooltip(added: Int, deleted: Int, config: DiffFrogConfig): String {
        val net = added - deleted
        val ratio = if (config.maxLines > 0) ((added + deleted).toFloat() / config.maxLines * 100).toInt() else 0
        return "<html>Added: +$added | Removed: -$deleted | Net: ${if (net > 0) "+$net" else net} ($ratio%)<br>Target: ${config.targetBranch}</html>"
    }
    
    fun getInterpolatedGreen(added: Int, maxLines: Int, isDark: Boolean = true): String {
        if (maxLines <= 0) return if (isDark) "#69F0AE" else "#2E7D32"
        val ratio = (added.toFloat() / maxLines.toFloat()).coerceIn(0f, 1f)
        
        val r = (255 - (ratio * (255 - (if(isDark) 255 else 198)))).toInt()
        val g = (255 - (ratio * (255 - (if(isDark) 82 else 84)))).toInt()
        val b = (255 - (ratio * (255 - (if(isDark) 82 else 80)))).toInt()

        return String.format("#%02x%02x%02x", r, g, b)
    }
}
