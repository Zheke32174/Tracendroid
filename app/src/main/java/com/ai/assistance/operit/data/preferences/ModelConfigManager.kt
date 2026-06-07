package com.ai.assistance.operit.data.preferences

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.R
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.operit.data.model.CustomParameterData
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.ModelConfigSummary
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ParameterCategory
import com.ai.assistance.operit.data.model.ParameterValueType
import com.ai.assistance.operit.data.model.StandardModelParameters
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ApiKeyInfo
import com.ai.assistance.operit.data.preferences.credentials.CredentialVault
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// 为ModelConfig创建专用的DataStore
private val Context.modelConfigDataStore: DataStore<Preferences> by
        preferencesDataStore(name = "model_configs")

// 获取ApiPreferences的DataStore
private val Context.apiDataStore: DataStore<Preferences> by
        preferencesDataStore(name = "api_settings")

class ModelConfigManager(private val context: Context) {

    // 提供context访问器
    val appContext: Context
        get() = context

    // 定义key
    companion object {
        // 配置相关key
        val CONFIG_LIST_KEY = stringPreferencesKey("config_list")

        // 默认值
        const val DEFAULT_CONFIG_ID = "default"
        const val DEFAULT_CONFIG_NAME = "model_config_default_name"

        // Default API provider type
        private val DEFAULT_API_PROVIDER_TYPE = ApiProviderType.DEEPSEEK

        /** § 4.9 vault store name. Per-config keys live under cfg:<id>:apiKey etc. */
        private const val VAULT_STORE = "model_config_credentials"
    }

    // Json解析器，支持宽松模式
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // § 4.9 — API keys (single + pool) live in CredentialVault, not in the DataStore
    // blob. The blob on disk carries blank apiKey + blank pool[*].key; the vault is
    // keyed on (configId, slot). Migration of legacy blobs happens lazily at first
    // read; subsequent reads see blanks on disk and pull from the vault.
    private val credentialVault = CredentialVault(context, VAULT_STORE)

    private fun vkApiKey(configId: String): String = "cfg:${configId}:apiKey"
    private fun vkPoolKey(configId: String, keyInfoId: String): String =
        "cfg:${configId}:pool:${keyInfoId}"

    /**
     * Returns a copy of [config] with every secret field blanked. The result is what
     * lands in DataStore JSON. The matching [persistSecrets] writes the originals to
     * the vault.
     */
    private fun stripSecrets(config: ModelConfigData): ModelConfigData {
        return config.copy(
            apiKey = "",
            apiKeyPool = config.apiKeyPool.map { it.copy(key = "") },
        )
    }

    /** Persist the single + pool secrets for [config] to the vault. */
    private fun persistSecrets(config: ModelConfigData) {
        credentialVault.put(vkApiKey(config.id), config.apiKey.ifBlank { null })
        for (info in config.apiKeyPool) {
            credentialVault.put(vkPoolKey(config.id, info.id), info.key.ifBlank { null })
        }
    }

    /**
     * Reverse of [stripSecrets] + [persistSecrets]. If the on-disk blob still has
     * secrets (an older app version wrote them), we treat that as the legacy source:
     * the secrets we read from the blob go to the vault and the blob is rewritten
     * blank on the next save. Idempotent.
     */
    private fun hydrateSecrets(configId: String, blob: ModelConfigData): ModelConfigData {
        val singleFromVault = credentialVault.get(vkApiKey(configId))
        val resolvedSingle = if (!blob.apiKey.isBlank()) {
            // Legacy: blob still has the secret — copy to vault.
            credentialVault.put(vkApiKey(configId), blob.apiKey)
            blob.apiKey
        } else {
            singleFromVault.orEmpty()
        }
        val resolvedPool = blob.apiKeyPool.map { info ->
            if (!info.key.isBlank()) {
                credentialVault.put(vkPoolKey(configId, info.id), info.key)
                info
            } else {
                info.copy(key = credentialVault.get(vkPoolKey(configId, info.id)).orEmpty())
            }
        }
        return blob.copy(apiKey = resolvedSingle, apiKeyPool = resolvedPool)
    }

    // 获取所有配置ID列表
    val configListFlow: Flow<List<String>> =
            context.modelConfigDataStore.data.map { preferences ->
                val configList = preferences[CONFIG_LIST_KEY] ?: ""
                if (configList.isEmpty()) emptyList()
                else json.decodeFromString<List<String>>(configList)
            }

