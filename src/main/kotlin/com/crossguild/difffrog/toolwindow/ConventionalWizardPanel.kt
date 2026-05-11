package com.crossguild.difffrog.toolwindow

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

// ─────────────────────────────────────────────────────────────────────────────
// State
// ─────────────────────────────────────────────────────────────────────────────

data class FooterTag(val key: String, val value: String) {
    override fun toString() = "$key: $value"
}

data class WizardState(
    val step: Int = 1,
    val type: String? = null,
    val scope: String? = null,
    val description: String = "",
    val body: String? = null,
    val footers: List<FooterTag> = emptyList()
) {
    fun buildMessage(): String {
        val allEmpty = type == null && scope.isNullOrBlank()
                    && description.isBlank() && body.isNullOrBlank()
                    && footers.isEmpty()
        if (allEmpty) return "im a vibe frogger"
        return buildString {
            if (type != null) append(type)
            if (!scope.isNullOrBlank()) append("($scope)")
            if (type != null || !scope.isNullOrBlank()) append(": ")
            append(description)
            if (!body.isNullOrBlank()) append("\n\n$body")
            if (footers.isNotEmpty()) append("\n\n${footers.joinToString("\n")}")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Draft persistence
// ─────────────────────────────────────────────────────────────────────────────

object WizardDraftStore {
    private const val PREFIX = "difffrog.wizard."
    fun save(project: Project, state: WizardState) {
        PropertiesComponent.getInstance(project).apply {
            setValue("${PREFIX}type", state.type ?: "")
            setValue("${PREFIX}scope", state.scope ?: "")
            setValue("${PREFIX}desc", state.description)
            setValue("${PREFIX}body", state.body ?: "")
            setValue("${PREFIX}footers", state.footers.joinToString("|") { "${it.key}::${it.value}" })
        }
    }
    fun load(project: Project): WizardState? {
        val pc = PropertiesComponent.getInstance(project)
        val type = pc.getValue("${PREFIX}type")?.takeIf { it.isNotBlank() }
        val scope = pc.getValue("${PREFIX}scope")?.takeIf { it.isNotBlank() }
        val desc = pc.getValue("${PREFIX}desc") ?: ""
        val body = pc.getValue("${PREFIX}body")?.takeIf { it.isNotBlank() }
        val footers = pc.getValue("${PREFIX}footers")?.takeIf { it.isNotBlank() }
            ?.split("|")?.mapNotNull { raw ->
                val p = raw.split("::"); if (p.size == 2) FooterTag(p[0], p[1]) else null
            } ?: emptyList()
        if (type == null && scope == null && desc.isBlank() && body == null && footers.isEmpty()) return null
        return WizardState(type = type, scope = scope, description = desc, body = body, footers = footers)
    }
    fun clear(project: Project) {
        PropertiesComponent.getInstance(project).apply {
            listOf("type","scope","desc","body","footers").forEach { setValue("$PREFIX$it", "") }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// File-type keyword mapper
// ─────────────────────────────────────────────────────────────────────────────

object FileTypeKeywordMapper {
    private val extMap = mapOf(
        setOf("kt","java") to listOf("api","service","viewmodel","repository","model","mapper","handler"),
        setOf("xml","html","css") to listOf("ui","layout","style","theme","nav","screen"),
        setOf("json","yaml","toml","properties") to listOf("config","deps","settings","schema","env"),
        setOf("gradle","kts") to listOf("build","deps","plugin","version","classpath")
    )
    private val namePatterns = listOf(
        Regex("(?i).*(test|spec).*") to listOf("test","mock","fixture","assert"),
        Regex("(?i).*(database|dao|room).*") to listOf("database","migration","query","schema"),
        Regex("(?i).*(viewmodel|presenter).*") to listOf("viewmodel","state","event","action"),
        Regex("(?i).*(repository|store).*") to listOf("repository","store","cache","fetch")
    )
    fun keywordsFor(changes: List<Change>): List<String> {
        val result = mutableListOf<String>()
        for (c in changes) {
            val f = c.virtualFile ?: continue
            val ext = f.extension?.lowercase() ?: ""
            extMap.forEach { (exts, words) -> if (ext in exts) result += words }
            namePatterns.forEach { (rx, words) -> if (rx.containsMatchIn(f.name)) result += words }
        }
        return result
    }
}

fun extractScopeSuggestions(project: Project, changes: List<Change>): List<String> {
    val stop = setOf("the","and","for","with","from","this","that","fix","add","use","get","set",
        "update","remove","change","refactor","feat","docs","test","build","style","ci","chore","revert","perf","merge")
    val freq = mutableMapOf<String, Int>()
    try {
        val paths = changes.mapNotNull { it.virtualFile?.path }
        if (paths.isNotEmpty()) {
            val cmd = mutableListOf("git","log","--oneline","-n","80","--") + paths
            val proc = ProcessBuilder(cmd).directory(java.io.File(project.basePath ?: ".")).start()
            proc.inputStream.bufferedReader().readText().lines().forEach { line ->
                line.substringAfter(" ").lowercase()
                    .split(Regex("[^a-z0-9]+"))
                    .filter { it.length in 3..14 && it !in stop }
                    .forEach { freq[it] = (freq[it] ?: 0) + 1 }
            }
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    } catch (_: Exception) {}
    FileTypeKeywordMapper.keywordsFor(changes).forEach { freq[it] = (freq[it] ?: 0) + 1 }
    return freq.entries.sortedByDescending { it.value }.take(10).map { it.key }
}

// ─────────────────────────────────────────────────────────────────────────────
// ChipButton
// ─────────────────────────────────────────────────────────────────────────────

class ChipButton(text: String) : JButton(text) {
    var isChipSelected = false
        set(v) { field = v; repaint() }
    init {
        isOpaque = false; isFocusPainted = true; isBorderPainted = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = font.deriveFont(Font.PLAIN, 12f)
        margin = Insets(3, 8, 3, 8)
    }
    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = when {
            !isEnabled -> JBColor(Color(245,245,245), Color(65,65,65))
            isChipSelected -> JBColor(Color(80,140,255), Color(60,110,220))
            hasFocus() -> JBColor(Color(200,215,255), Color(60,70,110))
            else -> JBColor(Color(230,235,245), Color(55,60,75))
        }
        //
        // timber here 
        
        g2.fillRoundRect(0,0,width,height,14,14)
        g2.color = when {
            !isEnabled -> JBColor(Color(180,180,180), Color(100,100,100))
            isChipSelected -> JBColor.WHITE
            else -> JBColor.foreground()
        }
        g2.font = font
        val fm = g2.fontMetrics
        g2.drawString(text, (width-fm.stringWidth(text))/2, (height-fm.height)/2+fm.ascent)
    }
    override fun paintBorder(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = if (isChipSelected) JBColor(Color(50,90,190), Color(40,80,180))
                   else JBColor(Color(180,190,210), Color(80,90,115))
        g2.drawRoundRect(0,0,width-1,height-1,14,14)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// WrapLayout
// ─────────────────────────────────────────────────────────────────────────────

class WrapLayout(align: Int = FlowLayout.LEFT, hgap: Int = 5, vgap: Int = 5) : FlowLayout(align, hgap, vgap) {
    override fun preferredLayoutSize(t: Container) = layout(t, true)
    override fun minimumLayoutSize(t: Container) = layout(t, false)
    private fun layout(t: Container, preferred: Boolean): Dimension {
        synchronized(t.treeLock) {
            val maxW = (t.size.width.takeIf { it > 0 } ?: Int.MAX_VALUE) - t.insets.left - t.insets.right
            var w = 0; var h = 0; var rw = 0; var rh = 0
            for (i in 0 until t.componentCount) {
                val m = t.getComponent(i); if (!m.isVisible) continue
                val d = if (preferred) m.preferredSize else m.minimumSize
                if (rw + d.width > maxW) { w = maxOf(w,rw); h += rh+vgap; rw = 0; rh = 0 }
                rw += d.width+hgap; rh = maxOf(rh,d.height)
            }
            return Dimension(maxOf(w,rw)+t.insets.left+t.insets.right, h+rh+t.insets.top+t.insets.bottom)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Step 1: Type chip grid
// ─────────────────────────────────────────────────────────────────────────────

class TypeChipStep(
    private val onAdvance: (skip: Boolean) -> Unit,
    private val onFocusedChanged: (String) -> Unit  // notifies panel of currently-focused type
) : JPanel(BorderLayout()) {
    // 4 columns grid so chips wrap into actual rows
    private val COLS = 2
    private val types = listOf("feat","fix","docs","style","refactor","test","chore","build","ci","perf","revert")
    val chips = types.map { ChipButton(it) }
    private var focusedIdx = 0
    var selectedType: String? = null

    init {
        isOpaque = false
        val rows = (types.size + COLS - 1) / COLS
        val grid = JPanel(GridLayout(rows, COLS, 6, 6)).also { it.isOpaque = false; it.border = JBUI.Borders.empty(4) }
        chips.forEachIndexed { i, chip ->
            chip.isFocusable = true
            chip.setFocusTraversalKeysEnabled(false)
            chip.addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusGained(e: java.awt.event.FocusEvent?) {
                    // pre-mark as hovered so user sees where they are
                    chip.repaint()
                    onFocusedChanged(types[i])
                }
            })
            chip.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when (e.keyCode) {
                        KeyEvent.VK_RIGHT, 'L'.code -> moveFocus(1)
                        KeyEvent.VK_LEFT, 'H'.code -> moveFocus(-1)
                        KeyEvent.VK_DOWN, 'J'.code -> moveFocus(COLS)
                        KeyEvent.VK_UP, 'K'.code -> moveFocus(-COLS)
                        KeyEvent.VK_ENTER -> { selectChip(i); onAdvance(false) }
                        KeyEvent.VK_TAB -> { selectChip(focusedIdx); onAdvance(false) }
                    }
                    e.consume()
                }
            })
            chip.addActionListener { selectChip(i); onAdvance(false) }
            grid.add(chip)
        }
        // fill empty cells in last row
        val remainder = types.size % COLS
        if (remainder != 0) repeat(COLS - remainder) { grid.add(JPanel().also { it.isOpaque = false }) }
        add(grid, BorderLayout.CENTER)
    }

    private fun moveFocus(d: Int) {
        focusedIdx = (focusedIdx + d).coerceIn(0, chips.lastIndex)
        chips[focusedIdx].requestFocusInWindow()
    }
    private fun selectChip(i: Int) {
        chips.forEach { it.isChipSelected = false }
        chips[i].isChipSelected = true; selectedType = types[i]; focusedIdx = i
    }
    fun restore(type: String?) {
        type?.let { t -> types.indexOfFirst { it == t }.takeIf { it >= 0 }?.let { selectChip(it) } }
    }
    fun reset() { chips.forEach { it.isChipSelected = false }; selectedType = null; focusedIdx = 0 }
    fun hasContent() = selectedType != null
    fun focus() { SwingUtilities.invokeLater { chips[focusedIdx].requestFocusInWindow() } }
}

// ─────────────────────────────────────────────────────────────────────────────
// Step 2: Scope (text + suggestions)
// ─────────────────────────────────────────────────────────────────────────────

class ScopeStep(
    suggestions: List<String>,
    private val onAdvance: (skip: Boolean) -> Unit,
    private val onChanged: () -> Unit
) : JPanel(BorderLayout()) {
    private val field = JBTextField().apply {
        border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
    }
    private val suggChips = suggestions.map { ChipButton(it) }
    private var suggIdx = 0
    val scopeText get() = field.text.trim()

    init {
        isOpaque = false
        field.setFocusTraversalKeysEnabled(false)
        field.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_TAB, KeyEvent.VK_ENTER -> { onAdvance(field.text.isBlank()); e.consume() }
                    KeyEvent.VK_DOWN, 'J'.code -> { if (suggChips.isNotEmpty()) { suggIdx = 0; suggChips[0].requestFocusInWindow() }; e.consume() }
                }
            }
        })
        field.document.addDocumentListener(simpleListener { onChanged() })

        val COLS = 2
        val rows = (suggChips.size + COLS - 1) / COLS
        val suggPanel = JPanel(GridLayout(rows, COLS, 6, 6)).also { it.isOpaque = false; it.border = JBUI.Borders.empty(4) }
        suggChips.forEachIndexed { i, chip ->
            chip.isFocusable = true; chip.setFocusTraversalKeysEnabled(false)
            chip.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when (e.keyCode) {
                        KeyEvent.VK_RIGHT, 'L'.code -> moveSugg(1)
                        KeyEvent.VK_LEFT, 'H'.code -> moveSugg(-1)
                        KeyEvent.VK_DOWN, 'J'.code -> moveSugg(COLS)
                        KeyEvent.VK_UP, 'K'.code -> moveSugg(-COLS)
                        KeyEvent.VK_ENTER -> { insertSugg(i); e.consume() }
                        KeyEvent.VK_TAB -> { insertSugg(i); onAdvance(field.text.isBlank()); e.consume() }
                    }
                }
            })
            chip.addActionListener { insertSugg(i) }
            suggPanel.add(chip)
        }
        val remainder = suggChips.size % COLS
        if (suggChips.isNotEmpty() && remainder != 0) repeat(COLS - remainder) { suggPanel.add(JPanel().also { it.isOpaque = false }) }
        val top = JPanel(BorderLayout()).also { it.isOpaque = false; it.border = JBUI.Borders.empty(0,0,6,0) }
        top.add(field, BorderLayout.CENTER)
        add(top, BorderLayout.NORTH)
        add(suggPanel, BorderLayout.SOUTH)
    }

    private fun moveSugg(d: Int) { suggIdx = (suggIdx+d).coerceIn(0,suggChips.lastIndex); suggChips[suggIdx].requestFocusInWindow() }
    private fun insertSugg(i: Int) { field.text = suggChips[i].text; onChanged(); onAdvance(false) }
    fun restore(scope: String?) { field.text = scope ?: "" }
    fun reset() { field.text = "" }
    fun hasContent() = field.text.isNotBlank()
    fun focus() { field.requestFocusInWindow() }
}

