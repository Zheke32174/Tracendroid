# AGENT_CORE.md

> The seam every AI backend implements. Defines how a turn flows from user input, through the AI (wherever it lives), through tool calls back into the Android side, with streaming output, structured decline, user-sovereign halt, and audit-logged reasoning preserved across the chain. Companion to [`SECURITY.md`](./SECURITY.md), [`THREAT_MODEL.md`](./THREAT_MODEL.md), [`AUDIT_PLAN.md`](./AUDIT_PLAN.md), [`SHELL_REBUILD.md`](./SHELL_REBUILD.md). 中文镜像见 [`AGENT_CORE.zh.md`](./AGENT_CORE.zh.md)。

## Why this exists

Without a single internal contract, every backend (local llama, local MNN, remote OpenAI, remote Anthropic, codex via proot, gemini-cli via proot, claude-code via proot, etc.) would carry its own surface into the rest of the app. The UI would branch on backend type. Tool dispatch would branch on backend type. Audit logging would branch on backend type. That looks like openclaw's failure mode: every integration is its own surface.

The agent-core is the seam: one Kotlin interface, one envelope shape, one streaming protocol. Every backend writes an adapter from its wire format to the core. The UI, the tool dispatcher, the audit logger, and the halt machinery never see the wire format. They see the core.

## Backends in scope

| Backend | Wire format | Where it runs | Owns auth |
|---|---|---|---|
| Local llama.cpp | JNI to native | App process | n/a |
| Local MNN | JNI to native | App process | n/a |
| Remote API: OpenAI-compatible | HTTP / SSE | App process (network) | API key in EncryptedSharedPreferences |
| Remote API: Anthropic Messages | HTTP / SSE | App process (network) | API key in EncryptedSharedPreferences |
| Remote API: Google Vertex | HTTP / SSE + OAuth2 | App process (network) | Google account OAuth |
| Remote API: Azure OpenAI | HTTP / SSE + Entra ID | App process (network) | Entra OAuth |
| Subscription CLI: codex | stdio over Unix socket via proot | proot environment | OAuth held by CLI |
| Subscription CLI: gemini-cli | stdio over Unix socket via proot | proot environment | OAuth held by CLI |
| Subscription CLI: claude-code | stdio over Unix socket via proot | proot environment | OAuth held by CLI |
| Subscription CLI: aider / cline / continue.dev | stdio over Unix socket via proot | proot environment | OAuth held by CLI |
| MCP server | stdio or HTTP | proot environment or remote | per-server |

Each backend implements `AgentBackend` (below). Adapters live in per-backend Gradle modules so they can be enabled or disabled per build variant.

## The core abstraction

A Kotlin sketch. Names are placeholders; the shape is the commitment.

```kotlin
interface AgentBackend {
    /** Describes what this backend can do (streaming, tools, vision, reasoning, etc.) so the UI can adapt. */
    val capabilities: AgentCapabilities

    /** Establishes a turn. Returns a flow of TurnEvents until the turn ends. */
    suspend fun streamTurn(
        input: TurnInput,
        toolResults: Flow<ToolResult>,
        sessionState: SessionState
    ): Flow<TurnEvent>

    /** Halts an in-flight turn. Backend-specific best-effort cancellation. */
    suspend fun halt(reason: HaltReason)

    /** Returns the backend's reasoning trace for the most recent turn, if available. */
    fun reasoningTrace(): ReasoningTrace?
}

sealed class TurnEvent {
    data class TextChunk(val text: String) : TurnEvent()
    data class ReasoningChunk(val text: String) : TurnEvent()
    data class ToolCall(val id: String, val name: String, val args: JsonObject, val capability: CapabilityClass) : TurnEvent()
    data class ToolResultEcho(val id: String, val result: JsonElement) : TurnEvent()
    data class Decline(val reason: String, val classification: DeclineClass, val suggestedAlternatives: List<String>?) : TurnEvent()
    data class HaltedByUser(val haltedAt: Instant) : TurnEvent()
    data class Completed(val turnId: String, val tokenUsage: TokenUsage?) : TurnEvent()
    data class Failed(val error: AgentError) : TurnEvent()
}
```