    // 删除获取当前活跃配置ID的流

    // 初始化，确保至少有一个默认配置
    suspend fun initializeIfNeeded() {
        // 检查配置列表，如果为空则创建默认配置
        // This is important for first-time users
        val configList = configListFlow.first()
        if (configList.isEmpty()) {
            val defaultConfig = createFreshDefaultConfig()
            saveConfigToDataStore(defaultConfig)

            // 保存配置列表，移除活跃ID
            context.modelConfigDataStore.edit { preferences ->
                preferences[CONFIG_LIST_KEY] = json.encodeToString(listOf(DEFAULT_CONFIG_ID))
            }
        } else {
            AppLogger.d("CONFIG_TIMING", "配置列表不为空，跳过初始化")
        }
    }

    // 从原有ApiPreferences创建默认配置
    private fun createFreshDefaultConfig(): ModelConfigData {
        return ModelConfigData(
                id = DEFAULT_CONFIG_ID,
                name = context.getString(R.string.model_config_default_name),
                apiKey = ApiPreferences.DEFAULT_API_KEY,
                apiEndpoint = ApiPreferences.DEFAULT_API_ENDPOINT,
                modelName = ApiPreferences.DEFAULT_MODEL_NAME,
                apiProviderType = DEFAULT_API_PROVIDER_TYPE,
                apiProviderTypeId = DEFAULT_API_PROVIDER_TYPE.name,
                hasCustomParameters = false,
                maxTokensEnabled = false,
                temperatureEnabled = false,
                topPEnabled = false,
                topKEnabled = false,
                presencePenaltyEnabled = false,
                frequencyPenaltyEnabled = false,
                repetitionPenaltyEnabled = false,
                maxTokens = StandardModelParameters.DEFAULT_MAX_TOKENS,
                temperature = StandardModelParameters.DEFAULT_TEMPERATURE,
                topP = StandardModelParameters.DEFAULT_TOP_P,
                topK = StandardModelParameters.DEFAULT_TOP_K,
                presencePenalty = StandardModelParameters.DEFAULT_PRESENCE_PENALTY,
                frequencyPenalty = StandardModelParameters.DEFAULT_FREQUENCY_PENALTY,
                repetitionPenalty = StandardModelParameters.DEFAULT_REPETITION_PENALTY,
                customParameters = "[]"
        )
    }

    // 保存配置 — secrets to vault, rest of blob (with blanks where secrets were) to DataStore.
    suspend fun saveModelConfig(config: ModelConfigData) {
        persistSecrets(config)
        val configKey = stringPreferencesKey("config_${config.id}")
        context.modelConfigDataStore.edit { preferences ->
            preferences[configKey] = json.encodeToString(stripSecrets(config))
        }
    }

    // 从DataStore加载配置 — hydrate secrets from vault on the way back.
    private suspend fun loadConfigFromDataStore(configId: String): ModelConfigData? {
        val configKey = stringPreferencesKey("config_${configId}")
        return context.modelConfigDataStore.data.first().let { preferences ->
            val configJson = preferences[configKey]
            val parsed = if (configJson != null) {
                try {
                    json.decodeFromString<ModelConfigData>(configJson)
                } catch (e: Exception) {
                    if (configId == DEFAULT_CONFIG_ID) {
                        createFreshDefaultConfig()
                    } else {
                        ModelConfigData(id = configId, name = context.getString(R.string.model_config_config_id, configId))
                    }
                }
            } else {
                if (configId == DEFAULT_CONFIG_ID) {
                    createFreshDefaultConfig()
                } else {
                    ModelConfigData(id = configId, name = context.getString(R.string.model_config_config_id, configId))
                }
            }
            hydrateSecrets(configId, parsed)
        }
    }

    // 将配置保存到DataStore
    private suspend fun saveConfigToDataStore(config: ModelConfigData) {
        persistSecrets(config)
        val configKey = stringPreferencesKey("config_${config.id}")
        context.modelConfigDataStore.edit { preferences ->
            preferences[configKey] = json.encodeToString(stripSecrets(config))
        }
    }

