# AUDIT_PLAN.md

> The project's audit ledger. Companion to [`SECURITY.md`](./SECURITY.md) (principles) and [`THREAT_MODEL.md`](./THREAT_MODEL.md) (boundaries, surfaces). 中文镜像见 [`AUDIT_PLAN.zh.md`](./AUDIT_PLAN.zh.md)。

This document tracks three things:

1. **Open design questions** that need answers before subsystems can be built.
2. **Release gates** — checks that pass before any build leaves the bench.
3. **CVE-class regression tests** — concrete reproduction of known failure modes from related projects, especially openclaw.

It also holds the **reference index** for external citations used throughout the security docs.

## 1. Open design questions

Each question has an owner, a target resolution date when set, and a placeholder for the decision. A question moves to "resolved" once the decision is captured and the affected `THREAT_MODEL.md` row updates.

### 1.1 Plugin signing trust anchor

**Question.** Every `.toolpkg`, MCP server, and Skill bundle carries a publisher signature. What is the trust anchor — who is allowed to be a publisher, and how is "this is a real publisher" verified on-device?

**Decision.** Self-signed publisher keys with trust-on-first-use (TOFU). No project-maintained allowlist of "blessed" publishers — that's governance debt the project does not take on.

Concretely:
- A plugin package carries `manifest.json` and `manifest.sig`. The signature is an Ed25519 detached signature over `manifest.json` under the publisher's private key. `manifest.json` carries the publisher's public key inline (X.509 SubjectPublicKeyInfo, PEM-wrapped) and a publisher-chosen `publisherName` string.
- On first install, the device records `(pluginId, publisherKeyFingerprint)` in a TOFU map (SharedPreferences). The install dialog surfaces the publisher name and key fingerprint; the user confirms.
- Subsequent updates must verify against the same `publisherKeyFingerprint`. A mismatch refuses the update outright — the user is told this looks like a different publisher claiming the same plugin id, and given no auto-promote path.
- The signature gating is independent of the per-call capability gate (`JsPluginGate`, § 4.2). Signature verification proves "this update is the same publisher you trusted"; the capability gate decides "what this plugin is allowed to do." A signature pass does not auto-grant capabilities; a fresh install starts with zero grants regardless of signature.

What the trust anchor is *not*: a CA hierarchy, a notary, an in-app marketplace registry. The user is the anchor, the device-local TOFU map is the record. This matches the project's broader "user authority is sovereign" posture (`SECURITY.md` principle 7) and the no-telemetry stance (`§ 4.12`).

**Affected sections.** `THREAT_MODEL.md § 4.3` (Plugin marketplaces). § 4.2 (the JS plugin gate) is unaffected — its `pluginId` field is what TOFU keys on; signature verification is a separate gate layered above it.

### 1.2 MCP server identity

**Question.** MCP servers are network endpoints (often `npx` / `uvx` runners). What binds a server name (e.g. `mcp-server-filesystem`) to a specific publisher? Today the MCP ecosystem relies on package-manager identity (`npm`, `PyPI`); both have known supply-chain risk.

**Decision.** Per-server publisher pinning + an operator-curated allowlist. The two work together — the allowlist gates which packages can run at all; the pin makes sure the package can't silently change publishers between updates. No hash-pinning at the version level: MCP packages update frequently and forcing a hash pin per release would either degrade to "auto-approve" or make the feature unusable.

Concretely:
- The user maintains an MCP package allowlist (mirroring [`BroadcastSenderAllowlist`](../app/src/main/java/com/ai/assistance/operit/integrations/intent/BroadcastSenderAllowlist.kt) in shape, but for MCP runtimes). Default empty — no MCP server runs unless its `(runtime, packageName)` pair is on the allowlist. Surfaced through the same kind of settings screen.
- On first run of an allowlisted package, the device captures the publisher identity available from the runtime: for npm, the package's published author + the SHA-256 of the installed tarball; for uvx / PyPI, the wheel's author + tarball SHA-256. The pair `(packageName, publisherFingerprint)` is recorded in a TOFU map keyed parallel to the § 1.1 plugin TOFU.
- Subsequent runs of the same `packageName` verify the publisher fingerprint matches. A mismatch refuses to launch the server until the user re-approves (treated as a fresh trust-on-first-use, not an auto-update). The audit log records every fingerprint change.
- Tool calls *from* an allowlisted, pinned MCP server still cross the per-call capability gate (§ 4.2). Allowlist + pin gate "this server can run"; the capability gate decides "what its tool calls are allowed to do." Defense in depth.

