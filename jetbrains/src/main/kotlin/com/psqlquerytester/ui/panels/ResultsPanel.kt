package com.psqlquerytester.ui.panels

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.psqlquerytester.db.QueryResult
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.border.TitledBorder
import javax.swing.table.DefaultTableModel

class ResultsPanel : JPanel(BorderLayout()) {

    private val tableModel = object : DefaultTableModel() {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }

    private val table = JBTable(tableModel).apply {
        autoResizeMode = JBTable.AUTO_RESIZE_OFF
        setShowGrid(true)
        gridColor = JBColor.border()
    }

    private val scrollPane = JBScrollPane(table)

    private val statusLabel = JBLabel().apply {
        border = JBUI.Borders.empty(5)
    }

    private val loadingLabel = JBLabel("Executing query...").apply {
        horizontalAlignment = JBLabel.CENTER
        isVisible = false
    }

    private val errorLabel = JBLabel().apply {
        foreground = JBColor.RED
        border = JBUI.Borders.empty(10)
        isVisible = false
    }

    init {
        border = TitledBorder("Results")

        add(loadingLabel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        setupContextMenu()
    }

    private fun setupContextMenu() {
        val popup = JPopupMenu()

        val copyCell = JMenuItem("Copy Cell").apply {
            addActionListener { copyCellValue() }
        }
        val copyRow = JMenuItem("Copy Row").apply {
            addActionListener { copyRowValue() }
        }
        val copyAll = JMenuItem("Copy All").apply {
            addActionListener { copyAllValues() }
        }

        popup.add(copyCell)
        popup.add(copyRow)
        popup.addSeparator()
        popup.add(copyAll)

        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showPopup(e)
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showPopup(e)
            }
            private fun showPopup(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                val col = table.columnAtPoint(e.point)
                if (row >= 0 && col >= 0) {
                    table.setRowSelectionInterval(row, row)
                    table.setColumnSelectionInterval(col, col)
                }
                popup.show(e.component, e.x, e.y)
            }
        })
    }

    private fun copyCellValue() {
        val row = table.selectedRow
        val col = table.selectedColumn
        if (row >= 0 && col >= 0) {
            val value = table.getValueAt(row, col)?.toString() ?: ""
            copyToClipboard(value)
        }
    }

    private fun copyRowValue() {
        val row = table.selectedRow
        if (row >= 0) {
            val values = (0 until table.columnCount).map { col ->
                table.getValueAt(row, col)?.toString() ?: ""
            }
            copyToClipboard(values.joinToString("\t"))
        }
    }

    private fun copyAllValues() {
        val sb = StringBuilder()

        // Header
        val headers = (0 until table.columnCount).map { table.getColumnName(it) }
        sb.appendLine(headers.joinToString("\t"))

        // Rows
        for (row in 0 until table.rowCount) {
            val values = (0 until table.columnCount).map { col ->
                table.getValueAt(row, col)?.toString() ?: ""
            }
            sb.appendLine(values.joinToString("\t"))
        }

        copyToClipboard(sb.toString())
    }

    private fun copyToClipboard(text: String) {
        val selection = StringSelection(text)
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
    }

    fun setResult(result: QueryResult) {
        tableModel.setRowCount(0)
        tableModel.setColumnCount(0)

        if (result.columns.isEmpty()) {
            statusLabel.text = "No data"
            return
        }

        // Set columns
        result.columns.forEach { tableModel.addColumn(it) }

        // Add rows
        result.rows.forEach { row ->
            tableModel.addRow(row.map { formatValue(it) }.toTypedArray())
        }

        // Auto-size columns (with max width)
        for (i in 0 until table.columnCount) {
            val column = table.columnModel.getColumn(i)
            var maxWidth = 100

            // Check header width
            val headerWidth = table.tableHeader.getFontMetrics(table.tableHeader.font)
                .stringWidth(table.getColumnName(i)) + 20
            maxWidth = maxOf(maxWidth, headerWidth)

            // Check data width (sample first 50 rows)
            val rowsToCheck = minOf(50, table.rowCount)
            for (row in 0 until rowsToCheck) {
                val value = table.getValueAt(row, i)?.toString() ?: ""
                val cellWidth = table.getFontMetrics(table.font).stringWidth(value) + 20
                maxWidth = maxOf(maxWidth, minOf(cellWidth, 400)) // Cap at 400px
            }

            column.preferredWidth = maxWidth
        }

        errorLabel.isVisible = false
        scrollPane.isVisible = true
        lastRowCount = result.rowCount

        val statusText = if (result.isSelect) {
            "${result.rowCount} rows (${result.durationMs}ms)"
        } else {
            "${result.affectedRows} affected (${result.durationMs}ms)"
        }
        statusLabel.text = statusText
    }

    private fun formatValue(value: Any?): String {
        return when (value) {
            null -> "NULL"
            is String -> value
            else -> value.toString()
        }
    }

    fun setLoading(loading: Boolean) {
        loadingLabel.isVisible = loading
        if (loading) {
            tableModel.setRowCount(0)
            tableModel.setColumnCount(0)
            errorLabel.isVisible = false
            statusLabel.text = ""
        }
    }

    fun setError(message: String) {
        tableModel.setRowCount(0)
        tableModel.setColumnCount(0)
        statusLabel.text = "Error"

        // Show error in a simple way
        tableModel.addColumn("Error")
        tableModel.addRow(arrayOf(message))
    }

    private var lastRowCount = 0

    fun clear() {
        tableModel.setRowCount(0)
        tableModel.setColumnCount(0)
        statusLabel.text = ""
        loadingLabel.isVisible = false
        errorLabel.isVisible = false
        lastRowCount = 0
    }

    fun getRowCount(): Int = lastRowCount
}