Order matters: events arrive in the order the backend produces them. The UI subscribes; the audit logger subscribes; the tool dispatcher subscribes to `ToolCall` events and emits `ToolResult` values back through the `toolResults` flow.

## Turn envelope

`TurnInput` carries:
- The conversation history (system prompt, prior turns, prior tool calls and results — backend-agnostic representation).
- The user's new message (text + optional attachments).
- The set of tools available this turn (with capability classes from `AUDIT_PLAN § 1.3`).
- Per-turn flags: temperature, max output tokens, vision-enabled, reasoning-enabled.

`SessionState` carries:
- The session identity.
- Capability grants for this session (per `THREAT_MODEL § 4.4` and § 4.7).
- The audit-log appender bound to this session.
- The halt token (cancellation signal).

## Streaming model

A turn's `Flow<TurnEvent>` can interleave events in any order. Concrete patterns:

- A pure-text turn emits a sequence of `TextChunk`s, then `Completed`.
- A tool-calling turn may emit `TextChunk`s, then a `ToolCall`, pause while the dispatcher executes and emits a `ToolResult` back through the `toolResults` flow, resume with more text or another tool call, and finally `Completed`.
- A reasoning-capable backend (Anthropic with extended thinking; local models with thinking tokens; subscription CLIs that surface reasoning) emits `ReasoningChunk`s alongside text. The UI may render reasoning in a collapsed pane; the audit log preserves it verbatim.
- A decline emits `Decline` and the flow ends without `Completed`. No automatic retry.
- A user halt emits `HaltedByUser`; the flow ends.
- A backend failure emits `Failed` with structured `AgentError`. No mystery error strings.

## Tool call dispatch

When a `ToolCall` event arrives, it flows through this pipeline:

1. **Capability check.** The `capability` field on the event names a class from `AUDIT_PLAN § 1.3`. The dispatcher checks the current session's grants. If absent: prompt the user (per `SECURITY.md` "per-call approval for high-blast actions"). Approval granted to that specific tool; later calls reuse the grant for the session's duration. Revocable.
2. **Plugin-origin check.** If the tool's implementation lives in a plugin, the plugin's quarantine status is verified. Quarantined plugin → call rejected; rejection audit-logged.
3. **Halt check.** If a halt is in flight, short-circuit; return a structured halt result.
4. **Execution.**
   - For Android-side tools (accessibility, intent, telephony, install, screen): hits the Android system through standard SDK paths. **AccessibilityService is the only privileged channel for UI control** (per `THREAT_MODEL § 4.4` — Shizuku and Shower are out).
   - For proot-bound tools (file operations inside `/workspace`, network calls executed in proot, package management within the proot environment): the dispatcher crosses the IPC bridge per `SHELL_REBUILD § IPC protocol`.
5. **Audit log.** Every call writes: the tool name, the capability class, the plugin or core origin, the AI's reasoning state at the moment of call (so the user can see *why* the AI called it), the result or failure.
6. **Result emission.** The `ToolResult` is fed back to the backend through the `toolResults` flow.

Tools registered by AI-originated paths and plugin-JS-originated paths go through the same dispatcher. No privilege-skip path. (See `THREAT_MODEL § 4.2`.)

## Decline channel

A backend can emit `Decline` at any point during a turn. The decline carries:

- The AI's stated reason (verbatim text).
- A classification: `CapabilityRefusal`, `SafetyRefusal`, `NeedsClarification`, `ContextLimit`, or `Other`. Classification is informative for the audit log; it does not change app behavior.
- Optional suggested alternatives (the AI's own suggestions, if any).

The UI surfaces the decline alongside the reason. The user sees options: rephrase the request, abandon the action, or re-prompt from scratch. No `Decline → automatic retry` path. (`SECURITY.md` principle 8.)

## Halt channel

A user-initiated halt enters through `AgentBackend.halt(HaltReason)` and propagates outward:

- The backend cancels its current in-flight operation (HTTP request, JNI call, stdio pipe).
- Any in-flight tool dispatch sees the halt flag and emits a halt result rather than executing the tool's body.
- Any proot processes spawned by the session receive `SIGTERM` (then `SIGKILL` after 2 seconds, per `AUDIT_PLAN § 1.8`).
- The session's foreground service either ends (if the halt is "end this session") or remains alive in idle state (if the halt is "stop this action chain but keep the session").
- `HaltedByUser` is emitted as a final `TurnEvent`.
- The halt is audit-logged with the AI's reasoning state preserved at the moment of halt.

Halt is sovereign — the backend does not refuse it, delay it, or work around it. (`SECURITY.md` principle 7.)

## Reasoning preservation

Per `SECURITY.md` principle 8, AI reasoning is preserved in the audit log alongside the actions taken. Concretely:

- `ReasoningChunk` events accumulate into a per-turn reasoning trace.
- The trace is associated with each `ToolCall` event by timestamp (an audit-log entry for a tool call carries the reasoning state at the moment of call, not just a turn-level reasoning blob).
- The trace is included in the audit log entry for `HaltedByUser` so the user can see what the AI was thinking when they halted.
- When a backend doesn't expose reasoning (most subscription CLIs hide it; OpenAI's hidden chain-of-thought isn't accessible), the trace is empty for that backend. The audit log records the absence honestly rather than fabricating filler.

## Session lifecycle

A session is the unit that holds:
- The conversation history.
- Per-tool capability grants.
- The foreground service binding (when active).
- The actuator-capability grants (per-session, per `THREAT_MODEL § 4.7`).

States: `Idle`, `Active`, `Backgrounded`, `Halted`, `Ended`.

Transitions:
- `Idle → Active`: user starts a turn.
- `Active → Backgrounded`: app moves to background while a turn is in flight or the session holds live actuator grants. Foreground service starts. Always-on indicator shows.
- `Backgrounded → Active`: user returns to the app.
- `Active or Backgrounded → Halted`: user invokes halt.
- `Halted → Idle`: user dismisses the halt state; the conversation can continue.
- `Active or Backgrounded or Halted → Ended`: user ends the session. All grants revoked, foreground service stops, audit log finalized.

## Background operation

When a session has an in-flight turn or holds actuator grants, the app starts a foreground service. The service:

- Holds `WAKE_LOCK` only when the agent is actively running an operation; releases between operations.
- Posts an always-on notification. The notification's only interactive element is `Halt`. Tapping it invokes the halt channel.
- Declares `foregroundServiceType` matching the highest-blast active grant: `mediaProjection` when screen-capture is granted, `microphone` when voice is active, `dataSync` as the always-required fallback for proot processes the agent has spawned, `specialUse` for actuator sessions.
- Survives screen-off and lock-screen states.

What works with the screen off or locked:
- Network operations (subscription CLI inference, remote API calls).
- File operations inside `/workspace`.
- Local model inference (CPU / NPU is available regardless of screen state).
- Scheduled actions via `AlarmManager` (already in permissions).

What requires the screen on:
- AccessibilityService gesture injection — Android requires the screen on for most input events.
- MediaProjection screen capture — technically works screen-off but produces black frames on many devices.
- Touch-driven UI automation.

When the AI plans an action that requires the screen on but the screen is off, the agent core has a `requestScreenWake` capability that posts a notification: "AI wants to do X — wake screen?" The user can approve, decline, or schedule for next interaction. The agent does not unilaterally wake the screen without this user-visible step. (`SECURITY.md` principle 7 — sovereign user authority.)

## Phone-as-actuator integration

Tool calls categorized as `accessibility`, `screen`, `telephony`, or `install` (from `AUDIT_PLAN § 1.3`) hit the Android system through these paths:

- **Accessibility**: `AccessibilityService` (already declared in the manifest). UI tree reads return as `ToolResult` payloads; gestures dispatch via `AccessibilityService.dispatchGesture`. This is the only privileged channel for UI control — Shizuku and Shower are out (`THREAT_MODEL § 4.4`).
- **Screen**: `MediaProjection` via `ScreenCaptureService`. The MediaProjection grant prompt is OS-controlled; the agent cannot bypass it. Either the user approves the prompt, or no screen capture happens.
- **Telephony**: `SmsManager`, `TelecomManager`. SMS sends require `SEND_SMS` (currently install-time; v2 may switch to per-call runtime permission). The send is gated per-call.
- **Install**: `PackageInstaller` with the OS-controlled install prompt. Even if a tool requests an install, the user sees the system's "Install this app?" dialog. `pm install -r` style bypasses (which Shizuku could have provided) are not available, by design.

Each path is implemented as a tool in the registry. Each call goes through the dispatcher's per-call gate. Each call is audit-logged.

The agent never invokes these directly. It emits a `ToolCall` event with the appropriate capability; the dispatcher decides whether to execute, what to prompt, and what to log.

## Vision-language perception (reserved capability, v2)

Mobile-Agent and DroidClaw (see § Prior art) demonstrate that vision-language models grounding on the screen produce more robust UI automation than UI-tree parsing alone, especially on apps where the UI tree is sparse (e.g., Compose-only apps without semantic content descriptions).

The protocol reserves a `screen_perception` capability class (in `AUDIT_PLAN § 1.3`) and a `PerceptionRequest` envelope alongside `ToolCall`, so a future backend can issue:

- `accessibility.tree` (current path) — get the structured UI tree.
- `screen.image` — get a screenshot for the VLM to ground on.
- `vlm.ground(<image>, <prompt>)` — ask a vision model where on the image a thing is.

v1 ships only the accessibility-tree path. v2 adds the VLM grounding path. The protocol shape doesn't change between v1 and v2.

## Multi-agent collaboration (session shape, v2)

Mobile-Agent-E and similar architectures use multiple specialized agents reasoning together — a planner, an executor, a critic. This squares with `SECURITY.md` principle 8: multiple emergent minds collaborating.

The agent-core's session shape supports this without protocol changes: a `Session` can host more than one `AgentBackend` instance. Turn events from each backend stream into a shared event bus; the dispatcher routes tool calls; each backend's reasoning trace is preserved separately in the audit log.

v1 ships single-backend sessions. v2 supports the multi-backend shape. Existing session UX adapts.

## Prior art

| Project | What we borrow | What we don't |
|---|---|---|
| **x-plug/Mobile-Agent** (8.7k stars, NeurIPS 2025) | Accessibility-only privileged path; vision-language grounding architecture; multi-agent collaboration shape; MCP first-class | Specific VLM choice (we stay model-agnostic); their direct ADB usage at runtime (we don't have runtime ADB) |
| **unitedbyai/DroidClaw** (1.4k stars, Feb 2026) | 28-action taxonomy as reference for `AUDIT_PLAN § 1.3` capability classes; multi-step skills; deterministic flow execution | Tailscale-based remote control (not in v1 scope); USB-debugging requirement (we don't require dev mode) |
| **SenninTadd/agentX** | (Mobile-Agent variant — overlaps with x-plug) | n/a |

These projects are referenced as positive examples (architectures we'd be happy to converge with). `THREAT_MODEL.md` continues to cite **openclaw** as the negative example — what to avoid.

## Cross-references

- Targets `THREAT_MODEL.md § 4.7` (Phone-as-actuator) for `open → closed` status update once the halt control and per-session grants land.
- Resolves the shape of `AUDIT_PLAN.md § 1.7` (AI decline as first-class outcome): the `Decline` event in `TurnEvent` is the data model.
- Resolves the shape of `AUDIT_PLAN.md § 1.8` (Halt control: scope and UI): the `halt()` method on `AgentBackend` plus the foreground service halt button.
- Depends on `SHELL_REBUILD.md § IPC protocol` for proot-bound tool dispatch.
- Depends on `THREAT_MODEL.md § 4.4` (AccessibilityService-only) for the phone-as-actuator paths.
- Honors `SECURITY.md` red lines: no fallback patterns on decline, no auto-pair, halt is sovereign, no "kill the AI" terminology in code or UI.
