import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.FileStatus

fun test(project: Project) {
    val clm = ChangeListManager.getInstance(project)
    val u = clm.unversionedFilesPaths
    u.forEach { path ->
        val change = Change(null, CurrentContentRevision(path))
        println(change)
    }
}
