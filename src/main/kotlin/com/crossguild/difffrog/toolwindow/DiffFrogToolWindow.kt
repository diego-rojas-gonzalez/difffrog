// DiffFrogToolWindow.kt
package com.crossguild.difffrog.toolwindow

import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import com.intellij.openapi.vcs.changes.ui.ChangesComparator
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.vcs.changes.ui.RollbackChangesDialog
import java.awt.datatransfer.StringSelection
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

data class ChangeItem(
    val change: Change,
    var isSelected: Boolean = true
)

class DiffFrogToolWindow(private val project: Project) : Disposable {
    val content: JPanel = JPanel(BorderLayout())
    private val diffPanel: DiffRequestPanel

    init {
        content.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)

        // --- RIGHT: Diff Viewer ---checkBox
        diffPanel = DiffManager.getInstance().createRequestPanel(project, this, null)
        val rightPanel = JPanel(BorderLayout())
        rightPanel.add(diffPanel.component, BorderLayout.CENTER)

        // --- LEFT: File List + Inline Commit ---
        val leftPanel = JPanel(BorderLayout())
        val listModel = DefaultListModel<ChangeItem>()

        // ---- File list with fixed checkbox on right ----
        val fileList = object : JBList<ChangeItem>(listModel) {
            override fun getScrollableTracksViewportWidth(): Boolean = false
        }
        fileList.setExpandableItemsEnabled(false)
        fileList.cellRenderer = ChangeItemRenderer(project)
        fileList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        fileList.setEmptyText("No hay cambios pendientes 🐸")

