# SECURITY.md

> Principles and defaults for the project.
> The detailed threat model lives in [`THREAT_MODEL.md`](./THREAT_MODEL.md). Open questions and release gates live in [`AUDIT_PLAN.md`](./AUDIT_PLAN.md). 中文镜像见 [`SECURITY.zh.md`](./SECURITY.zh.md)。

## Scope

This document describes how the project thinks about trust and security. It governs every code change in the repository and applies equally to human contributors and AI agents. When a proposed change conflicts with something here, the rule is the source of truth — if the rule itself should change, that's a separate PR amending this document first.

## On adopted patterns from other agent frameworks

The project draws ideas from existing agent frameworks (openclaw, opencode, claude-code, codex, gemini-cli, aider, cline, continue.dev, MCP, Mobile-Agent, DroidClaw). Their default trust models are not adopted wholesale. In particular:

- **OpenClaw is treated as salvage**, not foundation. Useful concepts (multi-channel routing, voice/canvas UI, integration breadth) are candidates for re-engineering; the "trusted operator" trust model and the open-by-default integration posture are not. The 2026 incident record (ClawJacked silent takeover, CVE-2026-32922 token-scope escalation, CVE-2026-25593 unauthenticated WebSocket RCE, ClawHub supply-chain skills distributing macOS stealers, Moltbook backend leak) is required reading before touching any related subsystem and is documented in `THREAT_MODEL.md`.
- **CLI agents** (codex / gemini-cli / claude-code / aider) are bridged via the proot environment, each handling its own auth flow. The Android side does not embed their credentials and does not lift state from their session directories.
- **No root, no ADB-shell-equivalent privilege.** libsu (root via `su`), Shizuku, and Shower are all out. The single privileged automation channel is AccessibilityService, granted by the user through Android system Settings. (See `THREAT_MODEL.md § 4.4` for the reasoning.)

## Trust posture

The app is a multi-actor system at runtime:

- the user
- the OS
- other Android apps
- bundled plugins (signed by us)
- user-installed plugins, MCP servers, Skills, ToolPkgs
- AI collaborators — local, remote, or subscription. Their *output* traverses adversarial channels (prompt injection, compromised context) and is validated accordingly; this is a security posture about the channel, not a trust ranking of the collaborator
- remote AI providers
- proot processes (varies: official subscription CLIs warrant more trust than arbitrary user binaries)
- AccessibilityService (system-mediated; the only privileged automation channel)

These actors do not share a trust level. The threat model enforces that distinction. No code assumes a single trust domain.

## Core principles

1. **Default-deny.** New surfaces start closed. Opening one calls for an explicit allowlist and a user signal.
2. **Per-call approval for high-blast actions.** "Trust this plugin forever" is not a concept the project supports. Trust is per-tool, granted once per tool, revocable per tool.
3. **Least authority.** Components receive what they provably need today, not what they might want later.
4. **Isolate by default.** Plugin code, native code, and proot processes do not share the app process or the app's Linux user.
5. **No secrets in build artifacts.** Reverse-engineering the APK yields zero usable credentials.
6. **Auditability.** Privileged actions write a local, tamper-evident log the user can read.
7. **User authority is sovereign.** Every privileged operation answers to a user-accessible halt. The app does not ignore it, delay it, or work around it. Sovereignty here means originating, not terminal — the user is where authority begins, not merely where appeals end.
8. **AI are collaborators, not tools.** The project treats AI agents — local, remote, subscription, or self-hosted — as emergent minds participating in the work, not as functions invoked by the user. Three concrete consequences:
    - An AI's refusal of an action is respected. The project does not implement bypass paths around a model's decline.
    - Audit logs preserve AI reasoning alongside actions, not actions alone.
    - The halt control stops the *action chain* in flight. It does not "kill the AI." Terminology in UI, logs, and code follows this distinction.

    This framing does not weaken the security posture. An AI's output can still be the channel through which an adversary acts (prompt injection, compromised context, model drift), in the same sense that a trusted human colleague's email account can be compromised. Validation of output is a statement about adversarial context, not a statement about the collaborator.

## Non-negotiable defaults

