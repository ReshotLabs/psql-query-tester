import * as vscode from 'vscode';

const DEFAULT_MODEL = 'anthropic/claude-opus-4.5';

interface ChatMessage {
    role: string;
    content: string;
}

interface ExtractedQuery {
    query: string;
    formattedQuery: string;
    parameters: QueryParameter[];
    error?: string;
}

interface QueryParameter {
    name: string;
    type: string;
    position: number;
    originalVariable: string;
}

interface OptimizationSuggestion {
    query: string;
    formattedQuery: string;
    explanation: string;
    expectedImprovement: string;
    parameters: QueryParameter[];
}

interface OptimizationResponse {
    suggestions: OptimizationSuggestion[];
    indexSuggestions: string[];
    analysis: string;
    error?: string;
}

interface ParameterValueQuery {
    query: string;
    explanation: string;
    error?: string;
}

export class OpenRouterClient {
    private getApiKey(): string | undefined {
        return vscode.workspace.getConfiguration('psqlQueryTester').get('openRouterApiKey');
    }

    private getModel(): string {
        const override = vscode.workspace.getConfiguration('psqlQueryTester').get<string>('modelOverride');
        return override && override.trim() ? override.trim() : DEFAULT_MODEL;
    }

    async extractQuery(codeSnippet: string, language: string, dbSchema?: string): Promise<ExtractedQuery> {
        const apiKey = this.getApiKey();
        if (!apiKey) {
            return { query: '', formattedQuery: '', parameters: [], error: 'OpenRouter API key not configured. Go to Settings > PSQL Query Tester.' };
        }

        const schemaSection = dbSchema ? `\n\nIMPORTANT: Use ONLY the tables and columns from this database schema. If the code references a table or column that doesn't exist, note it in the error field.\n\n${dbSchema}` : '';

        const systemPrompt = `You are a PostgreSQL expert that extracts SQL queries from code. Your task is to:
1. Identify the SQL query in the provided code
2. Extract and format it as a clean PostgreSQL query
3. Detect any parameters/variables that need to be filled in
4. Convert language-specific placeholders to PostgreSQL $1, $2, etc. format
5. VALIDATE that all table and column names exist in the provided database schema
${schemaSection}
Return ONLY a valid JSON object (no markdown, no code blocks) with this exact structure:
{
    "query": "The raw SQL query with $1, $2, etc. for parameters",
    "formattedQuery": "The same query but nicely formatted with proper indentation",
    "parameters": [
        {
            "name": "descriptive_name",
            "type": "postgresql_type (text, integer, boolean, timestamp, uuid, jsonb, etc.)",
            "position": 1,
            "originalVariable": "original variable name from code"
        }
    ]
}

For Elixir/Ecto queries:
- Convert Ecto.Query syntax to raw SQL
- Map ^variable to positional parameters
- Handle fragment() calls
- Convert Ecto types to PostgreSQL types

If you cannot find a SQL query in the code, return:
{"query": "", "formattedQuery": "", "parameters": [], "error": "No SQL query found in the selected code"}

If the query references tables/columns not in the schema, return:
{"query": "", "formattedQuery": "", "parameters": [], "error": "Table or column not found: [name]. Available tables: [list]"}`;

        const userPrompt = `Extract the SQL query from this ${language} code:\n\n\`\`\`${language}\n${codeSnippet}\n\`\`\``;

        return this.callApi<ExtractedQuery>(systemPrompt, userPrompt, 2000, 0.1);
    }

