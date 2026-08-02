package dev.pleiades.masamune.operator

import dev.pleiades.masamune.ai.AiException
import dev.pleiades.masamune.ai.AiService
import dev.pleiades.masamune.ai.PromptTurn
import dev.pleiades.masamune.ai.PromptTurnKind
import dev.pleiades.masamune.flow.expr.Value
import kotlinx.coroutines.flow.catch
import org.json.JSONObject

/**
 * The production [OperatorDecider]: a real LLM served by Masamune's existing provider layer.
 *
 * docs/AUTH-SUBSCRIPTION.md is explicit that the operator's model is the same [AiService] the
 * chat surface uses — API key or subscription, whichever the user configured — so this decider
 * does *not* build a new auth path. It streams one completion, accumulates it, extracts the JSON action
 * the model was asked for, and maps it onto the [InterfaceCall] vocabulary. There is no fabricated
 * fallback: an empty reply, a non-JSON reply, or an unknown action all throw [AiException], which
 * the decide block turns into a visible fiber failure. A wrong answer is surfaced, never invented.
 *
 * The action grammar handed to the model is a thin projection of the Interface-category blocks:
 *
 * | action        | Interface block          | arguments                    |
 * |---------------|--------------------------|------------------------------|
 * | `tap`         | `interact_touch` Click   | `x`, `y`                     |
 * | `long_press`  | `interact_touch` Long    | `x`, `y`                     |
 * | `swipe`       | `interact_touch` Swipe   | `x0,y0,x1,y1`, opt `duration`|
 * | `tap_text`    | `interact` Click         | `text` (matched on screen)   |
 * | `type`        | `key_send_characters`    | `text`                       |
 * | `key`         | `key_send`               | `key` (BACK/HOME/RECENTS/…)  |
 * | `read_field`  | `inspect_text_edit`      | —                            |
 * | (done)        | —                        | `done:true`, `reason`        |
 */
