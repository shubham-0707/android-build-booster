package com.yourname.androidbuildbooster.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import javax.swing.JTabbedPane

/**
 * Factory that creates the "Android Build Booster" tool window content.
 *
 * The tool window now hosts three tabs:
 *   0. 🩺 Health    – BuildHealthPanel   (existing build-config analyser)
 *   1. ⏱ Timeline  – BuildTimelinePanel (per-task Gradle duration bar chart)
 *   2. 🌐 Impact   – ModuleImpactPanel  (module dependency + VCS impact analyser)
 *
 * Registered in plugin.xml under <toolWindow>.
 */
class BuildHealthToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val tabbedPane = JTabbedPane()

        tabbedPane.addTab("🩺 Health",   BuildHealthPanel(project))
        tabbedPane.addTab("⏱ Timeline", BuildTimelinePanel(project))
        tabbedPane.addTab("🌐 Impact",  ModuleImpactPanel(project))

        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(
            tabbedPane,
            /* displayName = */ "",
            /* isLockable  = */ false
        )
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
