package com.shubham0707.androidbuildbooster.services

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationEvent
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.project.Project
import com.shubham0707.androidbuildbooster.model.TaskMetric
import com.shubham0707.androidbuildbooster.model.TaskStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * Listens to every Gradle build and records per-task durations by parsing the
 * raw text output lines that Gradle streams during execution.
 *
 * Registered via [ExternalSystemProgressNotificationManager] so it works as a
 * project-scoped listener tied to the project's [Disposable] lifetime.
 *
 * [onTaskOutput], [onEnd], [onSuccess], [onFailure] and [onCancel] are the
 * only meaningful callbacks — the remaining interface methods are no-ops.
 * Deprecation warnings on the overrides are suppressed because the
 * interface itself is the only stable public hook available across all
 * IntelliJ Platform versions (2024.1 – 2026.1+).
 *
 * Subscribed in [BuildListenerStartupActivity] on project open.
 */
class BuildListenerService(private val project: Project) : ExternalSystemTaskNotificationListener {

    private val log = thisLogger()

    // Active builds keyed by ExternalSystemTaskId
    private val sessions = ConcurrentHashMap<ExternalSystemTaskId, BuildSession>()

    // Regex to detect Gradle task lines:
    //   "> Task :app:compileDebugKotlin"
    //   "> Task :app:compileDebugKotlin UP-TO-DATE"
    private val taskLineRegex =
        Regex("""^> Task (:[\\w:.\-]+)(?: (UP-TO-DATE|FROM-CACHE|SKIPPED|FAILED))?\s*$""")

    // -------------------------------------------------------------------------
    // Registration (called once per project from BuildListenerStartupActivity)
    // -------------------------------------------------------------------------

    fun register() {
        val notificationManager =
            com.intellij.openapi.externalSystem.service.notification
                .ExternalSystemProgressNotificationManager.getInstance()
        notificationManager.addNotificationListener(this, project)
        log.info("BuildListenerService: registered for project '${project.name}'")
    }

    // -------------------------------------------------------------------------
    // ExternalSystemTaskNotificationListener — suppress deprecation warnings
    // on the override declarations; the interface itself is the only stable
    // cross-version hook and the methods still function correctly at runtime.
    // -------------------------------------------------------------------------

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onTaskOutput(id: ExternalSystemTaskId, text: String, stdOut: Boolean) {
        val session = sessions.getOrPut(id) {
            BuildSession(workingDir = project.basePath ?: "")
        }
        text.lines().forEach { line: String -> processLine(session, line.trim()) }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onEnd(id: ExternalSystemTaskId) {
        finaliseAndStore(id)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onSuccess(id: ExternalSystemTaskId) {
        // finalised by onEnd
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onFailure(id: ExternalSystemTaskId, e: Exception) {
        sessions[id]?.let { finaliseCurrentTask(it, forcedStatus = TaskStatus.FAILED) }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onCancel(id: ExternalSystemTaskId) {
        sessions.remove(id) // discard cancelled builds
    }

    override fun beforeCancel(id: ExternalSystemTaskId) { /* no-op */ }

    override fun onStatusChange(event: ExternalSystemTaskNotificationEvent) { /* no-op */ }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun finaliseAndStore(id: ExternalSystemTaskId) {
        val session = sessions.remove(id) ?: return
        finaliseCurrentTask(session, forcedStatus = null)
        if (session.tasks.isNotEmpty()) {
            log.info("BuildListenerService: storing ${session.tasks.size} tasks for ${session.workingDir}")
            BuildMetricsStore.getInstance().storeBuild(session.workingDir, session.tasks.toList())
        }
    }

    private fun processLine(session: BuildSession, line: String) {
        val match = taskLineRegex.matchEntire(line) ?: return
        val newPath = match.groupValues[1]
        val statusStr = match.groupValues[2].takeIf { it.isNotEmpty() }

        finaliseCurrentTask(session, forcedStatus = null)

        session.currentPath = newPath
        session.currentStatus = parseTaskStatus(statusStr)
        session.currentStart = System.currentTimeMillis()
    }

    private fun finaliseCurrentTask(session: BuildSession, forcedStatus: TaskStatus?) {
        val path = session.currentPath ?: return
        val durationMs = System.currentTimeMillis() - session.currentStart
        val status = forcedStatus ?: session.currentStatus
        val (module, taskName) = parseModuleFromPath(path)

        session.tasks += TaskMetric(
            path = path,
            module = module,
            taskName = taskName,
            durationMs = durationMs,
            status = status,
            buildTimestamp = session.startTime
        )

        session.currentPath = null
        session.currentStatus = TaskStatus.UNKNOWN
        session.currentStart = System.currentTimeMillis()
    }

    private fun parseTaskStatus(statusStr: String?): TaskStatus = when (statusStr) {
        "UP-TO-DATE" -> TaskStatus.UP_TO_DATE
        "FROM-CACHE" -> TaskStatus.FROM_CACHE
        "SKIPPED"    -> TaskStatus.SKIPPED
        "FAILED"     -> TaskStatus.FAILED
        null         -> TaskStatus.SUCCESS
        else         -> TaskStatus.UNKNOWN
    }

    private fun parseModuleFromPath(path: String): Pair<String, String> {
        val lastColon = path.lastIndexOf(':')
        return if (lastColon <= 0) {
            Pair(":", path.trimStart(':'))
        } else {
            Pair(path.substring(0, lastColon), path.substring(lastColon + 1))
        }
    }

    // -------------------------------------------------------------------------
    // Inner session data class
    // -------------------------------------------------------------------------

    private data class BuildSession(
        val workingDir: String,
        val startTime: Long = System.currentTimeMillis(),
        val tasks: MutableList<TaskMetric> = mutableListOf(),
        var currentPath: String? = null,
        var currentStatus: TaskStatus = TaskStatus.UNKNOWN,
        var currentStart: Long = System.currentTimeMillis()
    )
}