// ─────────────────────────────────────────────────────────────────────────────
// Step 3: Description
// ─────────────────────────────────────────────────────────────────────────────

class DescriptionStep(
    private val onAdvance: (skip: Boolean) -> Unit,
    private val onChanged: () -> Unit
) : JPanel(BorderLayout()) {
    private val field = JBTextField().apply {
        border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
    }
    private val charLbl = JLabel("0 / 72")
    val descText get() = field.text.trim()

    init {
        isOpaque = false
        field.setFocusTraversalKeysEnabled(false)
        field.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_TAB || e.keyCode == KeyEvent.VK_ENTER) {
                    onAdvance(field.text.isBlank()); e.consume()
                }
            }
        })
        field.document.addDocumentListener(simpleListener { updateBar(); onChanged() })
        val footer = JPanel(BorderLayout(4,0)).also { it.isOpaque = false; it.border = JBUI.Borders.empty(4,0,0,0) }
        footer.add(charLbl, BorderLayout.EAST)
        add(field, BorderLayout.NORTH); add(footer, BorderLayout.CENTER)
    }

    private fun updateBar() {
        val n = field.text.length; charLbl.text = "$n / 72"
        charLbl.foreground = when { 
            n > 72 -> JBColor.RED
            n > 50 -> JBColor(Color(255,165,0),Color(200,130,0))
            else -> JBColor.foreground() 
        }
    }
    fun restore(desc: String) { field.text = desc }
    fun reset() { field.text = "" }
    fun hasContent() = field.text.isNotBlank()
    fun focus() { field.requestFocusInWindow() }
}

