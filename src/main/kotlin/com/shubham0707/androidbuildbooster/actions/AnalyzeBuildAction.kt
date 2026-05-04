package com.shubham0707.androidbuildbooster.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.shubham0707.androidbuildbooster.toolwindow.BuildHealthPanel
import javax.swing.JTabbedPane

/**
 * Action registered under Tools → Android Build Booster → Analyze Build Health.
 *
 * Shows the tool window and immediately kicks off a build analysis run on the
 * "🩺 Health" tab.
 */
class AnalyzeBuildAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindow = toolWindowManager.getToolWindow("Android Build Booster") ?: return

        // Make the tool window visible, then trigger analysis
        toolWindow.show {
            triggerAnalysis(toolWindowManager)
        }
    }

    private fun triggerAnalysis(toolWindowManager: ToolWindowManager) {
        val toolWindow = toolWindowManager.getToolWindow("Android Build Booster") ?: return
        val contentManager = toolWindow.contentManager

        // The factory adds a single content whose component is a JTabbedPane.
        // Walk its tabs to find the BuildHealthPanel.
        for (i in 0 until contentManager.contentCount) {
            val root = contentManager.getContent(i)?.component ?: continue
            if (root is JTabbedPane) {
                for (tabIdx in 0 until root.tabCount) {
                    val tabComponent = root.getComponentAt(tabIdx)
                    if (tabComponent is BuildHealthPanel) {
                        // Switch to the Health tab so the user sees the results
                        root.selectedIndex = tabIdx
                        tabComponent.runAnalysis()
                        return
                    }
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        // Only enable when a project is open
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