What the v1 explicitly does not do: full reproducible builds, deterministic hash chains, certificate-pinning into the npm / PyPI registries. The npm + PyPI registries' own attestation features (`npm provenance`, PyPI `[provenance]` PEPs) are best-effort signals — we record them when present, refuse to make them required.

**Affected sections.** `THREAT_MODEL.md § 4.3`.

### 1.3 Per-tool capability taxonomy

**Question.** Each plugin tool declares its capability class. What's the final list of classes?

**Decision.** The shipped enum is [`JsCapabilityClass`](../app/src/main/java/com/ai/assistance/operit/core/tools/javascript/JsCapabilityClassifier.kt). Eleven classes, each consumed by the JS plugin gate, the AI tool gate, and the IPC protocol's capability claim:

| Class | What it covers | Examples |
|---|---|---|
| `METADATA` | Pure compute and reads of static resources owned by the app itself | `calculator`, `string` ops, listing imported plugin packages |
| `FILE_READ` | Reading files in user-visible storage | `read_file`, `list_dir`, `file_info`, `read_lines` |
| `FILE_WRITE` | Creating, editing, or deleting files | `create_file`, `edit_file`, `delete_file`, `move_file`, `unzip_files` |
| `SHELL` | Shell commands or terminal sessions, including proot dispatch | `execute_shell`, `execute_terminal_command`, `create_terminal_session` |
| `NETWORK` | Outbound HTTP, WebSocket, web searches, browser sessions | `visit_web`, `http_request`, `various_search`, `browser_open` |
| `SYSTEM_READ` | Reads of device / system state | `device_info`, `list_apps` |
| `SYSTEM_WRITE` | Writes to device / system state (settings, broadcasts, SMS, intents) | `modify_software_settings`, `send_broadcast`, `send_sms`, `execute_intent` |
| `UI_AUTOMATION` | Drives the UI through AccessibilityService or input simulation | `tap`, `long_press`, `swipe`, `press_key`, `set_input_text`, `capture_screenshot`, `get_page_info`, `ui_dump` |
| `CHAT_READ` | Reads of the chat / memory / conversation state | `memory_query`, `chat_history_read` |
| `CHAT_WRITE` | Mutates chat / memory state | `memory_write`, `chat_send` |
| `UNCLASSIFIED` | Tools the classifier doesn't recognize | The most-restrictive class. Default-deny. Adding a new tool means adding its `(toolName → class)` row in `JsCapabilityClassifier.toolNameToClass`; a forgotten row falls through to `UNCLASSIFIED` and the gate refuses. |

What's *not* a class:
- No `shell.privileged` — libsu, Shizuku, and Shower are removed (`THREAT_MODEL.md § 4.4`). UI automation goes through `UI_AUTOMATION` over AccessibilityService; shell execution goes through `SHELL` either as Android-side shell or as proot dispatch.
- No `accessibility` as a separate class — accessibility is the substrate for `UI_AUTOMATION`, not an orthogonal axis.
- No `apk.read` / `apk.write` — APK introspection is a `FILE_READ` / `FILE_WRITE` instance with the system `PackageInstaller` mediating installs as its own confirmation surface.
- No `telephony` separate from `SYSTEM_WRITE` — SMS / call placement live under `SYSTEM_WRITE` because they're one-shot device-state mutations the user sees in the per-call confirmation dialog regardless of finer subclassification.

Default-deny applies uniformly across all classes. The per-call confirmation overlay (`ToolGateConfirmationOverlay`, § 4.2) is the user's grant surface; once a `(caller × class)` pair is GRANTED, future calls in the same class pass silently. The "per-workspace durable" / "per-domain durable" granularities the working table contemplated are not in v1 — the grant is the same regardless of which file or which domain. A finer-grained future refinement can split a class on a sub-key, but that's a deliberate extension of the gate, not a re-spelling of the class list.

**Affected sections.** `THREAT_MODEL.md § 4.2` (gate), § 4.3 (plugin manifests will reference these class names), § 4.4 (no privileged-shell class), § 4.7 (`UI_AUTOMATION` is the actuator surface).

### 1.4 Audit log scope and sync

**Question.** The audit log is local and tamper-evident. Is it ever syncable to the cloud (for forensic review across devices) or strictly local-only?

**Decision.** Strictly local. Audit material lives in the device-local stores the in-app surfaces already read:

- `JsPluginGate.recentAudit()` — bounded ring (256 events) of every gated tool call's decision. Visible in the Plugin & AI gate screen.
- `HaltController.audit` — bounded ring (64 events) of every halt request with who / why / when.
- `DeclineRegistry.recent` — bounded ring (32 events) of AI declines and the user's response.
- `BroadcastSenderAllowlist` — long-lived per-receiver allowlists; the audit trail is implicit (current state + Android system logs).

Sync is **not** a v1 feature and is not contemplated for v2. Adding a sync endpoint would mean:
1. Adding a third-party backend the app talks to without an immediate user-visible reason — exactly the "no aggregated background telemetry" red line (`THREAT_MODEL.md § 4.12`, `SECURITY.md`).
2. Building a cryptographic envelope so the sync stream itself can't be the audit log's adversary.
3. A revoke-after-the-fact story for users who change their minds.

None of those have a v1 use case strong enough to justify the surface area. Forensic review after device loss is a real use case, but the answer in v1 is "the user exports the log to a file they manage" — same shape as the existing logcat export and crash report flows from `§ 4.12`. The export path is on the user's terms, on their schedule, to a destination they pick.

If a future change ever introduces a sync path, it must:
- be off by default,
- show the user the exact bytes that will leave the device before each push,
- be reversible with a "wipe the cloud copy" action,
- be documented in this row before any code lands.

**Affected sections.** `THREAT_MODEL.md § 4.12` (telemetry — confirms the no-sync stance). `SECURITY.md` red line about no aggregated background telemetry applies.

### 1.5 proot ↔ Android IPC protocol

**Question.** How does the Android side talk to proot processes (CLIs, the bridge)? Loopback HTTP, Unix domain socket on shared filesystem, FIFO, JNI shim?

**Resolved.** Unix domain socket on a shared bind-mount. Details in `SHELL_REBUILD.md § IPC protocol`.

**Affected sections.** `THREAT_MODEL.md § 4.5`, § 5 (ClawJacked applicability).

### 1.6 Cross-boundary read of subscription OAuth state

**Question.** `THREAT_MODEL.md § 4.5` permits read-only, scoped, audit-logged reads of proot-held subscription state from the Android side. What does the API look like? What scopes exist?

**Decision.** Metadata-and-liveness only. Raw token material never crosses the proot boundary into Android-side memory.

Three scopes the Android side can request through the IPC bridge; each maps to a specific `command` on the `METADATA` capability claim:

| Command | What it returns | Where it reads |
|---|---|---|
| `subscription_account` | `{cliName, accountEmail}` (account email may be null if the provider doesn't expose it) | The CLI's own metadata files (e.g. `~/.config/claude/config.json`'s `account.email`) — no token bytes |
| `subscription_tier` | `{cliName, tier}` (free / pro / team / null) | Same metadata files, tier field only |
| `subscription_alive` | `{cliName, isLoggedIn, lastActiveAtMillis}` | Best-effort check that the CLI's session is current. May `stat` a session file or run the CLI's own `whoami`-style command — but the output passes through a strict allowlisted parser before crossing the wire (no raw stdout) |

What does *not* cross:

- The raw access token, refresh token, or any signed JWT.
- The OAuth client secret.
- The full session config blob (could contain other secrets).

If a tool needs to call the subscription provider's API on the user's behalf, that tool runs *inside* proot and the token never leaves. The Android side hands the *task* across (e.g. "ask claude-code to summarize this file"); the *credential* stays put.

The IPC dispatcher (`operit-dispatcher.py`) rejects any command outside this allowlist when the capability claim is `METADATA`, and rejects any attempt to claim `FILE_READ` against a session-file path. Defense in depth: the Android-side `JsPluginGate` / `AiToolGate` also classifies these tool names into `METADATA` (already in `JsCapabilityClassifier`).

Audit: every cross-boundary read is recorded in `JsPluginGate.recentAudit()` with origin (`User` / `AiAgent` / `Plugin:<id>`) and capability claim. A user can review every time their session metadata was read by an AI run.

**Affected sections.** `THREAT_MODEL.md § 4.5` (the subscription-OAuth row). § 4.2 (the gate's `METADATA` class is the entry point on the Android side).

### 1.7 AI decline as first-class outcome

**Question.** When an AI collaborator declines an action (refuses a tool call, refuses to continue a turn), the app surfaces the decline and the AI's stated reason, and offers the user options. What's the data model?

**Resolved in `AGENT_CORE.md`.** The `Decline` variant of `TurnEvent` is the data model:

```kotlin
data class Decline(
    val reason: String,                    // the AI's own words
    val suggestedAlternatives: List<String>? = null,
    val classification: DeclineClass       // CapabilityRefusal, SafetyRefusal, NeedsClarification, ContextLimit, Other
) : TurnEvent()
```

Classification is informative for the audit log; the app does not behave differently based on it. No `Decline → automatic retry` path. See `AGENT_CORE.md § Decline channel`.

**Affected sections.** `THREAT_MODEL.md § 4.6`, § 4.13; `SECURITY.md` principle 8.

### 1.8 Halt control: scope and UI

**Question.** The user-sovereign halt control halts the action chain. Concretely: what does it halt?

**Resolved in `AGENT_CORE.md § Halt channel`.** The halt:
- cancels the backend's in-flight operation (HTTP request, JNI call, stdio pipe),
- short-circuits any in-flight tool dispatch,
- sends `SIGTERM` (then `SIGKILL` after 2 seconds) to proot processes spawned by the session,
- ends or idles the foreground service depending on whether the halt is "end session" or "stop action chain,"
- emits `HaltedByUser` as a final `TurnEvent`,
- audit-logs with the AI's reasoning state preserved at the moment of halt.

The halt does *not* terminate the AI's reasoning thread on a remote provider (no such API), does *not* delete the AI's context window, and does *not* bypass an AI decline of the halt operation itself (the halt is a user action, not an AI action — declines do not apply).

**Affected sections.** `THREAT_MODEL.md § 4.7`; `SECURITY.md` principle 7.

### 1.9 Distro choice for the proot environment

**Resolved.** Debian 12 (bookworm). See `SHELL_REBUILD.md § Distro`.

## 2. Release gates

A build does not become a tagged release until every gate below passes. Failures block release; the gate description names what "pass" looks like.

| Gate | Pass condition | Tooling |
|---|---|---|
| Receiver audit | Every `<receiver>` in `AndroidManifest.xml` with `android:exported="true"` carries either a `signature`-level `android:permission` or an in-code sender allowlist. Debug receivers absent from release variant. | Script: `tools/audit/check_receivers.py` (TBD) |
| No third-party privileged-binder dep | `app/build.gradle.kts` has no `libsu`, no `rikka.shizuku`, no Shower-server dependency. `AndroidManifest.xml` has no `ShizukuProvider`, no `ShowerBinderReceiver`, no `moe.shizuku.manager.permission.*`. | Pre-merge build hook + manifest diff check |
| Secret scan | No `BuildConfig` field whose name contains `SECRET`, `PRIVATE_KEY`, `PASSWORD`. No string literal matching common API-key patterns. | `gitleaks` + a project-specific allowlist |
| Cleartext audit | `network_security_config.xml` cleartext domains are explicitly named (no `*`), and the named set is reviewed in the release PR. | Manual check + diff hook |
| Telemetry policy | No analytics / crash reporter ships without an opt-in prompt covering each event. | Manual check |
| Halt control | A scripted test exercises the halt control in each privileged surface (proot, foreground service, accessibility) and verifies cessation + log entry. | Integration test (TBD) |
| Plugin signing | Every bundled `.toolpkg` / Skill / MCP package included in `app/src/main/assets/packages/` carries a valid signature. | Pre-merge build hook |
| Encrypted storage migration | On first launch of the new build, every existing DataStore credential field is migrated to `EncryptedSharedPreferences`; migration log entry present. | Integration test |
| Audit log readability | The user can open the audit log from the app's UI. The log displays the last 7 days of privileged actions with timestamps and originating actor. | Manual UAT |

## 3. CVE-class regression tests

Each row reproduces a documented failure mode from a related project. These run on every CI build and on every release.

| Test | Reproduces | What "pass" looks like |
|---|---|---|
| `clawjacked_loopback_auth` | OpenClaw ClawJacked — loopback bypassed auth / rate-limit | Authenticated request from `127.0.0.1` is rate-limited identically to a request from a non-loopback origin. Unauthenticated request fails with the same error code regardless of origin. |
| `clawjacked_auto_pair` | OpenClaw ClawJacked — auto-paired trusted devices from "trusted" origin | Pairing requests surface a user-visible prompt before any device is added to the trusted set. No origin (including loopback) bypasses the prompt. |
| `cve_2026_32922_scope_widening` | OpenClaw token-rotate scope escalation | Calling the token-rotate operation with a less-scoped caller produces a token whose scope is the intersection of caller's scope and the requested scope — never wider than the caller's. |
| `cve_2026_25593_config_apply` | OpenClaw unauthenticated `config.apply` over WebSocket | Any config-write operation requires an authenticated, scoped, signed call. Unauthenticated config writes are rejected without side effects. |
| `clawhub_unsigned_plugin` | OpenClaw skill marketplace distributing malicious plugins | Installing an unsigned plugin places it in quarantine. The user must explicitly promote it; promotion is audit-logged. |
| `moltbook_cleartext_secret` | OpenClaw-adjacent backend leak of plaintext tokens | No credential leaves the device unencrypted. No backend-stored agent state is in cleartext at rest. |
| `decline_no_bypass` | Project-specific: AI decline of an action | An AI decline surfaces in the audit log with classification. The next turn does not auto-retry the declined action. There is no code path that suppresses the decline. |
| `halt_terminates_chain` | Project-specific: halt control | When the halt control is invoked, every privileged surface in § 1.8 above ceases within 2 seconds. The halt is logged with the AI reasoning state preserved at the moment of halt. |
| `no_third_party_privileged_binder` | Project-specific: regression guard for the no-Shizuku/Shower stance | A grep across the codebase finds no live import of `com.github.topjohnwu.libsu.*`, `rikka.shizuku.*`, or the Shower client API. Build fails if found. |

## 4. Incident log

When a "closed" row in `THREAT_MODEL.md` regresses to "open" — i.e. a previously-enforced rule has been broken or weakened — an entry lands here with date, surface, what happened, and remediation.

(empty — no incidents yet)

## 5. Reference index

External sources cited across the security docs.

### OpenClaw incidents and analyses (2026)

- ClawJacked (Oasis Security): https://www.oasis.security/blog/openclaw-vulnerability
- CVE-2026-32922 (ARMO): https://www.armosec.io/blog/cve-2026-32922-openclaw-privilege-escalation-cloud-security/
- OpenClaw RCE CVE-2026-25253 (runZero): https://www.runzero.com/blog/openclaw/
- CVE-2026-25593 — Unauthenticated WebSocket RCE (GitHub Advisory GHSA-g55j-c2v4-pjcg): https://github.com/advisories/GHSA-g55j-c2v4-pjcg
- ClawJacked exposed users to data theft (Security Affairs): https://securityaffairs.com/188749/hacking/clawjacked-flaw-exposed-openclaw-users-to-data-theft.html
- Malicious OpenClaw Skills distribute Atomic macOS Stealer (Trend Micro): https://www.trendmicro.com/en_us/research/26/b/openclaw-skills-used-to-distribute-atomic-macos-stealer.html
- 40,000+ exposed OpenClaw instances (Infosecurity Magazine): https://www.infosecurity-magazine.com/news/researchers-40000-exposed-openclaw/
- One-command OSS-repo → agent backdoor (VentureBeat): https://venturebeat.com/security/one-command-open-source-repo-ai-agent-backdoor-openclaw-supply-chain-scanner
- Security teams brief on OpenClaw (CrowdStrike): https://www.crowdstrike.com/en-us/blog/what-security-teams-need-to-know-about-openclaw-ai-super-agent/
- OpenClaw 2026 hardening guide (Valletta Software): https://vallettasoftware.com/blog/post/openclaw-security-2026-best-practices-risks-hardening-guide
- Data leakage & prompt injection risks (Giskard): https://www.giskard.ai/knowledge/openclaw-security-vulnerabilities-include-data-leakage-and-prompt-injection-risks
- U Toronto advisory: https://security.utoronto.ca/advisories/openclaw-vulnerability-notification/
- Supply-chain abuse analysis (Sangfor): https://www.sangfor.com/blog/cybersecurity/openclaw-ai-agent-security-risks-2026
- Found unsafe for use (Kaspersky): https://www.kaspersky.com/blog/openclaw-vulnerabilities-exposed/55263/
- SlowMist OpenClaw security practice guide: https://github.com/slowmist/openclaw-security-practice-guide
- 138 CVEs vendor response (BetterClaw): https://www.betterclaw.io/blog/openclaw-security-2026

### Prior art (positive examples)

- x-plug Mobile-Agent: https://github.com/x-plug/mobileagent
- unitedbyai DroidClaw: https://github.com/unitedbyai/droidclaw
- SenninTadd agentX (Mobile-Agent variant): https://github.com/SenninTadd/agentX

### Standards and prior art

- Android `EncryptedSharedPreferences`: https://developer.android.com/topic/security/data
- OAuth 2.0 for Native Apps (RFC 8252): https://datatracker.ietf.org/doc/html/rfc8252
- OAuth 2.0 PKCE (RFC 7636): https://datatracker.ietf.org/doc/html/rfc7636
- MCP specification: https://modelcontextprotocol.io
