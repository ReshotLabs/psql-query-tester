package com.psqlquerytester.ui.panels

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.psqlquerytester.api.QueryParameter
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.border.TitledBorder

class ParameterFormPanel(
    private val onExecute: () -> Unit,
    private val onAiAssist: ((paramName: String, paramType: String) -> Unit)? = null
) : JPanel(BorderLayout()) {

    private val formPanel = JPanel(GridBagLayout())
    private val executeButton = JButton("Execute Query").apply {
        addActionListener { onExecute() }
    }

    private val parameterInputs = mutableMapOf<String, Any>() // JBTextField or JBCheckBox
    private var parameters: List<QueryParameter> = emptyList()

    private val noParamsLabel = JBLabel("No parameters detected").apply {
        border = JBUI.Borders.empty(10)
    }

    init {
        border = TitledBorder("Parameters")

        val buttonPanel = JPanel().apply {
            add(executeButton)
        }

        // Button at top so it's always visible
        add(buttonPanel, BorderLayout.NORTH)
        add(formPanel, BorderLayout.CENTER)
    }

    fun setParameters(params: List<QueryParameter>) {
        parameters = params
        parameterInputs.clear()
        formPanel.removeAll()

        if (params.isEmpty()) {
            setNoParameters()
            return
        }

        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(5)
        }

        params.forEachIndexed { index, param ->
            // Label
            gbc.gridx = 0
            gbc.gridy = index
            gbc.weightx = 0.0
            val label = JBLabel("${param.name} (${param.type}):")
            formPanel.add(label, gbc)

            // Input field
            gbc.gridx = 1
            gbc.weightx = 1.0

            val input = createInputForType(param.type)
            parameterInputs[param.name] = input

            when (input) {
                is JBCheckBox -> formPanel.add(input, gbc)
                is JBTextField -> formPanel.add(input, gbc)
            }

            // AI Assist button (only for non-boolean types)
            if (onAiAssist != null && param.type.lowercase() !in listOf("boolean", "bool")) {
                gbc.gridx = 2
                gbc.weightx = 0.0
                val aiButton = JButton("AI").apply {
                    toolTipText = "AI: Generate value from description (e.g., 'oldest user', 'random product')"
                    addActionListener { onAiAssist.invoke(param.name, param.type) }
                }
                formPanel.add(aiButton, gbc)
            }
        }

        // Add spacer at bottom
        gbc.gridx = 0
        gbc.gridy = params.size
        gbc.weighty = 1.0
        formPanel.add(JPanel(), gbc)

        executeButton.isEnabled = true
        formPanel.revalidate()
        formPanel.repaint()
    }

    fun setNoParameters() {
        formPanel.removeAll()
        parameterInputs.clear()
        parameters = emptyList()

        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(10)
        }
        formPanel.add(noParamsLabel, gbc)

        executeButton.isEnabled = true
        formPanel.revalidate()
        formPanel.repaint()
    }

    fun clear() {
        formPanel.removeAll()
        parameterInputs.clear()
        parameters = emptyList()
        executeButton.isEnabled = false
        formPanel.revalidate()
        formPanel.repaint()
    }

    fun getParameterValues(): Map<String, Any?> {
        val values = mutableMapOf<String, Any?>()

        parameters.forEach { param ->
            val input = parameterInputs[param.name]
            val value = when (input) {
                is JBCheckBox -> input.isSelected
                is JBTextField -> input.text.ifBlank { null }
                else -> null
            }
            values[param.name] = value
        }

        return values
    }

    private fun createInputForType(type: String): Any {
        return when (type.lowercase()) {
            "boolean", "bool" -> JBCheckBox()
            else -> JBTextField().apply {
                columns = 20
                when (type.lowercase()) {
                    "integer", "int", "int4", "bigint", "int8" -> {
                        toolTipText = "Enter an integer value"
                    }
                    "numeric", "decimal", "float", "double", "real" -> {
                        toolTipText = "Enter a numeric value"
                    }
                    "timestamp", "timestamptz" -> {
                        toolTipText = "Format: YYYY-MM-DD HH:MM:SS"
                    }
                    "date" -> {
                        toolTipText = "Format: YYYY-MM-DD"
                    }
                    "uuid" -> {
                        toolTipText = "Enter a UUID"
                    }
                    "jsonb", "json" -> {
                        toolTipText = "Enter valid JSON"
                    }
                    else -> {
                        toolTipText = "Enter a text value"
                    }
                }
            }
        }
    }

    fun setParameterValue(paramName: String, value: String) {
        val input = parameterInputs[paramName]
        if (input is JBTextField) {
            input.text = value
        } else if (input is JBCheckBox) {
            input.isSelected = value.lowercase() in listOf("true", "1", "yes")
        }
    }

    fun getParameters(): List<QueryParameter> = parameters
}
