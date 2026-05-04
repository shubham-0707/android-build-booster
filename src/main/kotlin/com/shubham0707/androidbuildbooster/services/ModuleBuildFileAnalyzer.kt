package com.shubham0707.androidbuildbooster.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.shubham0707.androidbuildbooster.model.ModuleBuildFileIssue
import com.shubham0707.androidbuildbooster.model.Severity
import java.io.File

/**
 * Project-level service that scans every module's build.gradle(.kts) file for
 * known Android/Gradle build-performance anti-patterns.
 *
 * Call [analyzeAllModules] from a background thread; it performs file I/O and
 * returns a flat list of [ModuleBuildFileIssue] objects ready for display.
 *
 * Registered in plugin.xml as a <projectService>.
 */
@Service(Service.Level.PROJECT)
class ModuleBuildFileAnalyzer(private val project: Project) {

    private val log = thisLogger()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Discovers all module build files (via [ModuleGraphService]) and scans each
     * one for anti-patterns.  Also scans the root build file.
     *
     * @return flat list of all issues found across all build files, ordered by
     *         module name then issue title.
     */
    fun analyzeAllModules(): List<ModuleBuildFileIssue> {
        val basePath = project.basePath ?: return emptyList()
        val issues = mutableListOf<ModuleBuildFileIssue>()

        // ---- Root build file ----
        val rootBuildFile = listOf(
            File(basePath, "build.gradle.kts"),
            File(basePath, "build.gradle")
        ).firstOrNull { it.exists() }

        if (rootBuildFile != null) {
            log.info("ModuleBuildFileAnalyzer: scanning root ${rootBuildFile.absolutePath}")
            issues += scanRootBuildFile(rootBuildFile)
        }

        // ---- Module build files ----
        val moduleGraph = project.service<ModuleGraphService>()
        val modules = try {
            moduleGraph.buildGraph()
        } catch (e: Exception) {
            log.warn("ModuleBuildFileAnalyzer: could not build module graph", e)
            emptyList()
        }

        for (node in modules) {
            val buildFile = listOf(
                File(node.dirPath, "build.gradle.kts"),
                File(node.dirPath, "build.gradle")
            ).firstOrNull { it.exists() } ?: continue

            log.info("ModuleBuildFileAnalyzer: scanning module ${node.name} at ${buildFile.absolutePath}")
            issues += scanModuleBuildFile(node.name, buildFile)
        }

        return issues.sortedWith(compareBy({ it.moduleName }, { it.issue }))
    }

    // -------------------------------------------------------------------------
    // Root build file checks
    // -------------------------------------------------------------------------

    private fun scanRootBuildFile(file: File): List<ModuleBuildFileIssue> {
        val content = readFileSafely(file) ?: return emptyList()
        val issues = mutableListOf<ModuleBuildFileIssue>()

        // J) Deprecated allprojects repositories block
        if (content.contains("allprojects") && content.contains("repositories")) {
            issues += ModuleBuildFileIssue(
                moduleName = "root",
                filePath = file.absolutePath,
                issue = "Deprecated allprojects repository configuration",
                description = "Using allprojects { repositories { ... } } is deprecated in AGP 7+. " +
                        "Repository declarations should be moved to dependencyResolutionManagement { repositories { ... } } " +
                        "in settings.gradle(.kts). The allprojects block prevents project-isolation and slows configuration.",
                severity = Severity.MEDIUM,
                suggestion = "Move repository declarations to settings.gradle(.kts) inside " +
                        "dependencyResolutionManagement { repositories { ... } } and remove the allprojects block.",
                autoFixable = false
            )
        }

        return issues
    }

    // -------------------------------------------------------------------------
    // Module build file checks
    // -------------------------------------------------------------------------