    private suspend fun updateConfigInternal(
            configId: String,
            transform: (ModelConfigData) -> ModelConfigData
    ): ModelConfigData {
        val configKey = stringPreferencesKey("config_${configId}")
        var updated: ModelConfigData? = null
        context.modelConfigDataStore.edit { preferences ->
            val current =
                    run {
                        val configJson = preferences[configKey]
                        val parsed = if (configJson != null) {
                            try {
                                json.decodeFromString<ModelConfigData>(configJson)
                            } catch (e: Exception) {
                                if (configId == DEFAULT_CONFIG_ID) {
                                    createFreshDefaultConfig()
                                } else {
                                    ModelConfigData(id = configId, name = context.getString(R.string.model_config_config_id, configId))
                                }
                            }
                        } else {
                            if (configId == DEFAULT_CONFIG_ID) {
                                createFreshDefaultConfig()
                            } else {
                                ModelConfigData(id = configId, name = context.getString(R.string.model_config_config_id, configId))
                            }
                        }
                        hydrateSecrets(configId, parsed)
                    }

            val newConfig = transform(current)
            persistSecrets(newConfig)
            preferences[configKey] = json.encodeToString(stripSecrets(newConfig))
            updated = newConfig
        }
        return updated ?: ModelConfigData(id = configId, name = context.getString(R.string.model_config_config_id, configId))
    }

    // 获取指定ID的配置
    fun getModelConfigFlow(configId: String): Flow<ModelConfigData> {
        return context.modelConfigDataStore.data.map { preferences ->
            val config = loadConfigFromDataStore(configId) ?: ModelConfigData(id = configId, name = context.getString(R.string.model_config_config_id, configId))
            config
        }
    }

    // 获取指定ID的配置的非Flow版本
    suspend fun getModelConfig(configId: String): ModelConfigData? {
        return loadConfigFromDataStore(configId)
    }

    // 更新API Key池的当前索引
    suspend fun updateConfigKeyIndex(configId: String, newIndex: Int) {
        updateConfigInternal(configId) { it.copy(currentKeyIndex = newIndex) }
    }

    // 获取所有配置的摘要信息
    suspend fun getAllConfigSummaries(): List<ModelConfigSummary> {
        val configIds = configListFlow.first()
        val summaries = mutableListOf<ModelConfigSummary>()

        for (id in configIds) {
            val config = getModelConfigFlow(id).first()
            summaries.add(
                    ModelConfigSummary(
                            id = config.id,
                            name = config.name,
                            modelName = config.modelName,
                            apiEndpoint = config.apiEndpoint
                    )
            )
        }

        return summaries
    }

    // 创建新配置
    suspend fun createConfig(name: String): String {
        val configId = UUID.randomUUID().toString()
        val configList = configListFlow.first().toMutableList()

        val newConfig =
                ModelConfigData(
                        id = configId,
                        name = name,
                        apiProviderType = ApiProviderType.OPENAI_GENERIC,
                        apiProviderTypeId = ApiProviderType.OPENAI_GENERIC.name,
                        enableToolCall = true
                )

        // 保存新配置
        saveConfigToDataStore(newConfig)

        // 更新配置列表
        configList.add(configId)
        context.modelConfigDataStore.edit { preferences ->
            preferences[CONFIG_LIST_KEY] = json.encodeToString(configList)
        }

        return configId
    }

    // 删除配置
    suspend fun deleteConfig(configId: String) {
        if (configId == DEFAULT_CONFIG_ID) {
            // 不允许删除默认配置
            return
        }

        // § 4.9 — also drop vault entries (single key + every pool slot we know about).
        // Load the config first so we have the pool key IDs.
        val configForCleanup = loadConfigFromDataStore(configId)
        credentialVault.remove(vkApiKey(configId))
        configForCleanup?.apiKeyPool?.forEach { info ->
            credentialVault.remove(vkPoolKey(configId, info.id))
        }

        val configList = configListFlow.first().toMutableList()
        configList.remove(configId)
        context.modelConfigDataStore.edit { preferences ->
            preferences.remove(stringPreferencesKey("config_${configId}"))
            preferences[CONFIG_LIST_KEY] = json.encodeToString(configList)
        }
    }

    // 更新配置基本信息（名称等）
    suspend fun updateConfigBase(configId: String, name: String): ModelConfigData {
        return updateConfigInternal(configId) { it.copy(name = name) }
    }

