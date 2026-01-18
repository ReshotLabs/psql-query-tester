package com.psqlquerytester.db

import com.psqlquerytester.api.QueryParameter
import com.psqlquerytester.settings.PluginSettings
import java.sql.ResultSet
import java.sql.Types

data class QueryResult(
    val columns: List<String>,
    val rows: List<List<Any?>>,
    val rowCount: Int,
    val durationMs: Long,
    val affectedRows: Int = 0,
    val isSelect: Boolean = true,
    val error: String? = null
)

object QueryExecutor {

    /**
     * Convert PostgreSQL-style $1, $2 parameters to JDBC-style ? parameters
     */
    private fun convertToJdbcParams(sql: String): String {
        // Replace $1, $2, $3, etc. with ?
        return sql.replace(Regex("""\$\d+"""), "?")
    }

    /**
     * Execute a simple query without parameters (used for AI-generated parameter value queries)
     */
    fun executeSimple(sql: String): QueryResult {
        val startTime = System.currentTimeMillis()

        if (sql.isBlank()) {
            return QueryResult(
                columns = emptyList(),
                rows = emptyList(),
                rowCount = 0,
                durationMs = 0,
                error = "No SQL query to execute."
            )
        }

        val connection = ConnectionManager.getConnection()
            ?: return QueryResult(
                columns = emptyList(),
                rows = emptyList(),
                rowCount = 0,
                durationMs = 0,
                error = "Database not connected."
            )

        try {
            val settings = PluginSettings.getInstance()
            connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.queryTimeout = settings.queryTimeoutSeconds

                    val isSelect = sql.trim().uppercase().startsWith("SELECT")

                    if (isSelect) {
                        stmt.executeQuery(sql).use { rs ->
                            val result = processResultSet(rs, 1) // Only need first row
                            val duration = System.currentTimeMillis() - startTime
                            return result.copy(durationMs = duration)
                        }
                    } else {
                        return QueryResult(
                            columns = emptyList(),
                            rows = emptyList(),
                            rowCount = 0,
                            durationMs = System.currentTimeMillis() - startTime,
                            error = "AI assist queries must be SELECT statements."
                        )
                    }
                }
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            return QueryResult(
                columns = emptyList(),
                rows = emptyList(),
                rowCount = 0,
                durationMs = duration,
                error = "Query failed: ${e.message}"
            )
        }
    }

    fun execute(
        sql: String,
        parameters: List<QueryParameter>,
        parameterValues: Map<String, Any?>
    ): QueryResult {
        val startTime = System.currentTimeMillis()

        if (sql.isBlank()) {
            return QueryResult(
                columns = emptyList(),
                rows = emptyList(),
                rowCount = 0,
                durationMs = 0,
                error = "No SQL query to execute. The query appears to be empty."
            )
        }

        val connection = ConnectionManager.getConnection()
            ?: return QueryResult(
                columns = emptyList(),
                rows = emptyList(),
                rowCount = 0,
                durationMs = 0,
                error = "Database not connected. Please configure connection in Settings > PSQL Query Tester."
            )

        // Convert PostgreSQL-style $1, $2 parameters to JDBC-style ? parameters
        val jdbcSql = convertToJdbcParams(sql)

        try {
            val settings = PluginSettings.getInstance()
            connection.use { conn ->
                conn.prepareStatement(jdbcSql).use { stmt ->
                    // Set query timeout
                    stmt.queryTimeout = settings.queryTimeoutSeconds

                    // Bind parameters
                    parameters.forEach { param ->
                        val value = parameterValues[param.name]
                        setParameter(stmt, param.position, param.type, value)
                    }

                    // Determine if this is a SELECT query
                    val isSelect = sql.trim().uppercase().startsWith("SELECT")

                    if (isSelect) {
                        stmt.executeQuery().use { rs ->
                            val result = processResultSet(rs, settings.maxResultRows)
                            val duration = System.currentTimeMillis() - startTime
                            return result.copy(durationMs = duration)
                        }
                    } else {
                        val affectedRows = stmt.executeUpdate()
                        val duration = System.currentTimeMillis() - startTime
                        return QueryResult(
                            columns = listOf("Affected Rows"),
                            rows = listOf(listOf(affectedRows)),
                            rowCount = 1,
                            durationMs = duration,
                            affectedRows = affectedRows,
                            isSelect = false
                        )
                    }
                }
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            return QueryResult(
                columns = emptyList(),
                rows = emptyList(),
                rowCount = 0,
                durationMs = duration,
                error = "Query execution failed: ${e.message}"
            )
        }
    }

    private fun setParameter(
        stmt: java.sql.PreparedStatement,
        position: Int,
        type: String,
        value: Any?
    ) {
        if (value == null || (value is String && value.isBlank())) {
            stmt.setNull(position, getSqlType(type))
            return
        }

        when (type.lowercase()) {
            "integer", "int", "int4", "bigint", "int8" -> {
                val intVal = when (value) {
                    is Number -> value.toLong()
                    is String -> value.toLongOrNull() ?: 0L
                    else -> 0L
                }
                stmt.setLong(position, intVal)
            }
            "boolean", "bool" -> {
                val boolVal = when (value) {
                    is Boolean -> value
                    is String -> value.lowercase() in listOf("true", "1", "yes", "t")
                    else -> false
                }
                stmt.setBoolean(position, boolVal)
            }
            "numeric", "decimal", "float", "double", "real", "float4", "float8" -> {
                val doubleVal = when (value) {
                    is Number -> value.toDouble()
                    is String -> value.toDoubleOrNull() ?: 0.0
                    else -> 0.0
                }
                stmt.setDouble(position, doubleVal)
            }
            "timestamp", "timestamptz", "timestamp with time zone" -> {
                val tsVal = when (value) {
                    is java.sql.Timestamp -> value
                    is String -> java.sql.Timestamp.valueOf(value.replace("T", " ").replace("Z", ""))
                    else -> java.sql.Timestamp(System.currentTimeMillis())
                }
                stmt.setTimestamp(position, tsVal)
            }
            "date" -> {
                val dateVal = when (value) {
                    is java.sql.Date -> value
                    is String -> java.sql.Date.valueOf(value)
                    else -> java.sql.Date(System.currentTimeMillis())
                }
                stmt.setDate(position, dateVal)
            }
            "uuid" -> {
                val uuidVal = when (value) {
                    is java.util.UUID -> value
                    is String -> java.util.UUID.fromString(value)
                    else -> java.util.UUID.randomUUID()
                }
                stmt.setObject(position, uuidVal)
            }
            "jsonb", "json" -> {
                stmt.setObject(position, value.toString(), Types.OTHER)
            }
            else -> {
                // Default to string
                stmt.setString(position, value.toString())
            }
        }
    }

    private fun getSqlType(type: String): Int {
        return when (type.lowercase()) {
            "integer", "int", "int4" -> Types.INTEGER
            "bigint", "int8" -> Types.BIGINT
            "boolean", "bool" -> Types.BOOLEAN
            "numeric", "decimal" -> Types.NUMERIC
            "float", "double", "real", "float4", "float8" -> Types.DOUBLE
            "timestamp", "timestamptz", "timestamp with time zone" -> Types.TIMESTAMP
            "date" -> Types.DATE
            "uuid" -> Types.OTHER
            "jsonb", "json" -> Types.OTHER
            else -> Types.VARCHAR
        }
    }

    private fun processResultSet(rs: ResultSet, maxRows: Int): QueryResult {
        val metaData = rs.metaData
        val columnCount = metaData.columnCount

        if (columnCount == 0) {
            return QueryResult(
                columns = listOf("Result"),
                rows = listOf(listOf("Query executed successfully (no columns returned)")),
                rowCount = 1,
                durationMs = 0,
                isSelect = true
            )
        }

        val columns = (1..columnCount).map { metaData.getColumnName(it) }
        val rows = mutableListOf<List<Any?>>()

        var count = 0
        while (rs.next() && count < maxRows) {
            val row = (1..columnCount).map { idx ->
                val value = rs.getObject(idx)
                // Convert some types to more readable formats
                when (value) {
                    is java.sql.Timestamp -> value.toString()
                    is java.sql.Date -> value.toString()
                    is java.sql.Time -> value.toString()
                    is ByteArray -> "[binary data: ${value.size} bytes]"
                    is org.postgresql.util.PGobject -> value.value
                    else -> value
                }
            }
            rows.add(row)
            count++
        }

        if (rows.isEmpty()) {
            return QueryResult(
                columns = columns,
                rows = listOf(listOf("(No rows returned)")),
                rowCount = 0,
                durationMs = 0,
                isSelect = true
            )
        }

        return QueryResult(
            columns = columns,
            rows = rows,
            rowCount = rows.size,
            durationMs = 0,
            isSelect = true
        )
    }
}
