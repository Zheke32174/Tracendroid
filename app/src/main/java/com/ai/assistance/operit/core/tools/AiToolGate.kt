package com.ai.assistance.operit.core.tools

import com.ai.assistance.operit.core.tools.javascript.JsCapabilityClass
import com.ai.assistance.operit.core.tools.javascript.JsCapabilityClassifier
import com.ai.assistance.operit.core.tools.javascript.JsPluginGate
import com.ai.assistance.operit.util.AppLogger

/**
 * AI-side tool gate (§ 4.2 follow-up).
 *
 * Sibling of the JS plugin gate. Records an audit event for every AI-originated tool
 * dispatch and — when [enforce] is on — applies the same (pluginId × capability) grant
 * table that the user manages through the settings UI. The synthetic plugin id is
 * "ai:default"; the user grants capabilities to that id the same way they grant to a
 * named plugin.
 *
 * Defaults:
 *  - [enforce] is false in v1. Per the partial closure of § 4.2 the AI-side gate is
 *    intentionally audit-only at first: an enforced default-deny would break every AI
 *    session before the user has the chance to grant anything. The per-call confirmation
 *    UX that would let enforcement default to true is a follow-up.
 *  - The user can flip [enforce] at any time from settings.
 */
object AiToolGate {

    private const val TAG = "AiToolGate"

    /** Synthetic plugin id used to tag AI-originated audit + grants. */
    const val AI_PLUGIN_ID: String = "ai:default"

    /**
     * When true, AI calls are blocked unless an explicit grant for the matched capability
     * exists. When false (the default), AI calls dispatch as before but still write to
     * the audit ring.
     */
    @Volatile
    var enforce: Boolean = false

    sealed class Decision {
        data object Allow : Decision()
        data class Deny(val reason: String) : Decision()
    }

    /** Evaluate + audit one AI tool dispatch. */
    fun evaluate(toolName: String, toolType: String = "default"): Decision {
        val capability = JsCapabilityClassifier.classify(toolType, toolName)
        val state = JsPluginGate.stateFor(AI_PLUGIN_ID, capability)
        // The JsPluginGate.shouldAllow path already records audit; we don't want to flip
        // its enforce flag for AI, so we walk the data ourselves and write our own audit
        // entry via the public API.
        JsPluginGate.shouldAllow(AI_PLUGIN_ID, capability, toolType, toolName)
        if (!enforce) {
            return Decision.Allow
        }
        return if (state == JsPluginGate.GateState.GRANTED) {
            Decision.Allow
        } else {
            val message = "AI tool '$toolName' ($capability) blocked: enforce=on, " +
                "grant=$state. User must grant '${AI_PLUGIN_ID}' the $capability capability " +
                "from the Plugin gate screen."
            AppLogger.d(TAG, message)
            Decision.Deny(message)
        }
    }
}