    // 更新模型配置
    suspend fun updateModelConfig(
            configId: String,
            apiKey: String,
            apiEndpoint: String,
            modelName: String
    ): ModelConfigData {
        return updateConfigInternal(configId) {
            it.copy(apiKey = apiKey, apiEndpoint = apiEndpoint, modelName = modelName)
        }
    }

    // 更新模型配置 - 包含API提供商类型
    suspend fun updateModelConfig(
            configId: String,
            apiKey: String,
            apiEndpoint: String,
            modelName: String,
            apiProviderType: com.ai.assistance.operit.data.model.ApiProviderType,
            apiProviderTypeId: String = apiProviderType.name
    ): ModelConfigData {
        return updateConfigInternal(configId) {
            it.copy(
                    apiKey = apiKey,
                    apiEndpoint = apiEndpoint,
                    modelName = modelName,
                    apiProviderType = apiProviderType,
                    apiProviderTypeId = apiProviderTypeId
            )
        }
    }

    // 更新模型配置 - 包含API提供商类型和MNN配置
    suspend fun updateModelConfig(
            configId: String,
            apiKey: String,
            apiEndpoint: String,
            modelName: String,
            apiProviderType: com.ai.assistance.operit.data.model.ApiProviderType,
            apiProviderTypeId: String = apiProviderType.name,
            mnnForwardType: Int,
            mnnThreadCount: Int
    ): ModelConfigData {
        return updateConfigInternal(configId) {
            it.copy(
                    apiKey = apiKey,
                    apiEndpoint = apiEndpoint,
                    modelName = modelName,
                    apiProviderType = apiProviderType,
                    apiProviderTypeId = apiProviderTypeId,
                    mnnForwardType = mnnForwardType,
                    mnnThreadCount = mnnThreadCount
            )
        }
    }

    suspend fun updateApiSettingsFull(
            configId: String,
            apiKey: String,
            apiEndpoint: String,
            modelName: String,
            apiProviderType: ApiProviderType,
            apiProviderTypeId: String = apiProviderType.name,
            mnnForwardType: Int,
            mnnThreadCount: Int,
            llamaThreadCount: Int,
            llamaContextSize: Int,
            llamaGpuLayers: Int,
            enableDirectImageProcessing: Boolean,
            enableDirectAudioProcessing: Boolean,
            enableDirectVideoProcessing: Boolean,
            enableGoogleSearch: Boolean,
            enableToolCall: Boolean
    ): ModelConfigData {
        return updateConfigInternal(configId) {
            it.copy(
                    apiKey = apiKey,
                    apiEndpoint = apiEndpoint,
                    modelName = modelName,
                    apiProviderType = apiProviderType,
                    apiProviderTypeId = apiProviderTypeId,
                    mnnForwardType = mnnForwardType,
                    mnnThreadCount = mnnThreadCount,
                    llamaThreadCount = llamaThreadCount.coerceAtLeast(1),
                    llamaContextSize = llamaContextSize.coerceAtLeast(1),
                    llamaGpuLayers = llamaGpuLayers.coerceAtLeast(0),
                    enableDirectImageProcessing = enableDirectImageProcessing,
                    enableDirectAudioProcessing = enableDirectAudioProcessing,
                    enableDirectVideoProcessing = enableDirectVideoProcessing,
                    enableGoogleSearch = enableGoogleSearch,
                    enableToolCall = enableToolCall
            )
        }
    }

    suspend fun updateCustomHeaders(configId: String, customHeaders: String): ModelConfigData {
        return updateConfigInternal(configId) {
            it.copy(customHeaders = customHeaders)
        }
    }

    suspend fun updateRequestQueueSettings(
            configId: String,
            requestLimitPerMinute: Int,
            maxConcurrentRequests: Int
    ): ModelConfigData {
        return updateConfigInternal(configId) {
            it.copy(
                    requestLimitPerMinute = requestLimitPerMinute.coerceAtLeast(0),
                    maxConcurrentRequests = maxConcurrentRequests.coerceAtLeast(0)
            )
        }
    }

