package com.yourname.androidbuildbooster.model

data class TaskMetric(
    val path: String,         // e.g. :app:compileDebugKotlin
    val module: String,       // e.g. :app
    val taskName: String,     // e.g. compileDebugKotlin
    val durationMs: Long,
    val status: TaskStatus,
    val buildTimestamp: Long = System.currentTimeMillis()
)

enum class TaskStatus {
    SUCCESS, UP_TO_DATE, FROM_CACHE, FAILED, SKIPPED, UNKNOWN
}