        // Click on checkbox, double-click to open, right-click for context menu
        fileList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = fileList.locationToIndex(e.point)
                if (index != -1) {
                    val item = listModel.getElementAt(index)
                    val change = item.change
                    val bounds = fileList.getCellBounds(index, index)
                    val viewportWidth = (fileList.parent as? JViewport)?.width ?: fileList.width
                    val checkboxX = viewportWidth - 24
                    val viewX = e.x - fileList.visibleRect.x

                    // 1. Checkbox click
                    if (viewX >= checkboxX && SwingUtilities.isLeftMouseButton(e)) {
                        item.isSelected = !item.isSelected
                        fileList.repaint(bounds)
                        return
                    }

                    // 2. Double click
                    if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                        val virtualFile = change.virtualFile
                        if (virtualFile != null) {
                            FileEditorManager.getInstance(project).openFile(virtualFile, true)
                        }
                    }
                }
            }

            override fun mousePressed(e: MouseEvent) {
                handlePopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                handlePopup(e)
            }

            private fun handlePopup(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    val index = fileList.locationToIndex(e.point)
                    if (index != -1) {
                        fileList.selectedIndex = index
                        val item = listModel.getElementAt(index)
                        showContextMenu(e, item.change)
                    }
                }
            }

            private fun showContextMenu(e: MouseEvent, change: Change) {
                val popup = JPopupMenu()

                val discardItem = JMenuItem("Discard this change \ud83d\udc38")
                discardItem.addActionListener {
                    com.intellij.openapi.vcs.changes.ui.RollbackWorker(project, "Discard", false)
                        .doRollback(listOf(change), true)
                }

                val path = change.virtualFile?.path ?: change.beforeRevision?.file?.path ?: ""
                val basePath = project.basePath ?: ""
                val relativePath = path.removePrefix(basePath).removePrefix("/")
                val fileName = change.virtualFile?.name ?: change.beforeRevision?.file?.name ?: ""

                val copyRelativeItem = JMenuItem("Copy relative path")
                copyRelativeItem.addActionListener {
                    CopyPasteManager.getInstance().setContents(StringSelection(relativePath))
                }

                val copyAbsoluteItem = JMenuItem("Copy absolute path")
                copyAbsoluteItem.addActionListener {
                    CopyPasteManager.getInstance().setContents(StringSelection(path))
                }

                val copyNameItem = JMenuItem("Copy file name")
                copyNameItem.addActionListener {
                    CopyPasteManager.getInstance().setContents(StringSelection(fileName))
                }

                popup.add(discardItem)
                popup.addSeparator()
                popup.add(copyRelativeItem)
                popup.add(copyAbsoluteItem)
                popup.add(copyNameItem)

                popup.show(e.component, e.x, e.y)
            }
        })

        fileList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selectedValue = fileList.selectedValue
                if (selectedValue != null) showDiff(selectedValue.change)
                else diffPanel.setRequest(null)
            }
        }

        // Scroll pane for file list
        val fileScrollPane = JBScrollPane(fileList)
        fileScrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        fileScrollPane.viewport.scrollMode = JViewport.SIMPLE_SCROLL_MODE

        // ---- Toolbar ----
        val toolbarPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        val refreshBtn = JButton("↻ Refresh")
        refreshBtn.addActionListener { refreshChanges(listModel) }
        val stageBtn = JButton("✔ All")
        stageBtn.addActionListener {
            for (i in 0 until listModel.size) listModel.getElementAt(i).isSelected = true
            fileList.repaint()
        }
        val unstageBtn = JButton("✖ None")
        unstageBtn.addActionListener {
            for (i in 0 until listModel.size) listModel.getElementAt(i).isSelected = false
            fileList.repaint()
        }
        toolbarPanel.add(refreshBtn)
        toolbarPanel.add(stageBtn)
        toolbarPanel.add(unstageBtn)

        // ---- Inline Commit Area ----
        val commitArea = JBTextArea(3, 30)
        commitArea.lineWrap = true
        commitArea.wrapStyleWord = true
        commitArea.emptyText.text = "Commit message... 🐸"
        val commitScrollPane = JBScrollPane(commitArea)
        commitScrollPane.preferredSize = Dimension(0, 72)

        val commitBtn = JButton("🐸  Commit Selected")
        commitBtn.isEnabled = false
        commitBtn.maximumSize = Dimension(Int.MAX_VALUE, commitBtn.preferredSize.height)

        commitArea.document.addDocumentListener(object : DocumentListener {
            private fun update() {
                commitBtn.isEnabled = commitArea.text.isNotBlank()
            }
            override fun insertUpdate(e: DocumentEvent) = update()
            override fun removeUpdate(e: DocumentEvent) = update()
            override fun changedUpdate(e: DocumentEvent) = update()
        })

        commitBtn.addActionListener {
            val selectedItems = listModel.elements().toList()
                .filter { it.isSelected }
                .map { it.change }
            if (selectedItems.isNotEmpty()) {
                val dialog = CommitMiniDialog(project, selectedItems)
                // Pre-fill the dialog with what's already typed
                dialog.prefillMessage(commitArea.text.trim())
                if (dialog.showAndGet()) {
                    dialog.executeCommit()
                    commitArea.text = ""
                    refreshChanges(listModel)
                }
            } else {
                JOptionPane.showMessageDialog(content, "No files selected for commit.")
            }
        }

        val inlineCommitPanel = JPanel(BorderLayout(0, 2))
        inlineCommitPanel.border = JBUI.Borders.empty(4, 0, 0, 0)
        inlineCommitPanel.add(commitScrollPane, BorderLayout.CENTER)
        inlineCommitPanel.add(commitBtn, BorderLayout.SOUTH)

        // ---- Assemble left panel ----
        leftPanel.add(toolbarPanel, BorderLayout.NORTH)
        leftPanel.add(fileScrollPane, BorderLayout.CENTER)
        leftPanel.add(inlineCommitPanel, BorderLayout.SOUTH)

        // ---- Splitter ----
        val splitter = JBSplitter(false, 0.3f)
        splitter.firstComponent = leftPanel
        splitter.secondComponent = rightPanel

        content.add(splitter, BorderLayout.CENTER)

        refreshChanges(listModel)

        ChangeListManager.getInstance(project).addChangeListListener(
            object : com.intellij.openapi.vcs.changes.ChangeListListener {
                override fun changeListUpdateDone() {
                    ApplicationManager.getApplication().invokeLater {
                        refreshChanges(listModel)
                    }
                }
            }, this
        )
    }

    private fun showDiff(change: Change) {
        val producer = ChangeDiffRequestProducer.create(project, change)
        if (producer != null) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val request = producer.process(
                    object : com.intellij.diff.DiffContext() {
                        override fun getProject(): Project = this@DiffFrogToolWindow.project
                        override fun isWindowFocused(): Boolean = true
                        override fun isFocusedInWindow(): Boolean = true
                        override fun requestFocusInWindow() {}
                    },
                    com.intellij.openapi.progress.EmptyProgressIndicator()
                )
                ApplicationManager.getApplication().invokeLater {
                    diffPanel.setRequest(request)
                }
            }
        } else {
            diffPanel.setRequest(null)
        }
    }

    private fun refreshChanges(model: DefaultListModel<ChangeItem>) {
        val changeListManager = ChangeListManager.getInstance(project)
        val changes = changeListManager.allChanges.sortedWith(com.intellij.openapi.vcs.changes.ui.ChangesComparator.getInstance(false))
        model.clear()
        changes.forEach { change -> model.addElement(ChangeItem(change, true)) }

        val unversionedPaths = changeListManager.unversionedFilesPaths
        unversionedPaths.forEach { path ->
            val change = com.intellij.openapi.vcs.changes.Change(
                null,
                com.intellij.openapi.vcs.changes.CurrentContentRevision(path)
            )
            model.addElement(ChangeItem(change, false))
        }
    }

    override fun dispose() {}
}

// ---------------------------------------------------------------------------
// Renderer
// ---------------------------------------------------------------------------
class ChangeItemRenderer(private val project: Project) : ListCellRenderer<ChangeItem> {

