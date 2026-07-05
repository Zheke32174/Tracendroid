package com.ai.assistance.operit.core.tools.defaultTool.accessbility

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.tools.FormFillResultData
import com.ai.assistance.operit.core.tools.SimplifiedUINode
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.UIActionResultData
import com.ai.assistance.operit.core.tools.UIElementMatchData
import com.ai.assistance.operit.core.tools.UIPageResultData
import com.ai.assistance.operit.core.tools.UITreeResultData
import com.ai.assistance.operit.core.tools.defaultTool.standard.StandardUITools
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.repository.UIHierarchyManager
import com.ai.assistance.operit.util.OperitPaths
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.api.chat.llmprovider.AIService
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.preferences.FunctionalConfigManager
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ai.assistance.operit.util.ImagePoolManager
import java.io.StringReader
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import kotlinx.coroutines.delay

/** 无障碍级别的UI工具，使用Android无障碍服务API实现UI操作 */
open class AccessibilityUITools(context: Context) : StandardUITools(context) {

    companion object {
        private const val TAG = "AccessibilityUITools"
        private const val MAX_RETRY_COUNT = 3
        private const val RETRY_DELAY_MS = 300L

        // Bounds for the multi-step UI sub-agent loop. Defaults are intentionally small; the hard cap
        // prevents runaways even if a caller passes a large max_steps.
        private const val DEFAULT_SUBAGENT_STEPS = 8
        private const val MAX_SUBAGENT_STEPS = 12
        private const val SUBAGENT_STEP_SETTLE_MS = 400L
    }

    /**
     * 检查无障碍服务是否正在运行
     */
    private suspend fun isAccessibilityServiceEnabled(): Boolean {
        return UIHierarchyManager.isAccessibilityServiceEnabled(context)
    }

    /**
     * 为需要无障碍服务的工具创建一个前置检查的包装器
     */
    private suspend fun <T> withAccessibilityCheck(tool: AITool, block: suspend () -> T): T {
        if (!isAccessibilityServiceEnabled()) {
            throw IllegalStateException("Accessibility Service is not enabled. Please enable it in system settings to use this feature.")
        }
        return block()
    }
    
    /**
     * 获取UI层次结构，失败时重试
     * @return UI层次结构XML字符串，获取失败返回空字符串
     */
    private suspend fun getUIHierarchyWithRetry(): String {
        var retryCount = 0
        var uiXml = ""

        while (retryCount < MAX_RETRY_COUNT) {
            uiXml = UIHierarchyManager.getUIHierarchy(context)
            if (uiXml.isNotEmpty()) {
                return uiXml
            }
            
            retryCount++
            if (retryCount < MAX_RETRY_COUNT) {
                AppLogger.d(TAG, "获取UI层次结构失败，正在重试 #$retryCount")
                delay(RETRY_DELAY_MS)
            }
        }
        
        AppLogger.w(TAG, "获取UI层次结构失败，已重试${MAX_RETRY_COUNT}次")
        return uiXml
    }

    /**
     * The single next UI action a decider chose, in the accessibility vocabulary.
     *
     * `type` is the normalized action kind. `DONE` ends the loop. `UNKNOWN` marks a reply that could
     * not be parsed into a valid action and also ends the loop with the raw text preserved for the
     * transcript. All coordinate/selector fields are optional and interpreted per [type].
     */
    private data class UiAction(
        val type: UiActionType,
        val by: String? = null,
        val value: String? = null,
        val index: Int = 0,
        val text: String? = null,
        val x: Int? = null,
        val y: Int? = null,
        val startX: Int? = null,
        val startY: Int? = null,
        val endX: Int? = null,
        val endY: Int? = null,
        val key: String? = null,
        val reason: String? = null,
        val raw: String = ""
    )

    private enum class UiActionType {
        CLICK, SET_TEXT, TAP, LONG_PRESS, SWIPE, PRESS_KEY, WAIT, DONE, UNKNOWN
    }

    /**
     * The decision seam: given the task, the current on-screen observation, the step number and the
     * running history, return the single next [UiAction]. The default implementation is model-backed
     * ([modelNextActionDecider]); a scripted implementation ([scriptedNextActionDecider]) replays an
     * explicit action list supplied by the caller. Keeping this as a seam lets the loop be exercised
     * off-device/offline (scripted) and swaps in the real model-in-the-loop when the call path is
     * clean — which it is (mirrors StandardFileSystemTools.runGrepModel over FunctionType).
     */
    private fun interface NextActionDecider {
        suspend fun decide(
            task: String,
            observation: String,
            step: Int,
            maxSteps: Int,
            history: List<String>
        ): UiAction
    }

    /**
     * Bounded multi-step UI automation sub-agent over the AccessibilityService transport.
     *
     * This is the live replacement for the removed Shower/Shizuku sub-agent (docs/THREAT_MODEL.md
     * § 4.4). It uses ONLY accessibility primitives — no shell `input`/`am`, no root, no adb.
     *
     * Loop: given a natural-language `intent`, up to `max_steps` (bounded 1..[MAX_SUBAGENT_STEPS],
     * default [DEFAULT_SUBAGENT_STEPS]) times it (1) observes the current UI via [getPageInfo],
     * (2) asks a [NextActionDecider] for the SINGLE next action as a small JSON object, (3) executes
     * it through an existing accessibility primitive, and (4) records a transcript line. It stops when
     * the decider returns DONE, when the step budget is exhausted, or when a step fails — always
     * returning a readable partial transcript plus the final page state. No step can crash the loop.
     *
     * Decider selection: if the caller supplies an explicit `actions` JSON array the loop replays it
     * deterministically (scripted decider — useful for tests and offline runs); otherwise it uses the
     * live model over [FunctionType.UI_CONTROLLER] (model-in-the-loop).
     *
     * When the service is NOT enabled it returns the same clear, actionable "enable accessibility"
     * message as brick 1 (points at the Tracendroid accessibility service in system settings, the same
     * target as the in-app AccessibilityOnboardingScreen).
     */
    override suspend fun runUiSubAgent(tool: AITool): ToolResult {
        return try {
            if (!isAccessibilityServiceEnabled()) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = context.getString(R.string.ui_subagent_accessibility_disabled)
                )
            }