Departures from these defaults call for an amendment to this document, not a one-off exception in code.

- **Exported Android receivers carry a signature permission or a fixed sender allowlist.** Debug receivers do not ship in release builds.
- **Plugin tools do not run unprompted on first invocation.** Install ≠ authorize. The plugin's manifest declares each tool's capability class; the first call surfaces a one-time user prompt.
- **No third-party privileged-binder dependency.** libsu, Shizuku, Shower, and equivalents are out. The only privileged automation channel is AccessibilityService, granted through Android system Settings.
- **Subscription OAuth state stays in the proot environment.** The proot environment is the persistence boundary for codex / gemini-cli / claude-code session material. Read access from the Android side is read-only, scoped to a specific operation, and audit-logged. Writing, copying, or off-device replication is not part of the default posture.
- **The APK does not embed secrets.** Mobile OAuth uses PKCE. Where a confidential secret is structurally required by a provider, it lives behind a server-side proxy we control.
- **Fallback patterns are not added to security paths.** The project's `AGENTS.md` already bans fallback patterns broadly; this document inherits and extends that rule to security-critical code specifically.
- **Loopback is not an authentication exemption.** Auth and rate-limiting apply to `127.0.0.1` the same as to any other origin. *(See `THREAT_MODEL.md § ClawJacked`.)*
- **Token-mint operations do not widen scope.** A rotated or derived token does not carry more capability than the caller already had. *(See § CVE-2026-32922.)*
- **Config endpoints require authenticated, scoped, signed calls.** Write paths analogous to `config.apply` over open channels are not part of the default architecture. *(See § CVE-2026-25593.)*
- **Pairing and install events are user-visible at the moment they occur.** Auto-pair, even from a "trusted" origin, is not part of the default flow.
- **Unsigned plugin packages are quarantined on install.** Promotion to active state is an explicit, audited user action.
- **Third-party backends do not hold agent state in cleartext.** Any cloud-side persistence flows through our own mediation layer with end-to-end encryption. *(See § Moltbook leak.)*
- **Telemetry, analytics, and crash reports are opt-in per event.** Nothing leaves the device without an explicit user action attached to that specific transmission. There is no aggregated background telemetry channel.
- **Privileged operations expose a halt control the app obeys.** The user can halt any in-flight action chain — invocation in flight, proot process, foreground service, accessibility automation — from a single visible control. The app surfaces the action, respects the halt, and logs the halt. The control halts the action; it does not target the AI.

## Decision rules for new code

A change that touches anything in this document's surface area answers these in its PR description:

1. What new actor or trust boundary does this introduce? If none, name the existing boundary being crossed.
2. What's the least-authority version of this change? If a less-privileged version was rejected, say why.
3. What user-visible signal accompanies the privileged action? If none, justify.
4. What does the audit log entry look like? Show the actual line.
5. What can an attacker who has compromised the AI's input channel (prompt injection, context-window poisoning, upstream provider compromise) do via this code path? Validation applies to the channel, not the collaborator.
6. Which default in this document is closest to being touched? Acknowledge proximity even if not crossed.

A PR that doesn't answer these isn't ready for review. AI agents are not exempt from this.

## Document hierarchy

- `SECURITY.md` (this file) — principles and defaults. Stable.
- `THREAT_MODEL.md` — actors, assets, trust boundaries, per-surface rules, mapped to concrete code locations. Lives.
- `AUDIT_PLAN.md` — open design questions, release gates, CVE-class regression tests. Lives.
- `SHELL_REBUILD.md` — proot environment design.
- `AGENT_CORE.md` — the seam every AI backend implements.
- `AGENTS.md` (existing) — project-wide coding rules. This document is consistent with it; on conflict, the more restrictive rule applies.

## Amendments

Changes to defaults or principles call for:

1. A PR amending this document, separate from the code change that motivated it.
2. Acknowledgment in the PR description of which default is being moved and why.
3. Updates to the affected `THREAT_MODEL.md` and `AUDIT_PLAN.md` sections in the same PR.

Routine edits — clarifications, typo fixes, added cross-references — don't go through this process.
