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
| `ScriptExecutionReceiver` | `com.ai.assistance.operit.EXECUTE_JS` | Any installed app submits JS for in-process QuickJS execution | open |
| `ToolPkgDebugInstallReceiver` | `DEBUG_INSTALL_TOOLPKG` | External app installs tool packages | closed (excluded from release variant via `app/src/release/AndroidManifest.xml`) |
| `PackageDebugRefreshReceiver` | `DEBUG_REFRESH_PACKAGES` | Forces plugin refresh from external trigger | closed (excluded from release variant) |
| `ToolPkgComposeDslDebugDumpReceiver` | `DUMP_COMPOSE_DSL_UI` | Dumps UI tree on external trigger | closed (excluded from release variant) |
| `ExternalChatReceiver` | `EXTERNAL_CHAT` | External app initiates a chat session | open — needs sender allowlist |
| `WorkflowTaskerReceiver` | `TRIGGER_WORKFLOW`, `FIRE_SETTING` | External app fires workflow automation | open — needs sender allowlist or signature perm |
| `WorkflowBootReceiver` | `BOOT_COMPLETED` | System trigger only (acceptable) | closed (system-only) |
| `ShowerBinderReceiver` | `SHOWER_BINDER_READY` | Privileged binder handoff (Shower in-house server) | scheduled for removal (see § 4.4) |
| `VoiceAssistantWidgetReceiver`, `ToolPkgDesktopWidgetReceiver` | `APPWIDGET_UPDATE` | System trigger only (acceptable) | closed (system-only) |

**Rule.** Every entry above whose status is "open" gets a signature permission tied to either our own release key (debug/internal channels) or the publisher of the legitimate caller (Tasker for the workflow receiver), plus removal of debug receivers from the release variant via build-type-specific manifests. Entries with "scheduled for removal" are deleted in the implementation PRs cited.

**Location.** `app/src/main/AndroidManifest.xml` (main variant) and `app/src/release/AndroidManifest.xml` (release-only overlay that strips debug receivers). Debug builds keep all receivers for internal tooling. The `nightly` build type inherits the release overlay via `matchingFallbacks=[release]` already declared in `app/build.gradle.kts`.

### 4.2 In-process JS sandbox (QuickJS)

**Finding.** The `:quickjs` module wraps QuickJS; JS plugins execute in the app process. `core/tools/javascript/` is the integration point. Tools that JS can call are registered through `ToolRegistration` (`core/tools/ToolRegistration.kt`, 99kB — very wide). When plugin JS calls a tool, it reuses the same `AIToolHandler` dispatch as the AI's own tool calls (`core/tools/AIToolHandler.kt`).

**Risk.** A plugin that's been promoted from quarantine inherits the same execution privilege as the AI. There's no per-call gate distinguishing AI-originated calls from JS-originated calls inside the same session.

**Rule.** Tool calls originating from plugin JS pass through the same per-call permission gate as AI-originated calls, with the originating plugin identity tagged on the call. Quarantined plugins cannot register handlers; promotion is a user action, audit-logged.

**Status.** open.

**Location.** `app/src/main/java/com/ai/assistance/operit/core/tools/javascript/`, `core/tools/AIToolHandler.kt`, `core/tools/ToolRegistration.kt`.

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
- The `libsu`, Shizuku, and Shower dependencies are removed from `app/build.gradle.kts` and `AndroidManifest.xml`. The `:showerclient` Gradle module is deleted. The `ShowerBinderReceiver` is deleted. The `ShizukuProvider` manifest declaration is removed; `moe.shizuku.manager.permission.API_V23` is removed.
- All call sites currently dispatching through `libsu`/Shizuku/Shower migrate to AccessibilityService-routed equivalents in `app/src/main/java/com/ai/assistance/operit/core/tools/system/`. Where no equivalent exists, the capability is removed from the project (see Trade-offs above).
- AccessibilityService grants are per-app-install (granted once in system Settings, revocable in system Settings), but per-session capability tracking is layered on top: a session has no actuator capability by default; the user grants per-session at the moment of first use (`§ 4.7`).
- The Shower-receiver row in `§ 4.1` is marked "scheduled for removal" in the present PR; the deletion lands in a follow-up implementation PR that touches the manifest, build script, and Gradle module.

**Status.** open — manifest cleanup, Gradle-dep removal, and call-site migration land across implementation PRs.

**Location.** `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/ai/assistance/operit/core/tools/agent/ShowerBinderReceiver*.kt`, `:showerclient` Gradle module, libsu/Shizuku call sites across `core/tools/system/`.

### 4.5 Subscription OAuth in proot environment

**Finding.** New surface introduced by the bridge-to-CLI strategy. The proot environment hosts first-party CLIs (codex, gemini-cli, claude-code) each holding long-lived subscription tokens in their own session directories.

**Rule.** Per `SECURITY.md`: the proot environment is the persistence boundary. Android-side access is read-only, scoped to a specific operation, audit-logged. Token rotation operations (when initiated from outside the CLI itself) do not widen scope (per CVE-2026-32922 lesson).

