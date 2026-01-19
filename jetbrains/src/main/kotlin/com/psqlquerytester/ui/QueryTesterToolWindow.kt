package com.psqlquerytester.ui

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.psqlquerytester.api.ExtractedQuery
import com.psqlquerytester.api.OpenRouterClient
import com.psqlquerytester.api.OptimizationSuggestion
import com.psqlquerytester.api.QueryParameter
import com.psqlquerytester.db.QueryExecutor
import com.psqlquerytester.db.QueryResult
import com.psqlquerytester.db.SchemaIntrospector
import com.psqlquerytester.extraction.QueryExtractor
import com.psqlquerytester.ui.panels.OptimizationPanel
import com.psqlquerytester.ui.panels.ParameterFormPanel
import com.psqlquerytester.ui.panels.QueryPreviewPanel
import com.psqlquerytester.ui.panels.ResultsPanel
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

class QueryTesterToolWindow(private val project: Project) {

    companion object {
        val KEY = Key.create<QueryTesterToolWindow>("QueryTesterToolWindow")
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val mainPanel = JPanel(BorderLayout())
    private val queryPreviewPanel = QueryPreviewPanel()
    private val parameterFormPanel = ParameterFormPanel(
        onExecute = { executeQuery() },
        onAiAssist = { paramName, paramType -> handleAiAssist(paramName, paramType) }
    )
    private val resultsPanel = ResultsPanel()
    private val optimizationPanel = OptimizationPanel(
        onTestQuery = { suggestion -> testOptimizedQuery(suggestion) },
        onApplyToCode = { suggestion -> applyToCode(suggestion) }
    )

    private val statusLabel = JLabel("Ready", SwingConstants.LEFT).apply {
        border = JBUI.Borders.empty(5)
    }

    private var currentQuery: ExtractedQuery? = null
    private var queryExtractor: QueryExtractor? = null
    private var openRouterClient: OpenRouterClient? = null
    private var lastExecutionTimeMs: Long = 0
    private var originalCode: String = ""
    private var detectedLanguage: String = "elixir"
    private var fullFileContent: String = ""
    private var surroundingContext: String = ""
    private var selectionStartOffset: Int = 0
    private var selectionEndOffset: Int = 0

    init {
        setupUI()
    }

    private fun setupUI() {
        // Top section: Query preview + Parameter form
        val topPanel = JPanel(BorderLayout()).apply {
            val topSplitter = JBSplitter(false, 0.6f).apply {
                firstComponent = JBScrollPane(queryPreviewPanel)
                secondComponent = JBScrollPane(parameterFormPanel)
            }
            add(topSplitter, BorderLayout.CENTER)
        }

        // Middle section: Results + Optimization
        val bottomPanel = JPanel(BorderLayout()).apply {
            val bottomSplitter = JBSplitter(false, 0.5f).apply {
                firstComponent = JBScrollPane(resultsPanel)
                secondComponent = JBScrollPane(optimizationPanel)
            }
            add(bottomSplitter, BorderLayout.CENTER)
        }

        // Main splitter: Top (query + params) and Bottom (results + optimization)
        val mainSplitter = JBSplitter(true, 0.35f).apply {
            firstComponent = topPanel
            secondComponent = bottomPanel
        }

        mainPanel.add(mainSplitter, BorderLayout.CENTER)
        mainPanel.add(statusLabel, BorderLayout.SOUTH)
    }

    fun getContent(): JPanel = mainPanel

    fun extractAndTestQuery(
        selectedCode: String,
        fileName: String?,
        fullFileContent: String = "",
        surroundingContext: String = "",
        selectionStartOffset: Int = 0,
        selectionEndOffset: Int = 0
    ) {
        if (queryExtractor == null) {
            queryExtractor = QueryExtractor()
        }

        // Store context for later use in code application
        this.fullFileContent = fullFileContent
        this.surroundingContext = surroundingContext
        this.selectionStartOffset = selectionStartOffset
        this.selectionEndOffset = selectionEndOffset
        if (openRouterClient == null) {
            openRouterClient = OpenRouterClient()
        }

        // Store original code for later code generation
        originalCode = selectedCode

        updateStatus("Extracting query...")
        queryPreviewPanel.setLoading(true)
        parameterFormPanel.clear()
        resultsPanel.clear()
        optimizationPanel.clear()

        scope.launch {
            try {
                val language = queryExtractor!!.detectLanguage(fileName, selectedCode)
                detectedLanguage = language
                val result = queryExtractor!!.extract(selectedCode, language, surroundingContext)

                withContext(Dispatchers.Swing) {
                    handleExtractionResult(result)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Swing) {
                    queryPreviewPanel.setLoading(false)
                    queryPreviewPanel.setError("Extraction failed: ${e.message}")
                    updateStatus("Extraction failed")
                }
            }
        }
    }

    private fun handleExtractionResult(result: ExtractedQuery) {
        queryPreviewPanel.setLoading(false)

        if (result.error != null && result.query.isEmpty() && !result.multipleQueries) {
            queryPreviewPanel.setError(result.error)
            updateStatus("Extraction failed")
            return
        }

        // Handle multiple queries - let user choose
        if (result.multipleQueries && result.options.isNotEmpty()) {
            val optionNames = result.options.map { it.name }.toTypedArray()
            val selectedIndex = Messages.showChooseDialog(
                project,
                "Multiple queries found. Select which one to test:",
                "Select Query",
                Messages.getQuestionIcon(),
                optionNames,
                optionNames[0]
            )

            if (selectedIndex >= 0) {
                val selected = result.options[selectedIndex]
                val selectedQuery = ExtractedQuery(
                    query = selected.query,
                    formattedQuery = selected.formattedQuery,
                    parameters = selected.parameters
                )
                currentQuery = selectedQuery
                displayQuery(selectedQuery)
            } else {
                updateStatus("No query selected")
            }
            return
        }

        currentQuery = result
        displayQuery(result)
    }

    private fun displayQuery(query: ExtractedQuery) {
        val queryToShow = query.formattedQuery.ifEmpty { query.query }
        queryPreviewPanel.setQuery(queryToShow)

        if (query.parameters.isNotEmpty()) {
            parameterFormPanel.setParameters(query.parameters)
            updateStatus("Query extracted. Fill in parameters and click Execute.")
        } else {
            parameterFormPanel.setNoParameters()
            updateStatus("Query extracted. Click Execute to run.")
        }
    }

    private fun executeQuery() {
        val query = currentQuery ?: return

        // Use edited query from panel if available
        val queryToExecute = queryPreviewPanel.getQuery().ifBlank { query.query }
        if (queryToExecute.isEmpty()) {
            updateStatus("No query to execute")
            return
        }

        val parameterValues = parameterFormPanel.getParameterValues()
        updateStatus("Executing query...")
        resultsPanel.setLoading(true)

        scope.launch {
            try {
                val result = QueryExecutor.execute(
                    queryToExecute,
                    query.parameters,
                    parameterValues
                )

                withContext(Dispatchers.Swing) {
                    handleQueryResult(result, queryToExecute)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Swing) {
                    resultsPanel.setLoading(false)
                    resultsPanel.setError("Execution failed: ${e.message}")
                    updateStatus("Execution failed")
                }
            }
        }
    }

    private fun handleQueryResult(result: QueryResult, executedQuery: String? = null) {
        resultsPanel.setLoading(false)

        if (result.error != null) {
            resultsPanel.setError(result.error)
            updateStatus("Query failed: ${result.error}")

            // Offer AI fix
            val query = executedQuery ?: currentQuery?.query ?: return
            offerAiFix(query, result.error)
            return
        }

        resultsPanel.setResult(result)
        lastExecutionTimeMs = result.durationMs

        val statusText = if (result.isSelect) {
            "${result.rowCount} rows returned in ${result.durationMs}ms"
        } else {
            "${result.affectedRows} rows affected in ${result.durationMs}ms"
        }
        updateStatus(statusText)

        // Trigger optimization suggestions after successful query
        if (result.isSelect && currentQuery != null) {
            requestOptimizations()
        }
    }

    private fun offerAiFix(failedQuery: String, error: String) {
        val choice = Messages.showYesNoCancelDialog(
            project,
            "Query failed with error:\n\n$error\n\nWould you like AI to try to fix it?",
            "Query Error",
            "Auto-fix",
            "Tell AI what to change",
            "Cancel",
            Messages.getErrorIcon()
        )

        when (choice) {
            Messages.YES -> autoFixQuery(failedQuery, error)
            Messages.NO -> askUserForFixInstructions(failedQuery, error)
        }
    }

    private fun autoFixQuery(failedQuery: String, error: String) {
        if (openRouterClient == null) {
            openRouterClient = OpenRouterClient()
        }

        updateStatus("AI fixing query...")

        scope.launch {
            try {
                val dbSchema = SchemaIntrospector.getSchema()
                val fixedQuery = openRouterClient!!.fixQuery(failedQuery, error, dbSchema)

                withContext(Dispatchers.Swing) {
                    if (fixedQuery.error != null) {
                        Messages.showErrorDialog(project, "AI could not fix the query: ${fixedQuery.error}", "Fix Failed")
                        updateStatus("AI fix failed")
                    } else {
                        queryPreviewPanel.setQuery(fixedQuery.formattedQuery.ifEmpty { fixedQuery.query })
                        if (fixedQuery.parameters.isNotEmpty()) {
                            parameterFormPanel.setParameters(fixedQuery.parameters)
                        }
                        updateStatus("AI fixed the query. Review and execute again.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Swing) {
                    Messages.showErrorDialog(project, "Failed to fix query: ${e.message}", "Fix Failed")
                    updateStatus("AI fix failed")
                }
            }
        }
    }

    private fun askUserForFixInstructions(failedQuery: String, error: String) {
        val instructions = Messages.showInputDialog(
            project,
            "What should the AI change?\n\nError: $error",
            "Fix Instructions",
            Messages.getQuestionIcon()
        )

        if (instructions.isNullOrBlank()) return

        if (openRouterClient == null) {
            openRouterClient = OpenRouterClient()
        }

        updateStatus("AI fixing query with your instructions...")

        scope.launch {
            try {
                val dbSchema = SchemaIntrospector.getSchema()
                val fixedQuery = openRouterClient!!.fixQueryWithInstructions(failedQuery, error, instructions, dbSchema)

                withContext(Dispatchers.Swing) {
                    if (fixedQuery.error != null) {
                        Messages.showErrorDialog(project, "AI could not fix the query: ${fixedQuery.error}", "Fix Failed")
                        updateStatus("AI fix failed")
                    } else {
                        queryPreviewPanel.setQuery(fixedQuery.formattedQuery.ifEmpty { fixedQuery.query })
                        if (fixedQuery.parameters.isNotEmpty()) {
                            parameterFormPanel.setParameters(fixedQuery.parameters)
                        }
                        updateStatus("AI fixed the query. Review and execute again.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Swing) {
                    Messages.showErrorDialog(project, "Failed to fix query: ${e.message}", "Fix Failed")
                    updateStatus("AI fix failed")
                }
            }
        }
    }

    private fun requestOptimizations() {
        val query = currentQuery ?: return
        optimizationPanel.setLoading(true)
        updateStatus("Finding optimizations...")

        scope.launch {
            try {
                val dbSchema = SchemaIntrospector.getSchema()
                val response = openRouterClient!!.suggestOptimizations(
                    query.query,
                    lastExecutionTimeMs,
                    dbSchema,
                    query.parameters
                )

                withContext(Dispatchers.Swing) {
                    optimizationPanel.setLoading(false)
                    if (response.error != null) {
                        optimizationPanel.setError(response.error)
                    } else {
                        optimizationPanel.setOptimizations(response, lastExecutionTimeMs)
                    }
                    updateStatus("${resultsPanel.getRowCount()} rows in ${lastExecutionTimeMs}ms - ${response.suggestions.size} optimizations found")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Swing) {
                    optimizationPanel.setLoading(false)
                    optimizationPanel.setError("Failed to get optimizations: ${e.message}")
                }
            }
        }
    }

    private fun testOptimizedQuery(suggestion: OptimizationSuggestion) {
        val parameterValues = parameterFormPanel.getParameterValues()
        updateStatus("Testing optimized query...")
        optimizationPanel.setTestingQuery(suggestion)

        // Use suggestion parameters if available, otherwise filter original params to those used in the query
        val paramsToUse = if (suggestion.parameters.isNotEmpty()) {
            suggestion.parameters
        } else {
            // Find which $N parameters are actually used in the optimized query
            val usedPositions = Regex("""\$(\d+)""").findAll(suggestion.query)
                .map { it.groupValues[1].toInt() }
                .toSet()
            currentQuery?.parameters?.filter { it.position in usedPositions } ?: emptyList()
        }

        scope.launch {
            try {
                val result = QueryExecutor.execute(
                    suggestion.query,
                    paramsToUse,
                    parameterValues
                )

                withContext(Dispatchers.Swing) {
                    optimizationPanel.setTestResult(suggestion, result)
                    if (result.error == null) {
                        val improvement = if (lastExecutionTimeMs > 0 && result.durationMs < lastExecutionTimeMs) {
                            val pct = ((lastExecutionTimeMs - result.durationMs) * 100) / lastExecutionTimeMs
                            " (${pct}% faster)"
                        } else ""
                        updateStatus("Optimized query: ${result.durationMs}ms$improvement")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Swing) {
                    optimizationPanel.setTestError(suggestion, e.message ?: "Unknown error")
                }
            }
        }
    }

    private fun applyToCode(suggestion: OptimizationSuggestion) {
        val query = currentQuery ?: return
        updateStatus("Generating code change...")

        scope.launch {
            try {
                val response = openRouterClient!!.generateCodeChange(
                    originalCode = originalCode,
                    originalQuery = query.query,
                    optimizedQuery = suggestion.query,
                    language = detectedLanguage,
                    surroundingContext = surroundingContext
                )

                withContext(Dispatchers.Swing) {
                    if (response.error != null) {
                        updateStatus("Code generation failed: ${response.error}")
                        return@withContext
                    }

                    // Apply the change to the editor using stored offsets
                    applyCodeToEditor(response.modifiedCode)
                    updateStatus("Code updated! ${response.explanation}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Swing) {
                    updateStatus("Failed to apply code: ${e.message}")
                }
            }
        }
    }

    private fun applyCodeToEditor(newCode: String) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val document = editor.document

        // Use stored offsets if available, otherwise fall back to current selection
        val startOffset: Int
        val endOffset: Int

        if (selectionStartOffset > 0 || selectionEndOffset > 0) {
            // Use the stored offsets from when the query was extracted
            startOffset = selectionStartOffset
            endOffset = selectionEndOffset
        } else if (editor.selectionModel.hasSelection()) {
            // Fall back to current selection
            startOffset = editor.selectionModel.selectionStart
            endOffset = editor.selectionModel.selectionEnd
        } else {
            updateStatus("Cannot apply: no selection found")
            return
        }

        // Validate offsets are within document bounds
        if (startOffset < 0 || endOffset > document.textLength || startOffset >= endOffset) {
            updateStatus("Cannot apply: invalid code location")
            return
        }

        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(startOffset, endOffset, newCode)
        }

        // Select the newly inserted code so user can see what changed
        editor.selectionModel.setSelection(startOffset, startOffset + newCode.length)
        editor.caretModel.moveToOffset(startOffset)
    }

    private fun handleAiAssist(paramName: String, paramType: String) {
        // Show input dialog to get description
        val description = Messages.showInputDialog(
            project,
            "Describe what value you want for '$paramName' (type: $paramType):\n\n" +
                    "Examples:\n" +
                    "• \"the oldest user in the database\"\n" +
                    "• \"any user created in the past 30 days\"\n" +
                    "• \"the id of the product with highest price\"",
            "AI Parameter Assist",
            Messages.getQuestionIcon()
        )

        if (description.isNullOrBlank()) return

        if (openRouterClient == null) {
            openRouterClient = OpenRouterClient()
        }

        updateStatus("AI generating query for '$paramName'...")

        scope.launch {
            try {
                val dbSchema = SchemaIntrospector.getSchema()
                val queryResponse = openRouterClient!!.generateParameterValueQuery(
                    paramName,
                    paramType,
                    description,
                    dbSchema
                )

                withContext(Dispatchers.Swing) {
                    if (queryResponse.error != null) {
                        Messages.showErrorDialog(
                            project,
                            "Could not generate query: ${queryResponse.error}",
                            "AI Assist Error"
                        )
                        updateStatus("AI assist failed: ${queryResponse.error}")
                        return@withContext
                    }

                    if (queryResponse.query.isBlank()) {
                        Messages.showErrorDialog(
                            project,
                            "No query generated",
                            "AI Assist Error"
                        )
                        updateStatus("AI assist failed: no query generated")
                        return@withContext
                    }

                    updateStatus("Executing AI-generated query...")
                }

                // Execute the generated query to get the value
                val result = QueryExecutor.executeSimple(queryResponse.query)

                withContext(Dispatchers.Swing) {
                    if (result.error != null) {
                        Messages.showErrorDialog(
                            project,
                            "Query failed: ${result.error}\n\nGenerated query:\n${queryResponse.query}",
                            "AI Assist Error"
                        )
                        updateStatus("AI assist query failed")
                        return@withContext
                    }

                    // Get the first value from the result
                    val value = result.rows.firstOrNull()?.firstOrNull()?.toString()
                    if (value == null) {
                        Messages.showWarningDialog(
                            project,
                            "Query returned no results.\n\nGenerated query:\n${queryResponse.query}",
                            "AI Assist Warning"
                        )
                        updateStatus("AI assist: no results found")
                        return@withContext
                    }

                    // Fill the parameter value
                    parameterFormPanel.setParameterValue(paramName, value)
                    updateStatus("AI filled '$paramName' = $value (${queryResponse.explanation})")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Swing) {
                    Messages.showErrorDialog(
                        project,
                        "AI assist failed: ${e.message}",
                        "AI Assist Error"
                    )
                    updateStatus("AI assist error: ${e.message}")
                }
            }
        }
    }

    private fun updateStatus(text: String) {
        statusLabel.text = text
    }

    fun dispose() {
        scope.cancel()
        queryExtractor?.close()
        openRouterClient?.close()
    }
}
