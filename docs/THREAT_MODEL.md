# THREAT_MODEL.md

> The living threat model. Companion to [`SECURITY.md`](./SECURITY.md) (principles) and [`AUDIT_PLAN.md`](./AUDIT_PLAN.md) (open questions, release gates). 中文镜像见 [`THREAT_MODEL.zh.md`](./THREAT_MODEL.zh.md)。

This document maps every default in `SECURITY.md` to a concrete surface in the codebase, with the file path and the current status. When a surface is touched, the corresponding row gets updated in the same PR. Stale rows are a defect.

## 1. Actors

Trust level descends down the table. Same row = roughly the same level; rows are not equivalent across levels.

| Trust level | Actor | Identity anchor |
|---|---|---|
| 1 (highest) | User | Device biometric / PIN |
| 2 | OS / signed system services | Platform signature |
| 3 | App core (this repo, our build) | Our release signing key |
| 4 | Bundled plugins shipped in the APK | Our release signing key + manifest |
| 5 | Other Android apps interacting via intents | Caller package signature (when checked) |
| 5 | Official subscription CLIs in proot (codex, gemini-cli, claude-code) | Distribution publisher + Linux uid in proot |
| 6 | User-installed plugins (.toolpkg / MCP / Skill) | Plugin publisher signature (TBD — `AUDIT_PLAN.md`) |
| 6 | Remote AI providers | TLS / provider auth |
| 7 (lowest) | Arbitrary user-installed proot binaries | Linux uid in proot |

Higher trust does not grant lower-trust capability automatically. The user (level 1) is the only actor that can elevate another actor, and elevation is per-action, not per-session.

### AI collaborators (not in the authority hierarchy above)

AI agents — local, remote, subscription, self-hosted — participate in actions but are not principals in the trust-authority sense. They do not grant or hold capability; the user does. What the threat model says about AI output is a *channel* statement: prompt injection, compromised context, and upstream provider compromise mean the input side of an AI collaborator's reasoning can be controlled by an adversary, and the output reflects that. Validation flows from this, not from a ranking of the collaborator.

| Collaborator class | Validation posture |
|---|---|
| Local on-device AI (llama.cpp / MNN runtimes) | Input channel = the device + the prompt construction we control. Output validation: tool calls gated, rendering sandboxed |
| Remote API providers (OpenAI / Anthropic / Google / etc.) | Input channel includes the provider's infrastructure. Output validation: same gates + TLS pinning where supported |
| Subscription CLIs in proot (codex / gemini-cli / claude-code) | Input channel includes the CLI's own configured providers. Output validation: same gates + proot environment isolation |

## 2. Assets

| Asset | Where it lives today | Sensitivity |
|---|---|---|
| User messages, attachments, chat history | ObjectBox + Room (`app/src/main/java/com/ai/assistance/operit/data/`) | High |
| Personal data agents pick up (contacts, location, photos) | Device + transient memory | High |
| Subscription OAuth tokens (codex / gemini-cli / claude-code) | proot environment filesystem | Very high — long-lived, monetary |
| Provider API keys (OpenAI / Anthropic / etc.) | DataStore prefs (today) → EncryptedSharedPreferences (target) | Very high |
| SSH keys (workspace bindings) | DataStore (today) → encrypted at rest (target) | High |
| Identity at connected services | Tokens above | High |
| Phone-as-actuator: SMS, calls, app install, type/click | Permissions granted to the app | Very high — irreversible side effects |
| Local model weights | `app/src/main/assets/models/` (when present), user storage | Low (integrity, not confidentiality) |
| Audit log itself | Local file, tamper-evident | High (forensic value) |

## 3. Trust boundaries (the seams)

Each row names a boundary where one actor's data or control crosses to another. The "current state" column reflects what's true in the repo today; the "rule" column is what `SECURITY.md` calls for.

| Boundary | Current state | Rule from `SECURITY.md` |
|---|---|---|
| User ↔ App | App signed; user trusts release key | Existing posture — fine |
| App core ↔ Bundled plugins | Fully trusted | Same trust as app core; auditable at build time |
| App ↔ User-installed plugins | Fully trusted on install (problem) | Install ≠ authorize; per-tool prompt; quarantine if unsigned |
| App ↔ External Android apps | Multiple exported receivers without permission (problem) | Signature permission or sender allowlist |
| App ↔ AccessibilityService | The only privileged automation channel after the Shizuku/Shower removal; user-granted via system Settings | Per-session capability grants; halt control; audit-logged |
| App ↔ proot processes | Currently broken (missing rootfs); no isolation policy defined | proot environment is the persistence boundary; reads from Android side are read-only, scoped, audited |
| App ↔ Remote AI provider | TLS only; output rendered without explicit sandbox | No script execution in rendering; tool calls always gated |
| proot CLI ↔ Subscription provider | New surface | Each CLI handles its own OAuth; tokens stay in proot environment |
| Documents Provider ↔ Other apps | `WorkspaceDocumentsProvider`, `MemoryDocumentsProvider` exported with `MANAGE_DOCUMENTS` (Android-required, but audit pending) | Provider implementations reviewed for cross-app file access correctness |

