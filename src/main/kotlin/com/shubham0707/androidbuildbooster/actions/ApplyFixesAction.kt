package com.shubham0707.androidbuildbooster.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.shubham0707.androidbuildbooster.toolwindow.BuildHealthPanel
import javax.swing.JTabbedPane

/**
 * Action registered under Tools → Android Build Booster → Apply All Fixes.
 *
 * Opens the tool window, switches to the "🩺 Health" tab, and triggers
 * analysis (which exposes the "Apply All Fixes" button) in one step.
 */
class ApplyFixesAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindow = toolWindowManager.getToolWindow("Android Build Booster") ?: return

        toolWindow.show {
            val contentManager = toolWindow.contentManager

            // Walk the JTabbedPane to locate BuildHealthPanel
            for (i in 0 until contentManager.contentCount) {
                val root = contentManager.getContent(i)?.component ?: continue
                if (root is JTabbedPane) {
                    for (tabIdx in 0 until root.tabCount) {
                        val tabComponent = root.getComponentAt(tabIdx)
                        if (tabComponent is BuildHealthPanel) {
                            root.selectedIndex = tabIdx
                            // Trigger analysis first so that the panel is populated;
                            // the user can then press "Apply All Fixes" directly in the UI.
                            tabComponent.runAnalysis()
                            return@show
                        }
                    }
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