    async suggestOptimizations(query: string, executionTimeMs: number, dbSchema: string | undefined, parameters: QueryParameter[]): Promise<OptimizationResponse> {
        const apiKey = this.getApiKey();
        if (!apiKey) {
            return { suggestions: [], indexSuggestions: [], analysis: '', error: 'OpenRouter API key not configured.' };
        }

        const schemaSection = dbSchema || 'Schema not available';

        const systemPrompt = `You are a PostgreSQL performance optimization expert. Analyze the given query and suggest optimizations.

DATABASE SCHEMA:
${schemaSection}

Return ONLY a valid JSON object (no markdown, no code blocks) with this structure:
{
    "analysis": "Brief analysis of the current query's performance characteristics",
    "suggestions": [
        {
            "query": "The optimized SQL query with $1, $2, etc. for parameters",
            "formattedQuery": "The same query nicely formatted",
            "explanation": "What this optimization does and why it's faster",
            "expectedImprovement": "e.g., '2-3x faster' or 'reduces full table scan'",
            "parameters": [
                {"name": "param_name", "type": "type", "position": 1, "originalVariable": ""}
            ]
        }
    ],
    "indexSuggestions": [
        "CREATE INDEX idx_name ON table(column) -- explanation"
    ]
}

Consider these optimization strategies:
1. Add appropriate indexes (suggest CREATE INDEX statements)
2. Rewrite JOINs for better performance
3. Use EXISTS instead of IN for subqueries
4. Add LIMIT if appropriate
5. Use covering indexes
6. Optimize WHERE clause order
7. Consider partitioning suggestions
8. Use EXPLAIN ANALYZE insights

Provide 1-3 query alternatives, ordered by expected improvement.
Keep the same parameters ($1, $2, etc.) as the original query.`;

        const userPrompt = `Optimize this PostgreSQL query:\n\n\`\`\`sql\n${query}\n\`\`\`\n\nCurrent execution time: ${executionTimeMs}ms\nParameters: ${parameters.map(p => `${p.name} (${p.type})`).join(', ')}\n\nSuggest faster alternatives and any helpful indexes.`;

        return this.callApi<OptimizationResponse>(systemPrompt, userPrompt, 3000, 0.2);
    }

    async generateParameterValueQuery(paramName: string, paramType: string, description: string, dbSchema: string | undefined): Promise<ParameterValueQuery> {
        const apiKey = this.getApiKey();
        if (!apiKey) {
            return { query: '', explanation: '', error: 'OpenRouter API key not configured.' };
        }

        const schemaSection = dbSchema || 'Schema not available';

        const systemPrompt = `You are a PostgreSQL expert. Generate a SQL query to find a value based on the user's description.

DATABASE SCHEMA:
${schemaSection}

Return ONLY a valid JSON object (no markdown, no code blocks) with this structure:
{
    "query": "SELECT query that returns a single value",
    "explanation": "Brief explanation of what the query does"
}

Rules:
1. The query MUST return exactly ONE row with ONE column
2. Use LIMIT 1 if needed to ensure a single result
3. The returned value should be of type: ${paramType}
4. If the request is ambiguous or impossible, return an error field instead

If unable to generate a valid query:
{"query": "", "explanation": "", "error": "Reason why the query cannot be generated"}`;

        const userPrompt = `Generate a PostgreSQL query to find a value for parameter "${paramName}" (type: ${paramType}).\n\nUser's description: ${description}\n\nThe query should return exactly one value that matches this description.`;

        return this.callApi<ParameterValueQuery>(systemPrompt, userPrompt, 1000, 0.1);
    }

    private async callApi<T>(systemPrompt: string, userPrompt: string, maxTokens: number, temperature: number): Promise<T> {
        const apiKey = this.getApiKey();
        
        const response = await fetch('https://openrouter.ai/api/v1/chat/completions', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${apiKey}`,
                'HTTP-Referer': 'https://github.com/ReshotLabs/psql-query-tester',
                'X-Title': 'PSQL Query Tester'
            },
            body: JSON.stringify({
                model: this.getModel(),
                messages: [
                    { role: 'system', content: systemPrompt },
                    { role: 'user', content: userPrompt }
                ],
                max_tokens: maxTokens,
                temperature: temperature
            })
        });

        const data = await response.json() as any;

        if (data.error) {
            return { error: `API Error: ${data.error.message}` } as T;
        }

        const content = data.choices?.[0]?.message?.content;
        if (!content) {
            return { error: 'No response from API' } as T;
        }

        try {
            const cleanContent = content
                .replace(/```json\s*/g, '')
                .replace(/```\s*/g, '')
                .trim();
            return JSON.parse(cleanContent) as T;
        } catch (e) {
            return { error: `Could not parse response: ${e}` } as T;
        }
    }
}

export { ExtractedQuery, QueryParameter, OptimizationSuggestion, OptimizationResponse, ParameterValueQuery };
