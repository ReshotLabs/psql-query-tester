import * as vscode from 'vscode';
import { OpenRouterClient, ExtractedQuery, QueryParameter, OptimizationSuggestion } from './openRouterClient';
import { QueryExecutor, QueryResult, getSchema } from './queryExecutor';

export class QueryTesterPanel {
    public static currentPanel: QueryTesterPanel | undefined;
    public static readonly viewType = 'psqlQueryTester';

    private readonly _panel: vscode.WebviewPanel;
    private readonly _extensionUri: vscode.Uri;
    private _disposables: vscode.Disposable[] = [];

    private openRouterClient: OpenRouterClient;
    private queryExecutor: QueryExecutor;
    private currentQuery: ExtractedQuery | undefined;
    private lastExecutionTimeMs: number = 0;
    private originalCode: string = '';
    private detectedLanguage: string = '';

    public static createOrShow(extensionUri: vscode.Uri, selectedCode: string, fileName: string) {
        const column = vscode.ViewColumn.Beside;

        if (QueryTesterPanel.currentPanel) {
            QueryTesterPanel.currentPanel._panel.reveal(column);
            QueryTesterPanel.currentPanel.extractQuery(selectedCode, fileName);
            return;
        }

        const panel = vscode.window.createWebviewPanel(
            QueryTesterPanel.viewType,
            'PSQL Query Tester',
            column,
            {
                enableScripts: true,
                retainContextWhenHidden: true
            }
        );

        QueryTesterPanel.currentPanel = new QueryTesterPanel(panel, extensionUri);
        QueryTesterPanel.currentPanel.extractQuery(selectedCode, fileName);
    }

    public static revive(panel: vscode.WebviewPanel, extensionUri: vscode.Uri) {
        QueryTesterPanel.currentPanel = new QueryTesterPanel(panel, extensionUri);
    }

    private constructor(panel: vscode.WebviewPanel, extensionUri: vscode.Uri) {
        this._panel = panel;
        this._extensionUri = extensionUri;
        this.openRouterClient = new OpenRouterClient();
        this.queryExecutor = new QueryExecutor();

        this._update();

        this._panel.onDidDispose(() => this.dispose(), null, this._disposables);

        this._panel.webview.onDidReceiveMessage(
            async message => {
                switch (message.command) {
                    case 'executeQuery':
                        await this.executeQuery(message.parameterValues);
                        break;
                    case 'testOptimization':
                        await this.testOptimization(message.suggestion, message.parameterValues);
                        break;
                    case 'aiAssist':
                        await this.aiAssist(message.paramName, message.paramType);
                        break;
                }
            },
            null,
            this._disposables
        );
    }

    private detectLanguage(fileName: string, code: string): string {
        const ext = fileName.split('.').pop()?.toLowerCase();
        switch (ext) {
            case 'ex':
            case 'exs':
                return 'elixir';
            case 'py':
                return 'python';
            case 'js':
                return 'javascript';
            case 'ts':
                return 'typescript';
            case 'rb':
                return 'ruby';
            case 'go':
                return 'go';
            case 'java':
                return 'java';
            case 'sql':
                return 'sql';
            default:
                return 'unknown';
        }
    }

    private async extractQuery(selectedCode: string, fileName: string) {
        this.originalCode = selectedCode;
        this.detectedLanguage = this.detectLanguage(fileName, selectedCode);

        this._panel.webview.postMessage({ command: 'setLoading', loading: true });

        try {
            const dbSchema = await getSchema();
            const result = await this.openRouterClient.extractQuery(selectedCode, this.detectedLanguage, dbSchema);
            this.currentQuery = result;

            this._panel.webview.postMessage({
                command: 'setExtractedQuery',
                query: result
            });
        } catch (e: any) {
            this._panel.webview.postMessage({
                command: 'setError',
                error: 'Extraction failed: ' + e.message
            });
        }
    }

