package com.shubham0707.androidbuildbooster.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.shubham0707.androidbuildbooster.model.ModuleNode
import com.shubham0707.androidbuildbooster.services.BuildMetricsStore
import com.shubham0707.androidbuildbooster.services.ModuleGraphService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

/**
 * Panel displayed in the "🌐 Impact" tab of the tool window.
 *
 * Layout:
 *  ┌──────────────────────────────────────────────────────────────────────────┐
 *  │  [🔄 Re-analyze]                                           NORTH         │
 *  ├──────────────────────────────────────────────────────────────────────────┤
 *  │  JSplitPane (VERTICAL)                                     CENTER        │
 *  │  ┌───────────────────────────────────────────────────────────────────┐   │
 *  │  │ JSplitPane (HORIZONTAL)  —  tree (60%) + detail panel (40%)  TOP │   │
 *  │  ├───────────────────────────────────────────────────────────────────┤   │
 *  │  │ "💡 Minimize Rebuild Scope" stash suggestions panel         BOTTOM│   │
 *  │  └───────────────────────────────────────────────────────────────────┘   │
 *  ├──────────────────────────────────────────────────────────────────────────┤
 *  │  Status bar: "X modules affected … | ⏱ Est. total rebuild: ~Xs"  SOUTH  │
 *  └──────────────────────────────────────────────────────────────────────────┘
 */
class ModuleImpactPanel(private val project: Project) : JPanel(BorderLayout()) {

    // ---- State ----
    private var currentNodes: List<ModuleNode> = emptyList()

    // ---- UI components ----
    private val reanalyzeButton = JButton("🔄 Re-analyze")
    private val statusLabel = JLabel("Press 'Re-analyze' to compute module impact.", SwingConstants.LEFT)

    // Tree components
    private val rootNode = DefaultMutableTreeNode("Project: ${project.name}")
    private val treeModel = DefaultTreeModel(rootNode)
    private val moduleTree = JTree(treeModel)

    // Detail panel
    private val detailPanel = DetailPanel()

    // Stash suggestions panel
    private val stashPanel = StashSuggestionPanel()

    init {
        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)

        // ---- NORTH toolbar ----
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        toolbar.add(reanalyzeButton)
        add(toolbar, BorderLayout.NORTH)

        // ---- CENTER: horizontal split (tree | detail) inside vertical split (top | stash) ----
        moduleTree.cellRenderer = ModuleTreeCellRenderer()
        moduleTree.isRootVisible = true

        val treeScroll = JScrollPane(moduleTree)
        treeScroll.border = BorderFactory.createTitledBorder("Module Dependency Graph")

        val detailScroll = JScrollPane(detailPanel)
        detailScroll.border = BorderFactory.createTitledBorder("Module Details")

