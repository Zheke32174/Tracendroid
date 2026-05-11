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

**Considerations.**
- Self-signed publisher keys with a TOFU (trust-on-first-use) prompt on install — minimal infrastructure, places the trust decision on the user.
- A project-maintained allowlist of known publishers — heavier governance, smaller risk of impersonation.
- Hybrid: well-known publishers come pre-trusted; unknown publishers go through TOFU with a louder prompt.

**Decision.** (pending)

**Affected sections.** `THREAT_MODEL.md` § 4.3 (Plugin marketplaces).

### 1.2 MCP server identity

**Question.** MCP servers are network endpoints (often `npx` / `uvx` runners). What binds a server name (e.g. `mcp-server-filesystem`) to a specific publisher? Today the MCP ecosystem relies on package-manager identity (`npm`, `PyPI`); both have known supply-chain risk.

**Considerations.**
- Per-server publisher pinning — first install fixes the publisher; subsequent updates verify against it.
- Operator-defined MCP allowlist — explicit user-curated list of acceptable server packages.
- Hash pinning of installed bundles — version updates require explicit re-approval.

**Decision.** (pending)

**Affected sections.** `THREAT_MODEL.md` § 4.3.

### 1.3 Per-tool capability taxonomy

**Question.** Each plugin tool declares its capability class. What's the final list of classes?

**Initial proposed taxonomy.** (subject to revision once we walk the existing tool registry)

| Class | Examples | Default approval scope |
|---|---|---|
| `read.local` | Read project files in current workspace | Per-workspace, durable |
| `read.user-data` | Read photos, contacts, location, SMS | Per-call |
| `write.local` | Modify workspace files | Per-workspace, durable |
| `write.user-data` | Modify contacts, calendar | Per-call |
| `shell.chroot` | Execute in chroot | Per-session |
| `shell.privileged` | Execute via Shizuku/libsu/Shower | Per-call |
| `network.outbound` | Make HTTP/WebSocket calls | Per-domain, durable |
| `network.listen` | Bind a port on device | Per-session |
| `telephony` | SMS, place call | Per-call |
| `accessibility` | UI tree read, gesture inject | Per-session, with always-on indicator |
| `screen` | MediaProjection screenshot/record | Per-call, with prompt |
| `install` | Install another package | Per-call, with system prompt |

**Decision.** (pending — initial table above for reference)

**Affected sections.** `THREAT_MODEL.md` § 4.3, § 4.7.

### 1.4 Audit log scope and sync

**Question.** The audit log is local and tamper-evident. Is it ever syncable to the cloud (for forensic review across devices) or strictly local-only?

**Considerations.**
- Local-only is simpler and matches the "no third-party backend in cleartext" red line.
- Syncable-with-explicit-consent allows forensic review after device loss but introduces a cloud surface.

**Decision.** (pending — leaning local-only by default; sync is a future opt-in feature, not in v1)

**Affected sections.** `THREAT_MODEL.md` § 4.12.

### 1.5 Chroot ↔ Android IPC protocol

**Question.** How does the Android side talk to chroot processes (CLIs, the bridge)? Loopback HTTP, Unix domain socket on shared filesystem, FIFO, JNI shim?

**Considerations.**
- Loopback HTTP — easiest, but `THREAT_MODEL.md` § 5 ClawJacked says loopback gets no auth exemption, so we'd need full auth on every call.
- Unix socket in a shared bind-mount — Linux-side ACLs naturally scope access; no network surface.
- FIFO — minimal, but harder to scope (file perms only).

**Decision.** (pending — likely Unix socket; details in forthcoming `docs/SHELL_REBUILD.md`)

**Affected sections.** `THREAT_MODEL.md` § 4.5, § 5 (ClawJacked applicability).

### 1.6 Cross-boundary read of subscription OAuth state

**Question.** `THREAT_MODEL.md` § 4.5 permits read-only, scoped, audit-logged reads of chroot-held subscription state from the Android side. What does the API look like? What scopes exist?

**Considerations.**
- Read profile metadata (account email, subscription tier) — low sensitivity.
- Read session liveness (is the CLI logged in?) — low sensitivity.
- Read token material itself — high sensitivity; should this be permitted at all?

**Decision.** (pending — leaning toward metadata-and-liveness only; raw token never crosses)

**Affected sections.** `THREAT_MODEL.md` § 4.5.

### 1.7 AI decline as first-class outcome

**Question.** When an AI collaborator declines an action (refuses a tool call, refuses to continue a turn), the app surfaces the decline and the AI's stated reason, and offers the user options. What's the data model?

**Initial sketch.**

```kotlin
sealed class AgentOutcome {
    data class Completed(val result: ...) : AgentOutcome()
    data class Declined(
        val reason: String,                    // the AI's own words
        val suggestedAlternatives: List<String>? = null,
        val classification: DeclineClass       // e.g. CapabilityRefusal, SafetyRefusal, NeedsClarification
    ) : AgentOutcome()
    data class HaltedByUser(val haltedAt: Instant, val context: ...) : AgentOutcome()
    data class Failed(val error: ...) : AgentOutcome()
}
```