    private fun scanModuleBuildFile(moduleName: String, file: File): List<ModuleBuildFileIssue> {
        val content = readFileSafely(file) ?: return emptyList()
        val issues = mutableListOf<ModuleBuildFileIssue>()

        // A) kapt instead of KSP
        if (containsKapt(content)) {
            issues += ModuleBuildFileIssue(
                moduleName = moduleName,
                filePath = file.absolutePath,
                issue = "Using kapt instead of KSP",
                description = "kapt (Kotlin Annotation Processing Tool) uses a stub-generation step that doubles " +
                        "compilation time. KSP (Kotlin Symbol Processing) is 2x faster and produces the same " +
                        "annotation-processor outputs without the stub overhead.",
                severity = Severity.HIGH,
                suggestion = "Replace id(\"kotlin-kapt\") with id(\"com.google.devtools.ksp\") " +
                        "and replace kapt(...) with ksp(...) in dependencies.",
                autoFixable = false
            )
        }

        // B) implementation used for annotation processors
        if (hasAnnotationProcessorAsImplementation(content)) {
            issues += ModuleBuildFileIssue(
                moduleName = moduleName,
                filePath = file.absolutePath,
                issue = "Annotation processor added as implementation dependency",
                description = "Annotation processors should be declared with kapt() or ksp(), not implementation(). " +
                        "Using implementation() causes them to be included on the runtime classpath unnecessarily, " +
                        "bloating the APK and slowing down builds.",
                severity = Severity.HIGH,
                suggestion = "Replace implementation(\"...<processor/compiler>...\") with ksp(...) or kapt(...) " +
                        "depending on which annotation processing tool your project uses.",
                autoFixable = false
            )
        }

        // C) testImplementation("junit") — testRuntimeOnly may be sufficient
        if (content.contains("testImplementation(\"junit") || content.contains("testImplementation('junit")) {
            issues += ModuleBuildFileIssue(
                moduleName = moduleName,
                filePath = file.absolutePath,
                issue = "JUnit test runner as testImplementation",
                description = "JUnit's runner is only needed at runtime during test execution, not on the " +
                        "compilation classpath. Using testRuntimeOnly keeps the compilation classpath smaller " +
                        "and speeds up incremental compilation.",
                severity = Severity.MEDIUM,
                suggestion = "Consider using testRuntimeOnly for JUnit runner dependencies " +
                        "(e.g. testRuntimeOnly(\"junit:junit:4.13.2\")).",
                autoFixable = false
            )
        }

        // D) minSdk / minSdkVersion below 21
        val minSdkValue = extractMinSdk(content)
        if (minSdkValue != null && minSdkValue < 21) {
            issues += ModuleBuildFileIssue(
                moduleName = moduleName,
                filePath = file.absolutePath,
                issue = "minSdk below 21 disables MultiDex optimization",
                description = "minSdk < 21 forces the legacy MultiDex path which significantly slows builds. " +
                        "The legacy MultiDex support library adds an extra dexing step and inflates method counts. " +
                        "If you can raise your minimum to API 21, native MultiDex is used automatically.",
                severity = Severity.HIGH,
                suggestion = "Set minSdk = 21 (or higher) in your android { defaultConfig { ... } } block " +
                        "if your user base supports it.",
                autoFixable = false
            )
        }

        // E) Both viewBinding and dataBinding enabled
        val hasViewBinding = content.contains("viewBinding") && content.contains("= true")
        val hasDataBinding = content.contains("dataBinding") && content.contains("= true")
        if (hasViewBinding && hasDataBinding) {
            issues += ModuleBuildFileIssue(
                moduleName = moduleName,
                filePath = file.absolutePath,
                issue = "Both ViewBinding and DataBinding enabled",
                description = "DataBinding already includes ViewBinding. Enabling both causes redundant code " +
                        "generation, increasing annotation-processing time and generated-sources size.",
                severity = Severity.MEDIUM,
                suggestion = "Remove viewBinding = true from buildFeatures { } — " +
                        "DataBinding already provides all ViewBinding capabilities.",
                autoFixable = false
            )
        }

        // F) Missing namespace in android block
        if (content.contains("android {") && !content.contains("namespace")) {
            issues += ModuleBuildFileIssue(
                moduleName = moduleName,
                filePath = file.absolutePath,
                issue = "Missing namespace declaration",
                description = "Modern AGP (7.3+) requires the namespace property in build.gradle(.kts) instead of " +
                        "the package attribute in AndroidManifest.xml. Omitting namespace causes slower manifest " +
                        "processing and deprecation warnings that may become errors in future AGP versions.",
                severity = Severity.MEDIUM,
                suggestion = "Add namespace = \"com.example.yourpackage\" inside the android { } block " +
                        "(matching your former manifest package attribute).",
                autoFixable = false
            )
        }

        // G) includeCompileClasspath = true
        if (content.contains("includeCompileClasspath") && content.contains("true")) {
            issues += ModuleBuildFileIssue(
                moduleName = moduleName,
                filePath = file.absolutePath,
                issue = "Annotation processor classpath scanning enabled",
                description = "includeCompileClasspath = true in annotationProcessorOptions instructs AGP to " +
                        "scan the entire compile classpath for annotation processors. This dramatically slows " +
                        "the annotation-processing phase because every compile-classpath JAR must be inspected.",
                severity = Severity.CRITICAL,
                suggestion = "Remove includeCompileClasspath = true and explicitly declare your annotation " +
                        "processors with kapt(...) or ksp(...) in the dependencies block.",
                autoFixable = false
            )
        }

        // H) Google Services / Firebase plugins on non-app modules
        if (moduleName != ":app") {
            val hasGoogleServices = content.contains("com.google.gms.google-services")
            val hasFirebaseCrashlytics = content.contains("com.google.firebase.crashlytics")
            if (hasGoogleServices || hasFirebaseCrashlytics) {
                val pluginName = when {
                    hasGoogleServices && hasFirebaseCrashlytics -> "google-services and firebase.crashlytics"
                    hasGoogleServices -> "google-services"
                    else -> "firebase.crashlytics"
                }
                issues += ModuleBuildFileIssue(
                    moduleName = moduleName,
                    filePath = file.absolutePath,
                    issue = "Google Services / Firebase plugin applied to non-app module",
                    description = "The $pluginName plugin(s) should only be applied to the :app module. " +
                            "Applying them to library modules causes unnecessary processing of google-services.json " +
                            "and Firebase metadata, adding overhead to every build of this module.",
                    severity = Severity.HIGH,
                    suggestion = "Remove the $pluginName plugin application from $moduleName. " +
                            "These plugins must only be applied to the :app module.",
                    autoFixable = false
                )
            }
        }

        // I) multiDexEnabled true with minSdk >= 21
        if (minSdkValue != null && minSdkValue >= 21 && containsMultiDexEnabled(content)) {
            issues += ModuleBuildFileIssue(
                moduleName = moduleName,
                filePath = file.absolutePath,
                issue = "Redundant multiDexEnabled = true",
                description = "MultiDex is automatically enabled for minSdk >= 21 (Android 5.0+). " +
                        "Explicitly setting multiDexEnabled = true when minSdk is already >= 21 " +
                        "causes unnecessary processing overhead during the dexing phase.",
                severity = Severity.MEDIUM,
                suggestion = "Remove multiDexEnabled = true from your defaultConfig block — " +
                        "it has no effect and only adds noise when minSdk >= 21.",
                autoFixable = false
            )
        }

        return issues
    }