// ─────────────────────────────────────────────────────────────────────────────
// Step 4: Body
// ─────────────────────────────────────────────────────────────────────────────

class BodyStep(
    private val onAdvance: (skip: Boolean) -> Unit,
    private val onChanged: () -> Unit
) : JPanel(BorderLayout()) {
    private val area = JBTextArea(4, 40).apply {
        border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
    }
    val bodyText get() = area.text.trim()
    private val suggs = listOf("Reviewed-by: ", "Required-by: ", "Suggested-by: ", "BREAKING CHANGE: ")
    private val suggChips = suggs.map { ChipButton(it) }
    private var suggIdx = 0

    init {
        isOpaque = false
        area.lineWrap = true; area.wrapStyleWord = true
        area.setFocusTraversalKeysEnabled(false)
        area.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_TAB) { onAdvance(area.text.isBlank()); e.consume() }
                if (e.keyCode == KeyEvent.VK_DOWN || e.keyCode == 'J'.code) {
                    if (suggChips.isNotEmpty()) { suggIdx = 0; suggChips[0].requestFocusInWindow() }
                }
            }
        })
        area.document.addDocumentListener(simpleListener { onChanged(); updateChipStates() })
        add(JBScrollPane(area).also { it.preferredSize = Dimension(0, 90); it.border = null }, BorderLayout.CENTER)
        
        val COLS = 2
        val rows = (suggChips.size + COLS - 1) / COLS
        val grid = JPanel(GridLayout(rows, COLS, 6, 6)).also { it.isOpaque = false; it.border = JBUI.Borders.empty(4) }
        suggChips.forEachIndexed { i, chip ->
            chip.isFocusable = true; chip.setFocusTraversalKeysEnabled(false)
            chip.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when (e.keyCode) {
                        KeyEvent.VK_RIGHT, 'L'.code -> moveSugg(1)
                        KeyEvent.VK_LEFT, 'H'.code -> moveSugg(-1)
                        KeyEvent.VK_DOWN, 'J'.code -> moveSugg(COLS)
                        KeyEvent.VK_UP, 'K'.code -> moveSugg(-COLS)
                        KeyEvent.VK_ENTER -> { insertSugg(i); e.consume() }
                        KeyEvent.VK_TAB -> { insertSugg(i); area.requestFocusInWindow(); e.consume() }
                    }
                }
            })
            chip.addActionListener { insertSugg(i) }
            grid.add(chip)
        }
        val remainder = suggChips.size % COLS
        if (suggChips.isNotEmpty() && remainder != 0) repeat(COLS - remainder) { grid.add(JPanel().also { it.isOpaque = false }) }
        add(grid, BorderLayout.SOUTH)
    }

    private fun moveSugg(d: Int) { suggIdx = (suggIdx+d).coerceIn(0,suggChips.lastIndex); suggChips[suggIdx].requestFocusInWindow() }
    private fun updateChipStates() {
        val text = area.text
        suggChips.forEachIndexed { i, chip ->
            val prefix = suggs[i].substringBefore(": ") + ":"
            val alreadyUsed = text.contains(prefix)
            chip.isEnabled = !alreadyUsed
        }
    }

    private fun insertSugg(i: Int) { 
        if (!suggChips[i].isEnabled) return
        val prefix = if (area.text.isNotEmpty() && !area.text.endsWith("\n")) "\n" else ""
        area.text += prefix + suggs[i]
        onChanged()
        updateChipStates()
        area.requestFocusInWindow() 
    }
    fun restore(body: String?) { area.text = body ?: "" }
    fun reset() { area.text = "" }
    fun hasContent() = area.text.isNotBlank()
    fun focus() { area.requestFocusInWindow() }
}

