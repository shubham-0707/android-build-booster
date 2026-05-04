package com.shubham0707.androidbuildbooster.services

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListenerAdapter
import com.shubham0707.androidbuildbooster.model.TaskMetric
import com.shubham0707.androidbuildbooster.model.TaskStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * Listens to every Gradle (external system) build and records per-task durations
 * by parsing the raw text output lines that Gradle streams during execution.
 *
 * Registered in plugin.xml as an <externalSystemTaskNotificationListener> extension.
 *
 * Lifecycle per build:
 *   onStart      → create a BuildSession keyed by ExternalSystemTaskId
 *   onTaskOutput → parse "⟩ Task :path:name [STATUS]" lines to time each task
 *   onEnd        → finalise last task, push results into BuildMetricsStore, clean up
 *   onFailure    → same as onEnd, but marks the last in-flight task as FAILED
 *   onCancel     → clean up without storing
 */
class BuildListenerService : ExternalSystemTaskNotificationListenerAdapter() {

    private val log = thisLogger()

    // Active builds keyed by ExternalSystemTaskId
    private val sessions = ConcurrentHashMap<ExternalSystemTaskId, BuildSession>()

    // Regex to detect Gradle task lines:
    //   "> Task :app:compileDebugKotlin"
    //   "> Task :app:compileDebugKotlin UP-TO-DATE"
    //   "> Task :compileJava SKIPPED"
    private val taskLineRegex =
        Regex("""^> Task (:[\\w:.\-]+)(?: (UP-TO-DATE|FROM-CACHE|SKIPPED|FAILED))?\s*$""")

    // -------------------------------------------------------------------------
    // ExternalSystemTaskNotificationListenerAdapter overrides
    // -------------------------------------------------------------------------

    override fun onStart(id: ExternalSystemTaskId, workingDir: String?) {
        log.debug("BuildListenerService.onStart id=$id workingDir=$workingDir")
        sessions[id] = BuildSession(workingDir = workingDir ?: "")
    }

    override fun onTaskOutput(id: ExternalSystemTaskId, text: String, stdOut: Boolean) {
        val session = sessions[id] ?: return
        // Gradle may batch multiple lines in one callback — split and process each
        text.lines().forEach { line ->
            processLine(session, line.trim())
        }
    }

    override fun onEnd(id: ExternalSystemTaskId) {
        val session = sessions.remove(id) ?: return
        finaliseCurrentTask(session, forcedStatus = null)
        val projectPath = session.workingDir
        if (session.tasks.isNotEmpty()) {
            log.info("BuildListenerService: storing ${session.tasks.size} tasks for $projectPath")
            BuildMetricsStore.getInstance().storeBuild(projectPath, session.tasks.toList())
        }
    }

    override fun onFailure(id: ExternalSystemTaskId, e: Exception) {
        val session = sessions.remove(id) ?: return
        finaliseCurrentTask(session, forcedStatus = TaskStatus.FAILED)
        val projectPath = session.workingDir
        if (session.tasks.isNotEmpty()) {
            log.info("BuildListenerService: storing ${session.tasks.size} tasks (build failed) for $projectPath")
            BuildMetricsStore.getInstance().storeBuild(projectPath, session.tasks.toList())
        }
    }

    override fun onCancel(id: ExternalSystemTaskId) {
        // Discard the session without storing — partial data is misleading
        sessions.remove(id)
        log.debug("BuildListenerService.onCancel id=$id — session discarded")
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

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
            val module = path.substring(0, lastColon)
            val taskName = path.substring(lastColon + 1)
            Pair(module, taskName)
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