    private async executeQuery(parameterValues: Record<string, any>) {
        if (!this.currentQuery || !this.currentQuery.query) {
            return;
        }

        this._panel.webview.postMessage({ command: 'setExecuting', executing: true });

        try {
            const result = await this.queryExecutor.execute(
                this.currentQuery.query,
                this.currentQuery.parameters,
                parameterValues
            );

            this.lastExecutionTimeMs = result.durationMs;

            this._panel.webview.postMessage({
                command: 'setQueryResult',
                result
            });

            // Get optimizations if successful
            if (!result.error && result.isSelect) {
                await this.getOptimizations();
            }
        } catch (e: any) {
            this._panel.webview.postMessage({
                command: 'setQueryResult',
                result: { error: 'Execution failed: ' + e.message }
            });
        }
    }

    private async getOptimizations() {
        if (!this.currentQuery) return;

        this._panel.webview.postMessage({ command: 'setOptimizationsLoading', loading: true });

        try {
            const dbSchema = await getSchema();
            const response = await this.openRouterClient.suggestOptimizations(
                this.currentQuery.query,
                this.lastExecutionTimeMs,
                dbSchema,
                this.currentQuery.parameters
            );

            this._panel.webview.postMessage({
                command: 'setOptimizations',
                optimizations: response,
                originalTime: this.lastExecutionTimeMs
            });
        } catch (e: any) {
            this._panel.webview.postMessage({
                command: 'setOptimizations',
                optimizations: { error: 'Failed to get optimizations: ' + e.message }
            });
        }
    }

    private async testOptimization(suggestion: OptimizationSuggestion, parameterValues: Record<string, any>) {
        // Find which parameters are used in the optimized query
        const usedPositions = new Set<number>();
        const matches = suggestion.query.matchAll(/\$(\d+)/g);
        for (const match of matches) {
            usedPositions.add(parseInt(match[1]));
        }

        const paramsToUse = suggestion.parameters.length > 0
            ? suggestion.parameters
            : (this.currentQuery?.parameters.filter(p => usedPositions.has(p.position)) || []);

        try {
            const result = await this.queryExecutor.execute(
                suggestion.query,
                paramsToUse,
                parameterValues
            );

            this._panel.webview.postMessage({
                command: 'setOptimizationResult',
                suggestionIndex: suggestion,
                result,
                originalTime: this.lastExecutionTimeMs
            });
        } catch (e: any) {
            this._panel.webview.postMessage({
                command: 'setOptimizationResult',
                suggestionIndex: suggestion,
                result: { error: 'Test failed: ' + e.message }
            });
        }
    }

    private async aiAssist(paramName: string, paramType: string) {
        const description = await vscode.window.showInputBox({
            prompt: 'Describe what value you want for "' + paramName + '" (type: ' + paramType + ')',
            placeHolder: 'e.g., "the oldest user in the database" or "any product from last 30 days"'
        });

        if (!description) return;

        this._panel.webview.postMessage({
            command: 'setAiAssistLoading',
            paramName,
            loading: true
        });

        try {
            const dbSchema = await getSchema();
            const queryResponse = await this.openRouterClient.generateParameterValueQuery(
                paramName,
                paramType,
                description,
                dbSchema
            );

            if (queryResponse.error) {
                vscode.window.showErrorMessage('AI Assist failed: ' + queryResponse.error);
                this._panel.webview.postMessage({
                    command: 'setAiAssistLoading',
                    paramName,
                    loading: false
                });
                return;
            }

            const result = await this.queryExecutor.executeSimple(queryResponse.query);

            if (result.error) {
                vscode.window.showErrorMessage('AI query failed: ' + result.error);
                this._panel.webview.postMessage({
                    command: 'setAiAssistLoading',
                    paramName,
                    loading: false
                });
                return;
            }

            const value = result.rows[0]?.[0];
            if (value !== undefined && value !== null) {
                this._panel.webview.postMessage({
                    command: 'setParameterValue',
                    paramName,
                    value: String(value)
                });
                vscode.window.showInformationMessage('AI filled "' + paramName + '" = ' + value);
            } else {
                vscode.window.showWarningMessage('AI query returned no results');
            }
        } catch (e: any) {
            vscode.window.showErrorMessage('AI Assist error: ' + e.message);
        }

        this._panel.webview.postMessage({
            command: 'setAiAssistLoading',
            paramName,
            loading: false
        });
    }

