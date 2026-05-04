package com.shubham0707.androidbuildbooster.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.shubham0707.androidbuildbooster.model.TaskMetric
import com.shubham0707.androidbuildbooster.model.TaskStatus
import com.shubham0707.androidbuildbooster.services.BuildMetricsStore
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Composite
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextPane
import javax.swing.ToolTipManager
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * Panel displayed in the "⏱ Timeline" tab of the tool window.
 *
 * Layout:
 *  ┌─────────────────────────────────────────────────────────────────────────┐
 *  │  [↻ Refresh]  [Build selector combo]  [Compare with combo]    NORTH     │
 *  │  Summary label                                                           │
 *  ├─────────────────────────────────────────────────────────────────────────┤
 *  │  JSplitPane vertical (70 / 30)                                 CENTER   │
 *  │  ┌─────────────────────────────────────────────────────────────┐        │
 *  │  │  Scrollable horizontal bar chart (TimelineChartComponent)   │ TOP    │
 *  │  ├─────────────────────────────────────────────────────────────┤        │
 *  │  │  Task detail panel (shown on click)                         │ BOTTOM │
 *  │  └─────────────────────────────────────────────────────────────┘        │
 *  ├─────────────────────────────────────────────────────────────────────────┤
 *  │  Module colour legend                                          SOUTH     │
 *  └─────────────────────────────────────────────────────────────────────────┘
 */
class BuildTimelinePanel(private val project: Project) : JPanel(BorderLayout()) {

    companion object {
        private const val MAX_TASKS_SHOWN = 30
        private const val NONE_LABEL = "(none)"
    }

    // ---- State ----
    private var currentTasks: List<TaskMetric> = emptyList()
    private var compareTasks: List<TaskMetric>? = null

    // ---- UI Components ----
    private val summaryLabel = JLabel("No build recorded yet. Run a Gradle build to see results.")
    private val refreshButton = JButton("↻ Refresh")
    @Suppress("UNCHECKED_CAST")
    private val buildCombo = JComboBox<String>()
    @Suppress("UNCHECKED_CAST")
    private val compareCombo = JComboBox<String>()
    private val chartComponent = TimelineChartComponent()
    private val legendPanel = LegendPanel()
    private val detailPanel = TaskDetailPanel()

    // Listener token so we can remove it if the panel is ever disposed
    private val storeListener: () -> Unit = {
        ApplicationManager.getApplication().invokeLater {
            refreshBuildSelectors()
        }
    }

    init {
        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)

        // ---- NORTH toolbar ----
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        toolbar.add(refreshButton)
        toolbar.add(JLabel("Build:"))
        toolbar.add(buildCombo)
        toolbar.add(JLabel("Compare with:"))
        toolbar.add(compareCombo)
        toolbar.add(summaryLabel)

        val northPanel = JPanel(BorderLayout())
        northPanel.add(toolbar, BorderLayout.NORTH)
        add(northPanel, BorderLayout.NORTH)

        // ---- CENTER: chart + detail in split pane ----
        val chartScroll = JScrollPane(chartComponent)
        chartScroll.border = BorderFactory.createTitledBorder("Task Duration (slowest tasks)")
        chartScroll.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        chartScroll.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER

