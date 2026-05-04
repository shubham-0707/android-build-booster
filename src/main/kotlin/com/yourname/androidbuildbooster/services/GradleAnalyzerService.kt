package com.yourname.androidbuildbooster.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.yourname.androidbuildbooster.model.BuildIssue
import com.yourname.androidbuildbooster.model.Severity
import java.io.File
import java.util.Properties

/**
 * Project-level service that analyses gradle.properties and build.gradle(.kts) for
 * missing Android/Gradle performance optimizations, and can automatically apply fixes.
 */
@Service(Service.Level.PROJECT)
class GradleAnalyzerService(private val project: Project) {

    private val log = thisLogger()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Reads [project]/gradle.properties, checks a set of known optimization keys,
     * and returns one [BuildIssue] per check (including OK entries for passing checks).
     */
    fun analyzeProject(): List<BuildIssue> {
        val propsFile = gradlePropertiesFile()
        val props = loadProperties(propsFile)
        log.info("AndroidBuildBooster: analyzing ${propsFile.absolutePath}")

        val issues = mutableListOf<BuildIssue>()

        // ---- org.gradle.parallel ----
        issues += checkBoolean(
            props,
            key = "org.gradle.parallel",
            expectedValue = "true",
            severity = Severity.CRITICAL,
            title = "Parallel Builds Disabled",
            description = "org.gradle.parallel=true lets Gradle execute independent subprojects in parallel, " +
                    "dramatically cutting multi-module build times.",
            fixValue = "true"
        )

        // ---- org.gradle.daemon ----
        issues += checkBoolean(
            props,
            key = "org.gradle.daemon",
            expectedValue = "true",
            severity = Severity.HIGH,
            title = "Gradle Daemon Disabled",
            description = "org.gradle.daemon=true keeps the JVM warm between builds. " +
                    "First build after enabling is the same speed; subsequent builds are much faster.",
            fixValue = "true"
        )

        // ---- org.gradle.caching ----
        issues += checkBoolean(
            props,
            key = "org.gradle.caching",
            expectedValue = "true",
            severity = Severity.HIGH,
            title = "Build Cache Disabled",
            description = "org.gradle.caching=true enables the Gradle build cache. " +
                    "Tasks whose inputs haven't changed are skipped using cached outputs.",
            fixValue = "true"
        )

        // ---- org.gradle.configureondemand ----
        issues += checkBoolean(
            props,
            key = "org.gradle.configureondemand",
            expectedValue = "true",
            severity = Severity.MEDIUM,
            title = "Configure-on-Demand Disabled",
            description = "org.gradle.configureondemand=true tells Gradle to configure only the subprojects " +
                    "that are relevant to the requested tasks, reducing configuration time.",
            fixValue = "true"
        )

        // ---- kotlin.incremental ----
        issues += checkBoolean(
            props,
            key = "kotlin.incremental",
            expectedValue = "true",
            severity = Severity.HIGH,
            title = "Kotlin Incremental Compilation Disabled",
            description = "kotlin.incremental=true enables incremental Kotlin compilation. " +
                    "Only changed Kotlin files (and their dependants) are recompiled.",
            fixValue = "true"
        )

        // ---- android.nonTransitiveRClass ----
        issues += checkBoolean(
            props,
            key = "android.nonTransitiveRClass",
            expectedValue = "true",
            severity = Severity.HIGH,
            title = "Transitive R Classes Enabled",
            description = "android.nonTransitiveRClass=true makes each module's R class contain only its own " +
                    "resources, reducing build times and avoiding resource ID conflicts.",
            fixValue = "true"
        )

        // ---- android.enableR8.fullMode ----
        issues += checkBoolean(
            props,
            key = "android.enableR8.fullMode",
            expectedValue = "true",
            severity = Severity.MEDIUM,
            title = "R8 Full Mode Disabled",
            description = "android.enableR8.fullMode=true enables R8's full mode, which can produce " +
                    "smaller APKs and faster builds through more aggressive optimizations.",
            fixValue = "true"
        )

        // ---- JVM heap size ----
        issues += checkJvmHeap(props)

        return issues
    }