    private _update() {
        this._panel.webview.html = this._getHtmlForWebview();
    }

    private _getHtmlForWebview(): string {
        return `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>PSQL Query Tester</title>
    <style>
        body {
            font-family: var(--vscode-font-family);
            padding: 10px;
            color: var(--vscode-foreground);
            background: var(--vscode-editor-background);
        }
        .section {
            margin-bottom: 20px;
            border: 1px solid var(--vscode-panel-border);
            border-radius: 4px;
            padding: 10px;
        }
        .section-title {
            font-weight: bold;
            margin-bottom: 10px;
            color: var(--vscode-textLink-foreground);
        }
        textarea, input {
            width: 100%;
            background: var(--vscode-input-background);
            color: var(--vscode-input-foreground);
            border: 1px solid var(--vscode-input-border);
            padding: 8px;
            font-family: monospace;
            border-radius: 4px;
            box-sizing: border-box;
        }
        textarea {
            min-height: 100px;
            resize: vertical;
        }
        button {
            background: var(--vscode-button-background);
            color: var(--vscode-button-foreground);
            border: none;
            padding: 8px 16px;
            cursor: pointer;
            border-radius: 4px;
            margin-right: 8px;
            margin-top: 8px;
        }
        button:hover {
            background: var(--vscode-button-hoverBackground);
        }
        button:disabled {
            opacity: 0.5;
            cursor: not-allowed;
        }
        .param-row {
            display: flex;
            align-items: center;
            margin-bottom: 8px;
            gap: 8px;
        }
        .param-row label {
            min-width: 150px;
        }
        .param-row input {
            flex: 1;
        }
        .ai-btn {
            padding: 4px 8px;
            font-size: 12px;
        }
        table {
            width: 100%;
            border-collapse: collapse;
            font-size: 13px;
        }
        th, td {
            border: 1px solid var(--vscode-panel-border);
            padding: 6px 8px;
            text-align: left;
        }
        th {
            background: var(--vscode-editor-selectionBackground);
        }
        .error {
            color: var(--vscode-errorForeground);
        }
        .success {
            color: var(--vscode-testing-iconPassed);
        }
        .status {
            margin-top: 10px;
            font-size: 13px;
        }
        .optimization {
            border: 1px solid var(--vscode-panel-border);
            padding: 10px;
            margin-top: 10px;
            border-radius: 4px;
        }
        .opt-title {
            font-weight: bold;
            margin-bottom: 8px;
        }
        .opt-explanation {
            font-style: italic;
            margin-bottom: 8px;
            color: var(--vscode-descriptionForeground);
        }
        .loading {
            opacity: 0.6;
        }
    </style>
</head>
<body>
    <div class="section">
        <div class="section-title">Extracted Query</div>
        <textarea id="queryText" readonly placeholder="Select code and extract a query..."></textarea>
        <div id="queryError" class="error" style="display:none;"></div>
    </div>

    <div class="section">
        <div class="section-title">Parameters</div>
        <div id="parameters"></div>
        <button id="executeBtn" disabled>Execute Query</button>
    </div>

    <div class="section">
        <div class="section-title">Results</div>
        <div id="results">No results yet</div>
        <div id="resultStatus" class="status"></div>
    </div>

    <div class="section">
        <div class="section-title">Optimizations</div>
        <div id="optimizations">Run a query to see optimization suggestions</div>
    </div>

    <script>
        const vscode = acquireVsCodeApi();
        let currentParams = [];
        let currentOptimizations = [];

        document.getElementById('executeBtn').addEventListener('click', () => {
            const values = {};
            currentParams.forEach(p => {
                const input = document.getElementById('param-' + p.name);
                if (input) {
                    values[p.name] = input.type === 'checkbox' ? input.checked : input.value;
                }
            });
            vscode.postMessage({ command: 'executeQuery', parameterValues: values });
        });

        window.addEventListener('message', event => {
            const message = event.data;
            switch (message.command) {
                case 'setLoading':
                    document.getElementById('queryText').value = message.loading ? 'Extracting query...' : '';
                    break;

                case 'setExtractedQuery':
                    const query = message.query;
                    if (query.error && !query.query) {
                        document.getElementById('queryText').value = '';
                        document.getElementById('queryError').textContent = query.error;
                        document.getElementById('queryError').style.display = 'block';
                    } else {
                        document.getElementById('queryText').value = query.formattedQuery || query.query;
                        document.getElementById('queryError').style.display = 'none';
                        renderParameters(query.parameters || []);
                        document.getElementById('executeBtn').disabled = false;
                    }
                    break;

                case 'setError':
                    document.getElementById('queryError').textContent = message.error;
                    document.getElementById('queryError').style.display = 'block';
                    break;

                case 'setExecuting':
                    document.getElementById('executeBtn').disabled = message.executing;
                    document.getElementById('executeBtn').textContent = message.executing ? 'Executing...' : 'Execute Query';
                    break;

                case 'setQueryResult':
                    renderResults(message.result);
                    document.getElementById('executeBtn').disabled = false;
                    document.getElementById('executeBtn').textContent = 'Execute Query';
                    break;

                case 'setOptimizationsLoading':
                    document.getElementById('optimizations').innerHTML = message.loading ? 'Finding optimizations...' : '';
                    break;

                case 'setOptimizations':
                    renderOptimizations(message.optimizations, message.originalTime);
                    break;

                case 'setOptimizationResult':
                    updateOptimizationResult(message.suggestionIndex, message.result, message.originalTime);
                    break;

                case 'setParameterValue':
                    const input = document.getElementById('param-' + message.paramName);
                    if (input) input.value = message.value;
                    break;

                case 'setAiAssistLoading':
                    const btn = document.getElementById('ai-' + message.paramName);
                    if (btn) {
                        btn.disabled = message.loading;
                        btn.textContent = message.loading ? '...' : 'AI';
                    }
                    break;
            }
        });

        function renderParameters(params) {
            currentParams = params;
            const container = document.getElementById('parameters');
            if (!params.length) {
                container.innerHTML = '<div>No parameters detected</div>';
                return;
            }
            container.innerHTML = params.map(p => {
                const inputType = p.type.toLowerCase().includes('bool') ? 'checkbox' : 'text';
                return '<div class="param-row">' +
                    '<label>' + p.name + ' (' + p.type + '):</label>' +
                    '<input type="' + inputType + '" id="param-' + p.name + '" />' +
                    (inputType !== 'checkbox' ? '<button class="ai-btn" id="ai-' + p.name + '" onclick="aiAssist(\'' + p.name + '\', \'' + p.type + '\')">AI</button>' : '') +
                    '</div>';
            }).join('');
        }

        function aiAssist(paramName, paramType) {
            vscode.postMessage({ command: 'aiAssist', paramName, paramType });
        }

        function renderResults(result) {
            const container = document.getElementById('results');
            const status = document.getElementById('resultStatus');

            if (result.error) {
                container.innerHTML = '<div class="error">' + result.error + '</div>';
                status.textContent = '';
                return;
            }

            if (!result.columns || !result.columns.length) {
                container.innerHTML = '<div>No data</div>';
                status.textContent = '';
                return;
            }

            let html = '<table><thead><tr>' +
                result.columns.map(c => '<th>' + c + '</th>').join('') +
                '</tr></thead><tbody>';

            result.rows.forEach(row => {
                html += '<tr>' + row.map(cell => '<td>' + cell + '</td>').join('') + '</tr>';
            });

            html += '</tbody></table>';
            container.innerHTML = html;

            const statusText = result.isSelect
                ? result.rowCount + ' rows (' + result.durationMs + 'ms)'
                : result.affectedRows + ' affected (' + result.durationMs + 'ms)';
            status.innerHTML = '<span class="success">' + statusText + '</span>';
        }

        function renderOptimizations(opt, originalTime) {
            const container = document.getElementById('optimizations');
            currentOptimizations = opt.suggestions || [];

            if (opt.error) {
                container.innerHTML = '<div class="error">' + opt.error + '</div>';
                return;
            }

            if (!opt.suggestions || !opt.suggestions.length) {
                container.innerHTML = '<div class="success">No optimization suggestions - query looks good!</div>';
                return;
            }

            let html = '';
            if (opt.analysis) {
                html += '<div style="margin-bottom:10px;"><strong>Analysis:</strong> ' + opt.analysis + '</div>';
            }

            opt.suggestions.forEach((s, i) => {
                html += '<div class="optimization" id="opt-' + i + '">' +
                    '<div class="opt-title">Option ' + (i + 1) + ': ' + s.expectedImprovement + '</div>' +
                    '<div class="opt-explanation">' + s.explanation + '</div>' +
                    '<textarea id="opt-query-' + i + '">' + (s.formattedQuery || s.query) + '</textarea>' +
                    '<button onclick="testOptimization(' + i + ')">Test Query</button>' +
                    '<span id="opt-result-' + i + '"></span>' +
                    '</div>';
            });

            if (opt.indexSuggestions && opt.indexSuggestions.length) {
                html += '<div style="margin-top:15px;"><strong>Suggested Indexes:</strong><pre>' +
                    opt.indexSuggestions.join('\\n\\n') + '</pre></div>';
            }

            container.innerHTML = html;
        }

        function testOptimization(index) {
            const suggestion = { ...currentOptimizations[index] };
            const textarea = document.getElementById('opt-query-' + index);
            if (textarea) {
                suggestion.query = textarea.value;
                suggestion.formattedQuery = textarea.value;
            }

            const values = {};
            currentParams.forEach(p => {
                const input = document.getElementById('param-' + p.name);
                if (input) {
                    values[p.name] = input.type === 'checkbox' ? input.checked : input.value;
                }
            });

            document.getElementById('opt-result-' + index).textContent = ' Testing...';
            vscode.postMessage({ command: 'testOptimization', suggestion, parameterValues: values });
        }

        function updateOptimizationResult(suggestion, result, originalTime) {
            const index = currentOptimizations.findIndex(s => s.query === suggestion.query || s.formattedQuery === suggestion.formattedQuery);
            const resultSpan = document.getElementById('opt-result-' + (index >= 0 ? index : 0));

            if (!resultSpan) return;

            if (result.error) {
                resultSpan.innerHTML = ' <span class="error">' + result.error + '</span>';
                return;
            }

            let comparison = '';
            if (originalTime > 0) {
                const diff = originalTime - result.durationMs;
                if (diff > 0) {
                    const pct = Math.round((diff * 100) / originalTime);
                    comparison = ' (' + pct + '% faster)';
                } else if (diff < 0) {
                    const pct = Math.round((-diff * 100) / originalTime);
                    comparison = ' (' + pct + '% slower)';
                }
            }

            resultSpan.innerHTML = ' <span class="success">' + result.durationMs + 'ms' + comparison + ' - ' + result.rowCount + ' rows</span>';
        }
    </script>
</body>
</html>`;
    }

    public dispose() {
        QueryTesterPanel.currentPanel = undefined;
        this._panel.dispose();
        while (this._disposables.length) {
            const d = this._disposables.pop();
            if (d) d.dispose();
        }
    }
}