    suspend fun updateApiKeyPoolSettings(
            configId: String,
            useMultipleApiKeys: Boolean,
            apiKeyPool: List<ApiKeyInfo>
    ): ModelConfigData {
        return updateConfigInternal(configId) {
            it.copy(
                    useMultipleApiKeys = useMultipleApiKeys,
                    apiKeyPool = apiKeyPool
            )
        }
    }

    // 更新自定义参数
    suspend fun updateCustomParameters(configId: String, parametersJson: String): ModelConfigData {
        return updateConfigInternal(configId) {
            it.copy(
                    customParameters = parametersJson,
                    hasCustomParameters = parametersJson.isNotBlank() && parametersJson != "[]"
            )
        }
    }

    // 更新参数 - 新增方法
    suspend fun updateParameters(configId: String, parameters: List<ModelParameter<*>>) {
        // 提取自定义参数并序列化
        val customParams = parameters.filter { it.isCustom }
        val customParamsJson = if (customParams.isNotEmpty()) {
            val customParamsData = customParams.map { it.toCustomParameterData() }
            json.encodeToString(customParamsData)
        } else {
            "[]"
        }

        updateConfigInternal(configId) { current ->
            current.copy(
                    maxTokens =
                            parameters.find { it.id == "max_tokens" }?.currentValue as Int?
                                    ?: current.maxTokens,
                    maxTokensEnabled =
                            parameters.find { it.id == "max_tokens" }?.isEnabled
                                    ?: current.maxTokensEnabled,
                    temperature =
                            parameters.find { it.id == "temperature" }?.currentValue as Float?
                                    ?: current.temperature,
                    temperatureEnabled =
                            parameters.find { it.id == "temperature" }?.isEnabled
                                    ?: current.temperatureEnabled,
                    topP =
                            parameters.find { it.id == "top_p" }?.currentValue as Float?
                                    ?: current.topP,
                    topPEnabled =
                            parameters.find { it.id == "top_p" }?.isEnabled
                                    ?: current.topPEnabled,
                    topK =
                            parameters.find { it.id == "top_k" }?.currentValue as Int?
                                    ?: current.topK,
                    topKEnabled =
                            parameters.find { it.id == "top_k" }?.isEnabled
                                    ?: current.topKEnabled,
                    presencePenalty =
                            parameters.find { it.id == "presence_penalty" }?.currentValue as Float?
                                    ?: current.presencePenalty,
                    presencePenaltyEnabled =
                            parameters.find { it.id == "presence_penalty" }?.isEnabled
                                    ?: current.presencePenaltyEnabled,
                    frequencyPenalty =
                            parameters.find { it.id == "frequency_penalty" }?.currentValue as Float?
                                    ?: current.frequencyPenalty,
                    frequencyPenaltyEnabled =
                            parameters.find { it.id == "frequency_penalty" }?.isEnabled
                                    ?: current.frequencyPenaltyEnabled,
                    repetitionPenalty =
                            parameters.find { it.id == "repetition_penalty" }?.currentValue as Float?
                                    ?: current.repetitionPenalty,
                    repetitionPenaltyEnabled =
                            parameters.find { it.id == "repetition_penalty" }?.isEnabled
                                    ?: current.repetitionPenaltyEnabled,
                    customParameters = customParamsJson,
                    hasCustomParameters = customParams.isNotEmpty()
            )
        }
    }

    // 更新图片直接处理配置
    suspend fun updateDirectImageProcessing(configId: String, enableDirectImageProcessing: Boolean): ModelConfigData {
        return updateConfigInternal(configId) {
            it.copy(enableDirectImageProcessing = enableDirectImageProcessing)
        }
    }

    suspend fun updateDirectAudioProcessing(configId: String, enableDirectAudioProcessing: Boolean): ModelConfigData {
        return updateConfigInternal(configId) {
            it.copy(enableDirectAudioProcessing = enableDirectAudioProcessing)
        }
    }

    suspend fun updateDirectVideoProcessing(configId: String, enableDirectVideoProcessing: Boolean): ModelConfigData {
        return updateConfigInternal(configId) {
            it.copy(enableDirectVideoProcessing = enableDirectVideoProcessing)
        }
    }

    // 更新 Google Search Grounding 配置 (仅Gemini支持)
    suspend fun updateGoogleSearch(configId: String, enableGoogleSearch: Boolean): ModelConfigData {
        return updateConfigInternal(configId) { it.copy(enableGoogleSearch = enableGoogleSearch) }
    }

