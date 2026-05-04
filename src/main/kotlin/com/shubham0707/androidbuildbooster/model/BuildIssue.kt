package com.shubham0707.androidbuildbooster.model

/**
 * Represents a single build configuration issue found during analysis.
 *
 * @param title         Short human-readable name of the issue.
 * @param description   Explanation of why this matters and what the fix does.
 * @param severity      How critical this issue is for build performance.
 * @param fixKey        The gradle.properties key that should be added/updated.
 * @param fixValue      The value that should be set for [fixKey].
 * @param moduleContext Which file/module context this issue was found in (e.g. ":app/build.gradle.kts").
 */
data class BuildIssue(
    val title: String,
    val description: String,
    val severity: Severity,
    val fixKey: String,
    val fixValue: String,
    val moduleContext: String = "root"
)

enum class Severity {
    /** Build is significantly impacted; fix immediately. */
    CRITICAL,

    /** Notable performance degradation; strongly recommended to fix. */
    HIGH,

    /** Minor improvement available. */
    MEDIUM,

    /** This setting is already correctly configured. */
    OK
}
