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
 * Implements [ExternalSystemTaskNotificationListener] directly (no deprecated adapter)
 * and registers itself via the EP_NAME extension point on project open.
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
        ExternalSystemTaskNotificationListener.EP_NAME.addExtensionPointListener(
            object : com.intellij.openapi.extensions.ExtensionPointListener<ExternalSystemTaskNotificationListener> {
                override fun extensionAdded(
                    extension: ExternalSystemTaskNotificationListener,
                    pluginDescriptor: com.intellij.openapi.extensions.PluginDescriptor
                ) { /* no-op */ }
                override fun extensionRemoved(
                    extension: ExternalSystemTaskNotificationListener,
                    pluginDescriptor: com.intellij.openapi.extensions.PluginDescriptor
                ) { /* no-op */ }
            },
            project
        )
        // Register this instance directly as a listener for this project's lifetime
        ExternalSystemTaskNotificationListener.EP_NAME.point.registerExtension(this, project)
        log.info("BuildListenerService: registered for project '${project.name}'")
    }

    // -------------------------------------------------------------------------
    // ExternalSystemTaskNotificationListener implementation
    // -------------------------------------------------------------------------

    override fun onTaskOutput(id: ExternalSystemTaskId, text: String, stdOut: Boolean) {
        val session = sessions.getOrPut(id) {
            BuildSession(workingDir = project.basePath ?: "")
        }
        text.lines().forEach { line -> processLine(session, line.trim()) }
    }

    override fun onEnd(id: ExternalSystemTaskId) {
        finaliseAndStore(id)
    }

    override fun onSuccess(id: ExternalSystemTaskId) {
        // finalised by onEnd
    }

    override fun onFailure(id: ExternalSystemTaskId, e: Exception) {
        val session = sessions.get(id) ?: return
        finaliseCurrentTask(session, forcedStatus = TaskStatus.FAILED)
    }

    override fun onCancel(id: ExternalSystemTaskId) {
        // Discard cancelled builds
        sessions.remove(id)
    }

    override fun beforeCancel(id: ExternalSystemTaskId) { /* no-op */ }

    override fun onStatusChange(event: ExternalSystemTaskNotificationEvent) { /* no-op */ }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun finaliseAndStore(id: ExternalSystemTaskId) {
        val session = sessions.remove(id) ?: return
        finaliseCurrentTask(session, forcedStatus = null)
        val projectPath = session.workingDir
        if (session.tasks.isNotEmpty()) {
            log.info("BuildListenerService: storing ${session.tasks.size} tasks for $projectPath")
            BuildMetricsStore.getInstance().storeBuild(projectPath, session.tasks.toList())
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
