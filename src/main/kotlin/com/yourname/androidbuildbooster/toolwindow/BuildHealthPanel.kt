package com.yourname.androidbuildbooster.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.yourname.androidbuildbooster.model.BuildIssue
import com.yourname.androidbuildbooster.model.ModuleBuildFileIssue
import com.yourname.androidbuildbooster.model.Severity
import com.yourname.androidbuildbooster.services.GradleAnalyzerService
import com.yourname.androidbuildbooster.services.ModuleBuildFileAnalyzer
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants

/**
 * Main UI panel shown inside the "🩺 Health" tab of the tool window.
 *
 * The panel now hosts two sub-tabs:
 *   • 📄 gradle.properties — existing functionality unchanged
 *   • 📦 Build Files       — module-level build.gradle(.kts) issue scanner
 *
 * The outer component is a [JTabbedPane]; both tabs are self-contained inner panels.
 */
class BuildHealthPanel(private val project: Project) : JPanel(BorderLayout()) {

    // The inner tabs
    private val gradlePropertiesTab = GradlePropertiesSubPanel(project)
    private val buildFilesTab = BuildFilesSubPanel(project)

    init {
        border = BorderFactory.createEmptyBorder(2, 2, 2, 2)

        val tabs = JTabbedPane()
        tabs.addTab("📄 gradle.properties", gradlePropertiesTab)
        tabs.addTab("📦 Build Files", buildFilesTab)
        add(tabs, BorderLayout.CENTER)
    }

    /**
     * Triggers a gradle.properties analysis — called from [AnalyzeBuildAction] /
     * [ApplyFixesAction] so external code can still kick off the health scan.
     */
    fun runAnalysis() {
        gradlePropertiesTab.runAnalysis()
    }

    // ==========================================================================
    // Sub-panel 1: gradle.properties
    // ==========================================================================

    private class GradlePropertiesSubPanel(private val project: Project) : JPanel(BorderLayout()) {

        private val listModel = DefaultListModel<BuildIssue>()
        private val issueList = JList(listModel)
        private val statusLabel = JLabel("Press 'Analyze' to scan your build configuration.")
        private val analyzeButton = JButton("🔍 Analyze")
        private val applyButton = JButton("⚡ Apply All Fixes")

        init {
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)

            // ---- TOP toolbar ----
            val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
            toolbar.add(analyzeButton)
            toolbar.add(applyButton)
            toolbar.add(statusLabel)
            add(toolbar, BorderLayout.NORTH)

            // ---- CENTER list ----
            issueList.cellRenderer = IssueRenderer()
            issueList.selectionMode = ListSelectionModel.SINGLE_SELECTION
            issueList.fixedCellHeight = 52
            val scroll = JScrollPane(issueList)
            scroll.border = BorderFactory.createTitledBorder("Issues")
            add(scroll, BorderLayout.CENTER)

            applyButton.isEnabled = false

            analyzeButton.addActionListener { runAnalysis() }
            applyButton.addActionListener { runApplyFixes() }
        }

