package com.shubham0707.androidbuildbooster.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.shubham0707.androidbuildbooster.model.ModuleNode
import java.io.File
import java.util.LinkedList

/**
 * Project-level service that builds a module dependency graph by parsing
 * settings.gradle(.kts) and each module's build.gradle(.kts), then cross-references
 * it with VCS change information to produce an impact analysis.
 *
 * Registered in plugin.xml as a <projectService>.
 */
@Service(Service.Level.PROJECT)
class ModuleGraphService(private val project: Project) {

    private val log = thisLogger()

    // Matches:  include(":app")  include ':app'  include(":core:network")
    private val includeRegex = Regex("""include[\s("':]+([:\w/\-]+)""")

    // Matches:  project(":core")  project(':core:network')
    private val projectDepRegex = Regex("""project\(["'](:[\w:\-/]+)["']\)""")

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Parses settings.gradle(.kts) and each module's build file to construct
     * a list of [ModuleNode] objects with their dependency relationships.
     *
     * Falls back to IntelliJ's [ModuleManager] if no settings file is found.
     */
    fun buildGraph(): List<ModuleNode> {
        val basePath = project.basePath ?: return emptyList()
        val settingsFile = findSettingsFile(basePath)

        val moduleNames: List<String> = if (settingsFile != null) {
            parseIncludedModules(settingsFile)
        } else {
            log.warn("ModuleGraphService: settings.gradle not found; falling back to ModuleManager")
            ModuleManager.getInstance(project).modules
                .map { it.name }
                .filter { it != project.name }
                .map { ":$it" }
        }

        if (moduleNames.isEmpty()) {
            log.info("ModuleGraphService: no modules found")
            return emptyList()
        }

        return moduleNames.map { moduleName ->
            val dirPath = moduleDirPath(basePath, moduleName)
            val deps = parseBuildFileDependencies(dirPath)
            ModuleNode(
                name = moduleName,
                dirPath = dirPath,
                dependencies = deps
            )
        }
    }

    /**
     * Runs [buildGraph] and then augments each node with VCS change information.
     *
     * Algorithm:
     * 1. Collect all locally changed files from [ChangeListManager].
     * 2. Mark modules that contain changed files as `isDirectlyChanged`.
     * 3. BFS through reverse-dependency edges to find transitively affected modules.
     * 4. Return the annotated list.
     */
    fun getImpactAnalysis(): List<ModuleNode> {
        val baseNodes = buildGraph()
        if (baseNodes.isEmpty()) return emptyList()

        // ------------------------------------------------------------------
        // Step 1: Collect changed file paths
        // ------------------------------------------------------------------
        val changedFilePaths: List<String> = try {
            ChangeListManager.getInstance(project).allChanges
                .mapNotNull { change ->
                    change.virtualFile?.path ?: change.afterRevision?.file?.path
                }
        } catch (e: Exception) {
            log.warn("ModuleGraphService: could not read VCS changes", e)
            emptyList()
        }

        // ------------------------------------------------------------------
        // Step 2: Identify directly changed modules
        // ------------------------------------------------------------------
        val directlyChangedNames = mutableSetOf<String>()
        val changedFilesPerModule = mutableMapOf<String, MutableList<String>>()

        for (node in baseNodes) {
            val myChanges = changedFilePaths.filter { filePath ->
                filePath.startsWith(node.dirPath)
            }
            if (myChanges.isNotEmpty()) {
                directlyChangedNames += node.name
                changedFilesPerModule[node.name] = myChanges.toMutableList()
            }
        }

        // ------------------------------------------------------------------
        // Step 3: Build reverse-dependency map  (B depends on A → A affects B)
        // ------------------------------------------------------------------
        val reverseDeps = mutableMapOf<String, MutableSet<String>>()
        for (node in baseNodes) {
            reverseDeps.getOrPut(node.name) { mutableSetOf() } // ensure entry exists
            for (dep in node.dependencies) {
                reverseDeps.getOrPut(dep) { mutableSetOf() }.add(node.name)
            }
        }

        // ------------------------------------------------------------------
        // Step 4: BFS to find transitively affected modules
        // ------------------------------------------------------------------
        val transitivelyAffected = mutableSetOf<String>()
        val queue = LinkedList(directlyChangedNames)
        val visited = mutableSetOf<String>()
        visited += directlyChangedNames

        while (queue.isNotEmpty()) {
            val current = queue.poll()
            val dependents = reverseDeps[current] ?: emptySet<String>()
            for (dependent in dependents) {
                if (dependent !in visited) {
                    visited += dependent
                    transitivelyAffected += dependent
                    queue += dependent
                }
            }
        }
        // A directly changed module should not also appear as "transitively affected"
        transitivelyAffected -= directlyChangedNames

        // ------------------------------------------------------------------
        // Step 5: Rebuild nodes with impact annotations
        // ------------------------------------------------------------------
        return baseNodes.map { node ->
            node.copy(
                isDirectlyChanged = node.name in directlyChangedNames,
                isTransitivelyAffected = node.name in transitivelyAffected,
                changedFiles = changedFilesPerModule[node.name]?.toList() ?: emptyList()
            )
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Looks for settings.gradle.kts first, then settings.gradle. */
    private fun findSettingsFile(basePath: String): File? {
        val kts = File(basePath, "settings.gradle.kts")
        if (kts.exists()) return kts
        val groovy = File(basePath, "settings.gradle")
        if (groovy.exists()) return groovy
        return null
    }

    /**
     * Parses `include(":module")` / `include ':module'` lines from the settings file
     * and returns the module names (e.g. [":app", ":core:network"]).
     */
    private fun parseIncludedModules(settingsFile: File): List<String> {
        return try {
            settingsFile.readLines()
                .flatMap { line -> includeRegex.findAll(line).map { it.groupValues[1] } }
                .map { raw ->
                    // Normalise to ":module" form (some projects omit the leading colon)
                    if (raw.startsWith(":")) raw else ":$raw"
                }
                .distinct()
        } catch (e: Exception) {
            log.warn("ModuleGraphService: failed to parse ${settingsFile.path}", e)
            emptyList()
        }
    }

    /**
     * Converts a module name like `:core:network` to an absolute directory path.
     *
     * Gradle convention: colons map to path separators, e.g.
     *   `:app`          → <basePath>/app
     *   `:core:network` → <basePath>/core/network
     */
    private fun moduleDirPath(basePath: String, moduleName: String): String {
        val relative = moduleName.trimStart(':').replace(':', File.separatorChar)
        return "$basePath${File.separator}$relative"
    }

    /**
     * Reads a module's build.gradle(.kts) and extracts `project(":<name>")` references
     * as the list of module dependencies.
     */
    private fun parseBuildFileDependencies(moduleDirPath: String): List<String> {
        val buildFile = listOf(
            File(moduleDirPath, "build.gradle.kts"),
            File(moduleDirPath, "build.gradle")
        ).firstOrNull { it.exists() } ?: return emptyList()

        return try {
            buildFile.readLines()
                .flatMap { line -> projectDepRegex.findAll(line).map { it.groupValues[1] } }
                .distinct()
        } catch (e: Exception) {
            log.warn("ModuleGraphService: failed to parse build file at $moduleDirPath", e)
            emptyList()
        }
    }
}
