package com.crossguild.difffrog.state

import com.crossguild.difffrog.config.DiffFrogConfigService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepositoryManager
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class DiffDataService(private val project: Project) : Disposable {

    companion object {
        fun getInstance(project: Project): DiffDataService = project.service()
    }

    private val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
    @Volatile private var scheduledTask: ScheduledFuture<*>? = null

    var targetAdded: Int = 0
        private set
    var targetDeleted: Int = 0
        private set

    private val isListenerRegistered = AtomicBoolean(false)

    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            triggerUpdate()
        }
    }

    init {
        if (isListenerRegistered.compareAndSet(false, true)) {
            EditorFactory.getInstance().eventMulticaster.addDocumentListener(documentListener, this)
            
            // Listen to VCS changes (commits, rollbacks, etc.)
            com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project).addChangeListListener(object : com.intellij.openapi.vcs.changes.ChangeListListener {
                override fun changeListUpdateDone() {
                    triggerUpdate()
                }
            }, this)
        }
        triggerUpdate()
    }

    private val selectedLines = mutableSetOf<String>() // Key: "filePath:lineNumber"

    fun setLineSelected(filePath: String, lineNumber: Int, isSelected: Boolean) {
        val key = "$filePath:$lineNumber"
        if (isSelected) selectedLines.add(key) else selectedLines.remove(key)
        triggerUpdate()
    }

    fun isLineSelected(filePath: String, lineNumber: Int): Boolean {
        // Por defecto, si no está en el conjunto, asumimos que está seleccionada (comportamiento estándar de Git)
        // O si quieres que el usuario las elija explícitamente, cambia la lógica aquí.
        val key = "$filePath:$lineNumber"
        return key in selectedLines
    }

    fun triggerUpdate() {
        if (project.isDisposed) return
        scheduledTask?.cancel(false)

        val config = DiffFrogConfigService.getInstance().loadConfig()
        val delay = mapOf(0 to 5000L, 1 to 2000L, 2 to 500L)[config.delayLevel] ?: 2000L

        scheduledTask = scheduler.schedule({
            if (project.isDisposed) return@schedule

            val stats = calculateDiff(project, config)
            
            // Si hay líneas seleccionadas manualmente, usamos ese conteo en lugar del total de Git
            if (selectedLines.isNotEmpty()) {
                targetAdded = selectedLines.size
                targetDeleted = 0 // Podríamos extender esto para borrados también
            } else {
                targetAdded = stats.first
                targetDeleted = stats.second
            }

            // Notify listeners
            project.messageBus.syncPublisher(DiffUpdateListener.TOPIC).onDiffUpdated(targetAdded, targetDeleted)

        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun calculateDiff(project: Project, config: com.crossguild.difffrog.config.DiffFrogConfig): Pair<Int, Int> {
        val repository = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return Pair(0, 0)
        var added = 0
        var deleted = 0
        try {
            val handler = GitLineHandler(project, repository.root, GitCommand.DIFF)
            handler.addParameters(config.targetBranch, "--numstat")
            
            // Agregar exclusiones si existen
            if (config.excludedPatterns.isNotEmpty()) {
                handler.addParameters("--", ".")
                config.excludedPatterns.forEach { pattern ->
                    handler.addParameters(":(exclude)$pattern")
                }
            }

            val result = Git.getInstance().runCommand(handler)
            result.output.forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2) {
                    added += parts[0].toIntOrNull() ?: 0
                    deleted += parts[1].toIntOrNull() ?: 0
                }
            }
        } catch (e: Exception) {
            // Ignored, fallback to defaults
        }
        return Pair(added, deleted)
    }

    override fun dispose() {
        scheduledTask?.cancel(false)
        isListenerRegistered.set(false)
        // Document listener is disposed automatically because we passed 'this' as disposable
    }
}