    // 更新 Tool Call 配置
    suspend fun updateToolCall(configId: String, enableToolCall: Boolean): ModelConfigData {
        return updateConfigInternal(configId) {
            it.copy(enableToolCall = enableToolCall)
        }
    }

    suspend fun updateContextSettings(
            configId: String,
            contextLength: Float,
            maxContextLength: Float,
            enableMaxContextMode: Boolean
    ): ModelConfigData {
        return updateConfigInternal(configId) {
            it.copy(
                    contextLength = contextLength,
                    maxContextLength = maxContextLength,
                    enableMaxContextMode = enableMaxContextMode
            )
        }
    }

    suspend fun updateSummarySettings(
            configId: String,
            enableSummary: Boolean,
            summaryTokenThreshold: Float,
            enableSummaryByMessageCount: Boolean,
            summaryMessageCountThreshold: Int
    ): ModelConfigData {
        return updateConfigInternal(configId) {
            it.copy(
                    enableSummary = enableSummary,
                    summaryTokenThreshold = summaryTokenThreshold,
                    enableSummaryByMessageCount = enableSummaryByMessageCount,
                    summaryMessageCountThreshold = summaryMessageCountThreshold
            )
        }
    }

    /**
     * 根据配置ID获取完整的模型参数列表（包括标准和自定义参数）
     * @param configId 配置ID
     * @return 模型参数列表
     */
    suspend fun getModelParametersForConfig(configId: String): List<ModelParameter<*>> {
        val config = getModelConfigFlow(configId).first()
        val parameters = mutableListOf<ModelParameter<*>>()

        // 映射标准参数
        StandardModelParameters.DEFINITIONS.forEach { def ->
            val (currentValue, isEnabled) =
                    when (def.id) {
                        "max_tokens" -> config.maxTokens to config.maxTokensEnabled
                        "temperature" -> config.temperature to config.temperatureEnabled
                        "top_p" -> config.topP to config.topPEnabled
                        "top_k" -> config.topK to config.topKEnabled
                        "presence_penalty" -> config.presencePenalty to config.presencePenaltyEnabled
                        "frequency_penalty" ->
                                config.frequencyPenalty to config.frequencyPenaltyEnabled
                        "repetition_penalty" ->
                                config.repetitionPenalty to config.repetitionPenaltyEnabled
                        else -> null to null
                    }

            if (currentValue != null && isEnabled != null) {
                parameters.add(
                        ModelParameter(
                                id = def.id,
                                name = def.name,
                                apiName = def.apiName,
                                description = def.description,
                                defaultValue = def.defaultValue,
                                currentValue = currentValue,
                                isEnabled = isEnabled,
                                valueType = def.valueType,
                                minValue = def.minValue,
                                maxValue = def.maxValue,
                                category = def.category
                        )
                )
            }
        }

        // 添加自定义参数
        if (config.hasCustomParameters &&
                        config.customParameters.isNotBlank() &&
                        config.customParameters != "[]"
        ) {
            try {
                val customParamsData =
                        json.decodeFromString<List<com.ai.assistance.operit.data.model.CustomParameterData>>(
                                config.customParameters
                        )
                customParamsData.forEach { data ->
                    val valueType = ParameterValueType.valueOf(data.valueType)
                    val category = ParameterCategory.valueOf(data.category)

                    val convertedParam =
                            when (valueType) {
                                ParameterValueType.INT ->
                                        ModelParameter(
                                                id = data.id,
                                                name = data.name,
                                                apiName = data.apiName,
                                                description = data.description,
                                                defaultValue = data.defaultValue.toInt(),
                                                currentValue = data.currentValue.toInt(),
                                                isEnabled = data.isEnabled,
                                                valueType = valueType,
                                                minValue = data.minValue?.toInt(),
                                                maxValue = data.maxValue?.toInt(),
                                                category = category,
                                                isCustom = true
                                        )
                                ParameterValueType.FLOAT ->
                                        ModelParameter(
                                                id = data.id,
                                                name = data.name,
                                                apiName = data.apiName,
                                                description = data.description,
                                                defaultValue = data.defaultValue.toFloat(),
                                                currentValue = data.currentValue.toFloat(),
                                                isEnabled = data.isEnabled,
                                                valueType = valueType,
                                                minValue = data.minValue?.toFloat(),
                                                maxValue = data.maxValue?.toFloat(),
                                                category = category,
                                                isCustom = true
                                        )
                                ParameterValueType.BOOLEAN ->
                                        ModelParameter(
                                                id = data.id,
                                                name = data.name,
                                                apiName = data.apiName,
                                                description = data.description,
                                                defaultValue = data.defaultValue.toBoolean(),
                                                currentValue = data.currentValue.toBoolean(),
                                                isEnabled = data.isEnabled,
                                                valueType = valueType,
                                                category = category,
                                                isCustom = true
                                        )
                                ParameterValueType.STRING ->
                                        ModelParameter(
                                                id = data.id,
                                                name = data.name,
                                                apiName = data.apiName,
                                                description = data.description,
                                                defaultValue = data.defaultValue,
                                                currentValue = data.currentValue,
                                                isEnabled = data.isEnabled,
                                                valueType = valueType,
                                                category = category,
                                                isCustom = true
                                        )
                                ParameterValueType.OBJECT ->
                                        ModelParameter(
                                                id = data.id,
                                                name = data.name,
                                                apiName = data.apiName,
                                                description = data.description,
                                                defaultValue = data.defaultValue,
                                                currentValue = data.currentValue,
                                                isEnabled = data.isEnabled,
                                                valueType = valueType,
                                                category = category,
                                                isCustom = true
                                        )
                            }
                    parameters.add(convertedParam)
                }
            } catch (e: Exception) {
                AppLogger.e("ModelConfigManager", "Failed to parse or convert custom parameters", e)
            }
        }

        return parameters
    }
    
