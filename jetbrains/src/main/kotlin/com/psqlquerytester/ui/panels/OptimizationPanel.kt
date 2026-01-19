package com.psqlquerytester.ui.panels

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.psqlquerytester.api.OptimizationResponse
import com.psqlquerytester.api.OptimizationSuggestion
import com.psqlquerytester.db.QueryResult
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.*
import javax.swing.border.TitledBorder

class OptimizationPanel(
    private val onTestQuery: (OptimizationSuggestion) -> Unit,
    private val onApplyToCode: (OptimizationSuggestion) -> Unit
) : JPanel(BorderLayout()) {

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val scrollPane = JBScrollPane(contentPanel)

    private val loadingLabel = JBLabel("Finding optimizations...").apply {
        horizontalAlignment = JBLabel.CENTER
        border = JBUI.Borders.empty(20)
    }

    private val statusLabel = JBLabel().apply {
        border = JBUI.Borders.empty(5)
    }

    private var originalTimeMs: Long = 0
    private val suggestionPanels = mutableMapOf<OptimizationSuggestion, SuggestionPanel>()

    init {
        border = TitledBorder("Optimizations")
        add(scrollPane, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        showEmpty()
    }

    fun clear() {
        contentPanel.removeAll()
        suggestionPanels.clear()
        showEmpty()
        statusLabel.text = ""
        revalidate()
        repaint()
    }

    private fun showEmpty() {
        contentPanel.removeAll()
        contentPanel.add(JBLabel("Run a query to see optimization suggestions").apply {
            alignmentX = CENTER_ALIGNMENT
            border = JBUI.Borders.empty(20)
            foreground = JBColor.GRAY
        })
        revalidate()
        repaint()
    }

    fun setLoading(loading: Boolean) {
        contentPanel.removeAll()
        if (loading) {
            contentPanel.add(loadingLabel)
        }
        revalidate()
        repaint()
    }

    fun setError(message: String) {
        contentPanel.removeAll()
        contentPanel.add(JBLabel(message).apply {
            foreground = JBColor.RED
            border = JBUI.Borders.empty(10)
        })
        revalidate()
        repaint()
    }

    fun setOptimizations(response: OptimizationResponse, originalTime: Long) {
        originalTimeMs = originalTime
        contentPanel.removeAll()
        suggestionPanels.clear()

        // Analysis section
        if (response.analysis.isNotBlank()) {
            contentPanel.add(createAnalysisSection(response.analysis))
            contentPanel.add(Box.createVerticalStrut(10))
        }

        // Suggestions
        if (response.suggestions.isEmpty()) {
            contentPanel.add(JBLabel("No optimization suggestions - query looks good!").apply {
                foreground = JBColor.GREEN.darker()
                border = JBUI.Borders.empty(10)
            })
        } else {
            response.suggestions.forEachIndexed { index, suggestion ->
                val panel = SuggestionPanel(index + 1, suggestion, originalTime, onTestQuery, onApplyToCode)
                suggestionPanels[suggestion] = panel
                contentPanel.add(panel)
                contentPanel.add(Box.createVerticalStrut(10))
            }
        }

        // Index suggestions
        if (response.indexSuggestions.isNotEmpty()) {
            contentPanel.add(createIndexSection(response.indexSuggestions))
        }

        statusLabel.text = "${response.suggestions.size} suggestions found"
        revalidate()
        repaint()
    }

    private fun createAnalysisSection(analysis: String): JPanel {
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Analysis"),
                JBUI.Borders.empty(5)
            )
            add(JBLabel("<html>${analysis.replace("\n", "<br>")}</html>"), BorderLayout.CENTER)
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun createIndexSection(indexes: List<String>): JPanel {
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Suggested Indexes"),
                JBUI.Borders.empty(5)
            )
            val textArea = JBTextArea().apply {
                text = indexes.joinToString("\n\n")
                isEditable = false
                font = Font(Font.MONOSPACED, Font.PLAIN, 12)
                lineWrap = true
                wrapStyleWord = true
            }
            add(JBScrollPane(textArea), BorderLayout.CENTER)
            preferredSize = java.awt.Dimension(preferredSize.width, 100)
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, 120)
        }
    }

    fun setTestingQuery(suggestion: OptimizationSuggestion) {
        suggestionPanels[suggestion]?.setTesting()
    }

    fun setTestResult(suggestion: OptimizationSuggestion, result: QueryResult) {
        suggestionPanels[suggestion]?.setTestResult(result, originalTimeMs)
    }

    fun setTestError(suggestion: OptimizationSuggestion, error: String) {
        suggestionPanels[suggestion]?.setTestError(error)
    }

    private class SuggestionPanel(
        index: Int,
        private val suggestion: OptimizationSuggestion,
        private val originalTime: Long,
        private val onTest: (OptimizationSuggestion) -> Unit,
        private val onApply: (OptimizationSuggestion) -> Unit
    ) : JPanel(BorderLayout()) {

        private val testButton = JButton("Test Query")
        private val applyButton = JButton("Apply to Code")
        private val resultLabel = JBLabel()
        private var testedTime: Long? = null
        private val queryArea: JBTextArea

        init {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Option $index: ${suggestion.expectedImprovement}"),
                JBUI.Borders.empty(5)
            )

            // Query display - editable so user can make tweaks
            queryArea = JBTextArea().apply {
                text = suggestion.formattedQuery.ifEmpty { suggestion.query }
                isEditable = true
                font = Font(Font.MONOSPACED, Font.PLAIN, 12)
                rows = 4
                toolTipText = "You can edit this query before testing"
            }

            // Explanation
            val explanationLabel = JBLabel("<html><b>Why:</b> ${suggestion.explanation}</html>").apply {
                border = JBUI.Borders.empty(5, 0)
            }

            // Buttons
            val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(testButton)
                add(applyButton)
                add(resultLabel)
            }

            // Use the edited query text when testing/applying
            testButton.addActionListener { onTest(getEditedSuggestion()) }
            applyButton.addActionListener { onApply(getEditedSuggestion()) }

            add(JBScrollPane(queryArea), BorderLayout.CENTER)
            add(explanationLabel, BorderLayout.NORTH)
            add(buttonPanel, BorderLayout.SOUTH)

            maximumSize = java.awt.Dimension(Int.MAX_VALUE, 200)
        }

        fun setTesting() {
            testButton.isEnabled = false
            testButton.text = "Testing..."
            resultLabel.text = ""
        }

        fun setTestResult(result: QueryResult, originalTime: Long) {
            testButton.isEnabled = true
            testButton.text = "Test Query"

            if (result.error != null) {
                resultLabel.text = "Error: ${result.error}"
                resultLabel.foreground = JBColor.RED
                return
            }

            testedTime = result.durationMs
            val comparison = if (originalTime > 0) {
                val diff = originalTime - result.durationMs
                if (diff > 0) {
                    val pct = (diff * 100) / originalTime
                    " (${pct}% faster)"
                } else if (diff < 0) {
                    val pct = (-diff * 100) / originalTime
                    " (${pct}% slower)"
                } else {
                    " (same speed)"
                }
            } else ""

            resultLabel.text = "${result.durationMs}ms$comparison - ${result.rowCount} rows"
            resultLabel.foreground = if (result.durationMs < originalTime) JBColor.GREEN.darker() else JBColor.ORANGE
        }

        fun setTestError(error: String) {
            testButton.isEnabled = true
            testButton.text = "Test Query"
            resultLabel.text = "Error: $error"
            resultLabel.foreground = JBColor.RED
        }

        private fun getEditedSuggestion(): OptimizationSuggestion {
            val editedQuery = queryArea.text.trim()
            return suggestion.copy(
                query = editedQuery,
                formattedQuery = editedQuery
            )
        }
    }
}
