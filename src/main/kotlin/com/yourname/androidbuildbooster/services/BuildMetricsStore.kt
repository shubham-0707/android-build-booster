package com.yourname.androidbuildbooster.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.yourname.androidbuildbooster.model.TaskMetric
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Application-level service that stores the last N=10 build results per project across sessions.
 * Keyed by project base path → a deque of build results, newest first.
 *
 * Registered in plugin.xml as an <applicationService>.
 */
@Service(Service.Level.APP)
class BuildMetricsStore {

    private val store = ConcurrentHashMap<String, ArrayDeque<List<TaskMetric>>>()
    private val MAX_BUILDS = 10
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val timeFormat = SimpleDateFormat("HH:mm")

    /**
     * Stores a completed build's task list for the given project path.
     * Trims the history to MAX_BUILDS and notifies all registered listeners.
     */
    fun storeBuild(projectPath: String, tasks: List<TaskMetric>) {
        val deque = store.getOrPut(projectPath) { ArrayDeque() }
        deque.addFirst(tasks)
        if (deque.size > MAX_BUILDS) deque.removeLast()
        listeners.forEach { it() }
    }

    /**
     * Returns the most recent build's task list for the given project path,
     * or an empty list if no builds have been recorded yet.
     */
    fun getLastBuild(projectPath: String): List<TaskMetric> =
        store[projectPath]?.firstOrNull() ?: emptyList()

    /**
     * Returns all stored builds (newest first) for the given project path,
     * or an empty list if none exist.
     */
    fun getAllBuilds(projectPath: String): List<List<TaskMetric>> =
        store[projectPath]?.toList() ?: emptyList()

    /**
     * Returns a human-readable label for the build at [index] (0 = latest) for the given project path.
     * Format: "Build N (Xm Ys) — HH:MM"
     */
    fun getBuildLabel(projectPath: String, index: Int): String {
        val builds = store[projectPath] ?: return "Build ${index + 1}"
        val buildList = builds.toList()
        if (index < 0 || index >= buildList.size) return "Build ${index + 1}"
        val tasks = buildList[index]
        val totalMs = tasks.sumOf { it.durationMs }
        val timestamp = tasks.firstOrNull()?.buildTimestamp ?: 0L
        val timeStr = if (timestamp > 0) " — ${timeFormat.format(Date(timestamp))}" else ""
        val durationStr = formatDuration(totalMs)
        val buildNumber = buildList.size - index  // newest = highest number
        return "Build $buildNumber ($durationStr)$timeStr"
    }

    /**
     * Returns the number of stored builds for the given project path.
     */
    fun getBuildCount(projectPath: String): Int =
        store[projectPath]?.size ?: 0

    /**
     * Registers a listener that is called on the calling thread whenever a new build is stored.
     * Callers should dispatch to the EDT themselves if they need to update UI.
     */
    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    /**
     * Removes a previously registered listener.
     */
    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun formatDuration(ms: Long): String = when {
        ms < 1_000  -> "${ms}ms"
        ms < 60_000 -> "${"%.1f".format(ms / 1_000.0)}s"
        else        -> "${ms / 60_000}m ${"%.0f".format((ms % 60_000) / 1_000.0)}s"
    }

    companion object {
        fun getInstance(): BuildMetricsStore =
            ApplicationManager.getApplication().getService(BuildMetricsStore::class.java)
    }
}