    /**
     * 导出所有模型配置为JSON字符串
     * @return JSON格式的所有配置数据
     */
    suspend fun exportAllConfigs(): String {
        val configList = configListFlow.first()
        val allConfigs = mutableListOf<ModelConfigData>()
        
        for (configId in configList) {
            val config = getModelConfigFlow(configId).first()
            allConfigs.add(config)
        }
        
        val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
        
        return json.encodeToString(allConfigs)
    }
    
    /**
     * 从JSON字符串导入模型配置
     * @param jsonContent JSON格式的配置数据
     * @return 导入结果统计 (新增数量, 更新数量, 跳过数量)
     */
    suspend fun importConfigs(jsonContent: String): Triple<Int, Int, Int> {
        try {
            val importedConfigs = json.decodeFromString<List<ModelConfigData>>(jsonContent)
            val existingConfigList = configListFlow.first().toMutableList()
            val existingConfigIds = existingConfigList.toSet()
            
            var newCount = 0
            var updatedCount = 0
            var skippedCount = 0
            
            for (config in importedConfigs) {
                if (config.id.isEmpty() || config.name.isEmpty()) {
                    skippedCount++
                    continue
                }
                
                // 保存配置
                saveConfigToDataStore(config)
                
                if (existingConfigIds.contains(config.id)) {
                    updatedCount++
                } else {
                    newCount++
                    existingConfigList.add(config.id)
                }
            }
            
            // 更新配置列表
            if (newCount > 0) {
                context.modelConfigDataStore.edit { preferences ->
                    preferences[CONFIG_LIST_KEY] = json.encodeToString(existingConfigList)
                }
            }
            
            return Triple(newCount, updatedCount, skippedCount)
        } catch (e: Exception) {
            AppLogger.e("ModelConfigManager", "导入配置失败", e)
            throw Exception(context.getString(R.string.model_config_import_failed, e.localizedMessage ?: e.message))
        }
    }
}

// 扩展函数，用于将ModelParameter转换为CustomParameterData
private fun ModelParameter<*>.toCustomParameterData(): com.ai.assistance.operit.data.model.CustomParameterData {
    return com.ai.assistance.operit.data.model.CustomParameterData(
        id = this.id,
        name = this.name,
        apiName = this.apiName,
        description = this.description,
        defaultValue = this.defaultValue.toString(),
        currentValue = this.currentValue.toString(),
        isEnabled = this.isEnabled,
        valueType = this.valueType.name,
        minValue = this.minValue?.toString(),
        maxValue = this.maxValue?.toString(),
        category = this.category.name
    )
}