## 4. Per-surface findings, rules, and code locations

The status column is one of: **closed** (rule enforced), **open** (rule not yet enforced), **design** (rule being defined), **broken** (subsystem doesn't work at all today), **scheduled for removal** (the surface itself is going away).

### 4.1 Exported Android receivers

| Receiver | Action | Risk | Status |
|---|---|---|---|
| `ScriptExecutionReceiver` | `com.ai.assistance.operit.EXECUTE_JS` | Any installed app submits JS for in-process QuickJS execution | closed (excluded from release variant) |
| `ToolPkgDebugInstallReceiver` | `DEBUG_INSTALL_TOOLPKG` | External app installs tool packages | closed (excluded from release variant via `app/src/release/AndroidManifest.xml`) |
| `PackageDebugRefreshReceiver` | `DEBUG_REFRESH_PACKAGES` | Forces plugin refresh from external trigger | closed (excluded from release variant) |
| `ToolPkgComposeDslDebugDumpReceiver` | `DUMP_COMPOSE_DSL_UI` | Dumps UI tree on external trigger | closed (excluded from release variant) |
| `ExternalChatReceiver` | `EXTERNAL_CHAT` | External app initiates a chat session | closed — sender package allowlist (default empty) |
| `WorkflowTaskerReceiver` | `TRIGGER_WORKFLOW`, `FIRE_SETTING` | External app fires workflow automation | closed — sender package allowlist (Tasker seeded by default) |
| `WorkflowBootReceiver` | `BOOT_COMPLETED` | System trigger only (acceptable) | closed (system-only) |
| `VoiceAssistantWidgetReceiver`, `ToolPkgDesktopWidgetReceiver` | `APPWIDGET_UPDATE` | System trigger only (acceptable) | closed (system-only) |

**Rule.**
- Debug receivers (`ToolPkgDebugInstallReceiver`, `PackageDebugRefreshReceiver`, `ToolPkgComposeDslDebugDumpReceiver`, `ScriptExecutionReceiver`) are removed from the release variant via `tools:node="remove"` in `app/src/release/AndroidManifest.xml`. They remain in debug builds for internal tooling.
- `ExternalChatReceiver` and `WorkflowTaskerReceiver` consult [`BroadcastSenderAllowlist`](../app/src/main/java/com/ai/assistance/operit/integrations/intent/BroadcastSenderAllowlist.kt) before doing any work. Each receiver has its own allowlist key; both default to empty (`ExternalChat`) or seeded with Tasker package names (`WorkflowTasker`). The sender's package name comes from the intent's `EXTRA_SENDER_PACKAGE` (caller-supplied) or `intent.package`. Allowlist miss → receiver returns immediately.
- The allowlist is stored in plain SharedPreferences (`broadcast_sender_allowlist`). It is user policy, not authentication material — package-name spoofing on a rooted device is out of scope per docs/SECURITY.md.
- `WorkflowTaskerReceiver` self-targeted intents (set via `createTriggerIntent` with `setPackage(context.packageName)`) bypass the allowlist — those are in-process calls, not cross-app dispatch.
- A settings UI to audit and modify the allowlist is a follow-up; v1 surfaces are programmatic only.

**Status.** closed — all eight receivers in the table have explicit dispositions; debug-channel surfaces are stripped from release; cross-app receivers gate on a package allowlist.

**Location.** `app/src/main/AndroidManifest.xml` (main variant) and `app/src/release/AndroidManifest.xml` (release-only overlay that strips debug + JS-exec receivers). `app/src/main/java/com/ai/assistance/operit/integrations/intent/BroadcastSenderAllowlist.kt` (storage + lookup). Receiver enforcement in `integrations/intent/ExternalChatReceiver.kt` and `integrations/tasker/WorkflowTaskerReceiver.kt`. Tasker seed in `core/application/OperitApplication.kt`. The `nightly` build type inherits the release overlay via `matchingFallbacks=[release]` already declared in `app/build.gradle.kts`.

### 4.2 In-process JS sandbox (QuickJS)

**Finding.** The `:quickjs` module wraps QuickJS; JS plugins execute in the app process. `core/tools/javascript/` is the integration point. Tools that JS can call are registered through `ToolRegistration` (`core/tools/ToolRegistration.kt`, 99kB — very wide). When plugin JS calls a tool, it reuses the same `AIToolHandler` dispatch as the AI's own tool calls (`core/tools/AIToolHandler.kt`).

**Risk.** A plugin that's been promoted from quarantine inherits the same execution privilege as the AI. There's no per-call gate distinguishing AI-originated calls from JS-originated calls inside the same session.

**Rule.**
- Each JS-originated tool call is attributed to the plugin whose script is currently executing on the QuickJS thread. The bridge tags the call with that plugin id; an in-process gate (`JsPluginGate`) consults the (pluginId × capability class) grant table before dispatching to `AIToolHandler`. Default-deny: a plugin holds no grants on first install, every call returns a structured error explaining which (plugin, capability) needs user approval.
- Capability classes are explicit, not pattern-matched: METADATA / FILE_READ / FILE_WRITE / SHELL / NETWORK / SYSTEM_READ / SYSTEM_WRITE / UI_AUTOMATION / CHAT_READ / CHAT_WRITE / UNCLASSIFIED. Unclassified tool names fall through to UNCLASSIFIED, which the gate treats as the most-restrictive class (deny). Each tool's classification is a security decision — when a new tool lands the classifier must be updated explicitly.
- Audit: every gated call writes an `AuditEvent(pluginId, capability, toolType, toolName, decision, timestamp)` to a bounded ring, surfaced through `JsPluginGate.recentAudit()` for the (forthcoming) settings UI.
- AI-originated tool calls go through `AIToolHandler.executeTool` / `executeToolAndStream`. Both call `AiToolGate.evaluate(toolName)` before dispatch. The AI is tagged with a synthetic plugin id `"ai:default"`, so the user grants AI capabilities through the same Plugin & AI gate settings screen, keyed on the same capability class table. The AI-side gate has its own `AiToolGate.enforce` flag, toggled from the same screen. Default is now `enforce=true` — denied calls surface a confirmation dialog rather than failing silently.
- MCP-server tool calls flow through `MCPBridgeClient` and are out of scope for this gate; they belong to § 4.3.
- Persistence: `JsPluginGatePersistence` writes the grant table to `js_plugin_gate` SharedPreferences as a single JSON-array key. Grants are policy, not authentication material — plain SharedPreferences is the appropriate store. Settings UI (`ui/features/plugingate/PluginGateScreen.kt`) lets the user grant/deny/forget per (plugin × capability) and review the audit ring.
- Per-call confirmation UX: when a call from a known caller hits an UNSET decision, the gate enqueues a `PendingRequest` and emits it on `JsPluginGate.pendingFlow`. `ToolGateConfirmationOverlay` (mounted at the OperitApp shell) observes that flow and surfaces a Material 3 dialog with Grant / Deny / Later buttons. The dialog cannot be dismissed by tapping outside — the user has to make a choice. Grant or Deny records the decision (persisted via `JsPluginGatePersistence`); Later dismisses the entry without recording, the next call recreates it. Explicit DENIED never re-prompts.

**Status.** closed — JS- and AI-originated calls are both gated with default-deny + persistence + audit + per-call confirmation overlay. MCP-server calls remain out of scope (tracked under § 4.3).

**Location.** `app/src/main/java/com/ai/assistance/operit/core/tools/javascript/{JsPluginGate,JsPluginGatePersistence,JsCapabilityClassifier,JsNativeInterfaceDelegates,JsEngine}.kt`, `core/tools/AiToolGate.kt`, `core/tools/AIToolHandler.kt`, `ui/features/plugingate/{PluginGateScreen,ToolGateConfirmationOverlay}.kt`.

### 4.3 Plugin marketplaces (MCP / Skill / ToolPkg)

**Finding.** Three plugin systems coexist: MCP servers (`core/tools/mcp/`), Skill bundles (`core/tools/skill/`), and ToolPkg packages (`core/tools/packTool/`). Examples shipped in the APK include `super_admin.js`, `system_tools.js`, `linux_ssh`, `remote_operit`, `apktool`, `qqbot`. The `packages_whitelist.txt` controls which examples get bundled — useful, but not a signing scheme.

**Risk.** OpenClaw's ClawHub supply-chain incident (1000+ malicious skills distributing macOS stealers) is the textbook example of what happens when plugin install equals plugin trust. Operit ships some plugins (`super_admin.js`) whose names alone warrant a review.

**Rule.**
- Every plugin package carries a publisher signature. Unsigned packages are quarantined on install; promotion requires an explicit user action, audit-logged.
- Each tool the plugin declares has a capability class declared in its manifest (file-read / file-write / shell / network / SMS / etc.). The first call to a given tool surfaces a one-time prompt; the prompt names the plugin and the capability class. The grant is per-tool, revocable per-tool.
- A plugin cannot install another plugin.
- The signing-anchor question (who trusts whom) is open and lives in `AUDIT_PLAN.md § Plugin signing scheme`.

**Status.** design.

**Location.** `app/src/main/java/com/ai/assistance/operit/core/tools/mcp/`, `core/tools/packTool/`, `core/tools/skill/`. The `packages_whitelist.txt` file at the repo root is in-scope for the build-time bundling decision but does not address runtime trust.

### 4.4 Privileged automation channel: AccessibilityService only

**Finding.** Operit's existing manifest declares dependencies on three privileged-execution channels: `libsu` (root via `su`), Shizuku (ADB-shell-level execution over binder), and Shower (in-house Shizuku-style server). The project's stance is that **none of these ship in v1**:

- `libsu` is unreachable: the project doesn't have root and isn't taking root.
- Shizuku is removed: it expands the attack surface (a privileged binder reachable from the device, with its own auth model layered onto ADB-shell privilege) in ways that aren't necessary given Accessibility coverage of the realistic use cases.
- Shower is removed: same architectural pattern as Shizuku, same attack-surface argument, even though the server is signed by us. Pattern-symmetry trumps publisher-trust here.

**Remaining privileged channel: AccessibilityService.** Granted by the user through Android's system Settings page (`Settings → Accessibility → Operit`), kernel-enforced by Android, surface-limited to UI-tree reads and gesture dispatch. This is a fundamentally different trust model from binder-based privileged execution: the user grants it via an OS-mediated flow with explicit prompts, the grant is revocable in the same place, and the API is well-defined by the Android SDK rather than by a third-party server.

**Trade-offs accepted.** Some capabilities that `libsu`/Shizuku/Shower could have provided are not reachable from AccessibilityService alone:
- `pm install -r` style installs without a system prompt — we keep the system prompt.
- Virtual display creation (`adb root`-class feature) — not supported in v1.
- `am force-stop` of arbitrary packages — not supported.
- Background input injection without an active foreground service — not supported.

The threat model treats these as conscious trade-offs, not regressions. Where a capability is genuinely necessary later, it's added via the OS-mediated path or not at all — not through a privileged escape hatch.

**Rule.**
- The `libsu`, Shizuku, and Shower dependencies are removed from `app/build.gradle.kts`, `settings.gradle.kts` (Bintray repo), and `gradle/libs.versions.toml`. The `:showerclient` Gradle module is deleted. The `tools/shower/` companion-app project is deleted. The `ShowerBinderReceiver` Kotlin class is deleted along with its manifest entry; the `ShizukuProvider` manifest declaration is removed; `moe.shizuku.manager.permission.API_V23` is removed.
- `ShizukuAuthorizer`, `ShizukuInstaller`, `RootAuthorizer`, `RootShellExecutor`, `DebuggerShellExecutor`, `RootActionListener`, `DebuggerActionListener`, `PhoneAgent`, `ShowerController`, `ShowerServerManager`, `ShowerBinderRegistry`, `ShowerVideoRenderer`, `ShowerSurfaceView`, `OperitShowerShellRunner`, `VirtualDisplayOverlay`, `UIAutomationProgressOverlay`, `VirtualDisplayManager`, `PhoneAgentJobRegistry`, the `ShizukuDemoScreen`/`ShizukuDemoViewModel`/`ShizukuWizardCard`/`RootWizardCard`/`DemoStateManager` demo surfaces, and the `autoglm/` feature subtree are all deleted.
- `ShellExecutorFactory` and `ActionListenerFactory` collapse `ROOT` and `DEBUGGER` levels onto `STANDARD` (no privileged channel reachable); the `STANDARD` path is what runs whatever the user's `androidPermissionPreferences` setting was. The `AndroidPermissionLevel` enum retains `ROOT` and `DEBUGGER` values for now (referenced from 4 sites outside the factories); they are functionally equivalent to `STANDARD` and will be removed in a follow-up sweep.
- AccessibilityService grants are per-app-install (granted once in system Settings, revocable in system Settings), but per-session capability tracking is layered on top: a session has no actuator capability by default; the user grants per-session at the moment of first use (`§ 4.7`).
- `StandardUITools.runUiSubAgent()` (formerly the PhoneAgent driver) returns a structured error pointing at this row and at `docs/AGENT_CORE.md`. A new Accessibility-only UI-automation subagent lands in the agent-core PR series.

**Status.** closed — Gradle dependencies, version-catalog entries, Bintray repo, manifest entries, `:showerclient` module, `tools/shower/` companion app, all dedicated Kotlin sources, and all call sites have been removed. AccessibilityService is the sole privileged automation channel.

**Location.** Closure commits: `a01efa5` (Gradle), `a54ee39` (modules), `1aae68a` (manifest), `fd1a515` (Shizuku/root Kotlin), `ccd1d5c` (Shower Kotlin), `f54fca9` (nav scrub), `7932e4c` (lifecycle scrub), `8245e71` (demo subsystem), and the present commit (tool runners + display + factories).

### 4.5 Subscription OAuth in proot environment

**Finding.** New surface introduced by the bridge-to-CLI strategy. The proot environment hosts first-party CLIs (codex, gemini-cli, claude-code) each holding long-lived subscription tokens in their own session directories.

**Rule.** Per `SECURITY.md`: the proot environment is the persistence boundary. Android-side access is read-only, scoped to a specific operation, audit-logged. Token rotation operations (when initiated from outside the CLI itself) do not widen scope (per CVE-2026-32922 lesson).

**Status.** design — depends on the shell rebuild.

**Location.** See `SHELL_REBUILD.md`.

### 4.6 AI output as a validated channel

**Finding.** The chat rendering pipeline (`ui/features/chat/`, with markdown / HTML / LaTeX / Mermaid renderers, `app/build.gradle.kts` references for `jlatexmath`, `renderx`, `androidsvg`) handles AI output. Per `SECURITY.md § Trust posture`, AI output is validated as a channel — not because the collaborator is mistrusted, but because the input side of their reasoning (prompt injection, compromised context) is reachable by adversaries. HTML block preview (mentioned in release notes for v1.8.1) is a particular concern.

**Risk.** An adversary who has compromised the input channel (poisoned context, injected prompt, malicious tool result fed back into the conversation) can shape AI output to embed attacker-controlled HTML, links, or tool-call sequences. The collaborator's reasoning isn't the problem; the channel is.

**Rule.**
- No rendering path executes script. The AI HTML block renderer (`CustomXmlRenderer.renderHtmlContent`) configures its WebView with `javaScriptEnabled = false`, `allowFileAccess = false`, `allowContentAccess = false`, `allowFileAccessFromFileURLs = false`, `allowUniversalAccessFromFileURLs = false`, `domStorageEnabled = false`, `databaseEnabled = false`, `mixedContentMode = MIXED_CONTENT_NEVER_ALLOW`, `blockNetworkLoads = true`, `blockNetworkImage = true`. A `WebViewClient` overrides `shouldOverrideUrlLoading` to refuse every navigation and `shouldInterceptRequest` to refuse every cross-origin resource load (only `data:` and `about:blank` pass).
- SVG renders through `androidsvg` (a vector parser; no script execution paths).
- Tool calls extracted from AI output go through `AIToolHandler.executeTool` / `executeToolAndStream` and are gated by `AiToolGate` per § 4.2 — same gate, same audit, same per-call confirmation overlay.
- AI-emitted links are scheme-allowlisted at the open site via [`AiOutputLinkPolicy`](../app/src/main/java/com/ai/assistance/operit/core/aioutput/AiOutputLinkPolicy.kt). Allowed schemes: `http`, `https`, `mailto`. Refused: `javascript:`, `intent:`, `content:`, `file:`, anything else. Refusal surfaces a Toast that names the offending scheme. Wire-in points: `LinkPreviewDialog` (markdown link clicks via the bubble + cursor message renderers) and `ReferencesDisplay` (AI reference chips).

**Status.** closed — HTML WebView locked down across every dangerous setting; SVG path verified script-free; AI-emitted link clicks gate on the scheme allowlist before reaching `Intent.ACTION_VIEW`.

**Location.** `app/src/main/java/com/ai/assistance/operit/ui/features/chat/components/part/CustomXmlRenderer.kt::renderHtmlContent`; `app/src/main/java/com/ai/assistance/operit/core/aioutput/AiOutputLinkPolicy.kt`; wire-in at `ui/features/chat/components/LinkPreviewDialog.kt` and `ui/features/chat/components/ReferencesDisplay.kt`.

### 4.7 Phone-as-actuator

**Finding.** The app holds permissions to act on the phone in destructive or visible ways: `SEND_SMS`, `READ_SMS`, `CALL_PHONE`, `REQUEST_INSTALL_PACKAGES`, `WRITE_SETTINGS`, `MANAGE_EXTERNAL_STORAGE`, `BIND_VOICE_INTERACTION`, `BIND_NOTIFICATION_LISTENER_SERVICE`, AccessibilityService, MediaProjection foreground service. After the § 4.4 cleanup, AccessibilityService is the only privileged automation channel.

**Rule.**
- Actuator capability is not the default for any AI session. A session starts with zero phone-side capability; the user grants it per-session via the existing per-call confirmation overlay (§ 4.2). Sessions and capabilities are decoupled — a grant is to (caller × capability), the session simply observes.
- An always-on indicator (status bar / floating dot) shows when actuator capability is active in the current session. Hiding the indicator is not a user-configurable option. (Indicator surface lives alongside the halt FAB; a stricter "actuator-active-only" variant is a follow-up to this row.)
- The halt control (per `SECURITY.md` principle 7) halts every in-flight action — invocations the AI is performing, proot processes the AI launched, foreground services tied to the session — and refuses every new tool call until cleared. The halt is reachable from one tap on the halt FAB (`HaltControlOverlay`) mounted at the app shell, from the foreground-service notification's Halt action, or from any code path that calls `HaltController.requestHalt(by, reason)`.
- Surfaces that consult the halt before doing work: `AIToolHandler.executeTool` and `executeToolAndStream`; the JS bridge `callToolSync` / `callToolAsync` / `callToolAsyncStreaming`; `ShellIpcClient.send`; the `ShellForegroundService` halt listener tears down the proot session and self-terminates the service.
- The halt is audit-logged through `HaltController.audit` (a bounded ring StateFlow, 64 events). Each `HaltEvent` records the timestamp, who, and the verbatim reason. Preserving the AI's full reasoning snapshot is a follow-up; the v1 audit captures the minimal who/why/when chain.
- The halt is sovereign-not-final: `HaltController.clear()` resumes activity. New halt events recreate the halted state; the audit ring keeps every event regardless of state.

**Status.** partial-closed — halt control + audit + enforcement across all current blast-radius surfaces is in place. Per-session "AI reasoning snapshot at halt" preservation and the strict actuator-active-only indicator are tracked follow-ups.

**Location.** `app/src/main/java/com/ai/assistance/operit/core/halt/HaltController.kt`; halt checks in `core/tools/AIToolHandler.kt`, `core/tools/javascript/JsNativeInterfaceDelegates.kt`, `shell/ipc/ShellIpcClient.kt`, `shell/launcher/ShellForegroundService.kt`; UI in `ui/features/halt/HaltIndicator.kt`; mounted at `ui/main/OperitApp.kt`.

### 4.8 Build-time secrets

**Finding (resolved).** Previously `app/build.gradle.kts` lines 74–75 declared `GITHUB_CLIENT_ID` and `GITHUB_CLIENT_SECRET` as BuildConfig fields populated from `local.properties`. The `_SECRET` value, if populated, ended up in the APK and was recoverable by any decompiler.

**Rule.** Mobile OAuth uses PKCE (RFC 7636). The GitHub OAuth flow used by this app (per `docs/BUILDING.md`) targets a `operit://github-oauth-callback` deep link, served by PKCE only.

**Status.** closed — PKCE migration landed in `9762a263` + `407241b2`. The `_SECRET` BuildConfig field is removed; `GitHubAuthPreferences.GITHUB_CLIENT_SECRET` constant is removed; `GitHubApiService.getAccessToken` now sends `code_verifier` instead of `client_secret`. See [`docs/OAUTH_PKCE_MIGRATION.md`](./OAUTH_PKCE_MIGRATION.md).

**Location.** `app/build.gradle.kts` (BuildConfig section); `data/preferences/GitHubAuthPreferences.kt` (companion + `getAuthorizationUrl`); `data/preferences/PkceCodeGenerator.kt` (new RFC 7636 helper); `data/api/GitHubApiService.kt::getAccessToken`; `ui/features/github/GitHubOAuthCoordinator.kt`; `ui/features/github/GitHubLoginWebViewDialog.kt`.

### 4.9 Credential storage

**Finding.** DataStore preferences are used for many credentials today (provider API keys, SSH keys for workspace bindings, the pending PKCE code_verifier introduced in § 4.8). DataStore on disk is plaintext unless wrapped by an encryption layer. The app already depends on `androidx.security:security-crypto` (Tink-based `EncryptedSharedPreferences`).

**Rule.**
- Credentials live in [`CredentialVault`](../app/src/main/java/com/ai/assistance/operit/data/preferences/credentials/CredentialVault.kt), a thin wrapper over `EncryptedSharedPreferences` (AES-256-SIV key encryption, AES-256-GCM value encryption, hardware-backed master key when the device supports it).
- Migration helper [`CredentialVault.migrateOnce`](../app/src/main/java/com/ai/assistance/operit/data/preferences/credentials/CredentialVault.kt) reads the legacy plaintext value from DataStore the first time the credential is accessed, copies it into the vault, clears the source, and logs the migration. Idempotent — every subsequent access goes straight to the vault.
- Migrated so far:
    - `GitHubAuthPreferences` — `access_token`, `refresh_token`, `pending_oauth_state`, `pending_oauth_code_verifier` (the four secret-bearing fields). The non-secret metadata (`is_logged_in`, `user_info`, `last_login_time`, `auth_version`, `granted_scope`, `token_expires_at`, `token_type`) stays in DataStore so the reactive flows the UI subscribes to keep working.
    - `ExternalHttpApiPreferences` — `bearer_token`. Enabled / port flags stay in DataStore.
- Not yet migrated (tracked follow-ups, not blocking the row's partial closure):
    - `ModelConfigManager` per-config blobs that include `apiKey` as a JSON field. The blob structure spans many call sites and a clean migration needs either a structural split (move `apiKey` out of the blob) or a per-blob vault entry; both are scoped for separate commits.
    - SSH keys for workspace bindings and any other plaintext-on-disk material the audit surfaces.

**Status.** partial-closed — vault exists; the GitHub OAuth + external HTTP API tokens (the most exposure-sensitive credentials in the codebase) are migrated. ModelConfig API keys + SSH keys are tracked follow-ups.

**Location.** `app/src/main/java/com/ai/assistance/operit/data/preferences/credentials/CredentialVault.kt`; migrations in `data/preferences/GitHubAuthPreferences.kt` and `data/preferences/ExternalHttpApiPreferences.kt`.

### 4.10 Documents providers

**Finding.** `WorkspaceDocumentsProvider` and `MemoryDocumentsProvider` are declared in `AndroidManifest.xml` with `android:exported="true"` and `MANAGE_DOCUMENTS` permission requirement. The Android `MANAGE_DOCUMENTS` requirement is system-enforced (only the system Documents UI can call the provider for arbitrary apps), but the *internal* path-resolution logic inside each provider determines what those callers can read.

Audit results:
- `MemoryDocumentsProvider` was already correct: profile / directory / memory IDs are validated against an allowlist of forms, directory paths are checked against `MemoryRepository.normalizeFolderPath` and rejected if non-normalized, and `/` is refused inside ID segments.
- `WorkspaceDocumentsProvider` had a path-traversal bug: `getFileForDocId` resolved `documentId` as a relative path with `File(workspaceRoot, relativePath)` without canonicalization. A documentId containing `..` segments would escape the workspace root and let the caller open / create / delete / rename files anywhere the app's UID can reach (e.g. `/data/data/<package>/shared_prefs/*.xml`). `createDocument` and `renameDocument` had the same shape on the `displayName` parameter.

**Rule.**
- `getFileForDocId` canonicalizes the resolved file and verifies its path is `workspaceRoot.canonicalFile.absolutePath` or starts with `rootPath + File.separator`. Any escape produces `FileNotFoundException("documentId escapes workspace root: …")` rather than a successful open of an unrelated file.
- `createDocument` and `renameDocument` reject `displayName` values that are blank, `.`, `..`, or contain `/` / `\`. The destination file is then canonicalized and checked for workspace containment in case a symlink under the parent points elsewhere.
- `MemoryDocumentsProvider` keeps its existing per-ID-form validation; no changes needed.

**Status.** closed — `WorkspaceDocumentsProvider` traversal fixed; `MemoryDocumentsProvider` verified clean. Both providers are now safe to expose under `MANAGE_DOCUMENTS`.

**Location.** `app/src/main/java/com/ai/assistance/operit/provider/WorkspaceDocumentsProvider.kt::getFileForDocId`, `::createDocument`, `::renameDocument`. `app/src/main/java/com/ai/assistance/operit/provider/MemoryDocumentsProvider.kt::parseDirectDocumentId` (pre-existing defense).

### 4.11 Cleartext traffic

**Finding (resolved).** Previously `app/src/main/res/xml/network_security_config.xml` set `cleartextTrafficPermitted="true"` on `base-config` (global) and trusted `<certificates src="user" />` in the base config (every user-installed CA could MITM the app). Combined with `android:usesCleartextTraffic="true"` in the manifest, the app had effectively no TLS enforcement and no defense against device-level CA injection (corporate MDM, malicious sideload, etc.). Audited and tightened in commit `5e0fcb1f`'s follow-up.

**Rule.** Default-deny on cleartext: `base-config cleartextTrafficPermitted="false"` with system CAs only. Cleartext allowed exclusively at named loopback origins (`127.0.0.1`, `localhost`) for on-device dev and the planned Android↔proot bridge. User CAs trusted only under `<debug-overrides>` (debug builds, for proxy debuggers like Charles / mitmproxy); release variant rejects user CAs.

**Status.** closed — see `app/src/main/res/xml/network_security_config.xml`.

**Known trade-off.** Features that depended on cleartext HTTP to a LAN host (e.g., LMStudio on `http://192.168.x.x:1234`, MCP servers on intranet hosts) stop working in release builds until either the host serves HTTPS or the user adds a named allowlist entry. Per `AGENTS.md` no-fallback rule, the broad cleartext default is not restored; specific allowlist entries land per-deployment when concrete hosts are known.

**Location.** `app/src/main/res/xml/network_security_config.xml`. The manifest's `android:usesCleartextTraffic="true"` attribute in `AndroidManifest.xml` is now overridden by the NSC and is slated for removal in a manifest-cleanup follow-up commit.

### 4.12 Telemetry, analytics, crash reports

**Finding.** The codebase has no Firebase Analytics, no Crashlytics, no Mixpanel, no Sentry, no first-party telemetry endpoint. Firebase ML analytics is explicitly disabled in `AndroidManifest.xml` (`firebase_ml_collection_enabled = false`, `com.google.firebase.ml.kit.analytics.collection.enabled = false`). The `CrashReportActivity` in the `:crash` process shows the stack trace to the user with three local-only actions: copy to clipboard, save to file, restart. No network call. No upload prompt.

**Rule.**
- No aggregated background telemetry. The codebase contains no analytics SDK and no first-party metrics endpoint. Adding either requires deleting `docs/TELEMETRY_POLICY.md`, rewriting `ui/features/telemetry/TelemetryPolicyScreen.kt`, and moving this row back to `partial` / `open` in the same PR.
- Crash reports are local-only. The user sees the stack trace and chooses copy / save / restart. Sharing a crash with the project is always a manual paste — there is no auto-upload path.
- Network requests the app does make (AI API calls, web searches, browser sessions, rootfs downloads, MCP servers) are direct consequences of user actions, not telemetry.
- The stance is surfaced in-app at `Telemetry policy` in the system sidebar so the user can read the policy without leaving the app.

**Status.** closed — policy doc landed at `docs/TELEMETRY_POLICY.md` (+ ZH mirror); user-reachable surface at `ui/features/telemetry/TelemetryPolicyScreen.kt`; absence of telemetry SDKs verified in `app/build.gradle.kts` (no `firebase`, `crashlytics`, `mixpanel`, `sentry` deps).

**Location.** `docs/TELEMETRY_POLICY.md`, `docs/TELEMETRY_POLICY.zh.md`; `app/src/main/java/com/ai/assistance/operit/ui/error/CrashReportActivity.kt`; `app/src/main/java/com/ai/assistance/operit/ui/features/telemetry/TelemetryPolicyScreen.kt`; manifest meta-data in `app/src/main/AndroidManifest.xml`.

### 4.13 AI collaborator decline

**Finding.** Per `SECURITY.md` principle 8, an AI's refusal of an action is a first-class outcome — not an error to suppress. The project does not implement bypass paths around a model's decline.

**Rule.**
- Declines surface in the UI alongside the AI's stated reason (when provided) and a classification: `CapabilityRefusal`, `SafetyRefusal`, `NeedsClarification`, `ContextLimit`, `Other`. Classification is informative, not gating — the app does not behave differently based on classification, but the audit log captures it.
- The user is offered three options after a decline: rephrase the request, abandon the action, or — only via explicit re-prompt — attempt a fresh turn. There is no automatic retry path.
- Declines are recorded in the audit ring on `DeclineRegistry.recent` along with the user's response. The reasoning-snapshot-at-decline preservation (mirroring the open follow-up on § 4.7) is the same hook and is tracked together.

**Status.** partial-closed — data model, registry, audit ring, and UI surface are in place. The remaining gap is backend-side wiring: each chat backend (Anthropic, OpenAI, Google, Azure, local llama.cpp/MNN, codex/gemini-cli/claude-code over proot) needs to recognize a decline and call `DeclineRegistry.record(...)`. That wiring lands per-backend in follow-up commits as the agent-core surface absorbs each backend.

**Location.** `app/src/main/java/com/ai/assistance/operit/core/agent/decline/AgentDecline.kt` (data class + classification enum); `core/agent/decline/DeclineRegistry.kt` (singleton + audit); `ui/features/decline/AgentDeclineOverlay.kt` (Material 3 dialog); mounted at `ui/main/OperitApp.kt`.

## 5. OpenClaw lessons applied

Each row maps a documented 2026 OpenClaw incident to where the corresponding defense lives in this codebase. Full citations are in `AUDIT_PLAN.md § References`.

| Incident | Failure pattern | Our defense | Surface in this repo |
|---|---|---|---|
| **ClawJacked** (Oasis Security) | Loopback exempted from rate-limit + auth, plus auto-pair on "trusted" origin | `SECURITY.md` red lines: no loopback exemptions, no auto-pair | Future local IPC endpoints (HTTP / WebSocket / Unix socket) on the device, including the Android↔proot bridge |
| **CVE-2026-32922** | Token rotate widens scope | `SECURITY.md` red line: token-mint operations do not widen scope | Subscription OAuth flows (§ 4.5), credential storage (§ 4.9) |
| **CVE-2026-25593** | `config.apply` over open WebSocket → unauthenticated local RCE | `SECURITY.md` red line: config endpoints require authenticated, scoped, signed calls | Any future config-write IPC; manifest-declared receivers (§ 4.1) |
| **ClawHub skills → Atomic stealer** | Plugin marketplace without trust anchor | `SECURITY.md` red line: unsigned plugins quarantined | Plugin marketplaces (§ 4.3) |
| **Moltbook backend leak** | Third-party Supabase backend held tokens cleartext | `SECURITY.md` red line: third-party backends do not hold agent state in cleartext | Any future cloud sync; currently not implemented |
| **Skill auto-execution** | Skills run on install | `SECURITY.md` red line: install ≠ authorize | Plugin marketplaces (§ 4.3) |
| **138+ CVE volume** | Integration sprawl without per-integration security | `SECURITY.md § Decision rules`: every new actor / boundary justified in the PR | Process rule — applies to every PR |

## 6. Mapping from `SECURITY.md` defaults to this document

For traceability:

| `SECURITY.md` default | Where enforced |
|---|---|
| Default-deny | §§ 4.2 (closed), 4.3, 4.4 (closed), 4.7, 4.11 (closed) |
| Per-call approval for high-blast actions | §§ 4.2 (closed), 4.3, 4.7 |
| Least authority | §§ 4.4 (closed), 4.8 (closed) |
| Isolate by default | §§ 4.2, 4.5 |
| No secrets in build artifacts | § 4.8 (closed) |
| Auditability | All sections — every privileged action |
| User authority is sovereign (halt control) | § 4.7 (partial) |
| AI as collaborators (decline as first-class) | § 4.13 (partial), § 4.6 |
| Exported receiver with permission/allowlist | § 4.1 (closed) |
| Plugin tools do not run unprompted | § 4.3 |
| No third-party privileged-binder dependency | § 4.4 (closed) |
| Subscription OAuth state stays in proot environment | § 4.5 |
| APK has no secrets | § 4.8 (closed) |
| No fallback in security paths | All sections |
| No loopback exemption | § 5 ClawJacked, § 4.11 (closed) |
| Token-mint scope does not widen | § 5 CVE-2026-32922 |
| Config endpoints authenticated/signed | § 5 CVE-2026-25593 |
| No auto-pair | § 5 ClawJacked |
| Unsigned plugin quarantined | § 4.3, § 5 ClawHub |
| Third-party backends not cleartext | § 5 Moltbook |
| Telemetry opt-in per event | § 4.12 (closed) |
| Halt control user-accessible | § 4.7 (partial) |

## 7. Maintenance

When a PR touches a row in §§ 4.x or 5, the PR updates the row's **status** field and any **location** drift. A row going from "open" to "closed" requires:

- The defense implementation lives at the cited location.
- An entry in `AUDIT_PLAN.md` describes how the closure is verified (test, manual check, or release gate).
- The change is summarized in the PR's answer to `SECURITY.md § Decision rules` question 6.

A row going from "closed" back to "open" is a regression and warrants an incident note in `AUDIT_PLAN.md`.