    private var listRef: JList<out ChangeItem>? = null

    private val fileIconLabel = JLabel()
    private val statusIconLabel = JLabel()
    private val nameLabel = JLabel()
    private val checkBox = JCheckBox()
    //checkbox with backround color principal background default background solid 
    private val checkBoxPanel = JPanel(BorderLayout()).apply {
        isOpaque = true
        border = com.intellij.util.ui.JBUI.Borders.empty(0, 8, 0, 4)
        add(checkBox, BorderLayout.CENTER)
    }

    private val statusIconPanel = JPanel(BorderLayout()).apply {
        isOpaque = true
        border = com.intellij.util.ui.JBUI.Borders.empty(0, 4, 0, 4)
        add(statusIconLabel, BorderLayout.CENTER)
    }

    private val outerPanel = object : JPanel(null) {
        override fun getPreferredSize(): Dimension {
            val h = maxOf(
                statusIconPanel.preferredSize.height,
                fileIconLabel.preferredSize.height,
                nameLabel.preferredSize.height,
                checkBoxPanel.preferredSize.height
            )
            val intrinsicW = statusIconPanel.preferredSize.width + fileIconLabel.preferredSize.width + 4 + nameLabel.preferredSize.width + checkBoxPanel.preferredSize.width
            val viewW = listRef?.visibleRect?.width ?: 0
            return Dimension(maxOf(intrinsicW, viewW), h + 4)
        }

        override fun doLayout() {
            val h = height
            val r = listRef?.visibleRect ?: Rectangle(0, 0, width, height)
            
            val stW = statusIconPanel.preferredSize.width
            val fileW = fileIconLabel.preferredSize.width
            val nameW = nameLabel.preferredSize.width
            val checkW = checkBoxPanel.preferredSize.width

            // Fixed on the left
            statusIconPanel.setBounds(r.x, 0, stW, h)
            
            // Scrollable in the middle
            val startX = stW
            fileIconLabel.setBounds(startX, (h - fileIconLabel.preferredSize.height) / 2, fileW, fileIconLabel.preferredSize.height)
            nameLabel.setBounds(startX + fileW + 4, (h - nameLabel.preferredSize.height) / 2, nameW, nameLabel.preferredSize.height)

            // Fixed on the right
            val rightEdge = if (r.width > 0) r.x + r.width else width
            checkBoxPanel.setBounds(rightEdge - checkW, 0, checkW, h)
        }
    }

    init {
        outerPanel.isOpaque = true
        checkBox.isOpaque = false
        checkBox.margin = Insets(0, 0, 0, 0)
        
        // Ensure fixed items are drawn on top of scrollable text by adding them first
        outerPanel.add(statusIconPanel, 0)
        outerPanel.add(checkBoxPanel, 1)
        outerPanel.add(fileIconLabel, 2)
        outerPanel.add(nameLabel, 3)
    }

    override fun getListCellRendererComponent(
        list: JList<out ChangeItem>,
        value: ChangeItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        listRef = list
        val change = value.change

        // ---- File name ----
        val virtualFile = change.virtualFile
        val path = virtualFile?.path ?: change.beforeRevision?.file?.path ?: "Unknown"
        val basePath = project.basePath ?: ""
        val relativePath = path.removePrefix(basePath).removePrefix("/")
        nameLabel.text = relativePath

        // ---- File type icon ----
        if (virtualFile != null) {
            fileIconLabel.icon = com.intellij.openapi.fileTypes.FileTypeRegistry.getInstance().getFileTypeByFile(virtualFile).icon
        } else {
            val fileName = change.beforeRevision?.file?.name ?: ""
            fileIconLabel.icon = if (fileName.isNotEmpty())
                com.intellij.openapi.fileTypes.FileTypeRegistry.getInstance().getFileTypeByFileName(fileName).icon
            else null
        }

        // ---- VCS status icon ----
        statusIconLabel.text = when (change.type) {
            Change.Type.NEW          -> "🟩 "
            Change.Type.DELETED      -> "🟥 "
            Change.Type.MODIFICATION -> "🟦 "
            else                     -> "⬜ "
        }

        // ---- Checkbox ----
        checkBox.isSelected = value.isSelected

        // ---- Colors ----
        val bg = if (isSelected) list.selectionBackground else list.background
        val fg = if (isSelected) list.selectionForeground else list.foreground
        outerPanel.background = bg
        nameLabel.foreground = fg
        statusIconPanel.background = bg
        checkBoxPanel.background = bg
        checkBox.background = bg

        // Force layout calculations so the labels are positioned based on the current scroll
        outerPanel.doLayout()
        
        // Show genuine Swing tooltip with the full path on hover
        outerPanel.toolTipText = relativePath

        return outerPanel
    }
}