class AiOperatorDecider(
    private val service: AiService,
    /** How much of the observed tree to include in the prompt, to stay inside the model's context. */
    private val maxObservationChars: Int = 6_000,
) : OperatorDecider {

    override suspend fun decide(goal: String, observation: String, step: Int): OperatorDecision {
        val trimmed = if (observation.length > maxObservationChars) {
            observation.take(maxObservationChars) + "\n… (screen truncated)"
        } else {
            observation
        }
        val turns = listOf(
            PromptTurn(PromptTurnKind.SYSTEM, SYSTEM_PROMPT),
            PromptTurn(
                PromptTurnKind.USER,
                "GOAL:\n$goal\n\nSTEP: ${step + 1}\n\nCURRENT SCREEN (compact accessibility tree):\n" +
                    trimmed.ifBlank { "(the screen read empty)" } +
                    "\n\nReply with exactly one JSON object for the next action.",
            ),
        )

        val builder = StringBuilder()
        var failure: String? = null
        service.stream(turns)
            .catch { e -> failure = (e as? AiException)?.message ?: "${e.javaClass.simpleName}: ${e.message}" }
            .collect { builder.append(it) }
        if (failure != null) throw AiException(failure!!)

        val reply = builder.toString()
        val json = extractJson(reply)
            ?: throw AiException("The model did not return a JSON action. It said: ${reply.take(300)}")
        return parse(json)
    }

    /** Pull the first balanced `{ … }` out of the reply — models often wrap JSON in prose or fences. */
    private fun extractJson(reply: String): JSONObject? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(reply.substring(start, end + 1)) }.getOrNull()
    }

    private fun parse(json: JSONObject): OperatorDecision {
        val reason = json.optString("reason").ifBlank { "(no reason given)" }
        if (json.optBoolean("done", false)) return OperatorDecision.Finish(reason)

        val action = json.optString("action").trim().lowercase()
        if (action.isEmpty()) throw AiException("The model's JSON had neither done:true nor an action field.")

        val call = when (action) {
            "tap" -> InterfaceCall(
                blockId = "interact_touch",
                args = mapOf("gesture" to Value.Text("Click"), "x0" to num(json, "x"), "y0" to num(json, "y")),
                label = "tap (${json.optInt("x")}, ${json.optInt("y")})",
            )
            "long_press" -> InterfaceCall(
                blockId = "interact_touch",
                args = mapOf("gesture" to Value.Text("Long click"), "x0" to num(json, "x"), "y0" to num(json, "y")),
                label = "long-press (${json.optInt("x")}, ${json.optInt("y")})",
            )
            "swipe" -> InterfaceCall(
                blockId = "interact_touch",
                args = buildMap {
                    put("gesture", Value.Text("Swipe"))
                    put("x0", num(json, "x0")); put("y0", num(json, "y0"))
                    put("x1", num(json, "x1")); put("y1", num(json, "y1"))
                    json.optInt("duration", -1).takeIf { it >= 0 }?.let { put("speed", Value.Num(it.toDouble())) }
                },
                label = "swipe (${json.optInt("x0")},${json.optInt("y0")})→(${json.optInt("x1")},${json.optInt("y1")})",
            )
            "tap_text" -> InterfaceCall(
                blockId = "interact",
                args = mapOf("action" to Value.Text("Click"), "xpathExpression" to text(json, "text")),
                label = "tap \"${json.optString("text")}\"",
            )
            "type" -> InterfaceCall(
                blockId = "key_send_characters",
                args = mapOf("characters" to text(json, "text")),
                label = "type \"${json.optString("text").take(40)}\"",
            )
            "key" -> InterfaceCall(
                blockId = "key_send",
                args = mapOf("keyCode" to text(json, "key")),
                label = "key ${json.optString("key")}",
            )
            "read_field" -> InterfaceCall(
                blockId = "inspect_text_edit",
                args = emptyMap(),
                label = "read focused field",
                outputs = mapOf("varNewText" to OperatorLoop.VAR_OBSERVATION),
            )
            else -> throw AiException("The model chose an unknown action '$action'.")
        }
        return OperatorDecision.Act(call, reason)
    }

    private fun num(json: JSONObject, key: String): Value =
        Value.Num(json.optDouble(key, 0.0).let { if (it.isNaN()) 0.0 else it })

    private fun text(json: JSONObject, key: String): Value = Value.Text(json.optString(key))

    private companion object {
        val SYSTEM_PROMPT = """
            You are Masamune's on-device operator. You drive an Android phone by choosing ONE action
            at a time to advance the user's GOAL, using an accessibility view of the current screen.

            You are given the goal and a compact tree of the on-screen elements. Each element line
            shows its class, any text, description, resource id, whether it is [clickable]/[editable],
            and its bounds [left,top][right,bottom]. To tap an element, aim at the centre of its
            bounds.

            Reply with EXACTLY ONE JSON object and nothing else. Shapes:
              {"action":"tap","x":<int>,"y":<int>,"reason":"..."}
              {"action":"long_press","x":<int>,"y":<int>,"reason":"..."}
              {"action":"swipe","x0":<int>,"y0":<int>,"x1":<int>,"y1":<int>,"duration":<ms?>,"reason":"..."}
              {"action":"tap_text","text":"<visible text or id>","reason":"..."}
              {"action":"type","text":"<text to type into the focused field>","reason":"..."}
              {"action":"key","key":"BACK|HOME|RECENTS|NOTIFICATIONS|QUICK_SETTINGS|POWER_DIALOG","reason":"..."}
              {"action":"read_field","reason":"..."}
              {"done":true,"reason":"why the goal is complete"}

            Rules: choose the single best next step; prefer tapping the centre of an element's bounds;
            set done:true only when the goal is genuinely achieved; never output prose outside the JSON.
        """.trimIndent()
    }
}
