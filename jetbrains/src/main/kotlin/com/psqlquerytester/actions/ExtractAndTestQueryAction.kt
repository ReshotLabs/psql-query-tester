package com.psqlquerytester.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.psqlquerytester.settings.CredentialManager
import com.psqlquerytester.ui.QueryTesterToolWindow

class ExtractAndTestQueryAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        // Get selected text
        val selectedText = editor.selectionModel.selectedText
        if (selectedText.isNullOrBlank()) {
            Messages.showWarningDialog(
                project,
                "Please select code containing a SQL query first.",
                "No Selection"
            )
            return
        }

        // Check if API key is configured
        if (!CredentialManager.hasOpenRouterApiKey()) {
            val result = Messages.showYesNoDialog(
                project,
                "OpenRouter API key is not configured. Would you like to configure it now?",
                "Configuration Required",
                Messages.getQuestionIcon()
            )
            if (result == Messages.YES) {
                openSettings(project)
            }
            return
        }

        // Get file name for language detection
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val fileName = virtualFile?.name

        // Get full file content for context
        val document = editor.document
        val fullFileContent = document.text

        // Get selection position for later code replacement
        val selectionStart = editor.selectionModel.selectionStart
        val selectionEnd = editor.selectionModel.selectionEnd
        val startLine = document.getLineNumber(selectionStart)
        val endLine = document.getLineNumber(selectionEnd)

        // Get surrounding context (20 lines before and after)
        val contextStartLine = maxOf(0, startLine - 20)
        val contextEndLine = minOf(document.lineCount - 1, endLine + 20)
        val contextStartOffset = document.getLineStartOffset(contextStartLine)
        val contextEndOffset = document.getLineEndOffset(contextEndLine)
        val surroundingContext = document.getText(com.intellij.openapi.util.TextRange(contextStartOffset, contextEndOffset))

        // Open tool window and trigger extraction
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow("PSQL Query Tester")

        toolWindow?.let { tw ->
            tw.show {
                // Get the QueryTesterToolWindow instance
                val queryTester = project.getUserData(QueryTesterToolWindow.KEY)
                queryTester?.extractAndTestQuery(
                    selectedCode = selectedText,
                    fileName = fileName,
                    fullFileContent = fullFileContent,
                    surroundingContext = surroundingContext,
                    selectionStartOffset = selectionStart,
                    selectionEndOffset = selectionEnd
                )
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.hasSelection() == true

        e.presentation.isEnabled = hasSelection
        e.presentation.isVisible = true
    }

    private fun openSettings(project: Project) {
        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, "PSQL Query Tester")
    }
}
