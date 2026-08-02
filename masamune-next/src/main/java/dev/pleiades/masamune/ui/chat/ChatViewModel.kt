package dev.pleiades.masamune.ui.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pleiades.masamune.ai.AiException
import dev.pleiades.masamune.ai.AiServiceFactory
import dev.pleiades.masamune.ai.PromptTurn
import dev.pleiades.masamune.ai.PromptTurnKind
import dev.pleiades.masamune.ai.ProviderStore
import dev.pleiades.masamune.core.capability.Capability
import dev.pleiades.masamune.core.capability.Caller
import dev.pleiades.masamune.core.capability.CapabilityGate
import dev.pleiades.masamune.core.capability.GateDecision
import dev.pleiades.masamune.core.decline.Decline
import dev.pleiades.masamune.core.decline.DeclineRegistry
import dev.pleiades.masamune.core.halt.HaltController
import dev.pleiades.masamune.data.ChatDatabase
import dev.pleiades.masamune.data.ChatEntity
import dev.pleiades.masamune.data.MessageEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class ChatUiState(
    val chatId: Long = 0L,
    val title: String = "",
    val messages: List<MessageEntity> = emptyList(),
    val streaming: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val providerModel: String = "",
    val providerConfigured: Boolean = false,
    val networkGranted: Boolean = false,
    val halted: Boolean = false,
)

/**
 * Chat harness.
 *
 * BYOK and provider-neutral: the only thing this class knows about a provider is the
 * [dev.pleiades.masamune.ai.AiService] seam and its `Flow<String>`. Persistence is Room.
 *
 * Honest about scope: there is no tool loop. The model cannot read files or run shell
 * commands from here, and the default system prompt says so to the model itself. The
 * capability gate still sits in front of the provider call (NETWORK), because "the harness
 * made an outbound request" is exactly the kind of effect the gate exists to mediate.
 */
class ChatViewModel(private val appContext: Context) : ViewModel() {

    private val db = ChatDatabase.get(appContext)
    private val providerStore = ProviderStore.get(appContext)
    private val gate = CapabilityGate.get(appContext)

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    val chats = db.chatDao().observeAll()

    private var streamJob: Job? = null
    private var messageJob: Job? = null

    init {
        viewModelScope.launch {
            providerStore.config.collect { cfg ->
                _state.value = _state.value.copy(
                    providerConfigured = cfg.isUsable,
                    providerModel = "${cfg.kind.label} · ${cfg.model}",
                    networkGranted = gate.isGranted(Caller.User, Capability.NETWORK),
                )
            }
        }
        viewModelScope.launch {
            HaltController.state.collect { s ->
                val halted = s is HaltController.State.Halted
                _state.value = _state.value.copy(halted = halted)
                if (halted) streamJob?.cancel()
            }
        }
        viewModelScope.launch {
            val existing = db.chatDao().observeAll()
            existing.collect { list ->
                if (_state.value.chatId == 0L && list.isNotEmpty()) {
                    openChat(list.first().id)
                }
                return@collect
            }
        }
    }

    fun refreshGrants() {
        _state.value = _state.value.copy(
            networkGranted = gate.isGranted(Caller.User, Capability.NETWORK),
        )
    }

    fun grantNetwork() {
        gate.grant(Caller.User, Capability.NETWORK)
        refreshGrants()
    }

    fun openChat(chatId: Long) {
        messageJob?.cancel()
        _state.value = _state.value.copy(chatId = chatId, streaming = null, error = null)
        messageJob = viewModelScope.launch {
            val chat = db.chatDao().byId(chatId)
            _state.value = _state.value.copy(title = chat?.title.orEmpty())
            db.messageDao().observeForChat(chatId).collect { msgs ->
                _state.value = _state.value.copy(messages = msgs)
            }
        }
    }

    fun newChat() {
        messageJob?.cancel()
        _state.value = _state.value.copy(chatId = 0L, messages = emptyList(), title = "", streaming = null)
    }

    fun deleteChat(chatId: Long) {
        viewModelScope.launch {
            db.chatDao().delete(chatId)
            if (_state.value.chatId == chatId) newChat()
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    fun stop() {
        streamJob?.cancel()
        _state.value = _state.value.copy(busy = false)
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.busy) return

        val config = providerStore.config.value
        if (!config.isUsable) {
            val msg = "No provider is configured. Set a base URL, API key and model at " +
                "About → AI provider before sending."
            DeclineRegistry.record(
                Decline(
                    callerTag = Caller.User.tag,
                    capability = Capability.NETWORK,
                    reason = Decline.Reason.PROVIDER_NOT_CONFIGURED,
                    detail = msg,
                    operation = "chat send",
                )
            )
            _state.value = _state.value.copy(error = msg)
            return
        }

        val decision = gate.check(Caller.User, Capability.NETWORK, "chat request to ${config.baseUrl}")
        if (decision is GateDecision.Denied) {
            _state.value = _state.value.copy(error = decision.message)
            return
        }

        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, streaming = "")

            val now = System.currentTimeMillis()
            var chatId = _state.value.chatId
            if (chatId == 0L) {
                chatId = db.chatDao().insert(
                    ChatEntity(
                        title = prompt.take(48),
                        createdAt = now,
                        updatedAt = now,
                        providerModel = config.model,
                    )
                )
                openChat(chatId)
            } else {
                db.chatDao().touch(chatId, _state.value.title.ifBlank { prompt.take(48) }, now)
            }

            db.messageDao().insert(
                MessageEntity(
                    chatId = chatId,
                    kind = PromptTurnKind.USER.name,
                    content = prompt,
                    createdAt = now,
                )
            )

            val history = db.messageDao().forChat(chatId).map { m ->
                PromptTurn(
                    kind = runCatching { PromptTurnKind.valueOf(m.kind) }
                        .getOrDefault(PromptTurnKind.USER),
                    content = m.content,
                )
            }

            val assistantId = db.messageDao().insert(
                MessageEntity(
                    chatId = chatId,
                    kind = PromptTurnKind.ASSISTANT.name,
                    content = "",
                    createdAt = System.currentTimeMillis(),
                )
            )

            val service = AiServiceFactory.create(config)
            val builder = StringBuilder()
            var failure: String? = null

            try {
                service.stream(history)
                    .catch { e ->
                        failure = when (e) {
                            is AiException -> e.message ?: "Provider error."
                            else -> "${e.javaClass.simpleName}: ${e.message}"
                        }
                    }
                    .collect { chunk ->
                        builder.append(chunk)
                        _state.value = _state.value.copy(streaming = builder.toString())
                    }
            } catch (e: Exception) {
                failure = "${e.javaClass.simpleName}: ${e.message}"
            }

            if (failure != null) {
                DeclineRegistry.record(
                    Decline(
                        callerTag = Caller.User.tag,
                        capability = Capability.NETWORK,
                        reason = Decline.Reason.UPSTREAM_ERROR,
                        detail = failure!!,
                        operation = "chat send",
                    )
                )
            }

            db.messageDao().update(
                assistantId,
                builder.toString().ifBlank {
                    if (failure != null) "" else "(the provider returned no content)"
                },
                failure,
            )
            db.chatDao().touch(chatId, _state.value.title.ifBlank { prompt.take(48) }, System.currentTimeMillis())

            _state.value = _state.value.copy(busy = false, streaming = null, error = failure)
        }
    }

    suspend fun testConnection(): Result<String> {
        val config = providerStore.config.value
        if (!config.isUsable) {
            return Result.failure(IllegalStateException("Provider is not fully configured."))
        }
        return AiServiceFactory.create(config).testConnection()
    }
}
