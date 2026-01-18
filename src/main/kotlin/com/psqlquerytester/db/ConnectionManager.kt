package com.psqlquerytester.db

import com.psqlquerytester.settings.CredentialManager
import com.psqlquerytester.settings.PluginSettings
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.DriverManager

object ConnectionManager {

    private var dataSource: HikariDataSource? = null
    private var currentConnectionString: String? = null
    private var driverLoaded = false

    private fun ensureDriverLoaded() {
        if (!driverLoaded) {
            // Explicitly load the PostgreSQL driver
            Class.forName("org.postgresql.Driver")
            driverLoaded = true
        }
    }

    @Synchronized
    fun initialize(connectionString: String? = null): Boolean {
        ensureDriverLoaded()
        val connStr = connectionString ?: CredentialManager.getConnectionString()
            ?: return false

        // If already initialized with same connection string, skip
        if (dataSource != null && currentConnectionString == connStr) {
            return true
        }

        // Close existing pool if any
        shutdown()

        try {
            val settings = PluginSettings.getInstance()

            val config = HikariConfig().apply {
                jdbcUrl = convertToJdbcUrl(connStr)
                maximumPoolSize = 3
                minimumIdle = 1
                connectionTimeout = 10_000
                idleTimeout = 300_000
                maxLifetime = 600_000

                // Add SSL mode if specified in connection string or settings
                if (!connStr.contains("sslmode=")) {
                    addDataSourceProperty("sslmode", settings.sslMode)
                }
            }

            dataSource = HikariDataSource(config)
            currentConnectionString = connStr
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun reinitialize(connectionString: String) {
        shutdown()
        initialize(connectionString)
    }

    fun getConnection(): Connection? {
        if (dataSource == null) {
            if (!initialize()) {
                return null
            }
        }
        return dataSource?.connection
    }

    fun testConnection(connectionString: String): Result<Boolean> {
        ensureDriverLoaded()
        var testDataSource: HikariDataSource? = null
        try {
            val jdbcUrl = convertToJdbcUrl(connectionString)
            val config = HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                maximumPoolSize = 1
                connectionTimeout = 10_000
            }
            testDataSource = HikariDataSource(config)
            testDataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT 1")
                }
            }
            return Result.success(true)
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            testDataSource?.close()
        }
    }

    @Synchronized
    fun shutdown() {
        dataSource?.close()
        dataSource = null
        currentConnectionString = null
    }

    fun isInitialized(): Boolean = dataSource != null

    /**
     * Converts a PostgreSQL connection URL to JDBC format
     * postgresql://user:pass@host:port/db -> jdbc:postgresql://host:port/db?user=user&password=pass
     */
    private fun convertToJdbcUrl(connectionString: String): String {
        // If already in JDBC format, return as-is
        if (connectionString.startsWith("jdbc:")) {
            return connectionString
        }

        // Parse postgresql:// format
        val regex = Regex("""postgres(?:ql)?://(?:([^:]+):([^@]+)@)?([^:/]+)(?::(\d+))?/([^?]+)(.*)""")
        val match = regex.matchEntire(connectionString)
            ?: return "jdbc:$connectionString" // Fallback: just prepend jdbc:

        val user = match.groupValues[1]
        val password = match.groupValues[2]
        val host = match.groupValues[3]
        val port = match.groupValues[4].ifEmpty { "5432" }
        val database = match.groupValues[5]
        val params = match.groupValues[6]

        val baseUrl = "jdbc:postgresql://$host:$port/$database"

        val allParams = mutableListOf<String>()
        if (user.isNotEmpty()) allParams.add("user=$user")
        if (password.isNotEmpty()) allParams.add("password=$password")

        // Add existing params (removing leading ?)
        if (params.isNotEmpty()) {
            allParams.add(params.removePrefix("?"))
        }

        return if (allParams.isNotEmpty()) {
            "$baseUrl?${allParams.joinToString("&")}"
        } else {
            baseUrl
        }
    }
}
