package com.ai.assistance.operit.ui.features.token.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.features.token.model.TabConfig
import com.ai.assistance.operit.ui.features.token.model.UrlConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.urlConfigDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "url_config")

class UrlConfigManager(private val context: Context) {
    companion object {
        private val URL_CONFIG_KEY = stringPreferencesKey("url_config")
        
        // Preset providers. The rule the user set:
        //   Google, Anthropic, OpenAI, DeepSeek, Nous Research -> ACCOUNT LOGIN.
        //   The user signs into their own account on the provider's real site
        //   inside the WebView; the session token is captured from that page.
        //   No client id, no OAuth-app registration, no pasted key.
        //   OpenCode -> the ONLY api-key provider (apiKeyOnly = true).
        //
        // signInUrl is the provider's real login page. apiKeyUrl is where a
        // signed-in user's key lives, which is also the page the capture script
        // reads the session token from. These endpoints are provider-owned and
        // move over time; each is the current best-known URL and should be
        // re-verified against the live site (see docs/PROVIDER-LOGIN-PORTAL.md).
        val PRESET_CONFIGS = listOf(
            UrlConfig(
                name = "Anthropic",
                signInUrl = "https://console.anthropic.com/login",
                apiKeyUrl = "https://console.anthropic.com/settings/keys",
                tabs = listOf(
                    TabConfig("API Keys", "https://console.anthropic.com/settings/keys"),
                    TabConfig("Usage", "https://console.anthropic.com/settings/usage"),
                    TabConfig("Billing", "https://console.anthropic.com/settings/billing"),
                    TabConfig("Account", "https://console.anthropic.com/settings/profile")
                )
            ),
            UrlConfig(
                name = "OpenAI",
                signInUrl = "https://platform.openai.com/login",
                apiKeyUrl = "https://platform.openai.com/api-keys",
                tabs = listOf(
                    TabConfig("API Keys", "https://platform.openai.com/api-keys"),
                    TabConfig("Usage", "https://platform.openai.com/usage"),
                    TabConfig("Billing", "https://platform.openai.com/account/billing"),
                    TabConfig("Account", "https://platform.openai.com/account")
                )
            ),
            UrlConfig(
                name = "Google",
                signInUrl = "https://aistudio.google.com/",
                apiKeyUrl = "https://aistudio.google.com/app/apikey",
                tabs = listOf(
                    TabConfig("API Keys", "https://aistudio.google.com/app/apikey"),
                    TabConfig("Studio", "https://aistudio.google.com/"),
                    TabConfig("Usage", "https://aistudio.google.com/app/usage"),
                    TabConfig("Account", "https://myaccount.google.com/")
                )
            ),
            UrlConfig(
                name = "DeepSeek",
                signInUrl = "https://platform.deepseek.com/sign_in",
                apiKeyUrl = "https://platform.deepseek.com/api_keys",
                tabs = listOf(
                    TabConfig("API Keys", "https://platform.deepseek.com/api_keys"),
                    TabConfig("Usage", "https://platform.deepseek.com/usage"),
                    TabConfig("Top Up", "https://platform.deepseek.com/top_up"),
                    TabConfig("Account", "https://platform.deepseek.com/profile")
                )
            ),
            UrlConfig(
                name = "Nous Research",
                signInUrl = "https://portal.nousresearch.com/login",
                apiKeyUrl = "https://portal.nousresearch.com/api-keys",
                tabs = listOf(
                    TabConfig("API Keys", "https://portal.nousresearch.com/api-keys"),
                    TabConfig("Usage", "https://portal.nousresearch.com/usage"),
                    TabConfig("Models", "https://portal.nousresearch.com/models"),
                    TabConfig("Account", "https://portal.nousresearch.com/account")
                )
            ),
            UrlConfig(
                name = "OpenCode",
                signInUrl = "https://opencode.ai/",
                apiKeyOnly = true,
                apiKeyUrl = "https://opencode.ai/docs/",
                tabs = emptyList()
            )
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    // 获取URL配置的Flow
    val urlConfigFlow: Flow<UrlConfig> = context.urlConfigDataStore.data.map { preferences ->
        val configJson = preferences[URL_CONFIG_KEY]
        if (configJson != null) {
            try {
                json.decodeFromString<UrlConfig>(configJson)
            } catch (e: Exception) {
                UrlConfig().localizePresetTabNames(context)
            }
        } else {
            UrlConfig().localizePresetTabNames(context)
        }
    }

    // 保存URL配置
    suspend fun saveUrlConfig(urlConfig: UrlConfig) {
        context.urlConfigDataStore.edit { preferences ->
            preferences[URL_CONFIG_KEY] = json.encodeToString(urlConfig)
        }
    }

    // 重置为默认配置
    suspend fun resetToDefault() {
        saveUrlConfig(UrlConfig().localizePresetTabNames(context))
    }
}

private fun UrlConfig.localizePresetTabNames(context: Context): UrlConfig {
    if (tabs.isEmpty()) return this

    val mappedTabs = tabs.map { tab ->
        val localizedTitle = when (tab.title) {
            "Chat", "聊天" -> context.getString(R.string.url_tab_chat)
            "Projects", "项目" -> context.getString(R.string.url_tab_projects)
            "Artifacts", "工件" -> context.getString(R.string.url_tab_artifacts)
            "Settings", "设置" -> context.getString(R.string.url_tab_settings)
            "Account", "账户" -> context.getString(R.string.url_tab_account)
            "History", "历史" -> context.getString(R.string.url_tab_history)
            "Help", "帮助" -> context.getString(R.string.url_tab_help)
            "Explore", "探索" -> context.getString(R.string.url_tab_explore)
            "Create", "创建" -> context.getString(R.string.url_tab_create)
            else -> tab.title
        }

        if (localizedTitle == tab.title) tab else TabConfig(title = localizedTitle, url = tab.url)
    }

    return copy(tabs = mappedTabs)
}