package dev.pleiades.masamune.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BYOK credential store.
 *
 * Honest boundary: this is app-private SharedPreferences, not hardware-backed. It is readable
 * by root and by a device backup that includes app data — `allowBackup` is off in the manifest
 * for exactly that reason. It is not an EncryptedSharedPreferences and does not claim to be.
 */
class ProviderStore private constructor(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("masamune_provider", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<ProviderConfig> = _config.asStateFlow()

    fun save(config: ProviderConfig) {
        prefs.edit()
            .putString(KEY_KIND, config.kind.name)
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_API_KEY, config.apiKey)
            .putString(KEY_MODEL, config.model)
            .putString(KEY_SYSTEM, config.systemPrompt)
            .apply()
        _config.value = config
    }

    fun clearKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
        _config.value = _config.value.copy(apiKey = "")
    }

    private fun load(): ProviderConfig {
        val kind = runCatching {
            ProviderKind.valueOf(prefs.getString(KEY_KIND, null) ?: ProviderKind.OPENAI_COMPATIBLE.name)
        }.getOrDefault(ProviderKind.OPENAI_COMPATIBLE)
        return ProviderConfig(
            kind = kind,
            baseUrl = prefs.getString(KEY_BASE_URL, null) ?: kind.defaultBaseUrl,
            apiKey = prefs.getString(KEY_API_KEY, null).orEmpty(),
            model = prefs.getString(KEY_MODEL, null) ?: kind.defaultModel,
            systemPrompt = prefs.getString(KEY_SYSTEM, null) ?: DEFAULT_SYSTEM_PROMPT,
        )
    }

    companion object {
        private const val KEY_KIND = "kind"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_SYSTEM = "system_prompt"

        const val DEFAULT_SYSTEM_PROMPT =
            "You are Masamune, an on-device assistant. You have no tools in this build: you " +
                "cannot read files, run shell commands or browse. If the user asks for any of " +
                "those, say so plainly and tell them to use the Files or Shell surface."

        @Volatile
        private var instance: ProviderStore? = null

        fun get(context: Context): ProviderStore =
            instance ?: synchronized(this) {
                instance ?: ProviderStore(context).also { instance = it }
            }
    }
}
