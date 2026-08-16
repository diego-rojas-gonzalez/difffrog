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
import git4idea.repo.GitRepositoryManager
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import java.awt.datatransfer.StringSelection
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import com.intellij.openapi.ui.ComboBox

data class ChangeItem(
    val change: Change,
    var isSelected: Boolean = true
)

class DiffFrogToolWindow(private val project: Project) : Disposable {
    val content: JPanel = JPanel(BorderLayout())
    private val diffPanel: DiffRequestPanel
    private val branchComboBox = ComboBox<String>().apply {
        preferredSize = Dimension(150, 26)
    }
    private var allBranchesList = listOf<String>()
    private var isUpdatingBranches = false

    init {
        content.border = BorderFactory.createEmptyBorder(0, 0, 0, 0)

        // --- RIGHT: Diff Viewer ---checkBox
        diffPanel = DiffManager.getInstance().createRequestPanel(project, this, null)
        val rightPanel = JPanel(BorderLayout())
        rightPanel.add(diffPanel.component, BorderLayout.CENTER)

        // --- LEFT: File List + Inline Commit ---
        val leftPanel = JPanel(BorderLayout())
        val listModel = DefaultListModel<ChangeItem>()
// other shi 
        // ---- File list with fixed checkbox on right ----
        val fileList = object : JBList<ChangeItem>(listModel) {
            override fun getScrollableTracksViewportWidth(): Boolean = false
        }
        fileList.setExpandableItemsEnabled(false)
        fileList.cellRenderer = ChangeItemRenderer(project)
        fileList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        fileList.setEmptyText("No changes bro... ? get help")
        //for git frog test
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
                    val vFile = change.virtualFile
                    val isUnversioned = change.beforeRevision == null && change.afterRevision != null
                    if (isUnversioned && vFile != null) {
                        // Unversioned file: just delete it from disk
                        com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
                            try { vFile.delete(this) } catch (_: Exception) {}
                        }
                    } else {
                        // Tracked change: roll back via VCS
                        com.intellij.openapi.vcs.changes.ui.RollbackWorker(project, "Discard", false)
                            .doRollback(listOf(change), true)
                    }
                    // Refresh the list after a short delay to let VCS state settle
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        refreshChanges(listModel)
                    }
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
        val toolbarTopPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        
        val refreshBtn = JButton("↻ Refresh")
        val stageBtn = JButton("✔ All")
        
        updateBranches()
        
        refreshBtn.addActionListener { 
            ApplicationManager.getApplication().executeOnPooledThread {
                val repoManager = GitRepositoryManager.getInstance(project)
                val repo = repoManager.repositories.firstOrNull()
                if (repo != null) {
                    try {
                        val handler = GitLineHandler(project, repo.root, GitCommand.FETCH)
                        handler.addParameters("origin")
                        Git.getInstance().runCommand(handler)
                    } catch (e: Exception) {
                        // ignore error
                    }
                }
                ApplicationManager.getApplication().invokeLater {
                    updateBranches()
                    refreshChanges(listModel)
                }
            }
        }
        
        stageBtn.addActionListener {
            for (i in 0 until listModel.size) listModel.getElementAt(i).isSelected = true
            fileList.repaint()
        }
        val unstageBtn = JButton("✖ None")
        unstageBtn.addActionListener {
            for (i in 0 until listModel.size) listModel.getElementAt(i).isSelected = false
            fileList.repaint()
        }
        
        toolbarTopPanel.add(refreshBtn)
        toolbarTopPanel.add(stageBtn)
        toolbarTopPanel.add(unstageBtn)
        
        val toolbarPanel = JPanel(BorderLayout(0, 4))
        toolbarPanel.border = com.intellij.util.ui.JBUI.Borders.empty(4)
        toolbarPanel.add(toolbarTopPanel, BorderLayout.NORTH)
        toolbarPanel.add(branchComboBox, BorderLayout.CENTER)
        
        // Branch ComboBox is not editable anymore        
        branchComboBox.addActionListener {
            if (isUpdatingBranches) return@addActionListener
            val selected = branchComboBox.selectedItem as? String ?: return@addActionListener
            val repoManager = git4idea.repo.GitRepositoryManager.getInstance(project)
            val repo = repoManager.repositories.firstOrNull() ?: return@addActionListener
            
            if (repo.currentBranch?.name != selected && allBranchesList.contains(selected)) {
                git4idea.branch.GitBrancher.getInstance(project).checkout(selected, false, listOf(repo), null)
            }
        }

        // ---- Commit Button (opens wizard dialog) ----
        val commitBtn = JButton("\ud83d\udc38  Commit Wizard")
        commitBtn.isEnabled = false
        // Update enabled state whenever list model changes
        fun updateCommitBtn() {
            commitBtn.isEnabled = listModel.elements().toList().any { it.isSelected }
        }
        listModel.addListDataListener(object : javax.swing.event.ListDataListener {
            override fun intervalAdded(e: javax.swing.event.ListDataEvent) = updateCommitBtn()
            override fun intervalRemoved(e: javax.swing.event.ListDataEvent) = updateCommitBtn()
            override fun contentsChanged(e: javax.swing.event.ListDataEvent) = updateCommitBtn()
        })

        commitBtn.addActionListener {
            val selectedItems = listModel.elements().toList()
                .filter { it.isSelected }
                .map { it.change }
            if (selectedItems.isNotEmpty()) {
                val dialog = CommitMiniDialog(project, selectedItems)
                dialog.show()
            } else {
                JOptionPane.showMessageDialog(content, "No files selected for commit.")
            }
        }

        val magicCommitBtn = JButton("\ud83d\udc38  Auto Commit ✡️")