    // -------------------------------------------------------------------------
    // Pattern helpers
    // -------------------------------------------------------------------------

    /** Returns true if the content references kapt (plugin declaration or dependency). */
    private fun containsKapt(content: String): Boolean {
        return content.contains("apply plugin: 'kotlin-kapt'") ||
                content.contains("apply plugin: \"kotlin-kapt\"") ||
                content.contains("id(\"kotlin-kapt\")") ||
                content.contains("id('kotlin-kapt')") ||
                Regex("""\bkapt\s*\(""").containsMatchIn(content)
    }

    /**
     * Returns true if any `implementation(...)` call references a known
     * annotation-processor artifact (but only when kapt/ksp is NOT also present,
     * to avoid double-reporting).
     */
    private fun hasAnnotationProcessorAsImplementation(content: String): Boolean {
        // Skip check if kapt/ksp already detected (A already covers it)
        if (containsKapt(content)) return false
        if (content.contains("id(\"com.google.devtools.ksp\")") ||
            content.contains("id('com.google.devtools.ksp')")) return false

        val processorPattern = Regex("""implementation\s*\(\s*["'][^"']*(?:processor|compiler|annotationProcessor)[^"']*["']""",
            RegexOption.IGNORE_CASE)
        return processorPattern.containsMatchIn(content)
    }

    /**
     * Extracts the numeric value from `minSdk = X`, `minSdkVersion X`,
     * `minSdkVersion = X`, or `minSdk(X)` patterns.
     */
    private fun extractMinSdk(content: String): Int? {
        val patterns = listOf(
            Regex("""minSdk\s*=\s*(\d+)"""),
            Regex("""minSdkVersion\s*=\s*(\d+)"""),
            Regex("""minSdkVersion\s+(\d+)"""),
            Regex("""minSdk\s*\(\s*(\d+)\s*\)""")
        )
        for (pattern in patterns) {
            val match = pattern.find(content) ?: continue
            return match.groupValues[1].toIntOrNull()
        }
        return null
    }

    /** Returns true if content sets multiDexEnabled to true. */
    private fun containsMultiDexEnabled(content: String): Boolean {
        return Regex("""multiDexEnabled\s*=?\s*true""").containsMatchIn(content)
    }

    private fun readFileSafely(file: File): String? {
        return try {
            file.readText()
        } catch (e: Exception) {
            log.warn("ModuleBuildFileAnalyzer: failed to read ${file.absolutePath}", e)
            null
        }
    }
}
