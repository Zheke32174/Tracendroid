package dev.pleiades.masamune.core.decline

import dev.pleiades.masamune.core.capability.Capability
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A classified refusal. Anything in this module that says no records one, so "it silently did
 * nothing" is never an available explanation. Rendered at Settings -> Refusal log.
 */
data class Decline(
    val callerTag: String,
    val capability: Capability?,
    val reason: Reason,
    val detail: String,
    val operation: String,
    val at: Long = System.currentTimeMillis(),
) {
    enum class Reason(val label: String) {
        CAPABILITY_NOT_GRANTED("Capability not granted"),
        HALTED("System halted"),
        NOT_IMPLEMENTED("Not implemented in this build"),
        PROVIDER_NOT_CONFIGURED("No chat provider configured"),
        TARGET_APP_ABSENT("Target app not installed"),
        TARGET_PERMISSION_MISSING("Permission to drive target app not granted"),
        UPSTREAM_ERROR("Upstream error"),
        BACKEND_UNSUPPORTED("Backend does not support this operation"),
    }
}

object DeclineRegistry {

    private const val BUFFER_SIZE = 64

    private val buffer = CopyOnWriteArrayList<Decline>()
    private val _recent = MutableStateFlow<List<Decline>>(emptyList())
    val recent: StateFlow<List<Decline>> = _recent.asStateFlow()

    fun record(decline: Decline) {
        buffer.add(0, decline)
        while (buffer.size > BUFFER_SIZE) buffer.removeAt(buffer.size - 1)
        _recent.value = buffer.toList()
    }

    fun clear() {
        buffer.clear()
        _recent.value = emptyList()
    }
}
