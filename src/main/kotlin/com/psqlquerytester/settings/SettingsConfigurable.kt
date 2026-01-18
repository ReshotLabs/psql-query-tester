package com.psqlquerytester.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.psqlquerytester.db.ConnectionManager
import javax.swing.JComponent

class SettingsConfigurable : Configurable {

    private var panel: DialogPanel? = null

    private val apiKeyField = JBPasswordField()
    private val connectionStringField = JBPasswordField()
    private val maxRowsField = JBTextField()
    private val timeoutField = JBTextField()
    private val modelOverrideField = JBTextField()

    private val settings = PluginSettings.getInstance()

    override fun getDisplayName(): String = "PSQL Query Tester"

    override fun createComponent(): JComponent {
        // Load current values
        apiKeyField.text = CredentialManager.getOpenRouterApiKey() ?: ""
        connectionStringField.text = CredentialManager.getConnectionString() ?: ""
        maxRowsField.text = settings.maxResultRows.toString()
        timeoutField.text = settings.queryTimeoutSeconds.toString()
        modelOverrideField.text = settings.modelOverride

        panel = panel {
            group("API Configuration") {
                row("OpenRouter API Key:") {
                    cell(apiKeyField)
                        .columns(COLUMNS_LARGE)
                        .comment("Get your API key from <a href='https://openrouter.ai/keys'>openrouter.ai/keys</a>")
                }
                row("Model Override:") {
                    cell(modelOverrideField)
                        .columns(COLUMNS_LARGE)
                        .comment("Leave blank for default (${PluginSettings.DEFAULT_MODEL}). Browse models at <a href='https://openrouter.ai/models'>openrouter.ai/models</a>")
                }
            }

            group("Database Configuration") {
                row("PostgreSQL Connection String:") {
                    cell(connectionStringField)
                        .columns(COLUMNS_LARGE)
                        .comment("Format: postgresql://user:password@host:port/database")
                }
                row {
                    button("Test Connection") {
                        testConnection()
                    }
                }
            }

            group("Query Settings") {
                row("Max Result Rows:") {
                    cell(maxRowsField)
                        .columns(COLUMNS_SHORT)
                        .comment("Maximum number of rows to return (default: 1000)")
                }
                row("Query Timeout (seconds):") {
                    cell(timeoutField)
                        .columns(COLUMNS_SHORT)
                        .comment("Query execution timeout (default: 30)")
                }
                row("SSL Mode:") {
                    comboBox(listOf("disable", "prefer", "require", "verify-ca", "verify-full"))
                        .bindItem(
                            getter = { settings.sslMode },
                            setter = { settings.sslMode = it ?: "prefer" }
                        )
                }
            }
        }

        return panel!!
    }

    private fun testConnection() {
        val connString = String(connectionStringField.password)
        if (connString.isBlank()) {
            showMessage("Please enter a connection string first.", isError = true)
            return
        }

        val result = ConnectionManager.testConnection(connString)
        result.fold(
            onSuccess = {
                showMessage("Connection successful!", isError = false)
            },
            onFailure = { e ->
                val errorMsg = buildString {
                    append("Connection failed:\n\n")
                    append(e.message ?: "Unknown error")
                    e.cause?.let { cause ->
                        append("\n\nCause: ${cause.message}")
                    }
                }
                showMessage(errorMsg, isError = true)
            }
        )
    }

    private fun showMessage(message: String, isError: Boolean) {
        val title = if (isError) "Connection Error" else "Success"
        val messageType = if (isError) {
            javax.swing.JOptionPane.ERROR_MESSAGE
        } else {
            javax.swing.JOptionPane.INFORMATION_MESSAGE
        }
        javax.swing.JOptionPane.showMessageDialog(panel, message, title, messageType)
    }

    override fun isModified(): Boolean {
        val currentApiKey = CredentialManager.getOpenRouterApiKey() ?: ""
        val currentConnString = CredentialManager.getConnectionString() ?: ""

        return String(apiKeyField.password) != currentApiKey ||
                String(connectionStringField.password) != currentConnString ||
                maxRowsField.text != settings.maxResultRows.toString() ||
                timeoutField.text != settings.queryTimeoutSeconds.toString() ||
                modelOverrideField.text != settings.modelOverride
    }

    override fun apply() {
        val apiKey = String(apiKeyField.password)
        if (apiKey.isNotBlank()) {
            CredentialManager.setOpenRouterApiKey(apiKey)
        }

        val connString = String(connectionStringField.password)
        if (connString.isNotBlank()) {
            CredentialManager.setConnectionString(connString)
        }

        maxRowsField.text.toIntOrNull()?.let {
            settings.maxResultRows = it
        }

        timeoutField.text.toIntOrNull()?.let {
            settings.queryTimeoutSeconds = it
        }

        settings.modelOverride = modelOverrideField.text.trim()

        // Reinitialize connection pool if connection string changed
        if (connString.isNotBlank()) {
            ConnectionManager.reinitialize(connString)
        }
    }

    override fun reset() {
        apiKeyField.text = CredentialManager.getOpenRouterApiKey() ?: ""
        connectionStringField.text = CredentialManager.getConnectionString() ?: ""
        maxRowsField.text = settings.maxResultRows.toString()
        timeoutField.text = settings.queryTimeoutSeconds.toString()
        modelOverrideField.text = settings.modelOverride
    }

    override fun disposeUIResources() {
        panel = null
    }
}
