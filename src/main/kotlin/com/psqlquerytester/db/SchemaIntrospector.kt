package com.psqlquerytester.db

object SchemaIntrospector {

    data class TableInfo(
        val schema: String,
        val name: String,
        val columns: List<ColumnInfo>
    )

    data class ColumnInfo(
        val name: String,
        val type: String,
        val nullable: Boolean,
        val isPrimaryKey: Boolean = false
    )

    fun getSchema(): String? {
        val connection = ConnectionManager.getConnection() ?: return null

        try {
            connection.use { conn ->
                val tables = mutableListOf<TableInfo>()

                // Get all tables in public schema (and other non-system schemas)
                val tablesQuery = """
                    SELECT table_schema, table_name
                    FROM information_schema.tables
                    WHERE table_schema NOT IN ('pg_catalog', 'information_schema')
                    AND table_type = 'BASE TABLE'
                    ORDER BY table_schema, table_name
                    LIMIT 100
                """.trimIndent()

                conn.createStatement().use { stmt ->
                    stmt.executeQuery(tablesQuery).use { rs ->
                        while (rs.next()) {
                            val schema = rs.getString("table_schema")
                            val tableName = rs.getString("table_name")
                            val columns = getColumnsForTable(conn, schema, tableName)
                            tables.add(TableInfo(schema, tableName, columns))
                        }
                    }
                }

                return formatSchemaForPrompt(tables)
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun getColumnsForTable(
        conn: java.sql.Connection,
        schema: String,
        tableName: String
    ): List<ColumnInfo> {
        val columns = mutableListOf<ColumnInfo>()

        val columnsQuery = """
            SELECT
                c.column_name,
                c.data_type,
                c.is_nullable,
                CASE WHEN pk.column_name IS NOT NULL THEN true ELSE false END as is_pk
            FROM information_schema.columns c
            LEFT JOIN (
                SELECT ku.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage ku
                    ON tc.constraint_name = ku.constraint_name
                WHERE tc.constraint_type = 'PRIMARY KEY'
                    AND tc.table_schema = ?
                    AND tc.table_name = ?
            ) pk ON c.column_name = pk.column_name
            WHERE c.table_schema = ? AND c.table_name = ?
            ORDER BY c.ordinal_position
        """.trimIndent()

        conn.prepareStatement(columnsQuery).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, tableName)
            stmt.setString(3, schema)
            stmt.setString(4, tableName)

            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    columns.add(
                        ColumnInfo(
                            name = rs.getString("column_name"),
                            type = rs.getString("data_type"),
                            nullable = rs.getString("is_nullable") == "YES",
                            isPrimaryKey = rs.getBoolean("is_pk")
                        )
                    )
                }
            }
        }

        return columns
    }

    private fun formatSchemaForPrompt(tables: List<TableInfo>): String {
        if (tables.isEmpty()) {
            return "No tables found in database."
        }

        val sb = StringBuilder()
        sb.appendLine("DATABASE SCHEMA:")
        sb.appendLine("================")

        tables.forEach { table ->
            val fullName = if (table.schema == "public") table.name else "${table.schema}.${table.name}"
            sb.appendLine("\nTable: $fullName")
            sb.appendLine("Columns:")
            table.columns.forEach { col ->
                val pk = if (col.isPrimaryKey) " [PK]" else ""
                val nullable = if (col.nullable) "" else " NOT NULL"
                sb.appendLine("  - ${col.name}: ${col.type}$nullable$pk")
            }
        }

        return sb.toString()
    }
}