        val detailScroll = JScrollPane(detailPanel)
        detailScroll.border = BorderFactory.createTitledBorder("Why is this slow?")

        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, chartScroll, detailScroll)
        splitPane.resizeWeight = 0.70
        splitPane.dividerSize = 6
        add(splitPane, BorderLayout.CENTER)

        // ---- SOUTH legend ----
        add(legendPanel, BorderLayout.SOUTH)

        // ---- Wire up listeners ----
        refreshButton.addActionListener {
            refreshBuildSelectors()
        }

        buildCombo.addActionListener {
            onBuildSelectionChanged()
        }

        compareCombo.addActionListener {
            onCompareSelectionChanged()
        }

        BuildMetricsStore.getInstance().addListener(storeListener)

        // Attempt an initial population if data already exists
        refreshBuildSelectors()
    }

    // -------------------------------------------------------------------------
    // Build selector logic
    // -------------------------------------------------------------------------

    private fun refreshBuildSelectors() {
        val basePath = project.basePath ?: ""
        val store = BuildMetricsStore.getInstance()
        val count = store.getBuildCount(basePath)

        // Rebuild main combo
        val prevMainIdx = buildCombo.selectedIndex.coerceAtLeast(0)
        buildCombo.removeAllItems()
        if (count == 0) {
            buildCombo.addItem("(no builds yet)")
        } else {
            for (i in 0 until count) {
                buildCombo.addItem(store.getBuildLabel(basePath, i))
            }
        }
        // Restore selection or default to 0
        if (count > 0) {
            buildCombo.selectedIndex = prevMainIdx.coerceAtMost(count - 1)
        }

        // Rebuild compare combo
        val prevCompareIdx = compareCombo.selectedIndex
        compareCombo.removeAllItems()
        compareCombo.addItem(NONE_LABEL)
        for (i in 0 until count) {
            compareCombo.addItem(store.getBuildLabel(basePath, i))
        }
        if (prevCompareIdx > 0 && prevCompareIdx <= count) {
            compareCombo.selectedIndex = prevCompareIdx
        } else {
            compareCombo.selectedIndex = 0
        }

        // Load selected build data
        loadSelectedBuilds()
    }

    private fun onBuildSelectionChanged() {
        loadSelectedBuilds()
    }

    private fun onCompareSelectionChanged() {
        loadSelectedBuilds()
    }

    private fun loadSelectedBuilds() {
        val basePath = project.basePath ?: ""
        val store = BuildMetricsStore.getInstance()
        val allBuilds = store.getAllBuilds(basePath)

        val mainIdx = buildCombo.selectedIndex
        currentTasks = if (allBuilds.isNotEmpty() && mainIdx >= 0 && mainIdx < allBuilds.size) {
            allBuilds[mainIdx]
        } else {
            emptyList()
        }

        val compareIdx = compareCombo.selectedIndex - 1 // offset by 1 because index 0 = "(none)"
        compareTasks = if (compareIdx >= 0 && compareIdx < allBuilds.size) {
            allBuilds[compareIdx]
        } else {
            null
        }

        updateSummary()
        chartComponent.setTasks(currentTasks, compareTasks)
        legendPanel.setTasks(currentTasks)
        detailPanel.clear()
        repaint()
    }

    // -------------------------------------------------------------------------
    // Refresh (legacy method kept for compatibility)
    // -------------------------------------------------------------------------

    @Suppress("UNUSED_PARAMETER")
    fun refresh(projectBasePath: String) {
        refreshBuildSelectors()
    }

    private fun updateSummary() {
        if (currentTasks.isEmpty()) {
            summaryLabel.text = "No build recorded yet. Run a Gradle build to see results."
            return
        }
        val totalMs = currentTasks.sumOf { it.durationMs }
        val slowest = currentTasks.maxByOrNull { it.durationMs }
        val slowestStr = if (slowest != null) {
            "${slowest.path} (${formatDuration(slowest.durationMs)})"
        } else "—"
        summaryLabel.text = "Last Build: ${currentTasks.size} tasks | " +
                "Total: ${formatDuration(totalMs)} | Slowest: $slowestStr"
    }

    // -------------------------------------------------------------------------
    // Inner: TimelineChartComponent
    // -------------------------------------------------------------------------

    inner class TimelineChartComponent : JComponent() {

        private val ROW_HEIGHT = 44
        private val LABEL_WIDTH = 280
        private val RIGHT_WIDTH = 100
        private val ROW_PADDING = 6
        private val BAR_HEIGHT = 20
        private val BAR_GAP = 4
        private val CORNER_RADIUS = 6

        private var tasks: List<TaskMetric> = emptyList()
        private var compareTasks: List<TaskMetric>? = null
        private var selectedRow: Int = -1

        init {
            isOpaque = true
            ToolTipManager.sharedInstance().registerComponent(this)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val row = (e.y - ROW_PADDING) / ROW_HEIGHT
                    if (row >= 0 && row < tasks.size) {
                        selectedRow = row
                        onTaskSelected(tasks[row])
                        repaint()
                    }
                }
            })
        }

        fun setTasks(primary: List<TaskMetric>, compare: List<TaskMetric>?) {
            tasks = primary.sortedByDescending { it.durationMs }.take(MAX_TASKS_SHOWN)
            compareTasks = compare
            selectedRow = -1
            val height = maxOf(tasks.size * ROW_HEIGHT + ROW_PADDING * 2, 80)
            preferredSize = Dimension(width.takeIf { it > 0 } ?: 800, height)
            revalidate()
            repaint()
        }

        override fun getToolTipText(e: MouseEvent): String? {
            val row = (e.y - ROW_PADDING) / ROW_HEIGHT
            if (row < 0 || row >= tasks.size) return null
            val task = tasks[row]
            val compareTask = compareTasks?.firstOrNull { it.path == task.path }
            val compareStr = if (compareTask != null) {
                val delta = task.durationMs - compareTask.durationMs
                val sign = if (delta >= 0) "+" else ""
                "<br/>Compare: ${formatDuration(compareTask.durationMs)} (${sign}${formatDuration(delta)})"
            } else if (compareTasks != null) "<br/>[NEW in selected build]" else ""
            return "<html><b>${task.path}</b><br/>" +
                    "Status: ${task.status}<br/>" +
                    "Duration: ${formatDuration(task.durationMs)}$compareStr<br/>" +
                    "Module: ${task.module}</html>"
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val w = width
            val barZoneWidth = w - LABEL_WIDTH - RIGHT_WIDTH

            // Background
            g2.color = background
            g2.fillRect(0, 0, w, height)

            if (tasks.isEmpty()) {
                g2.color = foreground
                g2.font = font.deriveFont(Font.ITALIC, 13f)
                g2.drawString("Run a Gradle build to populate the timeline.", 20, 40)
                return
            }

            val compareMap: Map<String, TaskMetric> = compareTasks?.associateBy { it.path } ?: emptyMap()
            val allDurations = tasks.map { it.durationMs } +
                    (compareTasks?.map { it.durationMs } ?: emptyList())
            val maxDuration = allDurations.maxOrNull()?.toDouble() ?: 1.0

            tasks.forEachIndexed { i, task ->
                val rowY = ROW_PADDING + i * ROW_HEIGHT
                val isSelected = (i == selectedRow)

                // Row background
                if (isSelected) {
                    g2.color = Color(100, 150, 255, 40)
                    g2.fillRect(0, rowY, w, ROW_HEIGHT)
                } else if (i % 2 == 0) {
                    g2.color = Color(0, 0, 0, 10)
                    g2.fillRect(0, rowY, w, ROW_HEIGHT)
                }

                val compareTask = compareMap[task.path]
                val hasCompare = compareTask != null && compareTasks != null

                if (hasCompare && compareTask != null) {
                    // Side-by-side bars: primary (blue tint) top, compare (orange tint) bottom
                    val primaryBarH = BAR_HEIGHT - BAR_GAP
                    val compareBarH = BAR_HEIGHT - BAR_GAP

                    val primaryBarY = rowY + (ROW_HEIGHT - BAR_HEIGHT) / 2
                    val compareBarY = primaryBarY + primaryBarH + 2

                    val primaryBarW = ((task.durationMs / maxDuration) * barZoneWidth).toInt().coerceAtLeast(4)
                    val compareBarW = ((compareTask.durationMs / maxDuration) * barZoneWidth).toInt().coerceAtLeast(4)

                    // Primary bar (blue tint)
                    drawBar(g2, task, LABEL_WIDTH, primaryBarY, primaryBarW, primaryBarH,
                        Color(60, 120, 220, 200))

                    // Compare bar (orange tint)
                    drawBar(g2, compareTask, LABEL_WIDTH, compareBarY, compareBarW, compareBarH,
                        Color(220, 130, 30, 200))

                    // Delta label
                    val delta = task.durationMs - compareTask.durationMs
                    if (delta != 0L) {
                        val deltaStr = if (delta > 0) "+${formatDuration(delta)} slower"
                                       else "${formatDuration(-delta)} faster"
                        val deltaColor = if (delta > 0) Color(200, 30, 30) else Color(0, 150, 60)
                        g2.color = deltaColor
                        g2.font = font.deriveFont(Font.BOLD, 10f)
                        val durationX = LABEL_WIDTH + barZoneWidth + 6
                        g2.drawString(deltaStr, durationX.toFloat(),
                            (rowY + (ROW_HEIGHT + g2.fontMetrics.ascent - g2.fontMetrics.descent) / 2).toFloat())
                    }
                } else {
                    // Single bar
                    val barY = rowY + (ROW_HEIGHT - BAR_HEIGHT) / 2
                    val barW = ((task.durationMs / maxDuration) * barZoneWidth).toInt().coerceAtLeast(4)
                    val baseColor = moduleColor(task.module)

                    drawBar(g2, task, LABEL_WIDTH, barY, barW, BAR_HEIGHT, baseColor)

                    // [NEW] or [REMOVED] label if in compare mode but task missing from compare
                    if (compareTasks != null) {
                        g2.color = Color(80, 160, 80)
                        g2.font = font.deriveFont(Font.BOLD, 9f)
                        val durationX = LABEL_WIDTH + barZoneWidth + 6
                        val labelY = rowY + (ROW_HEIGHT + g2.fontMetrics.ascent - g2.fontMetrics.descent) / 2
                        g2.drawString("[NEW]", durationX.toFloat(), labelY.toFloat())
                    } else {
                        // Normal duration label
                        g2.color = foreground
                        g2.font = font.deriveFont(Font.BOLD, 11f)
                        val durationStr = formatDuration(task.durationMs)
                        val durationX = LABEL_WIDTH + barZoneWidth + 6
                        val labelY = rowY + (ROW_HEIGHT + g2.fontMetrics.ascent - g2.fontMetrics.descent) / 2
                        g2.drawString(durationStr, durationX.toFloat(), labelY.toFloat())
                    }
                }

                // Task path label (left zone, right-aligned, truncated)
                g2.color = foreground
                g2.font = font.deriveFont(12f)
                val labelText = truncateMiddle(task.path, g2, LABEL_WIDTH - 12)
                val fm = g2.fontMetrics
                val labelX = LABEL_WIDTH - 8 - fm.stringWidth(labelText)
                val labelY = rowY + (ROW_HEIGHT + fm.ascent - fm.descent) / 2
                g2.drawString(labelText, labelX.toFloat(), labelY.toFloat())
            }

            // Also show tasks that are in compare but NOT in primary (REMOVED)
            if (compareTasks != null) {
                val primaryPaths = tasks.map { it.path }.toSet()
                val removedTasks = (compareTasks ?: emptyList())
                    .filter { it.path !in primaryPaths }
                    .sortedByDescending { it.durationMs }
                    .take(maxOf(0, MAX_TASKS_SHOWN - tasks.size))

                removedTasks.forEachIndexed { j, rTask ->
                    val rowY = ROW_PADDING + (tasks.size + j) * ROW_HEIGHT
                    val barY = rowY + (ROW_HEIGHT - BAR_HEIGHT) / 2
                    val barW = ((rTask.durationMs / maxDuration) * barZoneWidth).toInt().coerceAtLeast(4)

                    val savedComposite: Composite = g2.composite
                    g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f)
                    g2.color = Color(200, 60, 60)
                    g2.fillRoundRect(LABEL_WIDTH, barY, barW, BAR_HEIGHT, CORNER_RADIUS, CORNER_RADIUS)
                    g2.composite = savedComposite

                    g2.color = foreground.darker()
                    g2.font = font.deriveFont(12f)
                    val labelText = truncateMiddle(rTask.path, g2, LABEL_WIDTH - 12)
                    val fm = g2.fontMetrics
                    val labelX = LABEL_WIDTH - 8 - fm.stringWidth(labelText)
                    val labelY2 = rowY + (ROW_HEIGHT + fm.ascent - fm.descent) / 2
                    g2.drawString(labelText, labelX.toFloat(), labelY2.toFloat())

                    g2.color = Color(180, 60, 60)
                    g2.font = font.deriveFont(Font.BOLD, 9f)
                    val durationX = LABEL_WIDTH + barZoneWidth + 6
                    g2.drawString("[REMOVED]", durationX.toFloat(), labelY2.toFloat())
                }
            }

            // Max-time reference line
            g2.color = Color(200, 60, 60, 120)
            g2.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(4f, 4f), 0f)
            val refX = LABEL_WIDTH + barZoneWidth
            val totalRows = tasks.size + ((compareTasks?.filter { ct ->
                tasks.none { it.path == ct.path } }?.size) ?: 0).coerceAtMost(MAX_TASKS_SHOWN - tasks.size)
            g2.drawLine(refX, ROW_PADDING, refX, ROW_PADDING + totalRows * ROW_HEIGHT)
        }

        private fun drawBar(
            g2: Graphics2D,
            task: TaskMetric,
            barX: Int, barY: Int,
            barWidth: Int, barHeight: Int,
            color: Color
        ) {
            val isDimmed = task.status == TaskStatus.UP_TO_DATE || task.status == TaskStatus.FROM_CACHE
            val savedComposite: Composite = g2.composite

            if (isDimmed) {
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f)
            }

            g2.color = color
            g2.fillRoundRect(barX, barY, barWidth, barHeight, CORNER_RADIUS, CORNER_RADIUS)

            if (isDimmed) {
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.25f)
                g2.color = Color.WHITE
                g2.stroke = BasicStroke(2f)
                var stripeX = barX
                while (stripeX < barX + barWidth) {
                    g2.drawLine(stripeX, barY, stripeX - barHeight, barY + barHeight)
                    stripeX += 8
                }
            }
            g2.composite = savedComposite

            // Status badge
            if (task.status != TaskStatus.SUCCESS && task.status != TaskStatus.UNKNOWN) {
                g2.color = Color(255, 255, 255, 200)
                g2.font = g2.font.deriveFont(Font.BOLD, 9f)
                val badge = task.status.name.replace('_', '-')
                g2.drawString(badge, (barX + 4).toFloat(), (barY + barHeight - 4).toFloat())
            }
        }

        private fun truncateMiddle(text: String, g2: Graphics2D, maxWidth: Int): String {
            val fm = g2.fontMetrics
            if (fm.stringWidth(text) <= maxWidth) return text
            val ellipsis = "…"
            val halfWidth = (maxWidth - fm.stringWidth(ellipsis)) / 2
            var leftEnd = text.length / 2
            while (leftEnd > 0 && fm.stringWidth(text.substring(0, leftEnd)) > halfWidth) leftEnd--
            var rightStart = text.length / 2
            while (rightStart < text.length && fm.stringWidth(text.substring(rightStart)) > halfWidth) rightStart++
            return text.substring(0, leftEnd) + ellipsis + text.substring(rightStart)
        }
    }

    // -------------------------------------------------------------------------
    // Inner: TaskDetailPanel
    // -------------------------------------------------------------------------

    private inner class TaskDetailPanel : JPanel(BorderLayout()) {

        private val textPane = JTextPane()

        init {
            border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            textPane.isEditable = false
            textPane.isOpaque = false
            add(textPane, BorderLayout.CENTER)
        }

        fun clear() {
            textPane.text = "Click a task bar above to see optimization tips."
        }

        fun showTask(task: TaskMetric, allTasks: List<TaskMetric>) {
            val totalMs = allTasks.sumOf { it.durationMs }.toDouble().coerceAtLeast(1.0)
            val pct = (task.durationMs / totalMs * 100).toInt()

            val (category, tip) = categorizeTask(task.path)

            val doc = textPane.styledDocument
            doc.remove(0, doc.length)

            fun bold() = SimpleAttributeSet().also { StyleConstants.setBold(it, true) }
            fun plain() = SimpleAttributeSet()
            fun colored(c: Color) = SimpleAttributeSet().also { StyleConstants.setForeground(it, c) }

            doc.insertString(doc.length, task.path + "\n", bold())
            doc.insertString(doc.length, "Status: ${task.status}  •  Duration: ${formatDuration(task.durationMs)}  •  $pct% of total build\n", plain())
            doc.insertString(doc.length, "\n📂 Category: $category\n", bold())
            doc.insertString(doc.length, "\n💡 $tip", colored(Color(0, 100, 200)))
        }
    }

    // -------------------------------------------------------------------------
    // Task category detection
    // -------------------------------------------------------------------------

    private fun onTaskSelected(task: TaskMetric) {
        detailPanel.showTask(task, currentTasks)
    }

    private fun categorizeTask(taskPath: String): Pair<String, String> {
        val lower = taskPath.lowercase()
        return when {
            lower.contains("kapt") ->
                "Annotation Processing" to
                "This task is running kapt (Kotlin Annotation Processing Tool). Consider switching to KSP " +
                "(Kotlin Symbol Processing) which is 2x faster. Replace id(\"kotlin-kapt\") with " +
                "id(\"com.google.devtools.ksp\") and replace kapt(...) with ksp(...) in dependencies."

            lower.contains("ksp") ->
                "Annotation Processing (KSP)" to
                "KSP is already the faster annotation processor. If this is still slow, check for " +
                "incremental KSP support in your processor's documentation, and ensure ksp.incremental=true " +
                "is set in gradle.properties."

            lower.contains("compile") || lower.contains("kotlin") ->
                "Kotlin Compilation" to
                "Slow Kotlin compilation often indicates incremental compilation is disabled or invalidated. " +
                "Ensure kotlin.incremental=true is in gradle.properties. Avoid using star imports and large " +
                "files — split them to reduce recompilation scope."

            lower.contains("merge") && (lower.contains("resource") || lower.contains("res")) ->
                "Resource Merging" to
                "Resource merging is slow when there are many resources or conflicting resources across modules. " +
                "Enable resource shrinking (shrinkResources = true) for release builds, reduce the number of " +
                "drawable density qualifiers, and consider using vector drawables instead of raster images."

            lower.contains("lint") ->
                "Lint Analysis" to
                "Lint can significantly slow debug builds. Run lint separately from your normal build: " +
                "exclude lint from debug builds by adding lintOptions { checkReleaseBuilds false } or " +
                "run ./gradlew lint only in CI. You can also disable specific slow lint checks."

            lower.contains("test") ->
                "Test Execution" to
                "Tests are expensive. Run tests only when needed: use ./gradlew testDebugUnitTest " +
                "instead of building everything. Consider enabling Gradle test caching with " +
                "org.gradle.caching=true so unchanged test results are reused."

            lower.contains("bundle") || lower.contains("package") ->
                "Packaging" to
                "Packaging/bundling is slower than assembling. For development, use assembleDebug instead of " +
                "bundleDebug — assembleDebug produces an APK which is faster to build and install. " +
                "Reserve bundleRelease for production CI builds."

            else ->
                "Gradle Task" to
                "No specific optimization tip available for this task type. Check if this task supports " +
                "incremental execution (look for @Incremental in its implementation) and whether its " +
                "outputs are properly declared for caching."
        }
    }

    // -------------------------------------------------------------------------
    // Inner: LegendPanel
    // -------------------------------------------------------------------------

    private inner class LegendPanel : JPanel(FlowLayout(FlowLayout.LEFT, 10, 4)) {

        init {
            border = BorderFactory.createTitledBorder("Module Legend")
        }

        fun setTasks(tasks: List<TaskMetric>) {
            removeAll()
            if (tasks.isEmpty()) {
                add(JLabel("(no data)"))
                revalidate(); repaint()
                return
            }

            val topModules = tasks
                .groupBy { it.module }
                .entries
                .sortedByDescending { (_, metrics) -> metrics.sumOf { it.durationMs } }
                .take(6)
                .map { it.key }

            for (moduleName in topModules) {
                val color = moduleColor(moduleName)
                val swatch = object : JLabel("  ") {
                    override fun paintComponent(g: Graphics) {
                        g.color = color
                        g.fillRoundRect(0, 2, width, height - 4, 4, 4)
                    }
                }
                swatch.preferredSize = Dimension(16, 16)
                add(swatch)
                add(JLabel(moduleName))
            }
            revalidate()
            repaint()
        }
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

    private fun moduleColor(moduleName: String): Color {
        val hash = moduleName.hashCode()
        val hue = ((hash and 0xFF_FFFF).toFloat() / 0xFF_FFFF.toFloat())
        return Color.getHSBColor(hue, 0.6f, 0.85f)
    }

    private fun formatDuration(ms: Long): String = when {
        ms < 1_000  -> "${ms}ms"
        ms < 60_000 -> "${"%.1f".format(ms / 1_000.0)}s"
        else        -> "${ms / 60_000}m ${"%.0f".format((ms % 60_000) / 1_000.0)}s"
    }
}