// FooterStep has been removed.

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

fun simpleListener(action: () -> Unit) = object : DocumentListener {
    override fun insertUpdate(e: DocumentEvent?) = action()
    override fun removeUpdate(e: DocumentEvent?) = action()
    override fun changedUpdate(e: DocumentEvent?) = action()
}

// ─────────────────────────────────────────────────────────────────────────────
// ConventionalWizardPanel — orquestador
// ─────────────────────────────────────────────────────────────────────────────

class ConventionalWizardPanel(
    private val project: Project,
    private val changes: List<Change>,
    private val onStateChanged: (WizardState) -> Unit,
    private val onStepChanged: (Int) -> Unit = {},
    private val onFinish: () -> Unit = {}
) : JPanel(BorderLayout()) {

    private var state = WizardState()
    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout).also { it.isOpaque = false }
    private val actionBtn = JButton("Skip ↓")
    private val backBtn = JButton("← Back")
    private val trashBtn = JButton("🗑")
    private val hintLbl = JLabel("Tab to continue  ·  hjkl / arrows to navigate").also {
        it.font = it.font.deriveFont(Font.ITALIC, 11f)
        it.foreground = JBColor.GRAY
    }

    private val suggestions by lazy { extractScopeSuggestions(project, changes) }

    private lateinit var typeStep: TypeChipStep
    private lateinit var scopeStep: ScopeStep
    private lateinit var descStep: DescriptionStep
    private lateinit var bodyStep: BodyStep

    init {
        isOpaque = false
        border = null
        buildSteps()
        buildUI()
        // Restore draft
        WizardDraftStore.load(project)?.let { draft ->
            state = draft
            typeStep.restore(draft.type)
            scopeStep.restore(draft.scope)
            descStep.restore(draft.description)
            bodyStep.restore(draft.body)
        }
        updateButtonLabel()
    }

    private fun buildSteps() {
        typeStep = TypeChipStep(
            onAdvance = { skip -> advance(1, if (skip) null else typeStep.selectedType, skip) },
            onFocusedChanged = { hoveredType ->
                // Live preview: show what the message would look like with this type pre-selected
                val preview = state.copy(type = hoveredType).buildMessage()
                onStateChanged(state.copy(type = hoveredType))
            }
        )
        scopeStep = ScopeStep(suggestions, { skip -> advance(2, if (skip) null else scopeStep.scopeText, skip) }, {
            onStateChanged(state.copy(scope = scopeStep.scopeText.takeIf { it.isNotBlank() }))
            updateButtonLabel()
        })
        descStep = DescriptionStep({ skip -> advance(3, if (skip) "" else descStep.descText, skip) }, {
            onStateChanged(state.copy(description = descStep.descText))
            updateButtonLabel()
        })
        bodyStep = BodyStep({ skip -> finishWizard(skip) }, {
            updateButtonLabel()
        })
    }

    private fun buildUI() {
        cardPanel.add(typeStep, "1"); cardPanel.add(scopeStep, "2")
        cardPanel.add(descStep, "3"); cardPanel.add(bodyStep, "4")

        backBtn.apply {
            setFocusTraversalKeysEnabled(false)
            addActionListener { goBack() }
        }
        trashBtn.apply {
            toolTipText = "Clear draft"
            isBorderPainted = false; isContentAreaFilled = false
            font = font.deriveFont(16f)
            addActionListener { clearDraft() }
            setFocusTraversalKeysEnabled(false)
        }

        actionBtn.setFocusTraversalKeysEnabled(false)
        actionBtn.addActionListener {
            when (state.step) {
                1 -> advance(1, if (!typeStep.hasContent()) null else typeStep.selectedType, !typeStep.hasContent())
                2 -> advance(2, if (!scopeStep.hasContent()) null else scopeStep.scopeText, !scopeStep.hasContent())
                3 -> advance(3, if (!descStep.hasContent()) "" else descStep.descText, !descStep.hasContent())
                4 -> finishWizard(!bodyStep.hasContent())
            }
        }

        val navRow = JPanel(BorderLayout(6,0)).also { it.isOpaque = false }
        val leftActions = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).also { it.isOpaque = false }

        leftActions.add(backBtn)
        leftActions.add(trashBtn)
        navRow.add(leftActions, BorderLayout.WEST)
        navRow.add(actionBtn, BorderLayout.EAST)

        val bottom = JPanel(BorderLayout()).also { it.isOpaque = false; it.border = JBUI.Borders.empty(6,0,0,0) }
        bottom.add(hintLbl, BorderLayout.WEST); bottom.add(navRow, BorderLayout.EAST)

        add(cardPanel, BorderLayout.CENTER); add(bottom, BorderLayout.SOUTH)
    }

    private fun advance(fromStep: Int, value: Any?, skip: Boolean) {
        state = when (fromStep) {
            1 -> state.copy(step=2, type = value as? String)
            2 -> state.copy(step=3, scope = (value as? String)?.takeIf { it.isNotBlank() })
            3 -> state.copy(step=4, description = (value as? String) ?: "")
            4 -> state.copy(step=5, body = (value as? String)?.takeIf { it.isNotBlank() })
            else -> state
        }
        WizardDraftStore.save(project, state)
        onStateChanged(state)
        cardLayout.show(cardPanel, state.step.toString())
        onStepChanged(state.step)
        updateButtonLabel()
        SwingUtilities.invokeLater {
            when (state.step) {
                1 -> typeStep.focus(); 2 -> scopeStep.focus(); 3 -> descStep.focus()
                4 -> bodyStep.focus()
            }
        }
    }

    private fun finishWizard(skip: Boolean) {
        state = state.copy(body = if (skip) null else bodyStep.bodyText.takeIf { it.isNotBlank() })
        WizardDraftStore.save(project, state)
        onStateChanged(state)
        onFinish()
    }

    private fun updateButtonLabel() {

        actionBtn.isOpaque = state.step != 4

        when (state.step) {
            1 -> hintLbl.text = footerText(state.step)
            2 -> hintLbl.text = footerText(state.step)
            3 -> hintLbl.text = footerText(state.step)
            4 -> hintLbl.text = footerText(state.step)
        }

        actionBtn.text = "Skip ↓"
        backBtn.isEnabled = state.step > 1
    }

    fun footerText (step: Int) : String {
        val continueText : String = "Tab to continue "
        val moveActionText : String = "· hjkl / arrows to navigate"
        val finishText : String = "Tab to finish 🐸"
        return when (step) {
            1, 2,  -> continueText + moveActionText
            3 -> continueText
            4 -> finishText
            else -> ""
        }
    }

    private fun goBack() {
        if (state.step <= 1) return
        state = state.copy(step = state.step - 1)
        cardLayout.show(cardPanel, state.step.toString())
        onStepChanged(state.step)
        updateButtonLabel()
        SwingUtilities.invokeLater {
            when (state.step) {
                1 -> typeStep.focus(); 2 -> scopeStep.focus(); 3 -> descStep.focus()
                4 -> bodyStep.focus()
            }
        }
    }

    fun clearDraft() {
        WizardDraftStore.clear(project)
        state = WizardState()
        typeStep.reset(); scopeStep.reset(); descStep.reset(); bodyStep.reset()
        cardLayout.show(cardPanel, "1")
        onStepChanged(state.step)
        updateButtonLabel()
        // Do NOT call onStateChanged — preview should just go blank
        SwingUtilities.invokeLater { typeStep.focus() }
    }

    fun getCurrentMessage() = state.buildMessage()
    /** Called from CommitMiniDialog to trigger initial focus */
    fun requestInitialFocus() { SwingUtilities.invokeLater { typeStep.focus() } }
}