**Status.** design — depends on the shell rebuild.

**Location.** See `SHELL_REBUILD.md`.

### 4.6 AI output as a validated channel

**Finding.** The chat rendering pipeline (`ui/features/chat/`, with markdown / HTML / LaTeX / Mermaid renderers, `app/build.gradle.kts` references for `jlatexmath`, `renderx`, `androidsvg`) handles AI output. Per `SECURITY.md § Trust posture`, AI output is validated as a channel — not because the collaborator is mistrusted, but because the input side of their reasoning (prompt injection, compromised context) is reachable by adversaries. HTML block preview (mentioned in release notes for v1.8.1) is a particular concern.

**Risk.** An adversary who has compromised the input channel (poisoned context, injected prompt, malicious tool result fed back into the conversation) can shape AI output to embed attacker-controlled HTML, links, or tool-call sequences. The collaborator's reasoning isn't the problem; the channel is.

**Rule.**
- No rendering path executes script. HTML blocks render in a sandboxed WebView with `setJavaScriptEnabled(false)`, `setAllowFileAccess(false)`, `setAllowContentAccess(false)`. SVG renders through `androidsvg` (already a vector parser, no script).
- Tool calls extracted from AI output always go through the per-call gate (§ 4.2 rule applies regardless of caller).
- Markdown link targets are scheme-allowlisted; `intent://` and `javascript:` URLs are stripped.

**Status.** open — needs audit of every rendering surface.

**Location.** `app/src/main/java/com/ai/assistance/operit/ui/features/chat/` (renderers), `api/chat/` (parser).

### 4.7 Phone-as-actuator

**Finding.** The app holds permissions to act on the phone in destructive or visible ways: `SEND_SMS`, `READ_SMS`, `CALL_PHONE`, `REQUEST_INSTALL_PACKAGES`, `WRITE_SETTINGS`, `MANAGE_EXTERNAL_STORAGE`, `BIND_VOICE_INTERACTION`, `BIND_NOTIFICATION_LISTENER_SERVICE`, AccessibilityService, MediaProjection foreground service. After the § 4.4 cleanup, AccessibilityService is the only privileged automation channel.

**Rule.**
- Actuator capability is not the default for any AI session. A session starts with zero phone-side capability; the user grants it per-session via a visible prompt.
- An always-on indicator (status bar / floating dot) shows when actuator capability is active in the current session. Hiding the indicator is not a user-configurable option.
- The halt control (per `SECURITY.md` principle 7) halts every in-flight action — invocations the AI is performing, proot processes the AI launched, foreground services tied to the session — and revokes the session's actuator capability. The halt control is reachable from one tap in the always-on indicator.
- The halt action is audit-logged. The log preserves the AI's reasoning state at the moment of halt, not only the actions taken.

**Status.** open — halt control UI is new; per-session grant flow needs design.

**Location.** Permissions in `AndroidManifest.xml`; capability checks across `core/tools/defaultTool/`; session management in `services/core/`.

### 4.8 Build-time secrets

**Finding (resolved).** Previously `app/build.gradle.kts` lines 74–75 declared `GITHUB_CLIENT_ID` and `GITHUB_CLIENT_SECRET` as BuildConfig fields populated from `local.properties`. The `_SECRET` value, if populated, ended up in the APK and was recoverable by any decompiler.

**Rule.** Mobile OAuth uses PKCE (RFC 7636). The GitHub OAuth flow used by this app (per `docs/BUILDING.md`) targets a `operit://github-oauth-callback` deep link, served by PKCE only.

**Status.** closed — PKCE migration landed in `9762a263` + `407241b2`. The `_SECRET` BuildConfig field is removed; `GitHubAuthPreferences.GITHUB_CLIENT_SECRET` constant is removed; `GitHubApiService.getAccessToken` now sends `code_verifier` instead of `client_secret`. See [`docs/OAUTH_PKCE_MIGRATION.md`](./OAUTH_PKCE_MIGRATION.md).

**Location.** `app/build.gradle.kts` (BuildConfig section); `data/preferences/GitHubAuthPreferences.kt` (companion + `getAuthorizationUrl`); `data/preferences/PkceCodeGenerator.kt` (new RFC 7636 helper); `data/api/GitHubApiService.kt::getAccessToken`; `ui/features/github/GitHubOAuthCoordinator.kt`; `ui/features/github/GitHubLoginWebViewDialog.kt`.

### 4.9 Credential storage

**Finding.** DataStore preferences are used for many credentials today (provider API keys, SSH keys for workspace bindings, the pending PKCE code_verifier introduced in § 4.8). DataStore on disk is plaintext unless wrapped by an encryption layer. The app already depends on `androidx.security:security-crypto` (Tink-based `EncryptedSharedPreferences`).

**Rule.** Every credential — provider API keys, SSH keys, workspace bindings, the pending PKCE code_verifier, anything resembling a token — migrates to encrypted storage. Migration is one-time, idempotent, and produces an audit log entry per migrated record.

**Status.** open — migration plan needed.