            val task = tool.parameters.find { it.name == "intent" }?.value.orEmpty().trim()
            if (task.isEmpty()) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = context.getString(R.string.ui_subagent_loop_empty_intent)
                )
            }

            val maxSteps = resolveMaxSteps(tool)

            // Choose the decider. An explicit `actions` array => deterministic scripted replay
            // (offline/testable); otherwise the live model-in-the-loop over UI_CONTROLLER.
            val scriptedActions = parseScriptedActions(
                tool.parameters.find { it.name == "actions" }?.value
            )
            val decider: NextActionDecider =
                if (scriptedActions != null) scriptedNextActionDecider(scriptedActions)
                else modelNextActionDecider()

            runUiSubAgentLoop(tool, task, maxSteps, decider)
        } catch (e: Exception) {
            // Defensive outer guard: the loop already guards each step, but never let the sub-agent
            // crash the tool call.
            AppLogger.e(TAG, "Error running UI sub-agent", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = context.getString(
                    R.string.ui_subagent_observation_failed,
                    e.message ?: ""
                )
            )
        }
    }

    /** Clamp the caller's `max_steps` into a safe budget; default when absent/invalid. */
    private fun resolveMaxSteps(tool: AITool): Int {
        val requested = tool.parameters.find { it.name == "max_steps" }?.value?.toIntOrNull()
        return (requested ?: DEFAULT_SUBAGENT_STEPS).coerceIn(1, MAX_SUBAGENT_STEPS)
    }

    /**
     * The bounded observe/decide/act loop. Fully guarded: any thrown or failed step ends the loop and
     * returns the partial transcript collected so far. Always succeeds at the ToolResult level (the
     * transcript reports partial progress); it only surfaces `success = false` when it could not take
     * even the first observation.
     */
    private suspend fun runUiSubAgentLoop(
        tool: AITool,
        task: String,
        maxSteps: Int,
        decider: NextActionDecider
    ): ToolResult {
        val transcript = StringBuilder()
        transcript.append(context.getString(R.string.ui_subagent_loop_transcript_header, task))
        transcript.append('\n')

        val history = mutableListOf<String>()
        var lastPageInfo: ToolResult? = null
        var finished = false

        var step = 1
        while (step <= maxSteps) {
            // 1) OBSERVE
            val pageInfo = try {
                getPageInfo(tool)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Sub-agent observe failed at step $step", e)
                null
            }
            if (pageInfo == null || !pageInfo.success) {
                val reason = pageInfo?.error ?: "observation returned no result"
                // On the very first step a failed observation is a hard failure (nothing to report).
                if (step == 1) {
                    return ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = context.getString(R.string.ui_subagent_observation_failed, reason)
                    )
                }
                appendStep(transcript, step, "observe", reason)
                break
            }
            lastPageInfo = pageInfo
            val observation = pageInfo.result.toString()

            // 2) DECIDE
            val action = try {
                decider.decide(task, observation, step, maxSteps, history.toList())
            } catch (e: Exception) {
                AppLogger.e(TAG, "Sub-agent decider failed at step $step", e)
                appendStep(
                    transcript,
                    step,
                    "decide",
                    context.getString(
                        R.string.ui_subagent_loop_decider_failed,
                        step,
                        e.message ?: "unknown error"
                    )
                )
                break
            }

            if (action.type == UiActionType.DONE) {
                val reason = action.reason.orEmpty()
                transcript.append(context.getString(R.string.ui_subagent_loop_done, reason, step - 1))
                transcript.append('\n')
                finished = true
                break
            }

            if (action.type == UiActionType.UNKNOWN) {
                appendStep(
                    transcript,
                    step,
                    "decide",
                    context.getString(R.string.ui_subagent_loop_parse_failed, action.raw)
                )
                break
            }

            // 3) ACT
            val actResult = try {
                executeUiAction(action)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Sub-agent act failed at step $step", e)
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = e.message ?: "action threw"
                )
            }

            // 4) RECORD
            val actionLabel = describeAction(action)
            val resultLabel =
                if (actResult.success) actResult.result.toString().ifBlank { "ok" }
                else "FAILED: ${actResult.error ?: "unknown error"}"
            appendStep(transcript, step, actionLabel, resultLabel)
            history.add("$actionLabel -> $resultLabel")

            // A failed action ends the loop with the partial transcript (never crash / never spin).
            if (!actResult.success) break

            // Let the UI settle before the next observation.
            delay(SUBAGENT_STEP_SETTLE_MS)
            step++
        }

        if (!finished && step > maxSteps) {
            transcript.append(context.getString(R.string.ui_subagent_loop_max_steps, maxSteps))
            transcript.append('\n')
        }

        // Append the final page state for the caller.
        val finalPage = lastPageInfo?.result?.toString()
        if (!finalPage.isNullOrBlank()) {
            transcript.append('\n')
            transcript.append(context.getString(R.string.ui_subagent_loop_final_page_header))
            transcript.append('\n')
            transcript.append(finalPage)
        }

        return ToolResult(
            toolName = tool.name,
            success = true,
            result = StringResultData(transcript.toString()),
            error = ""
        )
    }

    private fun appendStep(sb: StringBuilder, step: Int, action: String, result: String) {
        sb.append(context.getString(R.string.ui_subagent_loop_step_line, step, action, result))
        sb.append('\n')
    }

    /** Human-readable one-line label for a chosen action (for the transcript + history). */
    private fun describeAction(action: UiAction): String {
        return when (action.type) {
            UiActionType.CLICK ->
                "click(by=${action.by ?: "?"}, value=${action.value ?: "?"}, index=${action.index})"
            UiActionType.SET_TEXT -> "set_text(\"${action.text ?: ""}\")"
            UiActionType.TAP -> "tap(${action.x},${action.y})"
            UiActionType.LONG_PRESS -> "long_press(${action.x},${action.y})"
            UiActionType.SWIPE ->
                "swipe(${action.startX},${action.startY}->${action.endX},${action.endY})"
            UiActionType.PRESS_KEY -> "press_key(${action.key ?: "?"})"
            UiActionType.WAIT -> "wait"
            UiActionType.DONE -> "done"
            UiActionType.UNKNOWN -> "unknown"
        }
    }

    /**
     * Dispatch a parsed [UiAction] to the matching accessibility primitive by constructing a synthetic
     * [AITool] with the parameter names those primitives already expect. This reuses the existing,
     * accessibility-only implementations (clickElement / setInputText / tap / longPress / swipe /
     * pressKey) — no new transport is introduced.
     */
    private suspend fun executeUiAction(action: UiAction): ToolResult {
        return when (action.type) {
            UiActionType.CLICK -> {
                val params = mutableListOf<ToolParameter>()
                when (action.by?.lowercase()) {
                    "id", "resourceid" -> action.value?.let { params.add(ToolParameter("resourceId", it)) }
                    "desc", "contentdesc" -> action.value?.let { params.add(ToolParameter("contentDesc", it)) }
                    "class", "classname" -> action.value?.let { params.add(ToolParameter("className", it)) }
                    "bounds" -> action.value?.let { params.add(ToolParameter("bounds", it)) }
                    // Default / "text": match by visible text via the accessibility node's content-desc
                    // is not equivalent, so fall back to resourceId-style contains match on text is not
                    // supported by clickElement; use contentDesc which findNodesInXml matches. When the
                    // selector is plain text we pass it as contentDesc (best-effort) — the model is told
                    // to prefer id/desc. Callers wanting exact text should provide bounds.
                    else -> action.value?.let { params.add(ToolParameter("contentDesc", it)) }
                }
                params.add(ToolParameter("index", action.index.toString()))
                clickElement(AITool(name = "click_element", parameters = params))
            }
            UiActionType.SET_TEXT ->
                setInputText(
                    AITool(
                        name = "set_input_text",
                        parameters = listOf(ToolParameter("text", action.text ?: ""))
                    )
                )
            UiActionType.TAP ->
                tap(
                    AITool(
                        name = "tap",
                        parameters = listOf(
                            ToolParameter("x", (action.x ?: 0).toString()),
                            ToolParameter("y", (action.y ?: 0).toString())
                        )
                    )
                )
            UiActionType.LONG_PRESS ->
                longPress(
                    AITool(
                        name = "long_press",
                        parameters = listOf(
                            ToolParameter("x", (action.x ?: 0).toString()),
                            ToolParameter("y", (action.y ?: 0).toString())
                        )
                    )
                )
            UiActionType.SWIPE ->
                swipe(
                    AITool(
                        name = "swipe",
                        parameters = listOf(
                            ToolParameter("start_x", (action.startX ?: 0).toString()),
                            ToolParameter("start_y", (action.startY ?: 0).toString()),
                            ToolParameter("end_x", (action.endX ?: 0).toString()),
                            ToolParameter("end_y", (action.endY ?: 0).toString())
                        )
                    )
                )
            UiActionType.PRESS_KEY ->
                pressKey(
                    AITool(
                        name = "press_key",
                        parameters = listOf(ToolParameter("key_code", normalizeKeyCode(action.key)))
                    )
                )
            UiActionType.WAIT -> {
                delay(SUBAGENT_STEP_SETTLE_MS)
                ToolResult(
                    toolName = "wait",
                    success = true,
                    result = StringResultData("waited"),
                    error = ""
                )
            }
            UiActionType.DONE, UiActionType.UNKNOWN ->
                ToolResult(
                    toolName = "noop",
                    success = true,
                    result = StringResultData(""),
                    error = ""
                )
        }
    }

    /** Map a short key name (BACK/HOME/…) to the KEYCODE_* string [pressKey] understands. */
    private fun normalizeKeyCode(key: String?): String {
        val k = key?.trim()?.uppercase().orEmpty()
        return if (k.startsWith("KEYCODE_")) k else "KEYCODE_$k"
    }

    /**
     * Model-backed decider: builds a strict prompt (system contract + task + current screen + history)
     * and asks the UI_CONTROLLER-configured model for a single JSON action, then parses it. Mirrors the
     * proven tool-calls-the-LLM path in StandardFileSystemTools.runGrepModel.
     */
    private fun modelNextActionDecider(): NextActionDecider = NextActionDecider { task, observation, step, maxSteps, history ->
        val historyText =
            if (history.isEmpty()) context.getString(R.string.ui_subagent_loop_history_none)
            else history.mapIndexed { i, h -> "${i + 1}. $h" }.joinToString("\n")
        val userPrompt = context.getString(
            R.string.ui_subagent_loop_user_prompt,
            task,
            step,
            maxSteps,
            observation,
            historyText
        )
        val systemPrompt = context.getString(R.string.ui_subagent_loop_system_prompt)
        val reply = runUiControllerModel(systemPrompt, userPrompt)
        parseAction(reply)
    }

    /**
     * Scripted decider (fallback / offline / test): replays a pre-parsed explicit action list, one per
     * step, and returns DONE once the list is exhausted. Deterministic; makes no model call.
     */
    private fun scriptedNextActionDecider(actions: List<UiAction>): NextActionDecider =
        NextActionDecider { _, _, step, _, _ ->
            val idx = step - 1
            if (idx < actions.size) actions[idx]
            else UiAction(type = UiActionType.DONE, reason = "scripted action list exhausted")
        }

    /**
     * Invoke the currently-active UI-automation model for a single decision and collect the full reply.
     * Uses the same clean path StandardFileSystemTools uses for GREP: resolve the service + model
     * parameters by [FunctionType], send a one-shot (system + user) prompt with streaming disabled, and
     * concatenate the streamed chunks into a String. No shell, no root — a plain LLM call.
     */
    private suspend fun runUiControllerModel(systemPrompt: String, userPrompt: String): String {
        val service: AIService =
            EnhancedAIService.getAIServiceForFunction(context, FunctionType.UI_CONTROLLER)
        val modelParameters: List<ModelParameter<*>> = getUiControllerModelParameters()
        val sb = StringBuilder()
        val stream = service.sendMessage(
            context = context,
            chatHistory = listOf(
                PromptTurn(kind = PromptTurnKind.SYSTEM, content = systemPrompt),
                PromptTurn(kind = PromptTurnKind.USER, content = userPrompt)
            ),
            modelParameters = modelParameters,
            enableThinking = false,
            stream = false,
            availableTools = null
        )
        stream.collect { chunk -> sb.append(chunk) }
        return sb.toString().trim()
    }

    private suspend fun getUiControllerModelParameters(): List<ModelParameter<*>> {
        val functionalConfigManager = FunctionalConfigManager(context)
        functionalConfigManager.initializeIfNeeded()
        val modelConfigManager = ModelConfigManager(context)
        val mapping = functionalConfigManager.getConfigMappingForFunction(FunctionType.UI_CONTROLLER)
        return modelConfigManager.getModelParametersForConfig(mapping.configId)
    }

    /**
     * Parse a model reply into a [UiAction]. Tolerant: extracts the first {...} object from the text
     * (models sometimes wrap JSON in prose/fences). An unrecognized or unparseable reply becomes
     * [UiActionType.UNKNOWN], which ends the loop cleanly with the raw text preserved.
     */
    private fun parseAction(reply: String): UiAction {
        val json = extractFirstJsonObject(reply) ?: return UiAction(type = UiActionType.UNKNOWN, raw = reply)
        return uiActionFromJson(json, reply)
    }

    private fun uiActionFromJson(json: JSONObject, raw: String): UiAction {
        val type = when (json.optString("action").trim().lowercase()) {
            "click", "click_element" -> UiActionType.CLICK
            "set_text", "settext", "set_input_text", "input", "type" -> UiActionType.SET_TEXT
            "tap" -> UiActionType.TAP
            "long_press", "longpress" -> UiActionType.LONG_PRESS
            "swipe" -> UiActionType.SWIPE
            "press_key", "presskey", "key" -> UiActionType.PRESS_KEY
            "wait" -> UiActionType.WAIT
            "done", "finish", "complete" -> UiActionType.DONE
            else -> UiActionType.UNKNOWN
        }
        if (type == UiActionType.UNKNOWN) {
            return UiAction(type = UiActionType.UNKNOWN, raw = raw)
        }
        return UiAction(
            type = type,
            by = json.optStringOrNull("by"),
            value = json.optStringOrNull("value"),
            index = json.optInt("index", 0),
            text = json.optStringOrNull("text"),
            x = json.optIntOrNull("x"),
            y = json.optIntOrNull("y"),
            startX = json.optIntOrNull("start_x"),
            startY = json.optIntOrNull("start_y"),
            endX = json.optIntOrNull("end_x"),
            endY = json.optIntOrNull("end_y"),
            key = json.optStringOrNull("key"),
            reason = json.optStringOrNull("reason"),
            raw = raw
        )
    }

    /** Extract the first balanced-ish JSON object from free text; null if none parses. */
    private fun extractFirstJsonObject(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(text.substring(start, end + 1))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parse the optional caller-supplied `actions` param (a JSON array of action objects) into a
     * scripted list. Returns null when the param is absent/blank (=> use the model). Returns an empty
     * list only if the array is empty (=> loop finishes immediately as DONE). Any unparseable entry is
     * kept as an UNKNOWN action so the scripted run surfaces it rather than silently skipping.
     */
    private fun parseScriptedActions(raw: String?): List<UiAction>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.optJSONObject(i)
                if (obj == null) UiAction(type = UiActionType.UNKNOWN, raw = arr.optString(i))
                else uiActionFromJson(obj, obj.toString())
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse scripted actions param; ignoring and using model", e)
            null
        }
    }

    // --- small JSONObject null-tolerant helpers (org.json returns "" / 0, we want null) ---
    private fun JSONObject.optStringOrNull(name: String): String? {
        if (!has(name) || isNull(name)) return null
        val v = optString(name, "")
        return v.ifBlank { null }
    }

    private fun JSONObject.optIntOrNull(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        return if (has(name)) optInt(name) else null
    }

    /** Gets the current UI page/window information */
    override suspend fun getPageInfo(tool: AITool): ToolResult {
        return try {
            withAccessibilityCheck(tool) {
        val format = tool.parameters.find { it.name == "format" }?.value ?: "xml"
        val detail = tool.parameters.find { it.name == "detail" }?.value ?: "summary"

        if (format !in listOf("xml", "json")) {
                    return@withAccessibilityCheck ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Invalid format specified. Must be 'xml' or 'json'."
            )
        }

            // 使用无障碍服务获取UI数据（带重试）
            val uiXml = getUIHierarchyWithRetry()
            if (uiXml.isEmpty()) {
                    return@withAccessibilityCheck ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = "Failed to retrieve UI data via accessibility service."
                )
            }

            // 解析当前窗口信息
            val focusInfo = extractFocusInfoFromAccessibility()

            // 简化布局信息
            val simplifiedLayout = simplifyLayout(uiXml)

            // 创建结构化数据
            val resultData =
                    UIPageResultData(
                            packageName = focusInfo.packageName ?: "Unknown",
                            activityName = focusInfo.activityName ?: "Unknown",
                            uiElements = simplifiedLayout
                    )

            ToolResult(toolName = tool.name, success = true, result = resultData, error = "")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error getting page info", e)
            ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Error getting page info: ${e.message}"
            )
        }
    }

    /** 从无障碍服务获取焦点信息 */
    private suspend fun extractFocusInfoFromAccessibility(): FocusInfo {
        val focusInfo = FocusInfo()
        try {
            // 1. 获取UI层次结构的XML快照（带重试）
            val hierarchyXml = getUIHierarchyWithRetry()
            if (hierarchyXml.isEmpty()) {
                AppLogger.w(TAG, "无法获取UI层次结构XML，使用默认值。")
                focusInfo.packageName = "android"
                // 即使XML获取失败，仍然尝试获取Activity名称
                focusInfo.activityName = UIHierarchyManager.getCurrentActivityName(context) ?: "ForegroundActivity"
                return focusInfo
            }

            // 2. 从XML中解析包名
            val (packageName, _) = UIHierarchyManager.extractWindowInfo(hierarchyXml)
            // 3. 从服务中直接获取当前Activity名称
            val activityName = UIHierarchyManager.getCurrentActivityName(context)

            focusInfo.packageName = packageName
            focusInfo.activityName = activityName // 使用从服务获取的Activity名称

            // 如果没有获取到，使用默认值
            if (focusInfo.packageName == null) focusInfo.packageName = "android"
            if (focusInfo.activityName == null) focusInfo.activityName = "ForegroundActivity"
        } catch (e: Exception) {
            AppLogger.e(TAG, "从XML解析焦点信息时出错", e)
            // 设置默认值
            focusInfo.packageName = "android"
            focusInfo.activityName = "ForegroundActivity"
        }
        return focusInfo
    }

    /** 简化XML布局为节点树 */
    fun simplifyLayout(xml: String): SimplifiedUINode {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newPullParser().apply { setInput(StringReader(xml)) }

        val nodeStack = mutableListOf<UINode>()
        var rootNode: UINode? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "node") {
                        val newNode = createNode(parser)
                        if (rootNode == null) {
                            rootNode = newNode
                            nodeStack.add(newNode)
                        } else {
                            nodeStack.lastOrNull()?.children?.add(newNode)
                            nodeStack.add(newNode)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "node") {
                        nodeStack.removeLastOrNull()
                    }
                }
            }
            parser.next()
        }

        return rootNode?.toUINode()
                ?: SimplifiedUINode(
                        className = null,
                        text = null,
                        contentDesc = null,
                        resourceId = null,
                        bounds = null,
                        isClickable = false,
                        children = emptyList()
                )
    }

    private fun createNode(parser: XmlPullParser): UINode {
        // 解析关键属性
        val className = parser.getAttributeValue(null, "class")?.substringAfterLast('.')
        val text = parser.getAttributeValue(null, "text")?.replace("&#10;", "\n")
        val contentDesc = parser.getAttributeValue(null, "content-desc")
        val resourceId = parser.getAttributeValue(null, "resource-id")
        val bounds = parser.getAttributeValue(null, "bounds")
        val isClickable = parser.getAttributeValue(null, "clickable") == "true"

        return UINode(
                className = className,
                text = text,
                contentDesc = contentDesc,
                resourceId = resourceId,
                bounds = bounds,
                isClickable = isClickable
        )
    }

    /** 点击元素 */
    override suspend fun clickElement(tool: AITool): ToolResult {
        return try {
            withAccessibilityCheck(tool) {
                val resourceId = tool.parameters.find { it.name == "resourceId" }?.value
                val className = tool.parameters.find { it.name == "className" }?.value
                val contentDesc = tool.parameters.find { it.name == "contentDesc" }?.value
                val index = tool.parameters.find { it.name == "index" }?.value?.toIntOrNull() ?: 0
                val bounds = tool.parameters.find { it.name == "bounds" }?.value

                if (resourceId == null && className == null && bounds == null && contentDesc == null) {
                    return@withAccessibilityCheck ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = "Missing element identifier. Provide at least one of 'resourceId', 'className', 'contentDesc', or 'bounds'."
                    )
                }

                // 如果提供了边界坐标，直接解析并点击中心点
                if (bounds != null) {
                    return@withAccessibilityCheck handleClickByBounds(tool, bounds)
                }

                // 获取UI层次结构XML（带重试）
                val uiXml = getUIHierarchyWithRetry()
                if (uiXml.isEmpty()) {
                    return@withAccessibilityCheck ToolResult(toolName = tool.name, success = false, result = StringResultData(""), error = "Unable to get UI hierarchy.")
                }

                // 在XML中查找匹配的节点
                val matchedNodes = findNodesInXml(uiXml) { parser ->
                    val hasSelectors = resourceId != null || className != null || contentDesc != null
                    if (!hasSelectors) {
                        return@findNodesInXml false
                    }

                    val actualId = parser.getAttributeValue(null, "resource-id")
                    val actualClass = parser.getAttributeValue(null, "class")
                    val actualDesc = parser.getAttributeValue(null, "content-desc")

                    if(resourceId != null && (actualId == null || !actualId.endsWith(resourceId))){
                        return@findNodesInXml false
                    }

                    if(className != null && (actualClass == null || actualClass != className)){
                        return@findNodesInXml false
                    }

                    if(contentDesc != null && (actualDesc == null || !actualDesc.equals(contentDesc, ignoreCase = true))){
                        return@findNodesInXml false
                    }
                    
                    true
                }

                if (matchedNodes.isEmpty()) {
                    return@withAccessibilityCheck ToolResult(toolName = tool.name, success = false, result = StringResultData(""), error = "No matching element found.")
                }

                // 检查索引是否有效
                if (index < 0 || index >= matchedNodes.size) {
                    return@withAccessibilityCheck ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = "Index out of range. Found ${matchedNodes.size} elements, but requested index $index."
                    )
                }

                // 获取目标节点的bounds
                val targetNodeBounds = matchedNodes[index].bounds
                if (targetNodeBounds == null) {
                    return@withAccessibilityCheck ToolResult(toolName = tool.name, success = false, result = StringResultData(""), error = "Target element has no bounds.")
                }

                // 解析bounds并点击
                handleClickByBounds(tool, targetNodeBounds)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error clicking element", e)
            operationOverlay.hide()
            ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                    error = "Error clicking element: ${e.message}"
                )
            }
    }

    private suspend fun handleClickByBounds(tool: AITool, bounds: String): ToolResult {
        try {
            val rect = parseBounds(bounds)
            if (rect.isEmpty) {
                 return ToolResult(toolName = tool.name, success = false, result = StringResultData(""), error = "Invalid bounds format: $bounds")
            }

            val centerX = rect.centerX()
            val centerY = rect.centerY()

            operationOverlay.showTap(centerX, centerY)
            val clickSuccess = performAccessibilityClick(centerX, centerY)

            return if (clickSuccess) {
                // 成功后也主动隐藏overlay，不等待自动清理
                operationOverlay.hide()
                ToolResult(
                        toolName = tool.name,
                        success = true,
                    result = UIActionResultData(
                                        actionType = "click",
                        actionDescription = "Successfully clicked at bounds $bounds",
                        coordinates = Pair(centerX, centerY)
                    )
                )
            } else {
                operationOverlay.hide()
                ToolResult(toolName = tool.name, success = false, result = StringResultData(""), error = "Failed to click at bounds $bounds via accessibility service.")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error clicking by bounds", e)
            operationOverlay.hide()
            return ToolResult(toolName = tool.name, success = false, result = StringResultData(""), error = "Error clicking at bounds: ${e.message}")
        }
    }

    private fun findNodesInXml(xml: String, predicate: (parser: XmlPullParser) -> Boolean): List<NodeInfo> {
        val matchedNodes = mutableListOf<NodeInfo>()
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newPullParser().apply { setInput(StringReader(xml)) }

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "node") {
                if (predicate(parser)) {
                    matchedNodes.add(
                        NodeInfo(
                            bounds = parser.getAttributeValue(null, "bounds"),
                            text = parser.getAttributeValue(null, "text")
                        )
                    )
                }
            }
            parser.next()
        }
        return matchedNodes
    }

    private data class NodeInfo(val bounds: String?, val text: String?)

    /**
     * Read-only structured dump of the current UI hierarchy (the `dump_ui_tree` tool).
     *
     * Reuses the same accessibility read path as [getPageInfo] ([getUIHierarchyWithRetry] +
     * [simplifyLayout]) but returns a [UITreeResultData] that can render either an indented tree
     * (format="tree") or a JSON object (format="json", the default — richer/structured than a
     * screenshot). No UI action is performed. Gated by [withAccessibilityCheck]: when the service is
     * off it surfaces the same clear "enable the Tracendroid accessibility service" message the
     * sub-agent uses.
     */
    override suspend fun dumpUiTree(tool: AITool): ToolResult {
        return try {
            if (!isAccessibilityServiceEnabled()) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = context.getString(R.string.ui_subagent_accessibility_disabled)
                )
            }

            val requestedFormat =
                tool.parameters.find { it.name == "format" }?.value?.trim()?.lowercase() ?: "json"
            val format = if (requestedFormat == "tree") "tree" else "json"

            val uiXml = getUIHierarchyWithRetry()
            if (uiXml.isEmpty()) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Failed to retrieve UI data via accessibility service."
                )
            }

            val focusInfo = extractFocusInfoFromAccessibility()
            val simplifiedLayout = simplifyLayout(uiXml)

            val resultData = UITreeResultData(
                packageName = focusInfo.packageName ?: "Unknown",
                activityName = focusInfo.activityName ?: "Unknown",
                format = format,
                uiElements = simplifiedLayout
            )
            ToolResult(toolName = tool.name, success = true, result = resultData, error = "")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error dumping UI tree", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error dumping UI tree: ${e.message}"
            )
        }
    }

    /**
     * Query-only search for on-screen element(s) (the `find_ui_element` tool).
     *
     * Reuses [findNodesInXml] — the same XML matcher [clickElement] uses — but is strictly read-only:
     * it returns the matched elements' bounds + basic info and NEVER clicks. Matching is
     * substring-tolerant across visible text, content-description and resource-id (a `by` param can
     * restrict to one field). Gated by the accessibility check with the same honest "enable service"
     * message as the sub-agent.
     */
    override suspend fun findUiElement(tool: AITool): ToolResult {
        return try {
            if (!isAccessibilityServiceEnabled()) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = context.getString(R.string.ui_subagent_accessibility_disabled)
                )
            }

            val query = tool.parameters.find { it.name == "query" }?.value?.trim().orEmpty()
            if (query.isEmpty()) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Missing required parameter: query (visible text, content-description, or resource-id substring)."
                )
            }

            // `by` restricts which field the substring must appear in. Default "any" checks all three.
            val by = when (tool.parameters.find { it.name == "by" }?.value?.trim()?.lowercase()) {
                "text" -> "text"
                "desc", "content-desc", "contentdesc" -> "desc"
                "id", "resourceid", "resource-id" -> "id"
                else -> "any"
            }
            val limit = tool.parameters.find { it.name == "limit" }?.value?.toIntOrNull()
                ?.coerceIn(1, 50) ?: 20

            val uiXml = getUIHierarchyWithRetry()
            if (uiXml.isEmpty()) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Failed to retrieve UI data via accessibility service."
                )
            }

            val needle = query.lowercase()
            val matches = mutableListOf<UIElementMatchData.Match>()
            findNodesInXml(uiXml) { parser ->
                if (matches.size >= limit) return@findNodesInXml false

                val text = parser.getAttributeValue(null, "text")
                val desc = parser.getAttributeValue(null, "content-desc")
                val id = parser.getAttributeValue(null, "resource-id")
                val className = parser.getAttributeValue(null, "class")?.substringAfterLast('.')
                val bounds = parser.getAttributeValue(null, "bounds")

                val matchesText = !text.isNullOrEmpty() && text.lowercase().contains(needle)
                val matchesDesc = !desc.isNullOrEmpty() && desc.lowercase().contains(needle)
                val matchesId = !id.isNullOrEmpty() && id.lowercase().contains(needle)

                val hit = when (by) {
                    "text" -> matchesText
                    "desc" -> matchesDesc
                    "id" -> matchesId
                    else -> matchesText || matchesDesc || matchesId
                }
                if (!hit) return@findNodesInXml false

                val (cx, cy) = if (!bounds.isNullOrBlank()) {
                    val rect = parseBounds(bounds)
                    if (rect.isEmpty) null to null else rect.centerX() to rect.centerY()
                } else null to null

                matches.add(
                    UIElementMatchData.Match(
                        text = text,
                        contentDesc = desc,
                        resourceId = id,
                        className = className,
                        bounds = bounds,
                        centerX = cx,
                        centerY = cy
                    )
                )
                // Predicate return value is unused for accumulation; we already recorded the match.
                true
            }

            val resultData = UIElementMatchData(
                query = query,
                matchBy = by,
                matches = matches.toList()
            )
            // A query that finds nothing is still a successful query (empty result), not an error.
            ToolResult(toolName = tool.name, success = true, result = resultData, error = "")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error finding UI element", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error finding UI element: ${e.message}"
            )
        }
    }

    /**
     * Fill a set of on-screen input fields from a `field identifier -> value` mapping (the
     * `fill_form` tool).
     *
     * Query-and-set only: for each entry it (1) locates the best-matching EDITABLE node by scanning
     * the live accessibility XML the same way [findUiElement]/[clickElement] do, then (2) sets that
     * node's text through the existing [UIHierarchyManager.setTextOnNode] primitive (keyed on the
     * node's bounds — the same nodeId contract [setInputText] relies on). It NEVER clicks a button or
     * submits the form.
     *
     * Matching is substring-tolerant and checks, in priority order: the input's own resource-id,
     * content-description, and visible text/hint; then, as a fallback, a NEARBY LABEL — a non-editable
     * text/desc node immediately preceding an editable node in document order (the common
     * "Label:" then EditText layout). The highest-priority match for each editable node wins, and each
     * editable node is used at most once so two fields don't collide on the same input.
     *
     * Gated by the accessibility check with the same honest "enable the Tracendroid accessibility
     * service" message the other accessibility tools use. Every field yields a per-field report entry
     * ("filled" / "not_found" / "failed"); the tool result is successful as long as the query ran,
     * even if some fields were not found.
     */
    override suspend fun fillForm(tool: AITool): ToolResult {
        return try {
            if (!isAccessibilityServiceEnabled()) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = context.getString(R.string.ui_subagent_accessibility_disabled)
                )
            }

            // Parse the `fields` param: a JSON object mapping field identifier -> value.
            val fieldsRaw = tool.parameters.find { it.name == "fields" }?.value
            val fieldMap = parseFieldMap(fieldsRaw)
            if (fieldMap == null) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Missing or invalid 'fields' parameter. Provide a JSON object mapping field identifier (matches an input's label / hint / content-desc / nearby text / resource-id, substring-tolerant) to the value to type."
                )
            }
            if (fieldMap.isEmpty()) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "The 'fields' object is empty; nothing to fill."
                )
            }

            val uiXml = getUIHierarchyWithRetry()
            if (uiXml.isEmpty()) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Failed to retrieve UI data via accessibility service."
                )
            }

            // Snapshot the editable inputs (and the label sitting just before each) once.
            val editables = collectEditableFields(uiXml)

            val usedBounds = mutableSetOf<String>()
            val fieldResults = mutableListOf<FormFillResultData.FieldResult>()
            var filled = 0
            var notFound = 0
            var failed = 0

            for ((identifier, value) in fieldMap) {
                val candidate = matchEditableField(identifier, editables, usedBounds)
                if (candidate == null) {
                    notFound++
                    fieldResults.add(
                        FormFillResultData.FieldResult(
                            field = identifier,
                            status = "not_found",
                            detail = "no editable input matched this identifier"
                        )
                    )
                    continue
                }

                val bounds = candidate.field.bounds
                if (bounds.isNullOrBlank()) {
                    failed++
                    fieldResults.add(
                        FormFillResultData.FieldResult(
                            field = identifier,
                            status = "failed",
                            matchedBy = candidate.matchedBy,
                            detail = "matched input has no bounds to target"
                        )
                    )
                    continue
                }

                usedBounds.add(bounds)

                // Reuse the existing setText primitive; nodeId == bounds string (same contract as
                // setInputText, which passes the focused node's bounds id to setTextOnNode).
                val ok = try {
                    UIHierarchyManager.setTextOnNode(context, bounds, value)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "fill_form: setTextOnNode threw for '$identifier'", e)
                    false
                }

                if (ok) {
                    filled++
                    fieldResults.add(
                        FormFillResultData.FieldResult(
                            field = identifier,
                            status = "filled",
                            matchedBy = candidate.matchedBy,
                            bounds = bounds
                        )
                    )
                } else {
                    failed++
                    fieldResults.add(
                        FormFillResultData.FieldResult(
                            field = identifier,
                            status = "failed",
                            matchedBy = candidate.matchedBy,
                            bounds = bounds,
                            detail = "accessibility setText failed"
                        )
                    )
                }
            }

            operationOverlay.hide()

            val resultData = FormFillResultData(
                totalFields = fieldMap.size,
                filledCount = filled,
                notFoundCount = notFound,
                failedCount = failed,
                fields = fieldResults.toList()
            )
            ToolResult(toolName = tool.name, success = true, result = resultData, error = "")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error filling form", e)
            operationOverlay.hide()
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error filling form: ${e.message}"
            )
        }
    }

    /** One editable input plus the label text that immediately precedes it in document order. */
    private data class EditableField(
        val resourceId: String?,
        val contentDesc: String?,
        val text: String?,
        val nearbyLabel: String?,
        val bounds: String?
    )

    /** An editable field selected for a requested identifier, and which attribute it matched on. */
    private data class FieldMatch(val field: EditableField, val matchedBy: String)

    /**
     * Parse the `fields` param into an ordered identifier -> value map. Returns null when the param is
     * absent/blank or is not a JSON object. Values are coerced to their string form; null JSON values
     * become the empty string (clearing the field).
     */
    private fun parseFieldMap(raw: String?): LinkedHashMap<String, String>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(raw)
            val out = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.isNullOrBlank()) continue
                out[key] = if (obj.isNull(key)) "" else obj.optString(key, "")
            }
            out
        } catch (e: Exception) {
            AppLogger.w(TAG, "fill_form: could not parse 'fields' as a JSON object", e)
            null
        }
    }

    /**
     * Walk the accessibility XML once and collect every editable input, remembering the most recent
     * non-editable label (visible text or content-desc) seen before it — the common "Label then input"
     * layout — so a field identifier can also be matched against a nearby label. A node is treated as
     * editable when its class looks like an EditText or it advertises the editable/focusable-input
     * attributes some frameworks emit.
     */
    private fun collectEditableFields(xml: String): List<EditableField> {
        val fields = mutableListOf<EditableField>()
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newPullParser().apply { setInput(StringReader(xml)) }

        var lastLabel: String? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "node") {
                val className = parser.getAttributeValue(null, "class")
                val text = parser.getAttributeValue(null, "text")
                val desc = parser.getAttributeValue(null, "content-desc")
                val resourceId = parser.getAttributeValue(null, "resource-id")
                val bounds = parser.getAttributeValue(null, "bounds")
                val editableAttr = parser.getAttributeValue(null, "editable")

                if (isEditableClass(className, editableAttr)) {
                    fields.add(
                        EditableField(
                            resourceId = resourceId,
                            contentDesc = desc,
                            text = text,
                            nearbyLabel = lastLabel,
                            bounds = bounds
                        )
                    )
                } else {
                    // Remember this node's visible label for the next editable input.
                    val label = text?.takeIf { it.isNotBlank() } ?: desc?.takeIf { it.isNotBlank() }
                    if (label != null) lastLabel = label
                }
            }
            parser.next()
        }
        return fields
    }

    /** Heuristic: does this node accept text input? */
    private fun isEditableClass(className: String?, editableAttr: String?): Boolean {
        if (editableAttr == "true") return true
        val c = className?.lowercase().orEmpty()
        return c.contains("edittext") || c.contains("autocompletetextview") || c.endsWith(".textfield")
    }

    /**
     * Pick the best still-unused editable node for a requested identifier. Priority (highest first):
     * resource-id, content-desc, the input's own text/hint, then the nearby label. Matching is
     * case-insensitive substring in both directions (identifier in attribute or attribute in
     * identifier) so short labels like "email" match a hint of "Email address" and vice-versa.
     */
    private fun matchEditableField(
        identifier: String,
        editables: List<EditableField>,
        usedBounds: Set<String>
    ): FieldMatch? {
        val needle = identifier.trim().lowercase()
        if (needle.isEmpty()) return null

        // (attribute-selector, priority) — lower priority number wins.
        val attributeExtractors: List<Pair<(EditableField) -> String?, String>> = listOf(
            { f: EditableField -> f.resourceId } to "resource-id",
            { f: EditableField -> f.contentDesc } to "content-desc",
            { f: EditableField -> f.text } to "text/hint",
            { f: EditableField -> f.nearbyLabel } to "nearby-label"
        )

        for ((extractor, label) in attributeExtractors) {
            for (field in editables) {
                val boundsKey = field.bounds ?: continue
                if (boundsKey in usedBounds) continue
                val attr = extractor(field)?.lowercase()?.trim() ?: continue
                if (attr.isEmpty()) continue
                if (attr.contains(needle) || needle.contains(attr)) {
                    return FieldMatch(field, label)
                }
            }
        }
        return null
    }

    /** 设置输入文本 */
    override suspend fun setInputText(tool: AITool): ToolResult {
        return try {
            withAccessibilityCheck(tool) {
        val text = tool.parameters.find { it.name == "text" }?.value ?: ""

            // 通过UIHierarchyManager请求远程服务找到焦点节点的ID
            val focusedNodeId = UIHierarchyManager.findFocusedNodeId(context)
            if (focusedNodeId.isNullOrEmpty()) {
                    return@withAccessibilityCheck ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = "No focused editable field found."
                )
            }

            // 显示反馈
            val rect = parseBounds(focusedNodeId)
            if (!rect.isEmpty) {
            operationOverlay.showTextInput(rect.centerX(), rect.centerY(), text)
            }

            // 通过UIHierarchyManager请求远程服务设置文本
            val result = UIHierarchyManager.setTextOnNode(context, focusedNodeId, text)

                if (result) {
                // 成功后主动隐藏overlay
                operationOverlay.hide()
                ToolResult(
                        toolName = tool.name,
                        success = true,
                        result =
                                UIActionResultData(
                                        actionType = "textInput",
                                        actionDescription =
                                                "Successfully set input text via accessibility service"
                                ),
                        error = ""
                )
            } else {
                operationOverlay.hide()
                ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = "Failed to set text via accessibility service."
                )
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error setting input text", e)
            operationOverlay.hide()
            ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Error setting input text: ${e.message}"
            )
        }
    }

    /** 执行轻触操作 */
    override suspend fun tap(tool: AITool): ToolResult {
        return try {
            withAccessibilityCheck(tool) {
        val x = tool.parameters.find { it.name == "x" }?.value?.toIntOrNull()
        val y = tool.parameters.find { it.name == "y" }?.value?.toIntOrNull()

        if (x == null || y == null) {
                    return@withAccessibilityCheck ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                        error = "Missing or invalid coordinates. Both 'x' and 'y' must be valid integers."
            )
        }

            // 显示点击反馈
            operationOverlay.showTap(x, y)

            // 使用无障碍服务执行点击
            val result = performAccessibilityClick(x, y)

                if (result) {
                // 成功后主动隐藏overlay
                operationOverlay.hide()
                ToolResult(
                        toolName = tool.name,
                        success = true,
                        result =
                                UIActionResultData(
                                        actionType = "tap",
                                        actionDescription =
                                                "Successfully tapped at coordinates ($x, $y) via accessibility service",
                                        coordinates = Pair(x, y)
                                ),
                        error = ""
                )
            } else {
                operationOverlay.hide()
                ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = "Failed to tap at coordinates via accessibility service."
                )
            }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error tapping at coordinates", e)
            operationOverlay.hide()
            ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Error tapping at coordinates: ${e.message}"
            )
        }
    }

    /** 执行长按操作 */
    override suspend fun longPress(tool: AITool): ToolResult {
        return try {
            withAccessibilityCheck(tool) {
        val x = tool.parameters.find { it.name == "x" }?.value?.toIntOrNull()
        val y = tool.parameters.find { it.name == "y" }?.value?.toIntOrNull()

        if (x == null || y == null) {
                    return@withAccessibilityCheck ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                        error = "Missing or invalid coordinates. Both 'x' and 'y' must be valid integers."
            )
        }

            // 显示长按反馈（复用点击效果）
            operationOverlay.showTap(x, y)

            // 使用无障碍服务执行长按
            val result = performAccessibilityLongPress(x, y)

                if (result) {
                // 成功后主动隐藏overlay
                operationOverlay.hide()
                ToolResult(
                        toolName = tool.name,
                        success = true,
                        result =
                                UIActionResultData(
                                        actionType = "long_press",
                                        actionDescription =
                                                "Successfully long pressed at coordinates ($x, $y) via accessibility service",
                                        coordinates = Pair(x, y)
                                ),
                        error = ""
                )
            } else {
                operationOverlay.hide()
                ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = "Failed to long press at coordinates via accessibility service."
                )
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error long pressing at coordinates", e)
            operationOverlay.hide()
            ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Error long pressing at coordinates: ${e.message}"
            )
        }
    }

    /** 执行滑动操作 */
    override suspend fun swipe(tool: AITool): ToolResult {
        return try {
            withAccessibilityCheck(tool) {
        val startX = tool.parameters.find { it.name == "start_x" }?.value?.toIntOrNull()
        val startY = tool.parameters.find { it.name == "start_y" }?.value?.toIntOrNull()
        val endX = tool.parameters.find { it.name == "end_x" }?.value?.toIntOrNull()
        val endY = tool.parameters.find { it.name == "end_y" }?.value?.toIntOrNull()
        val duration = tool.parameters.find { it.name == "duration" }?.value?.toIntOrNull() ?: 300

        if (startX == null || startY == null || endX == null || endY == null) {
                    return@withAccessibilityCheck ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                        error = "Missing or invalid coordinates. 'start_x', 'start_y', 'end_x', and 'end_y' must be valid integers."
            )
        }

            // 显示滑动反馈
            operationOverlay.showSwipe(startX, startY, endX, endY)

            // 使用无障碍服务执行滑动
            val result = performAccessibilitySwipe(startX, startY, endX, endY, duration)

                if (result) {
                // 成功后主动隐藏overlay
                operationOverlay.hide()
                ToolResult(
                        toolName = tool.name,
                        success = true,
                        result =
                                UIActionResultData(
                                        actionType = "swipe",
                                        actionDescription =
                                                "Successfully performed swipe from ($startX, $startY) to ($endX, $endY) via accessibility service"
                                ),
                        error = ""
                )
            } else {
                operationOverlay.hide()
                ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = "Failed to perform swipe via accessibility service."
                )
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error performing swipe", e)
            operationOverlay.hide()
            ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Error performing swipe: ${e.message}"
            )
        }
    }

    // 使用无障碍服务执行点击的辅助方法
    private suspend fun performAccessibilityClick(x: Int, y: Int): Boolean {
        return try {
            UIHierarchyManager.performClick(context, x, y)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error performing accessibility click", e)
            return false
        }
    }

    // 使用无障碍服务执行长按的辅助方法
    private suspend fun performAccessibilityLongPress(x: Int, y: Int): Boolean {
        return try {
            UIHierarchyManager.performLongPress(context, x, y)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error performing accessibility long press", e)
            return false
        }
    }

    // 使用无障碍服务执行滑动的辅助方法
    private suspend fun performAccessibilitySwipe(
            startX: Int,
            startY: Int,
            endX: Int,
            endY: Int,
            duration: Int
    ): Boolean {
        return try {
            UIHierarchyManager.performSwipe(context, startX, startY, endX, endY, duration.toLong())
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error performing accessibility swipe", e)
            return false
        }
    }

    /** 模拟按键操作 */
    override suspend fun pressKey(tool: AITool): ToolResult {
        val keyCode = tool.parameters.find { it.name == "key_code" }?.value

        if (keyCode == null) {
            return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Missing 'key_code' parameter."
            )
        }

        try {
            // 将字符串keyCode转换为AccessibilityService中的常量
            val keyAction = when (keyCode) {
                "KEYCODE_BACK" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                "KEYCODE_HOME" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                "KEYCODE_RECENTS" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                "KEYCODE_NOTIFICATIONS" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                "KEYCODE_QUICK_SETTINGS" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                "KEYCODE_POWER_DIALOG" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
                        else -> null
                    }

            if (keyAction != null) {
                // 通过UIHierarchyManager请求远程服务执行操作
                val success = UIHierarchyManager.performGlobalAction(context, keyAction)
                return if (success) {
                    ToolResult(
                            toolName = tool.name,
                            success = true,
                            result =
                                    UIActionResultData(
                                            actionType = "keyPress",
                                            actionDescription =
                                                    "Successfully pressed key: $keyCode via accessibility service"
                                    ),
                            error = ""
                    )
                } else {
                    ToolResult(
                            toolName = tool.name,
                            success = false,
                            result = StringResultData(""),
                            error =
                                    "Failed to press key: $keyCode via accessibility service. Not all keys are supported."
                    )
                }
            } else {
                // 如果不是标准全局操作，返回不支持的错误
                return ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error =
                                "Key: $keyCode is not supported via accessibility service. Only system keys like BACK, HOME, etc. are supported."
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error pressing key", e)
            return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Error pressing key: ${e.message ?: "Unknown exception"}"
            )
        }
    }

    override suspend fun captureScreenshotToFile(tool: AITool): Pair<String?, Pair<Int, Int>?> {
        return try {
            val screenshotDir = OperitPaths.cleanOnExitDir()

            val shortName = System.currentTimeMillis().toString().takeLast(4)
            val file = File(screenshotDir, "$shortName.png")

            val success = UIHierarchyManager.takeScreenshot(context, file.absolutePath, "png")
            if (!success) {
                AppLogger.w(TAG, "captureScreenshotForAgent: AIDL takeScreenshot failed")
                return Pair(null, null)
            }

            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            val dimensions = if (options.outWidth > 0 && options.outHeight > 0) {
                Pair(options.outWidth, options.outHeight)
            } else {
                null
            }

            Pair(file.absolutePath, dimensions)
        } catch (e: Exception) {
            AppLogger.e(TAG, "captureScreenshot via accessibility failed", e)
            Pair(null, null)
        }
    }

    override suspend fun captureScreenshot(tool: AITool): Pair<String?, Pair<Int, Int>?> {
        return captureScreenshotToFile(tool)
    }

    override suspend fun captureScreenshotBitmap(tool: AITool): Pair<Bitmap?, Pair<Int, Int>?> {
        val (filePath, dimensions) = captureScreenshot(tool)
        if (filePath == null) {
            return Pair(null, dimensions)
        }

        val bitmap = BitmapFactory.decodeFile(filePath) ?: return Pair(null, dimensions)
        val resolvedDimensions = dimensions ?: Pair(bitmap.width, bitmap.height)
        return Pair(bitmap, resolvedDimensions)
    }

    private fun parseBounds(boundsString: String): android.graphics.Rect {
        // 解析 "[left,top][right,bottom]" 格式的边界字符串
        val rect = android.graphics.Rect()
        try {
            val parts = boundsString.replace("[", "").replace("]", ",").split(",")
            if (parts.size >= 4) {
                rect.left = parts[0].toInt()
                rect.top = parts[1].toInt()
                rect.right = parts[2].toInt()
                rect.bottom = parts[3].toInt()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error parsing bounds: $boundsString", e)
        }
        return rect
    }
}
