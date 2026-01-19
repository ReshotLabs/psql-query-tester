import * as vscode from 'vscode';
import { QueryTesterPanel } from './webviewPanel';

export function activate(context: vscode.ExtensionContext) {
    console.log('PSQL Query Tester is now active');

    const extractCommand = vscode.commands.registerCommand(
        'psql-query-tester.extractQuery',
        () => {
            const editor = vscode.window.activeTextEditor;
            if (!editor) {
                vscode.window.showErrorMessage('No active editor');
                return;
            }

            const selection = editor.selection;
            const selectedText = editor.document.getText(selection);

            if (!selectedText) {
                vscode.window.showErrorMessage('Please select code containing a SQL query');
                return;
            }

            const fileName = editor.document.fileName;
            const fullFileContent = editor.document.getText();

            // Get surrounding context (20 lines before and after)
            const startLine = Math.max(0, selection.start.line - 20);
            const endLine = Math.min(editor.document.lineCount - 1, selection.end.line + 20);
            const contextRange = new vscode.Range(startLine, 0, endLine, editor.document.lineAt(endLine).text.length);
            const surroundingContext = editor.document.getText(contextRange);

            // Store selection offsets for later code replacement
            const selectionStartOffset = editor.document.offsetAt(selection.start);
            const selectionEndOffset = editor.document.offsetAt(selection.end);

            QueryTesterPanel.createOrShow(
                context.extensionUri,
                selectedText,
                fileName,
                fullFileContent,
                surroundingContext,
                selectionStartOffset,
                selectionEndOffset
            );
        }
    );

    context.subscriptions.push(extractCommand);

    // Register the webview panel serializer for persistence
    if (vscode.window.registerWebviewPanelSerializer) {
        vscode.window.registerWebviewPanelSerializer(QueryTesterPanel.viewType, {
            async deserializeWebviewPanel(webviewPanel: vscode.WebviewPanel, state: any) {
                QueryTesterPanel.revive(webviewPanel, context.extensionUri);
            }
        });
    }
}

export function deactivate() {}