    /**
     * Appends all non-OK [issues] to gradle.properties (creating the file if necessary).
     * Uses plain Java I/O — safe to call from a background thread; no VFS needed.
     */
    fun applyFixes(issues: List<BuildIssue>) {
        val fixable = issues.filter { it.severity != Severity.OK }
        if (fixable.isEmpty()) {
            log.info("AndroidBuildBooster: nothing to fix")
            return
        }

        val propsFile = gradlePropertiesFile()
        val existingProps = loadProperties(propsFile)

        // Build the lines to append
        val sb = StringBuilder()
        if (propsFile.exists() && propsFile.length() > 0) {
            val lastChar = propsFile.readText().lastOrNull()
            if (lastChar != null && lastChar != '\n') {
                sb.append('\n')
            }
        }

        sb.append("\n# ===== Android Build Booster auto-fixes =====\n")

        for (issue in fixable) {
            val currentVal = existingProps.getProperty(issue.fixKey)
            if (currentVal == null) {
                // Key completely absent — append it
                sb.append("${issue.fixKey}=${issue.fixValue}\n")
                log.info("AndroidBuildBooster: adding ${issue.fixKey}=${issue.fixValue}")
            } else if (issue.fixKey == "org.gradle.jvmargs") {
                // For jvmargs we always overwrite because the heap value may be too low
                replaceOrAppendJvmArgs(propsFile, issue.fixValue)
                log.info("AndroidBuildBooster: updating org.gradle.jvmargs")
            }
            // If key present with correct value, nothing to do (it would be severity OK anyway)
        }

        val toAppend = sb.toString()
        if (toAppend.isNotBlank()) {
            if (!propsFile.exists()) {
                propsFile.parentFile?.mkdirs()
                propsFile.createNewFile()
            }
            propsFile.appendText(toAppend)
        }

        log.info("AndroidBuildBooster: fixes applied to ${propsFile.absolutePath}")
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun gradlePropertiesFile(): File =
        File(project.basePath ?: ".", "gradle.properties")

    private fun loadProperties(file: File): Properties {
        val props = Properties()
        if (file.exists()) {
            file.inputStream().use { props.load(it) }
        }
        return props
    }

    /**
     * Checks whether [key] exists in [props] and equals [expectedValue] (case-insensitive).
     * Returns an OK issue when it passes, or an issue with [severity] when it fails.
     */
    private fun checkBoolean(
        props: Properties,
        key: String,
        expectedValue: String,
        severity: Severity,
        title: String,
        description: String,
        fixValue: String
    ): BuildIssue {
        val currentValue = props.getProperty(key)
        val passes = currentValue?.trim()?.equals(expectedValue, ignoreCase = true) == true
        return if (passes) {
            BuildIssue(
                title = "✔ $title",
                description = "$key is already set correctly ($currentValue).",
                severity = Severity.OK,
                fixKey = key,
                fixValue = fixValue
            )
        } else {
            BuildIssue(
                title = title,
                description = description + if (currentValue != null) " (current: $currentValue)" else " (missing)",
                severity = severity,
                fixKey = key,
                fixValue = fixValue
            )
        }
    }

    /**
     * Inspects the JVM heap configured in `org.gradle.jvmargs`.
     * Reports CRITICAL if heap < 2 GB (or not set), otherwise OK.
     */
    private fun checkJvmHeap(props: Properties): BuildIssue {
        val jvmArgs = props.getProperty("org.gradle.jvmargs") ?: ""
        val heapMb = parseXmxMb(jvmArgs)
        val recommended = "-Xmx4g -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8"

        return if (heapMb != null && heapMb >= 2048) {
            BuildIssue(
                title = "✔ JVM Heap Size",
                description = "org.gradle.jvmargs has sufficient heap (${heapMb}m).",
                severity = Severity.OK,
                fixKey = "org.gradle.jvmargs",
                fixValue = recommended
            )
        } else {
            val detail = if (heapMb != null) " (current: ${heapMb}m)" else " (not set or no -Xmx)"
            BuildIssue(
                title = "Low JVM Heap for Gradle Daemon",
                description = "org.gradle.jvmargs should include at least -Xmx2048m. " +
                        "Insufficient heap causes frequent GC pauses and OOM during large builds.$detail",
                severity = Severity.CRITICAL,
                fixKey = "org.gradle.jvmargs",
                fixValue = recommended
            )
        }
    }

    /**
     * Parses the -Xmx value from a jvmargs string and returns it in megabytes,
     * or null if not found / unrecognised format.
     */
    private fun parseXmxMb(jvmArgs: String): Int? {
        // Matches patterns like -Xmx2g, -Xmx2048m, -Xmx2048M, -Xmx2G
        val regex = Regex("""-Xmx(\d+)([gGmM])?""")
        val match = regex.find(jvmArgs) ?: return null
        val value = match.groupValues[1].toIntOrNull() ?: return null
        val unit = match.groupValues[2].lowercase()
        return when (unit) {
            "g" -> value * 1024
            "m", "" -> value
            else -> value
        }
    }

    /**
     * Replaces the `org.gradle.jvmargs` line in-place, or appends if not found.
     */
    private fun replaceOrAppendJvmArgs(propsFile: File, newValue: String) {
        if (!propsFile.exists()) {
            propsFile.parentFile?.mkdirs()
            propsFile.writeText("org.gradle.jvmargs=$newValue\n")
            return
        }
        val lines = propsFile.readLines().toMutableList()
        val idx = lines.indexOfFirst { it.trimStart().startsWith("org.gradle.jvmargs") }
        if (idx >= 0) {
            lines[idx] = "org.gradle.jvmargs=$newValue"
            propsFile.writeText(lines.joinToString("\n") + "\n")
        } else {
            propsFile.appendText("\norg.gradle.jvmargs=$newValue\n")
        }
    }
}
