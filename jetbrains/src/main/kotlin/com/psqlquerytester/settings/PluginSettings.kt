package com.psqlquerytester.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service
@State(
    name = "PsqlQueryTesterSettings",
    storages = [Storage("PsqlQueryTesterSettings.xml")]
)
class PluginSettings : PersistentStateComponent<PluginSettings.State> {

    data class State(
        var maxResultRows: Int = 1000,
        var queryTimeoutSeconds: Int = 30,
        var sslMode: String = "prefer",
        var defaultLanguage: String = "elixir",
        var modelOverride: String = ""  // Empty means use default
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var maxResultRows: Int
        get() = state.maxResultRows
        set(value) {
            state.maxResultRows = value
        }

    var queryTimeoutSeconds: Int
        get() = state.queryTimeoutSeconds
        set(value) {
            state.queryTimeoutSeconds = value
        }

    var sslMode: String
        get() = state.sslMode
        set(value) {
            state.sslMode = value
        }

    var defaultLanguage: String
        get() = state.defaultLanguage
        set(value) {
            state.defaultLanguage = value
        }

    var modelOverride: String
        get() = state.modelOverride
        set(value) {
            state.modelOverride = value
        }

    fun getModel(): String {
        return if (state.modelOverride.isNotBlank()) state.modelOverride else DEFAULT_MODEL
    }

    companion object {
        const val DEFAULT_MODEL = "anthropic/claude-opus-4.5"

        fun getInstance(): PluginSettings {
            return ApplicationManager.getApplication().getService(PluginSettings::class.java)
        }
    }
}
