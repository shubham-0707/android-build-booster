package com.yourname.androidbuildbooster.startup

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Runs once per project open.
 *
 * 1. Extracts `abb.sh` from the plugin JAR to `~/.android-build-booster/abb`
 *    and makes it executable.
 * 2. Shows a one-time balloon notification telling the user how to add
 *    `~/.android-build-booster` to their PATH (or run the script directly).
 * 3. Appends a `PATH` export line to `~/.abb_profile` so the user can
 *    source it from their shell config once.
 */
class AbbStartupActivity : ProjectActivity {

    private val log = thisLogger()

    companion object {
        private val INSTALL_DIR = File(System.getProperty("user.home"), ".android-build-booster")
        private val INSTALL_FILE = File(INSTALL_DIR, "abb")
        private val PROFILE_FILE = File(System.getProperty("user.home"), ".abb_profile")
    }

    override suspend fun execute(project: Project) {
        try {
            extractScript()
            writeProfileHelper()
            showTipNotification(project)
        } catch (e: Exception) {
            log.warn("AbbStartupActivity: failed to extract abb script", e)
        }
    }

    // -------------------------------------------------------------------------

    private fun extractScript() {
        INSTALL_DIR.mkdirs()

        val resourceStream = AbbStartupActivity::class.java
            .getResourceAsStream("/scripts/abb.sh")
            ?: run {
                log.warn("AbbStartupActivity: /scripts/abb.sh not found in plugin resources")
                return
            }

        resourceStream.use { input ->
            INSTALL_FILE.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        // Make executable
        try {
            val perms = PosixFilePermissions.fromString("rwxr-xr-x")
            Files.setPosixFilePermissions(INSTALL_FILE.toPath(), perms)
        } catch (_: UnsupportedOperationException) {
            // Windows – skip
            INSTALL_FILE.setExecutable(true)
        }

        log.info("AbbStartupActivity: abb script installed at ${INSTALL_FILE.absolutePath}")
    }

    private fun writeProfileHelper() {
        if (PROFILE_FILE.exists()) return          // only write once

        val exportLine = "export PATH=\"\$PATH:${INSTALL_DIR.absolutePath}\""
        PROFILE_FILE.writeText(
            """
            # Android Build Booster – added automatically
            # Source this file in your ~/.zshrc or ~/.bashrc:
            #   source ~/.abb_profile
            $exportLine
            """.trimIndent() + "\n"
        )
        log.info("AbbStartupActivity: wrote PATH helper to ${PROFILE_FILE.absolutePath}")
    }

    private fun showTipNotification(project: Project) {
        val notificationGroup = try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Android Build Booster")
        } catch (_: Exception) {
            null
        } ?: return

        val abbPath = INSTALL_FILE.absolutePath
        val profilePath = PROFILE_FILE.absolutePath

        notificationGroup.createNotification(
            "Android Build Booster CLI ready",
            """
            <b>abb</b> has been installed at:<br>
            <code>$abbPath</code><br><br>
            <b>Run from this terminal right now:</b><br>
            <code>$abbPath &lt;command&gt;</code><br><br>
            <b>Add to PATH permanently</b> – add to your <code>~/.zshrc</code> or <code>~/.bashrc</code>:<br>
            <code>source $profilePath</code><br><br>
            Commands: <code>abb health</code> &nbsp; <code>abb impact</code> &nbsp;
            <code>abb timeline</code> &nbsp; <code>abb fix</code> &nbsp; <code>abb</code> (all)
            """.trimIndent(),
            NotificationType.INFORMATION
        ).notify(project)
    }
}
