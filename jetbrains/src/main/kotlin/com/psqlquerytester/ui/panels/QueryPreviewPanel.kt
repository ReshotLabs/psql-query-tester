package com.psqlquerytester.ui.panels

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JPanel
import javax.swing.border.TitledBorder

class QueryPreviewPanel : JPanel(BorderLayout()) {

    private val titleLabel = JBLabel("Extracted SQL Query")
    private val queryTextArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        border = JBUI.Borders.empty(10)
        background = JBColor.background()
    }

    private val loadingLabel = JBLabel("Extracting query...").apply {
        horizontalAlignment = JBLabel.CENTER
        isVisible = false
    }

    private val errorLabel = JBLabel().apply {
        foreground = JBColor.RED
        border = JBUI.Borders.empty(10)
        isVisible = false
    }

    init {
        border = TitledBorder("SQL Query")
        add(queryTextArea, BorderLayout.CENTER)
        add(loadingLabel, BorderLayout.NORTH)
        add(errorLabel, BorderLayout.SOUTH)
    }

    fun setQuery(sql: String) {
        queryTextArea.text = sql
        queryTextArea.caretPosition = 0
        queryTextArea.isVisible = true
        errorLabel.isVisible = false
    }

    fun setLoading(loading: Boolean) {
        loadingLabel.isVisible = loading
        if (loading) {
            queryTextArea.text = ""
            errorLabel.isVisible = false
        }
    }

    fun setError(message: String) {
        errorLabel.text = "<html><body style='width: 300px'>$message</body></html>"
        errorLabel.isVisible = true
        queryTextArea.isVisible = false
    }

    fun clear() {
        queryTextArea.text = ""
        errorLabel.isVisible = false
        loadingLabel.isVisible = false
    }
}