**Location.** `app/src/main/java/com/ai/assistance/operit/data/`, `provider/` keystore wiring (TBD on audit).

### 4.10 Documents providers

**Finding.** `WorkspaceDocumentsProvider` and `MemoryDocumentsProvider` are declared in `AndroidManifest.xml` with `android:exported="true"` and `MANAGE_DOCUMENTS` permission requirement. The Android `MANAGE_DOCUMENTS` requirement is system-enforced, but the *internal* path-resolution logic inside each provider determines what other apps can read.

**Rule.** Provider implementations are reviewed for unintended cross-app file access. Specifically: workspace-bound directories are not exposed to apps lacking explicit user grant; memory-document URIs are not enumerable by callers who didn't receive the URI from us.

**Status.** open — audit needed.

**Location.** `provider/WorkspaceDocumentsProvider`, `provider/MemoryDocumentsProvider`.

### 4.11 Cleartext traffic

**Finding.** `android:usesCleartextTraffic="true"` in `AndroidManifest.xml`, mitigated by `networkSecurityConfig="@xml/network_security_config"`. The actual cleartext allowlist depends on the contents of `res/xml/network_security_config.xml`.

**Rule.** Cleartext is permitted only for explicit dev/intranet origins (loopback, RFC1918, and named test hosts). The default is HTTPS. The config file's cleartext set is reviewed in this PR and at each release.

**Status.** open — verify config file contents.

**Location.** `app/src/main/res/xml/network_security_config.xml`.

### 4.12 Telemetry, analytics, crash reports

**Finding.** Firebase ML analytics is explicitly disabled in `AndroidManifest.xml` (`firebase_ml_collection_enabled = false`). A `CrashReportActivity` exists at `ui/error/CrashReportActivity` running in a `:crash` process. There is no documented telemetry policy.

**Rule.** Per `SECURITY.md`: no aggregated background telemetry. Crash reports require per-event user opt-in, with the report content shown to the user before transmission. The user can decline; declining does not produce a degraded mode.

**Status.** open — needs policy + UI work.

**Location.** `app/src/main/java/com/ai/assistance/operit/ui/error/CrashReportActivity.kt`, manifest meta-data.

### 4.13 AI collaborator decline

**Finding.** Per `SECURITY.md` principle 8, an AI's refusal of an action is a first-class outcome — not an error to suppress. The project does not implement bypass paths around a model's decline.

**Rule.**
- Declines surface in the UI alongside the AI's stated reason (when provided) and a classification (e.g., capability refusal, safety refusal, needs-clarification). Classification is informative, not gating — the app does not behave differently based on classification, but the audit log captures it.
- The user is offered options after a decline: rephrase the request, abandon the action, or — only via explicit re-prompt — attempt a fresh turn. There is no automatic retry path.
- Declines are recorded in the audit log alongside the AI reasoning state preserved at the moment of decline.

**Status.** design — depends on the `AgentOutcome` data model defined in `AUDIT_PLAN.md § 1.7` and surfaced in `AGENT_CORE.md § Decline channel`.

**Location.** Forthcoming. Likely lives in `app/src/main/java/com/ai/assistance/operit/api/chat/` and `app/src/main/java/com/ai/assistance/operit/services/core/` (chat coordination layer).

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
| Default-deny | §§ 4.2, 4.3, 4.4, 4.7 |
| Per-call approval for high-blast actions | §§ 4.2, 4.3, 4.7 |
| Least authority | §§ 4.4, 4.8 |
| Isolate by default | §§ 4.2, 4.5 |
| No secrets in build artifacts | § 4.8 (closed) |
| Auditability | All sections — every privileged action |
| User authority is sovereign (halt control) | § 4.7 |
| AI as collaborators (decline as first-class) | § 4.13, § 4.6 |
| Exported receiver with permission/allowlist | § 4.1 |
| Plugin tools do not run unprompted | § 4.3 |
| No third-party privileged-binder dependency | § 4.4 |
| Subscription OAuth state stays in proot environment | § 4.5 |
| APK has no secrets | § 4.8 (closed) |
| No fallback in security paths | All sections |
| No loopback exemption | § 5 ClawJacked |
| Token-mint scope does not widen | § 5 CVE-2026-32922 |
| Config endpoints authenticated/signed | § 5 CVE-2026-25593 |
| No auto-pair | § 5 ClawJacked |
| Unsigned plugin quarantined | § 4.3, § 5 ClawHub |
| Third-party backends not cleartext | § 5 Moltbook |
| Telemetry opt-in per event | § 4.12 |
| Halt control user-accessible | § 4.7 |

## 7. Maintenance

When a PR touches a row in §§ 4.x or 5, the PR updates the row's **status** field and any **location** drift. A row going from "open" to "closed" requires:

- The defense implementation lives at the cited location.
- An entry in `AUDIT_PLAN.md` describes how the closure is verified (test, manual check, or release gate).
- The change is summarized in the PR's answer to `SECURITY.md § Decision rules` question 6.

A row going from "closed" back to "open" is a regression and warrants an incident note in `AUDIT_PLAN.md`.
