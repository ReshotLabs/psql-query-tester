import * as vscode from 'vscode';
import { Client } from 'pg';
import { QueryParameter } from './openRouterClient';

export interface QueryResult {
    columns: string[];
    rows: any[][];
    rowCount: number;
    durationMs: number;
    affectedRows: number;
    isSelect: boolean;
    error?: string;
}

export class QueryExecutor {
    private getConnectionString(): string | undefined {
        return vscode.workspace.getConfiguration('psqlQueryTester').get('connectionString');
    }

    private getTimeout(): number {
        return vscode.workspace.getConfiguration('psqlQueryTester').get('queryTimeout') || 30;
    }

    private getMaxRows(): number {
        return vscode.workspace.getConfiguration('psqlQueryTester').get('maxResultRows') || 1000;
    }

    async execute(sql: string, parameters: QueryParameter[], parameterValues: Record<string, any>): Promise<QueryResult> {
        const startTime = Date.now();

        if (!sql.trim()) {
            return {
                columns: [],
                rows: [],
                rowCount: 0,
                durationMs: 0,
                affectedRows: 0,
                isSelect: true,
                error: 'No SQL query to execute.'
            };
        }

        const connectionString = this.getConnectionString();
        if (!connectionString) {
            return {
                columns: [],
                rows: [],
                rowCount: 0,
                durationMs: 0,
                affectedRows: 0,
                isSelect: true,
                error: 'Database not connected. Please configure connection in Settings > PSQL Query Tester.'
            };
        }

        const client = new Client({ connectionString });

        try {
            await client.connect();

            // Set statement timeout
            const timeoutSec = this.getTimeout();
            await client.query('SET statement_timeout = \'' + timeoutSec + 's\'');

            // Build parameter values array in correct order
            const values = parameters
                .sort((a, b) => a.position - b.position)
                .map(p => this.convertValue(parameterValues[p.name], p.type));

            const isSelect = sql.trim().toUpperCase().startsWith('SELECT');

            const result = await client.query(sql, values);
            const durationMs = Date.now() - startTime;

            if (isSelect) {
                const columns = result.fields.map(f => f.name);
                const rows = result.rows.slice(0, this.getMaxRows()).map(row => 
                    columns.map(col => this.formatValue(row[col]))
                );

                return {
                    columns,
                    rows,
                    rowCount: result.rowCount || 0,
                    durationMs,
                    affectedRows: 0,
                    isSelect: true
                };
            } else {
                return {
                    columns: ['Affected Rows'],
                    rows: [[result.rowCount || 0]],
                    rowCount: 1,
                    durationMs,
                    affectedRows: result.rowCount || 0,
                    isSelect: false
                };
            }
        } catch (e: any) {
            return {
                columns: [],
                rows: [],
                rowCount: 0,
                durationMs: Date.now() - startTime,
                affectedRows: 0,
                isSelect: true,
                error: 'Query execution failed: ' + e.message
            };
        } finally {
            await client.end();
        }
    }

    async executeSimple(sql: string): Promise<QueryResult> {
        return this.execute(sql, [], {});
    }

    private convertValue(value: any, type: string): any {
        if (value === null || value === undefined || value === '') {
            return null;
        }

        switch (type.toLowerCase()) {
            case 'integer':
            case 'int':
            case 'int4':
            case 'bigint':
            case 'int8':
                return parseInt(value, 10);
            case 'boolean':
            case 'bool':
                return value === true || value === 'true' || value === '1';
            case 'numeric':
            case 'decimal':
            case 'float':
            case 'double':
            case 'real':
                return parseFloat(value);
            default:
                return value;
        }
    }

    private formatValue(value: any): string {
        if (value === null || value === undefined) {
            return 'NULL';
        }
        if (value instanceof Date) {
            return value.toISOString();
        }
        if (typeof value === 'object') {
            return JSON.stringify(value);
        }
        return String(value);
    }
}

export async function getSchema(): Promise<string | undefined> {
    const connectionString = vscode.workspace.getConfiguration('psqlQueryTester').get<string>('connectionString');
    if (!connectionString) {
        return undefined;
    }

    const client = new Client({ connectionString });

    try {
        await client.connect();

        const result = await client.query(`
            SELECT 
                t.table_name,
                c.column_name,
                c.data_type,
                c.is_nullable
            FROM information_schema.tables t
            JOIN information_schema.columns c 
                ON t.table_name = c.table_name 
                AND t.table_schema = c.table_schema
            WHERE t.table_schema = 'public'
                AND t.table_type = 'BASE TABLE'
            ORDER BY t.table_name, c.ordinal_position
        `);

        const tables: Record<string, string[]> = {};
        for (const row of result.rows) {
            const tableName = row.table_name;
            if (!tables[tableName]) {
                tables[tableName] = [];
            }
            const nullable = row.is_nullable === 'NO' ? ', NOT NULL' : '';
            tables[tableName].push(row.column_name + ' (' + row.data_type + nullable + ')');
        }

        let schema = 'DATABASE SCHEMA:\n\n';
        for (const [table, columns] of Object.entries(tables)) {
            schema += 'TABLE: ' + table + '\n';
            schema += columns.map(c => '  - ' + c).join('\n');
            schema += '\n\n';
        }

        return schema;
    } catch (e) {
        console.error('Failed to get schema:', e);
        return undefined;
    } finally {
        await client.end();
    }
}
