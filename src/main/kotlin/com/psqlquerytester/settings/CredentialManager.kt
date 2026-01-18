package com.psqlquerytester.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import java.io.File

object CredentialManager {

    private const val SUBSYSTEM = "PsqlQueryTester"

    private val openRouterKeyAttributes = CredentialAttributes(
        generateServiceName(SUBSYSTEM, "OpenRouterApiKey")
    )

    private val connectionStringAttributes = CredentialAttributes(
        generateServiceName(SUBSYSTEM, "PostgresConnectionString")
    )

    fun getOpenRouterApiKey(): String? {
        // First try PasswordSafe
        val stored = PasswordSafe.instance.getPassword(openRouterKeyAttributes)
        if (!stored.isNullOrBlank()) {
            return stored
        }

        // Fall back to .env file in workspace
        return getEnvValue("OPENROUTER_API_KEY")
    }

    fun setOpenRouterApiKey(key: String) {
        PasswordSafe.instance.setPassword(openRouterKeyAttributes, key)
    }

    fun getConnectionString(): String? {
        // First try PasswordSafe
        val stored = PasswordSafe.instance.getPassword(connectionStringAttributes)
        if (!stored.isNullOrBlank()) {
            return stored
        }

        // Fall back to .env file
        return getEnvValue("POSTGRES_CONNECTION_STRING")
            ?: getEnvValue("DATABASE_URL")
    }

    fun setConnectionString(connectionString: String) {
        PasswordSafe.instance.setPassword(connectionStringAttributes, connectionString)
    }

    fun clearCredentials() {
        PasswordSafe.instance.setPassword(openRouterKeyAttributes, null)
        PasswordSafe.instance.setPassword(connectionStringAttributes, null)
    }

    private fun getEnvValue(key: String): String? {
        // Check system environment first
        System.getenv(key)?.let { return it }

        // Try to find .env file in common locations
        val envFiles = listOf(
            File(System.getProperty("user.dir"), ".env"),
            File(System.getProperty("user.home"), ".env")
        )

        for (envFile in envFiles) {
            if (envFile.exists()) {
                try {
                    envFile.readLines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("$key=")) {
                            val value = trimmed.substringAfter("=").trim()
                            // Remove surrounding quotes if present
                            return value.removeSurrounding("\"").removeSurrounding("'")
                        }
                    }
                } catch (e: Exception) {
                    // Ignore file read errors
                }
            }
        }

        return null
    }

    fun hasOpenRouterApiKey(): Boolean = !getOpenRouterApiKey().isNullOrBlank()

    fun hasConnectionString(): Boolean = !getConnectionString().isNullOrBlank()
}