        val horizontalSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, detailScroll)
        horizontalSplit.resizeWeight = 0.6
        horizontalSplit.dividerSize = 6

        val stashScroll = JScrollPane(stashPanel)
        stashScroll.border = BorderFactory.createTitledBorder("💡 Minimize Rebuild Scope")

        val verticalSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, horizontalSplit, stashScroll)
        verticalSplit.resizeWeight = 0.65
        verticalSplit.dividerSize = 6
        add(verticalSplit, BorderLayout.CENTER)

        // ---- SOUTH status bar ----
        val southPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2))
        southPanel.border = BorderFactory.createEtchedBorder()
        southPanel.add(statusLabel)
        add(southPanel, BorderLayout.SOUTH)

        // ---- Listeners ----
        reanalyzeButton.addActionListener { runAnalysis() }

        moduleTree.addTreeSelectionListener { e ->
            val node = e?.path?.lastPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val moduleNode = node.userObject as? ModuleNode ?: return@addTreeSelectionListener
            detailPanel.showModule(moduleNode, currentNodes)
        }
    }

    // -------------------------------------------------------------------------
    // Analysis
    // -------------------------------------------------------------------------

    private fun runAnalysis() {
        reanalyzeButton.isEnabled = false
        statusLabel.text = "⏳ Analyzing module graph and VCS changes…"

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Android Build Booster: Analyzing module impact…",
            false
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val nodes = project.service<ModuleGraphService>().getImpactAnalysis()
                ApplicationManager.getApplication().invokeLater {
                    populateTree(nodes)
                }
            }
        })
    }

    private fun populateTree(nodes: List<ModuleNode>) {
        currentNodes = nodes
        rootNode.removeAllChildren()

        nodes.forEach { node ->
            rootNode.add(DefaultMutableTreeNode(node))
        }

        treeModel.reload()

        for (i in 0 until moduleTree.rowCount) {
            moduleTree.expandRow(i)
        }

        // ---- Recompile time estimation ----
        val basePath = project.basePath ?: ""
        val store = BuildMetricsStore.getInstance()
        val lastBuild = store.getLastBuild(basePath)

        val estimatedTimes = computeEstimatedTimes(nodes, lastBuild)
        val totalEstimateMs = estimatedTimes.values.sum()

        // ---- Status bar ----
        val affectedCount = nodes.count { it.isAffected }
        val affectedText = if (affectedCount == 0) {
            "No modules affected by current changes."
        } else {
            "$affectedCount module${if (affectedCount != 1) "s" else ""} affected by your current changes."
        }
        val timeText = if (totalEstimateMs > 0) {
            "  |  ⏱ Est. total rebuild: ~${formatDuration(totalEstimateMs)}"
        } else ""
        statusLabel.text = affectedText + timeText

        reanalyzeButton.isEnabled = true
        detailPanel.clear()

        // ---- Stash suggestions ----
        val affectedNodes = nodes.filter { it.isAffected }
        stashPanel.showSuggestions(affectedNodes, nodes, estimatedTimes, lastBuild)
    }

    // -------------------------------------------------------------------------
    // Recompile time estimation
    // -------------------------------------------------------------------------

    /**
     * For each affected module, sums all tasks that start with `<module>:compile`
     * from the last build to estimate how long it will take to recompile.
     */
    private fun computeEstimatedTimes(
        nodes: List<ModuleNode>,
        lastBuild: List<com.shubham0707.androidbuildbooster.model.TaskMetric>
    ): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        for (node in nodes.filter { it.isAffected }) {
            val prefix = node.name + ":compile"
            val moduleTimeMs = lastBuild
                .filter { it.path.startsWith(prefix, ignoreCase = true) }
                .sumOf { it.durationMs }
            if (moduleTimeMs > 0) {
                result[node.name] = moduleTimeMs
            }
        }
        return result
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun formatDuration(ms: Long): String = when {
        ms < 1_000  -> "${ms}ms"
        ms < 60_000 -> "${"%.1f".format(ms / 1_000.0)}s"
        else        -> "${ms / 60_000}m ${"%.0f".format((ms % 60_000) / 1_000.0)}s"
    }

    // -------------------------------------------------------------------------
    // Inner: ModuleTreeCellRenderer
    // -------------------------------------------------------------------------

    private inner class ModuleTreeCellRenderer : DefaultTreeCellRenderer() {

        private val directChangedColor = Color(200, 30, 30)
        private val transitiveColor = Color(210, 130, 0)
        private val cleanColor = Color(0, 150, 60)

        // Precompute estimates once per populateTree call
        private fun getEstimatedTime(moduleName: String): Long {
            val basePath = project.basePath ?: return 0L
            val lastBuild = BuildMetricsStore.getInstance().getLastBuild(basePath)
            val prefix = "$moduleName:compile"
            return lastBuild.filter { it.path.startsWith(prefix, ignoreCase = true) }
                .sumOf { it.durationMs }
        }

        override fun getTreeCellRendererComponent(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ): Component {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)

            val treeNode = value as? DefaultMutableTreeNode ?: return this
            val moduleNode = treeNode.userObject as? ModuleNode

            if (moduleNode == null) {
                text = treeNode.userObject.toString()
                font = font.deriveFont(Font.BOLD)
                return this
            }

            font = font.deriveFont(Font.PLAIN)
            val changedCount = moduleNode.changedFiles.size

            val estimateMs = getEstimatedTime(moduleNode.name)
            val estimateSuffix = if (estimateMs > 0) "  (est. ~${formatDuration(estimateMs)})" else ""

            text = when {
                moduleNode.isDirectlyChanged -> {
                    foreground = if (selected) textSelectionColor else directChangedColor
                    val fileSuffix = if (changedCount > 0) " ($changedCount file${if (changedCount != 1) "s" else ""} changed)" else ""
                    "● CHANGED  ${moduleNode.name}$fileSuffix$estimateSuffix"
                }
                moduleNode.isTransitivelyAffected -> {
                    foreground = if (selected) textSelectionColor else transitiveColor
                    "◐ AFFECTED  ${moduleNode.name}$estimateSuffix"
                }
                else -> {
                    foreground = if (selected) textSelectionColor else cleanColor
                    "✓  ${moduleNode.name}"
                }
            }

            return this
        }
    }

    // -------------------------------------------------------------------------
    // Inner: DetailPanel
    // -------------------------------------------------------------------------

    private class DetailPanel : JPanel(BorderLayout(0, 8)) {

        private val titleLabel = JLabel("Select a module to see details.")
        private val changedFilesModel = DefaultListModel<String>()
        private val changedFilesList = JList(changedFilesModel)
        private val depsOnLabel = JLabel()
        private val dependedByLabel = JLabel()

        init {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 13f)
            add(titleLabel, BorderLayout.NORTH)

            val infoPanel = JPanel(BorderLayout(0, 6))
            infoPanel.isOpaque = false
            infoPanel.add(depsOnLabel, BorderLayout.NORTH)
            infoPanel.add(dependedByLabel, BorderLayout.CENTER)

            val listScroll = JScrollPane(changedFilesList)
            listScroll.border = BorderFactory.createTitledBorder("Changed Files")
            listScroll.preferredSize = Dimension(200, 150)

            val centerPanel = JPanel(BorderLayout(0, 8))
            centerPanel.isOpaque = false
            centerPanel.add(infoPanel, BorderLayout.NORTH)
            centerPanel.add(listScroll, BorderLayout.CENTER)
            add(centerPanel, BorderLayout.CENTER)
        }

        fun showModule(node: ModuleNode, allNodes: List<ModuleNode>) {
            val statusStr = when {
                node.isDirectlyChanged      -> "🔴 DIRECTLY CHANGED"
                node.isTransitivelyAffected -> "🟠 TRANSITIVELY AFFECTED"
                else                        -> "✅ UNAFFECTED"
            }
            titleLabel.text = "${node.name}  —  $statusStr"

            changedFilesModel.clear()
            node.changedFiles.forEach { fullPath ->
                val fileName = fullPath.substringAfterLast('/')
                changedFilesModel.addElement(fileName)
            }

            val depsText = if (node.dependencies.isEmpty()) "(none)" else node.dependencies.joinToString(", ")
            depsOnLabel.text = "<html><b>Depends on:</b> $depsText</html>"

            val dependedBy = allNodes
                .filter { other -> node.name in other.dependencies }
                .map { it.name }
            val dependedByText = if (dependedBy.isEmpty()) "(none)" else dependedBy.joinToString(", ")
            dependedByLabel.text = "<html><b>Depended on by:</b> $dependedByText</html>"

            revalidate()
            repaint()
        }

        fun clear() {
            titleLabel.text = "Select a module to see details."
            changedFilesModel.clear()
            depsOnLabel.text = ""
            dependedByLabel.text = ""
            repaint()
        }
    }

    // -------------------------------------------------------------------------
    // Inner: StashSuggestionPanel
    // -------------------------------------------------------------------------

    private inner class StashSuggestionPanel : JPanel(BorderLayout()) {

        private val listModel = DefaultListModel<String>()
        private val suggestionList = JList(listModel)

        init {
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
            add(JScrollPane(suggestionList), BorderLayout.CENTER)
        }

        /**
         * For each changed file, computes "if I stash just this file, how many affected
         * modules would be removed?" and shows the top suggestions sorted by impact.
         *
         * @param affectedNodes   modules currently flagged as affected
         * @param allNodes        full module graph
         * @param estimatedTimes  estimated compile time per module (from build history)
         * @param lastBuild       unused directly here (kept for signature consistency)
         */
        fun showSuggestions(
            affectedNodes: List<ModuleNode>,
            allNodes: List<ModuleNode>,
            estimatedTimes: Map<String, Long>,
            @Suppress("UNUSED_PARAMETER")
            lastBuild: List<com.shubham0707.androidbuildbooster.model.TaskMetric>
        ) {
            listModel.clear()

            if (affectedNodes.isEmpty()) {
                listModel.addElement("No affected modules — nothing to stash.")
                return
            }

            // Collect every changed file across all directly-changed modules
            data class FileSuggestion(
                val file: String,
                val removedModules: Set<String>,
                val savedMs: Long
            )

            val suggestions = mutableListOf<FileSuggestion>()

            val directlyChanged = affectedNodes.filter { it.isDirectlyChanged }

            for (node in directlyChanged) {
                for (filePath in node.changedFiles) {
                    // Which modules would no longer be directly changed if this file were stashed?
                    val removedDirect = directlyChanged
                        .filter { n ->
                            // Stashing this file removes it from n's changed files;
                            // if n's entire change set would then be empty, n is no longer directly changed
                            n.changedFiles.all { it == filePath }
                        }
                        .map { it.name }
                        .toSet()

                    // Which transitive modules only exist because of the removed direct modules?
                    val removedTransitive = computeRemovedTransitive(removedDirect, allNodes, directlyChanged)

                    val allRemoved = removedDirect + removedTransitive
                    if (allRemoved.isEmpty()) continue

                    val savedMs = allRemoved.sumOf { estimatedTimes[it] ?: 0L }
                    suggestions.add(FileSuggestion(filePath, allRemoved, savedMs))
                }
            }

            if (suggestions.isEmpty()) {
                listModel.addElement("No single-file stash would reduce the rebuild scope.")
                return
            }

            // Sort by number of modules removed (desc), then savings (desc)
            val sorted = suggestions
                .sortedWith(compareByDescending<FileSuggestion> { it.removedModules.size }
                    .thenByDescending { it.savedMs })

            for (s in sorted.take(10)) {
                val fileName = s.file.substringAfterLast('/')
                val modulesStr = s.removedModules.joinToString(", ")
                val saveStr = if (s.savedMs > 0) ", saves ~${formatDuration(s.savedMs)}" else ""
                val row = "📌 Stash [$fileName]$saveStr → removes ${s.removedModules.size} module(s) from rebuild: $modulesStr"
                listModel.addElement(row)
            }
        }

        /**
         * Given the set of directly-changed module names that are being removed,
         * finds which transitive modules would also no longer be affected
         * (because all their affected dependencies are now removed).
         */
        private fun computeRemovedTransitive(
            removedDirect: Set<String>,
            allNodes: List<ModuleNode>,
            directlyChanged: List<ModuleNode>
        ): Set<String> {
            // Remaining directly changed (after hypothetical stash)
            val remainingDirect = directlyChanged.map { it.name }.toSet() - removedDirect

            // Re-run transitive impact with remaining direct set
            val transitiveResult = mutableSetOf<String>()
            val visited = remainingDirect.toMutableSet()
            val queue = ArrayDeque(remainingDirect.toList())

            // Build reverse dep map
            val reverseDeps = mutableMapOf<String, MutableSet<String>>()
            for (node in allNodes) {
                reverseDeps.getOrPut(node.name) { mutableSetOf() }
                for (dep in node.dependencies) {
                    reverseDeps.getOrPut(dep) { mutableSetOf() }.add(node.name)
                }
            }

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                for (dependent in (reverseDeps[current] ?: emptySet<String>())) {
                    if (dependent !in visited) {
                        visited += dependent
                        transitiveResult += dependent
                        queue.add(dependent)
                    }
                }
            }

            // Original transitive set (without stash)
            val originalTransitive = mutableSetOf<String>()
            val origVisited = directlyChanged.map { it.name }.toMutableSet()
            val origQueue = ArrayDeque(directlyChanged.map { it.name })
            while (origQueue.isNotEmpty()) {
                val current = origQueue.removeFirst()
                for (dependent in (reverseDeps[current] ?: emptySet<String>())) {
                    if (dependent !in origVisited) {
                        origVisited += dependent
                        originalTransitive += dependent
                        origQueue.add(dependent)
                    }
                }
            }
            originalTransitive -= directlyChanged.map { it.name }.toSet()

            // Modules that are in original transitive but NOT in new transitive
            return originalTransitive - transitiveResult - remainingDirect
        }
    }
}
