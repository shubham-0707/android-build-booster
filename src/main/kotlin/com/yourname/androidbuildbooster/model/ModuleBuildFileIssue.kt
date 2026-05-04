package com.yourname.androidbuildbooster.model

/**
 * Represents a build-configuration issue found in a specific module's build.gradle(.kts) file.
 *
 * @param moduleName  Gradle module path, e.g. ":app" or ":core:network". Use "root" for root build file.
 * @param filePath    Absolute path to the build.gradle(.kts) file where the issue was detected.
 * @param issue       Short human-readable issue title.
 * @param description Detailed explanation of why this hurts build performance.
 * @param severity    Criticality level (CRITICAL / HIGH / MEDIUM).
 * @param suggestion  Concrete action the developer should take.
 * @param autoFixable Whether the plugin can safely apply this fix automatically (reserved for future use).
 */
data class ModuleBuildFileIssue(
    val moduleName: String,
    val filePath: String,
    val issue: String,
    val description: String,
    val severity: Severity,
    val suggestion: String,
    val autoFixable: Boolean = false
)