1
        // ---- Assemble left panel ----
        leftPanel.add(toolbarPanel, BorderLayout.NORTH)
        leftPanel.add(fileScrollPane, BorderLayout.CENTER)
        leftPanel.add(magicCommitBtn, BorderLayout.SOUTH)
        leftPanel.add(commitBtn, BorderLayout.SOUTH)

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
                // Forzar vista unificada (Unified View)
                if (request is com.intellij.openapi.util.UserDataHolder) {
                    val key = com.intellij.openapi.util.Key.create<String>("diff_default_view_type")
                    request.putUserData(key, "Unified")
                }

                // Habilitar selección de líneas si el panel lo soporta
                ApplicationManager.getApplication().invokeLater {
                    diffPanel.setRequest(request)
                    
                    // Intentar activar el soporte de selección en el visor
                    val viewer = diffPanel.javaClass.getDeclaredField("myViewer").let {
                        it.isAccessible = true
                        it.get(diffPanel)
                    }
                    if (viewer is com.intellij.diff.impl.DiffRequestPanelImpl) {
                        // Aquí podrías añadir listeners para capturar la selección de líneas
                    }
                }
            }
        } else {
            diffPanel.setRequest(null)
        }
    }

    private fun refreshChanges(model: DefaultListModel<ChangeItem>) {
        updateBranches()
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

    private fun updateBranches() {
        if (isUpdatingBranches) return
        val repoManager = GitRepositoryManager.getInstance(project)
        val repo = repoManager.repositories.firstOrNull()
        if (repo != null) {
            val currentBranch = repo.currentBranch?.name
            val localBranches = repo.branches.localBranches.map { it.name }.sorted()
            val remoteBranches = repo.branches.remoteBranches.map { it.name }.sorted()
            
            allBranchesList = (localBranches + remoteBranches).distinct()
            
            isUpdatingBranches = true
            branchComboBox.model = DefaultComboBoxModel(allBranchesList.toTypedArray())
            if (currentBranch != null) {
                branchComboBox.selectedItem = currentBranch
            }
            isUpdatingBranches = false
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