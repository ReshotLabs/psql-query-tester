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
            QueryTesterPanel.createOrShow(context.extensionUri, selectedText, fileName);
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