        fun runAnalysis() {
            analyzeButton.isEnabled = false
            applyButton.isEnabled = false
            statusLabel.text = "⏳ Analyzing…"

            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project,
                "Android Build Booster: Analyzing…",
                false
            ) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val service = project.service<GradleAnalyzerService>()
                    val results = service.analyzeProject()
                    ApplicationManager.getApplication().invokeLater {
                        populateList(results)
                    }
                }
            })
        }

        private fun runApplyFixes() {
            val issues = (0 until listModel.size()).map { listModel.getElementAt(it) }
            applyButton.isEnabled = false
            analyzeButton.isEnabled = false
            statusLabel.text = "⏳ Applying fixes…"

            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project,
                "Android Build Booster: Applying fixes…",
                false
            ) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val service = project.service<GradleAnalyzerService>()
                    service.applyFixes(issues)
                    val updated = service.analyzeProject()
                    ApplicationManager.getApplication().invokeLater {
                        populateList(updated)
                    }
                }
            })
        }

        private fun populateList(results: List<BuildIssue>) {
            listModel.clear()
            results.forEach { listModel.addElement(it) }

            val criticalCount = results.count { it.severity == Severity.CRITICAL }
            val highCount = results.count { it.severity == Severity.HIGH }
            val mediumCount = results.count { it.severity == Severity.MEDIUM }
            val okCount = results.count { it.severity == Severity.OK }

            statusLabel.text = buildString {
                if (criticalCount > 0) append("$criticalCount critical  ")
                if (highCount > 0) append("$highCount high  ")
                if (mediumCount > 0) append("$mediumCount medium  ")
                if (okCount > 0) append("$okCount ok")
            }.trim().ifEmpty { "Analysis complete." }

            val hasFixable = results.any { it.severity != Severity.OK }
            applyButton.isEnabled = hasFixable
            analyzeButton.isEnabled = true
        }

        // ---- Cell renderer ----
        private class IssueRenderer : ListCellRenderer<BuildIssue> {
            private val panel = JPanel(BorderLayout(6, 0))
            private val titleLabel = JLabel()
            private val descLabel = JLabel()
            private val textPanel = JPanel(BorderLayout())

            init {
                panel.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
                titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 13f)
                descLabel.font = descLabel.font.deriveFont(12f)
                textPanel.isOpaque = false
                textPanel.add(titleLabel, BorderLayout.NORTH)
                textPanel.add(descLabel, BorderLayout.CENTER)
                panel.add(textPanel, BorderLayout.CENTER)
            }

            override fun getListCellRendererComponent(
                list: JList<out BuildIssue>,
                value: BuildIssue,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val (fg, prefix) = when (value.severity) {
                    Severity.CRITICAL -> Pair(Color(200, 0, 0), "❌")
                    Severity.HIGH     -> Pair(Color(200, 100, 0), "⚠️")
                    Severity.MEDIUM   -> Pair(Color(180, 130, 0), "ℹ️")
                    Severity.OK       -> Pair(Color(0, 150, 0), "✅")
                }

                titleLabel.text = "$prefix ${value.title}"
                titleLabel.foreground = if (isSelected) list.selectionForeground else fg

                descLabel.text = "<html><body style='width:600px'>${value.description}</body></html>"
                descLabel.foreground = if (isSelected) list.selectionForeground else fg.darker()

                if (isSelected) {
                    panel.background = list.selectionBackground
                    textPanel.background = list.selectionBackground
                } else {
                    val bg = if (index % 2 == 0) list.background else list.background.darker().let { bg ->
                        Color(
                            (bg.red + list.background.red * 3) / 4,
                            (bg.green + list.background.green * 3) / 4,
                            (bg.blue + list.background.blue * 3) / 4
                        )
                    }
                    panel.background = bg
                    textPanel.background = bg
                }
                panel.isOpaque = true
                return panel
            }
        }
    }

    // ==========================================================================
    // Sub-panel 2: Build Files
    // ==========================================================================

    private class BuildFilesSubPanel(private val project: Project) : JPanel(BorderLayout()) {

        /**
         * Sealed marker type that the list model holds: either a module header
         * (shown as a bold group separator) or an actual issue row.
         */
        private sealed class ListRow {
            data class Header(val moduleName: String) : ListRow()
            data class IssueRow(val issue: ModuleBuildFileIssue) : ListRow()
        }

        private val listModel = DefaultListModel<ListRow>()
        private val issueList = JList(listModel)
        private val statusLabel = JLabel("Press '🔍 Scan Build Files' to analyze module build scripts.")
        private val scanButton = JButton("🔍 Scan Build Files")

        init {
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)

            // ---- NORTH toolbar ----
            val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
            toolbar.add(scanButton)
            toolbar.add(statusLabel)
            add(toolbar, BorderLayout.NORTH)

            // ---- CENTER list ----
            issueList.cellRenderer = BuildFileIssueRenderer()
            issueList.selectionMode = ListSelectionModel.SINGLE_SELECTION
            val scroll = JScrollPane(issueList)
            scroll.border = BorderFactory.createTitledBorder("Module Build File Issues")
            add(scroll, BorderLayout.CENTER)

            scanButton.addActionListener { runScan() }
        }

        private fun runScan() {
            scanButton.isEnabled = false
            statusLabel.text = "⏳ Scanning module build files…"

            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project,
                "Android Build Booster: Scanning build files…",
                false
            ) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val analyzer = project.service<ModuleBuildFileAnalyzer>()
                    val results = analyzer.analyzeAllModules()
                    ApplicationManager.getApplication().invokeLater {
                        populateList(results)
                    }
                }
            })
        }

        private fun populateList(results: List<ModuleBuildFileIssue>) {
            listModel.clear()

            if (results.isEmpty()) {
                statusLabel.text = "✅ No issues found in any module build files."
                scanButton.isEnabled = true
                return
            }

            // Group by module name and emit header + issue rows
            val grouped = results.groupBy { it.moduleName }
            val moduleCount = grouped.size

            for ((moduleName, moduleIssues) in grouped.entries.sortedBy { it.key }) {
                listModel.addElement(ListRow.Header(moduleName))
                for (issue in moduleIssues) {
                    listModel.addElement(ListRow.IssueRow(issue))
                }
            }

            val totalIssues = results.size
            statusLabel.text = "Found $totalIssues issue${if (totalIssues != 1) "s" else ""} across $moduleCount module${if (moduleCount != 1) "s" else ""}."
            scanButton.isEnabled = true
        }

        // ---- Cell renderer ----
        private class BuildFileIssueRenderer : ListCellRenderer<ListRow> {

            // Header row components
            private val headerPanel = JPanel(BorderLayout())
            private val headerLabel = JLabel()

            // Issue row components
            private val issuePanel = JPanel(BorderLayout(6, 0))
            private val issueTitleLabel = JLabel()
            private val issueSuggestionLabel = JLabel()
            private val issueTextPanel = JPanel(BorderLayout())

            init {
                // Header style
                headerPanel.border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
                headerLabel.font = headerLabel.font.deriveFont(Font.BOLD, 12f)
                headerPanel.add(headerLabel, BorderLayout.WEST)

                // Issue row style
                issuePanel.border = BorderFactory.createEmptyBorder(4, 18, 4, 8)
                issueTitleLabel.font = issueTitleLabel.font.deriveFont(Font.BOLD, 12f)
                issueSuggestionLabel.font = issueSuggestionLabel.font.deriveFont(11f)
                issueTextPanel.isOpaque = false
                issueTextPanel.add(issueTitleLabel, BorderLayout.NORTH)
                issueTextPanel.add(issueSuggestionLabel, BorderLayout.CENTER)
                issuePanel.add(issueTextPanel, BorderLayout.CENTER)
            }

            override fun getListCellRendererComponent(
                list: JList<out ListRow>,
                value: ListRow,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                return when (value) {
                    is ListRow.Header -> {
                        headerLabel.text = "  📦 ${value.moduleName}"
                        headerPanel.background = Color(120, 120, 120, 40)
                        headerPanel.isOpaque = true
                        headerLabel.foreground = if (isSelected) list.selectionForeground
                                                 else list.foreground.darker()
                        headerPanel
                    }
                    is ListRow.IssueRow -> {
                        val issue = value.issue
                        val (fg, prefix) = when (issue.severity) {
                            Severity.CRITICAL -> Pair(Color(200, 0, 0), "❌")
                            Severity.HIGH     -> Pair(Color(200, 100, 0), "⚠️")
                            Severity.MEDIUM   -> Pair(Color(180, 130, 0), "ℹ️")
                            Severity.OK       -> Pair(Color(0, 150, 0), "✅")
                        }

                        issueTitleLabel.text = "$prefix ${issue.issue}"
                        issueTitleLabel.foreground = if (isSelected) list.selectionForeground else fg

                        // Show a short snippet of the suggestion
                        val snippet = issue.suggestion.take(100).let {
                            if (issue.suggestion.length > 100) "$it…" else it
                        }
                        issueSuggestionLabel.text = "<html><body style='width:550px;color:gray'>$snippet</body></html>"
                        issueSuggestionLabel.foreground = if (isSelected) list.selectionForeground else fg.darker()

                        if (isSelected) {
                            issuePanel.background = list.selectionBackground
                            issueTextPanel.background = list.selectionBackground
                        } else {
                            val bg = if (index % 2 == 0) list.background else {
                                val b = list.background
                                Color(
                                    ((b.red * 3 + b.red.coerceAtLeast(0)) / 4).coerceIn(0, 255),
                                    ((b.green * 3 + b.green.coerceAtLeast(0)) / 4).coerceIn(0, 255),
                                    ((b.blue * 3 + b.blue.coerceAtLeast(0)) / 4).coerceIn(0, 255)
                                )
                            }
                            issuePanel.background = bg
                            issueTextPanel.background = bg
                        }
                        issuePanel.isOpaque = true
                        issuePanel
                    }
                }
            }
        }
    }
}