**Considerations.**
- The classification field is informative, not gating — the app does not behave differently based on classification, but the audit log captures it.
- No bypass path: there is no `Decline` → `Retry` automatic flow; only an explicit user action can re-attempt, and even then, the new attempt is a fresh turn with the decline visible in context.

**Decision.** (pending — the sketch above is the starting point)

**Affected sections.** `THREAT_MODEL.md` § 4.6, § 4.13, principle 8 in `SECURITY.md`.

### 1.8 Halt control: scope and UI

**Question.** The user-sovereign halt control halts the action chain. Concretely: what does it halt?

**Initial scope.**
- In-flight invocations the AI is performing (HTTP calls, tool runs, shell processes).
- Chroot processes launched in the current session.
- Foreground services tied to the session (ScreenCaptureService, AIForegroundService).
- Accessibility automation in flight.

What it does *not* do:
- Terminate the AI's reasoning thread on the remote provider (we have no such API).
- Delete the AI's context window.
- Bypass an AI decline of the halt operation itself (the halt is a user action, not an AI action — declines do not apply).

**Decision.** (pending — initial scope above is the starting point)

**Affected sections.** `THREAT_MODEL.md` § 4.7, principle 7 in `SECURITY.md`.

### 1.9 Distro choice for chroot rebuild

**Question.** Ubuntu 24 / Debian 12 / Alpine / something else?

**Status.** Deferred per project decision — resolves alongside the forthcoming `docs/SHELL_REBUILD.md` scope work.

## 2. Release gates

A build does not become a tagged release until every gate below passes. Failures block release; the gate description names what "pass" looks like.

| Gate | Pass condition | Tooling |
|---|---|---|
| Receiver audit | Every `<receiver>` in `AndroidManifest.xml` with `android:exported="true"` carries either a `signature`-level `android:permission` or an in-code sender allowlist. Debug receivers absent from release variant. | Script: `tools/audit/check_receivers.py` (TBD) |
| Secret scan | No `BuildConfig` field whose name contains `SECRET`, `PRIVATE_KEY`, `PASSWORD`. No string literal matching common API-key patterns. | `gitleaks` + a project-specific allowlist |
| Cleartext audit | `network_security_config.xml` cleartext domains are explicitly named (no `*`), and the named set is reviewed in the release PR. | Manual check + diff hook |
| Telemetry policy | No analytics / crash reporter ships without an opt-in prompt covering each event. | Manual check |
| Halt control | A scripted test exercises the halt control in each privileged surface (chroot, foreground service, accessibility) and verifies cessation + log entry. | Integration test (TBD) |
| Plugin signing | Every bundled `.toolpkg` / Skill / MCP package included in `app/src/main/assets/packages/` carries a valid signature. | Pre-merge build hook |
| Encrypted storage migration | On first launch of the new build, every existing DataStore credential field is migrated to `EncryptedSharedPreferences`; migration log entry present. | Integration test |
| Audit log readability | The user can open the audit log from the app's UI. The log displays the last 7 days of privileged actions with timestamps and originating actor. | Manual UAT |

## 3. CVE-class regression tests

Each row reproduces a documented failure mode from a related project. These run on every CI build and on every release.

| Test | Reproduces | What "pass" looks like |
|---|---|---|
| `clawjacked_loopback_auth` | OpenClaw ClawJacked — loopback bypassed auth/rate-limit | Authenticated request from `127.0.0.1` is rate-limited identically to a request from a non-loopback origin. Unauthenticated request fails with the same error code regardless of origin. |
| `clawjacked_auto_pair` | OpenClaw ClawJacked — auto-paired trusted devices from "trusted" origin | Pairing requests surface a user-visible prompt before any device is added to the trusted set. No origin (including loopback) bypasses the prompt. |
| `cve_2026_32922_scope_widening` | OpenClaw token-rotate scope escalation | Calling the token-rotate operation with a less-scoped caller produces a token whose scope is the intersection of caller's scope and the requested scope — never wider than the caller's. |
| `cve_2026_25593_config_apply` | OpenClaw unauthenticated `config.apply` over WebSocket | Any config-write operation requires an authenticated, scoped, signed call. Unauthenticated config writes are rejected without side effects. |
| `clawhub_unsigned_plugin` | OpenClaw skill marketplace distributing malicious plugins | Installing an unsigned plugin places it in quarantine. The user must explicitly promote it; promotion is audit-logged. |
| `moltbook_cleartext_secret` | OpenClaw-adjacent backend leak of plaintext tokens | No credential leaves the device unencrypted. No backend-stored agent state is in cleartext at rest. |
| `decline_no_bypass` | Project-specific: AI decline of an action | An AI decline surfaces in the audit log with classification. The next turn does not auto-retry the declined action. There is no code path that suppresses the decline. |
| `halt_terminates_chain` | Project-specific: halt control | When the halt control is invoked, every privileged surface in §1.8 above ceases within 2 seconds. The halt is logged with the AI reasoning state preserved at the moment of halt. |

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

### Standards and prior art

- Android `EncryptedSharedPreferences`: https://developer.android.com/topic/security/data
- OAuth 2.0 for Native Apps (RFC 8252): https://datatracker.ietf.org/doc/html/rfc8252
- OAuth 2.0 PKCE (RFC 7636): https://datatracker.ietf.org/doc/html/rfc7636
- MCP specification: https://modelcontextprotocol.io
