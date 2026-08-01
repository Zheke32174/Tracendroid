# NETHUNTER_MODULE.md

> NLSpec + architecture for a Kali-NetHunter-style **authorized penetration-testing
> module** inside Tracendroid. This is a *specification*, not an implementation — per the
> project's spec-first / attractor methodology, code derives from this document. Companion
> to [`SECURITY.md`](./SECURITY.md), [`THREAT_MODEL.md`](./THREAT_MODEL.md),
> [`SHELL_REBUILD.md`](./SHELL_REBUILD.md), [`AGENT_CORE.md`](./AGENT_CORE.md).
> Status: **draft for review** — no code lands until the invariants in § 4 and the open
> questions in § 9 are resolved with the maintainer.

---

## 0. Scope of this document

This spec defines a mobile penetration-testing / security-research feature set for
Tracendroid, taking Kali NetHunter as the reference product. It does **not** attempt to
reproduce NetHunter faithfully — NetHunter assumes root + a custom kernel, and Tracendroid
has deliberately renounced both (`THREAT_MODEL.md § 4.4`). This document's core job is to
draw the honest line between:

- what a NetHunter-like toolkit can actually do on an **unrooted** Tracendroid device today,
- what is **gated on the later-stage `ryznix` substrate** (the `/data/local/tmp` rootless
  Linux subsystem), and
- what is **physically out of reach** on this device class without root or a custom kernel.

The module lands as a first-class Tracendroid feature (like the terminal toolbox), reusing
the existing tool-dispatch, permission-gate, halt, audit, and (forthcoming) proot subsystems.

---

> **Names.** `operit` is the internal package/namespace of the app shipped as **Tracendroid**
> (a fork of AAswordman/Operit) — so `/var/lib/operit`, `OperitApp`, and `operit-dispatcher.py`
> are intentional, not stale. `Shower` = a since-removed in-house Shizuku-style privileged
> server; `rish` = the Shizuku shell entrypoint (Linux uid 2000); `ClawJacked` = the 2026
> OpenClaw silent-takeover incident cited in `THREAT_MODEL.md § 5`.

---

## 1. Intent — what this is and why

**What.** An on-device toolkit that lets the user (and, when explicitly granted, an AI
collaborator) run *authorized* reconnaissance, traffic analysis, and security tooling
against targets the user owns or is contracted to test — organized around an **engagement**
(a scoped, consented, audited testing session), and executed through Tracendroid's existing
permission-gated tool plane.

**Why here.** Tracendroid already ships — or is actively building — the substrate a mobile
pentest cockpit needs (availability is per-component; the proot environment in particular is
*scaffolded but not yet functional*, § 7 Phase 2):

| NetHunter needs | Tracendroid already has |
|---|---|
| A Linux userland with tools | The proot Debian 12 environment — **scaffolded, not yet functional** (`libproot.so` + a signed rootfs still pending: `SHELL_REBUILD.md`, `STATUS.md`; see § 7 Phase 2) |
| A terminal | Terminal toolbox UI (`ui/features/toolbox/screens/`) |
| A permission/consent model | Global-default + per-tool exceptions; `JsPluginGate`/`AiToolGate` default-deny + per-call overlay + audit (`THREAT_MODEL.md § 4.2`) |
| An emergency stop | `HaltController` sovereign halt (`THREAT_MODEL.md § 4.7`) |
| Credential handling | `CredentialVault` (EncryptedSharedPreferences) (`THREAT_MODEL.md § 4.9`) |
| A tool-authoring pipeline | Default-tools architecture (`docs/DEFAULT_TOOLS_ARCH.md`) + ToolPkg plugins |

Building NetHunter capabilities as a *module* rather than a new app means we inherit all of
the above instead of re-implementing chroot, terminal, permissions, and audit — the exact
tension the user flagged ("module in Tracendroid," not a standalone app).

**Non-goals.** This is not a "one-tap attack" app. There is no capability that runs without
an engagement scope, a consent gate, and an audit trail. There is no bypass around a user
halt or an AI decline. There is no fallback path in any security-relevant flow
(`AGENTS.md`, `SECURITY.md`).

---

## 2. The hard constraint — the unrooted reality

Kali NetHunter's headline capabilities (Wi-Fi monitor mode + injection, HID/BadUSB, SYN
scans, deauth, evil-AP) all require **root and/or a NetHunter kernel** with wireless-injection
and USB-gadget drivers. Tracendroid's threat model closed this door on purpose:

- **`libsu` (root), Shizuku, and Shower are removed.** AccessibilityService is the *only*
  privileged automation channel (`THREAT_MODEL.md § 4.4`, `SECURITY.md` non-negotiables).
- **The proot environment is a *soft* boundary**, not a sandbox, and runs as the app's own
  Linux UID — it grants a Debian userland, **not** new kernel privileges. A tool that needs
  `CAP_NET_RAW` or `CAP_NET_ADMIN` cannot get it merely by living inside proot. (The
  environment itself is not yet functional — its binary and rootfs are pending, § 7 Phase 2;
  this describes its nature *once it runs*.)
- **`chroot()` is not used** (needs `CAP_SYS_CHROOT`); proot is the launcher (`SHELL_REBUILD.md`).

Consequence, stated plainly: **on a stock unrooted phone, an app-UID (or even shell-UID)
process cannot open raw sockets, put the Wi-Fi NIC in monitor mode, inject frames, or expose
a USB-HID gadget.** No amount of userland tooling changes that — it is a kernel/driver
boundary, not a packaging problem. This spec therefore treats those NetHunter features as
**out-of-reach-on-this-device** and routes them to the later-stage substrate or to external
hardware (§ 6), rather than pretending a proot Kali gives them to us.

What *is* reachable unrooted is still substantial: connect-scan reconnaissance, service and
host discovery, Wi-Fi/BLE environment survey, on-device traffic capture and interception via
`VPNService`, and the full Kali/Debian userland of analysis tools (nmap connect scans,
whatweb, nikto, sqlmap, hydra against reachable services, gobuster, etc.). That reachable
subset — wrapped in scope/consent/audit — is the module.

---

## 3. Capability matrix

For each capability the roadmap leads with, three columns. **Now** = reachable with what
exists or is in active development, tagged **(P1)** = app-UID Android APIs (available today) or
**(P2)** = the proot Debian userland (gated on the shell rebuild, § 7 Phase 2). **Later-stage**
= gated on the `ryznix` shell-UID substrate / external hardware. **Out of reach** = needs root
or a custom kernel on this device.

### 3.1 Kali chroot manager

| Now (unrooted / proot) | Later-stage (ryznix substrate) | Out of reach |
|---|---|---|
| **(P2 — gated on the shell rebuild)** Manage the **Debian 12 proot** rootfs (bootstrap/verify/extract per `SHELL_REBUILD.md`); a curated **tool catalog** (install-on-demand: nmap, whatweb, nikto, gobuster, sqlmap, hydra, dnsutils, tcpdump*, mitmproxy, python tooling); launch tools via the Unix-socket IPC bridge; per-tool capability gating; evidence capture to the engagement | Run a Kali/Debian userland as **shell-UID (uid 2000)** in `/data/local/tmp` via the ryznix `ryzkern` router (bionic/glibc/musl/x86_64-QEMU), i.e. a wider arsenal + `pm`/shell-uid reach than app-UID proot | A true privileged Kali chroot (`chroot()` + `CAP_*`); kernel modules |

\* `tcpdump` runs but cannot capture without `CAP_NET_RAW`; capture is provided instead via `VPNService` (§ 3.3), not libpcap on the NIC.

### 3.2 Wi-Fi / network recon

| Now (unrooted / proot) | Later-stage | Out of reach |
|---|---|---|
| **(P1 app-UID; nmap variants P2)** **TCP-connect port scans** (app-UID sockets or nmap `-sT`); **host discovery** by connect-sweep + `InetAddress.isReachable`; **mDNS/DNS-SD, SSDP/UPnP, NetBIOS** enumeration (multicast via `MulticastLock`); **Wi-Fi environment survey** (`WifiManager.scanResults`: SSID/BSSID/RSSI/caps — location-gated, throttled to ~4 scans/2 min); **BLE device survey** (`BluetoothLeScanner`); ARP **neighbor-table read** where the OS permits; service/version probing on reachable ports | Native scanners run as shell-UID; QEMU-hosted x86_64 tools; larger nmap script sets | **Monitor mode / packet capture off the NIC**; **SYN/stealth/`-sS` scans**; **frame injection / deauth**; **evil-AP** — all need `CAP_NET_RAW`/`CAP_NET_ADMIN` + injection-capable driver (root + kernel) |

### 3.3 MITM / proxy

| Now (unrooted / proot) | Later-stage | Out of reach |
|---|---|---|
| **(P1 VpnService capture · P2 in-proot mitmproxy)** **On-device traffic capture** via `VPNService` (no root — the PCAPdroid model): route this device's egress to a **local intercepting proxy** (mitmproxy running in proot on loopback); inspect/replay HTTP(S); **TLS interception only for apps that trust a user CA** — which, on Android 7+, means the module's own traffic, explicitly cooperating apps, or **debug builds** where `network_security_config.xml` trusts user CAs under `<debug-overrides>` (`THREAT_MODEL.md § 4.11`) | Transparent proxying of shell-UID/QEMU workloads; proxying other userlands staged in `/data/local/tmp` | **System-wide TLS MITM of arbitrary third-party apps** (needs the **system** CA store → root); ARP-spoof on-path MITM of *other* hosts (needs `CAP_NET_RAW`) |

Hard rule carried from `SECURITY.md`: the local proxy endpoint is **not** loopback-exempt —
auth + the halt apply to `127.0.0.1` exactly as to any origin (`THREAT_MODEL.md § 5`, ClawJacked).

### 3.4 HID / BadUSB

| Now (unrooted / proot) | Later-stage | Out of reach |
|---|---|---|
| **(P1)** **Author + manage payloads** (Ducky-Script-style) and keep them in the engagement as artifacts; *simulate*/lint them; drive on-device UI **only** through the existing AccessibilityService automation (never presented as "HID") | Deliver payloads through an **external hardware companion** (a USB Rubber Ducky / microcontroller the phone talks to over OTG serial or BLE), or through a future **ryzdroid** device that ships a HID-gadget-capable kernel | **On-device USB-HID gadget injection** on stock hardware — needs the ConfigFS USB gadget driver + `CAP_SYS_ADMIN` (root + kernel) |

HID is therefore an **author-here, deliver-elsewhere** capability in v1: the phone is the
console, not the injector.

---

## 4. Invariants (non-negotiable)

The module inherits every `SECURITY.md` red line and adds engagement-specific ones. These
are the acceptance gates; a change that violates one is rejected regardless of utility.

1. **Authorized-scope gate (new, module-defining).** No recon/capture/intercept/payload tool
   executes outside an **active engagement** whose `scope` (target hosts/CIDRs/SSIDs/domains)
   and `authorization` attestation the user has affirmed. A target not in scope is refused
   with a structured error naming the missing scope entry — never silently widened. This is
   the pentest analogue of default-deny.
2. **Default-deny + per-call approval for high-blast.** Every module tool maps to a capability
   class and is default-deny per `(caller × capability)` through the existing gate
   (`JsPluginGate`/`AiToolGate`, `THREAT_MODEL.md § 4.2`). That gate persists a grant per
   `(caller × class)` after first approval — *once-then-silent*. For the module's
   **irreversible / high-blast** tools (active scan against a live target, capture start,
   intercept start, payload build) that model is too weak: these tools **re-prompt on every
   invocation** and must **not** ride a persisted class grant. Only low-blast, read-only tools
   (passive survey, evidence read) use the standard once-then-persist grant. (This is a
   deliberate strengthening over the shared gate's default; see the note under invariant 5 on
   why the module cannot simply inherit the substrate's guarantees verbatim.)
3. **New capability classes are an explicit security decision.** The module introduces
   `NETHUNTER_RECON`, `NETHUNTER_CAPTURE`, `NETHUNTER_INTERCEPT`, and `NETHUNTER_PAYLOAD`
   classes in `JsCapabilityClassifier`. Unclassified module tools fall through to
   `UNCLASSIFIED` (most-restrictive → deny), so a new tool is inert until classified.
4. **Halt is sovereign.** Every long-running action (a scan, a capture, a proxy session, a
   proot tool invocation) registers with `HaltController`; a halt tears down the scan
   threads, stops the `VPNService`, kills the in-proot process, and refuses new module tool
   calls until cleared — same semantics as `THREAT_MODEL.md § 4.7`.
5. **Everything is audited; evidence is durable.** Every module action writes an `AuditEvent`
   (engagement id, capability, tool, target, decision, timestamp) plus, where the AI initiated
   it, the `AgentReasoningTrace` snapshot. The existing app-wide audit surfaces are **bounded,
   in-memory rings** — they don't survive process death and can be flooded to evict an entry —
   so the engagement's **evidence log does not reuse them**. It is **append-only, persistent,
   and hash-chained** (each `EvidenceItem` stores the hash of its predecessor), giving a pentest
   report a chain of custody that is genuinely tamper-evident and survives restarts; an
   evidence-write failure **fails the action closed** rather than letting it proceed unrecorded.
   (General verdict from spec review: a module cannot claim a guarantee its substrate doesn't
   provide — where the shared gate/audit fall short of what a pentest tool needs, the module
   specifies its own stronger mechanism rather than papering over the gap.)
6. **No fallback, no degradation.** If proot is unavailable, a proot-dependent tool returns a
   structured "environment not ready" error pointing at `ShellBootstrapScreen` — it does
   **not** silently fall back to a weaker app-UID path. (`AGENTS.md`, `SECURITY.md`.)
7. **Credentials via the vault.** Any secret the module handles (target creds for an
   authorized cred-audit, proxy CA private key, API tokens) lives in `CredentialVault`, never
   in DataStore, never in the APK.
8. **AI-collaborator posture is unchanged.** An AI can be *granted* module capabilities
   per-call, but its output is a validated channel (prompt-injection aware); an AI decline of
   a pentest action is respected and never bypassed (`SECURITY.md § 8`).
9. **Loopback is not an exemption; token-mint does not widen scope; config writes are
   authenticated/signed** — the three CVE-class red lines apply to every new IPC endpoint the
   module adds (`THREAT_MODEL.md § 5`).

---

## 5. Architecture — where it docks in the codebase

### 5.1 Layering

```
 ┌─────────────────────────────────────────────────────────────┐
 │ UI: ui/features/nethunter/                                   │
 │   EngagementListScreen · EngagementScreen (scope/consent)   │
 │   ReconScreen · CaptureScreen · InterceptScreen · Payloads  │
 │   EvidenceScreen  (+ overlays already mounted at OperitApp) │
 ├─────────────────────────────────────────────────────────────┤
 │ Domain: core/nethunter/                                     │
 │   Engagement, Scope, Target, Evidence, EngagementStore      │
 │   ScopeEnforcer (the scope gate)                            │
 ├─────────────────────────────────────────────────────────────┤
 │ Tools: core/tools/defaultTool/standard/nethunter/*         │
 │   ReconTools · CaptureTools · InterceptTools · PayloadTools │
 │   → registered in ToolRegistration, prompted in            │
 │     SystemToolPrompts, classified in JsCapabilityClassifier │
 ├───────────────┬─────────────────────────────────────────────┤
 │ App-UID path  │ Proot path                                  │
 │ Android APIs: │ shell/ipc/ShellIpcClient → operit-          │
 │ Socket, Wifi- │ dispatcher.py (Debian tools in the env)     │
 │ Manager, BLE, │ ("nethunter" capability claim on each call) │
 │ VpnService    │                                             │
 └───────────────┴─────────────────────────────────────────────┘
        every call ↓ passes through
   AiToolGate / JsPluginGate (default-deny) · HaltController · AuditEvent
```

### 5.2 Reuse (do **not** re-implement)

- **Tool dispatch:** register executors in
  `core/tools/ToolRegistration.kt`; prompts/schemas in `core/config/SystemToolPrompts.kt`;
  JS-side wrappers in `core/tools/javascript/JsTools.kt`; TS types in `examples/types/*.d.ts`
  — the full checklist in `docs/DEFAULT_TOOLS_ARCH.md` applies to every module tool.
- **Gating/audit:** `core/tools/AiToolGate.kt`, `core/tools/javascript/{JsPluginGate,
  JsCapabilityClassifier}.kt`; per-call overlay `ui/features/plugingate/ToolGateConfirmationOverlay.kt`.
- **Halt:** `core/halt/HaltController.kt` (register every long-running task; observe
  `requestHalt`).
- **Proot execution:** `shell/ipc/ShellIpcClient.kt` → `app/src/main/assets/rootfs/operit-dispatcher.py`.
  Module tools that run Debian binaries send an envelope tagged with origin +
  `NETHUNTER_*` capability claim; the dispatcher enforces the class before exec (defense in
  depth, matching `THREAT_MODEL.md § 4.5`, the dispatcher command-refusal pattern). The proot fs contract (`/workspace` persistent, `/var/lib/operit/ipc/`
  socket, `/opt/cli/`) is unchanged; the Kali tool catalog installs under the env's package
  manager on demand, never vendored into the base rootfs.
- **Credentials:** `data/preferences/credentials/CredentialVault.kt`.
- **Distribution as plugins (optional):** the catalog can also ship as signed `.toolpkg`
  bundles via the plugin-trust pipeline (`core/plugintrust/*`) rather than baked-in tools —
  decided per § 9.

### 5.3 The engagement domain model

```
Engagement {
  id, title, createdAt, state: DRAFT|ACTIVE|HALTED|CLOSED
  scope: Scope { hosts: [CIDR|IP|host], ssids: [BSSID|SSID], domains: [glob] }
  authorization: { attestedBy, attestedAt, note }   // user affirmation of authorized target
  targets: [Target { ref, notes, findings: [...] }]
  evidence: append-only, hash-chained, persistent
            [EvidenceItem { ts, kind, capability, tool, target, blobRef, contentHash, prevHash }]
}
```

`ScopeEnforcer.check(target, capability)` is called by *every* module tool before any active
operation and is the code embodiment of invariant 1 (the authorized-scope gate). Passive environment survey
(Wi-Fi/BLE list of what's in the air) is allowed pre-scope but is itself gated and audited,
and never associates observed devices with a target until the user adds them to scope.

### 5.4 New capability classes

`JsCapabilityClassifier` gains four rows; the class → risk mapping is a security decision
recorded in `AUDIT_PLAN.md`:

| Class | Example tools | Default |
|---|---|---|
| `NETHUNTER_RECON` | `nh_port_scan`, `nh_host_discover`, `nh_wifi_survey`, `nh_ble_survey`, `nh_service_probe` | deny (ASK on first use) |
| `NETHUNTER_CAPTURE` | `nh_capture_start`/`stop` (VpnService) | deny (ASK) |
| `NETHUNTER_INTERCEPT` | `nh_proxy_start`/`stop`, `nh_replay` | deny (ASK) |
| `NETHUNTER_PAYLOAD` | `nh_payload_build`, `nh_payload_lint` | deny (ASK) |

---

## 6. The later-stage substrate bridge (ryznix / termux2 / ryzdroid micro / emulator)

This is the "later stage" the user described: the NetHunter module's ceiling rises when it
can execute against the **`ryznix`** rootless subsystem rather than only app-UID proot.

- **`ryznix`** = a multi-libc, multi-arch Linux subsystem in `/data/local/tmp`, run as
  **shell uid 2000 via Shizuku/rish**, with the `ryzkern` router (bionic/glibc/musl +
  **x86_64 via QEMU-user** — "the emulator"). Shell-UID buys reach app-UID lacks (write
  `/data/local/tmp`, `pm`, broader FS read), and the QEMU path buys x86_64 tooling.
- **`termux2`** = a second/rebuilt bionic userland staged alongside, feeding ryznix's
  bionic stratum.
- **`ryzdroid micro`** = a minimal on-device ryznix profile — the compact substrate the
  module would target on the phone.
- **`masamune`** = **not** a Tracendroid component. It is the top orchestration layer of the
  "shogun" multi-agent system (Masamune→Yojimbo→Genji ≈ Shogun→Karō/Gunshi→Ashigaru; Bash +
  tmux, YAML-mailbox coordination). "Masamune must be completed" is a **sequencing gate**: it
  is the orchestrator meant to drive the fleet that builds/stages the ryznix + termux2 +
  ryzdroid substrate. Its one known defect (a test `timeout 300` killing slow CLI agents) was
  already diagnosed — run it detached (per the hive-journey `shogun-masamune-yojimbo-genji`
  memory; the orchestrator's own source isn't in a repo I can currently reach — § 9 Q4).
  Completion is prerequisite *to the substrate build*,
  not to the near-term (Phase 1–3) Tracendroid work.

**The architectural tension to resolve before any of this lands (§ 9):** `ryznix` derives its
power from **Shizuku/rish (shell uid 2000)** — precisely the privileged channel Tracendroid
*removed* in `THREAT_MODEL.md § 4.4`. Bridging Tracendroid to ryznix therefore re-introduces a
shell-UID channel and is an **`SECURITY.md`-amendment-level decision**, not a code detail:
it needs its own THREAT_MODEL row, its own actor entry, and answers to the six decision-rule
questions. Crucially, **even shell-UID does not grant root** — monitor-mode/injection/HID
remain out of reach (§ 3) until a rooted or custom-kernel `ryzdroid` device exists.

---

## 7. Roadmap (phased and sequenced — each phase delivers standalone user value; only Phase 1 is dependency-free)

- **Phase 0 — this spec.** Land `NETHUNTER_MODULE.md`; add a `THREAT_MODEL.md` row (design);
  register the four `NETHUNTER_*` capability classes; answer the six decision-rule questions
  (§ 8). *Depends on:* nothing. No runtime code.
- **Phase 1 — engagement + passive/connect recon (app-UID only).** Engagement domain +
  `ScopeEnforcer` + evidence log; `nh_wifi_survey`, `nh_ble_survey`, `nh_host_discover`,
  `nh_port_scan` (connect), `nh_service_probe`, mDNS/SSDP/NetBIOS enumeration; UI screens.
  *Depends on:* Phase 0 only. This is the one genuinely dependency-free, ships-today phase.
- **Phase 2 — chroot / tool manager.** Kali/Debian tool catalog install-on-demand; run tools
  through the IPC dispatcher under `NETHUNTER_*` claims; evidence capture. *Depends on:* the
  shell rebuild — `libproot.so` + a signed rootfs release (`STATUS.md` open follow-ups), which
  do not exist yet.
- **Phase 3a — traffic capture (app-UID).** `VpnService` on-device capture (`nh_capture_*`) +
  payload authoring (`nh_payload_*`) as artifacts. *Depends on:* Phase 1. Independent of proot.
- **Phase 3b — intercept (in-proot).** mitmproxy on loopback (`nh_proxy_*`, `nh_replay`);
  user-CA TLS interception constrained to debug builds / cooperating apps per § 3.3.
  *Depends on:* Phase 2 (runs inside proot) + Phase 3a.
- **Phase 4 — later-stage substrate.** Shell-UID ryznix bridge; target the ryzdroid-micro
  arsenal + QEMU x86_64; external-hardware HID delivery. *Depends on, in order:* (a) `masamune`
  completed and (b) the `ryznix` substrate actually built by it (§ 6 — masamune's source is
  currently unreachable, § 9 Q4); (c) a `SECURITY.md` amendment authorising the shell-UID
  channel (§ 9 Q3). Monitor-mode / injection / on-device HID remain out of reach until a rooted
  or custom-kernel device exists — no phase delivers them on stock hardware.

---

## 8. SECURITY.md decision-rule answers (pre-answered for the module)

1. **New actor / boundary?** Yes — an *engagement* is a new authorization boundary; the
   later-stage ryznix bridge (Phase 4) is a new shell-UID actor (deferred, amendment-gated).
2. **Least-authority version?** Phase 1 uses app-UID Android APIs only (no proot, no shell-UID).
   Each phase adds the minimum reach its capability requires; nothing acquires root-class
   authority (it isn't available and isn't sought).
3. **User-visible signal?** Engagement state banner; the per-call confirmation overlay — on
   first use for low-blast tools, on **every** invocation for high-blast ones (invariant 2);
   an always-visible capture/proxy indicator while `VpnService`/proxy is live; the halt FAB.
4. **Audit line?** `AuditEvent(engagementId, capability=NETHUNTER_RECON, tool=nh_port_scan,
   target=10.0.0.5, decision=GRANTED, ts=…)` appended to the engagement's tamper-evident log.
5. **Compromised-AI-input blast radius?** An AI with a poisoned input channel could *request*
   an out-of-scope scan or a payload build — but `ScopeEnforcer` refuses out-of-scope targets,
   the gate default-denies each capability with a user confirmation, and the halt stops any
   in-flight chain. Validation is on the channel, not the collaborator.
6. **Closest default touched?** Default-deny and "no fallback in security paths" — both
   honored (§ 4). Proximity to the "no privileged-binder dependency" default is acknowledged
   for Phase 4 (ryznix/Shizuku) and explicitly deferred behind a SECURITY.md amendment.

---

## 9. Open questions (need a maintainer decision before coding)

1. **Baked-in tools vs. signed `.toolpkg` plugins.** Should the recon/capture/intercept tools
   be first-party default tools (in `core/tools/defaultTool/standard/nethunter/`) or shipped
   as signed plugins through the `core/plugintrust` pipeline? Default-tools = tighter
   integration + review at build time; plugins = optional install + the `THREAT_MODEL.md § 4.3` trust story.
2. **Authorization attestation strength.** Is a user-affirmed scope + note sufficient, or does
   the engagement need something stronger (e.g. a signed scope file, an expiry)? Pentest tools
   invite misuse; the gate should match the maintainer's risk appetite.
3. **Phase 4 shell-UID bridge — amend SECURITY.md?** Bringing in ryznix (Shizuku/shell-UID)
   reverses a closed threat-model decision. Confirm this is desired and worth the amendment
   before any Phase 4 design.
4. **`masamune` completion — is it in scope for me?** Its code appears to live outside the
   GitHub repos I can see (referenced as a local `~/stack/multi-agent-shogun` clone in the
   hive-journey memory), so I can't complete it from here without that source. If you want it
   done as a separate work item, point me at the repo/path.
5. **ZH mirror.** The repo mirrors every `docs/*.md` to `.zh.md`. Confirm you want a
   `NETHUNTER_MODULE.zh.md` mirror generated once the EN spec is settled.

---

## 10. Cross-references

- `SECURITY.md` — principles / red lines the module inherits.
- `THREAT_MODEL.md` — § 4.2 (tool gate), § 4.3 (plugin/ToolPkg trust), § 4.4 (no
  root/Shizuku), § 4.5 (subscription OAuth in proot; dispatcher command-refusal), § 4.7 (halt),
  § 4.9 (vault), § 4.11 (cleartext/user-CA), § 5 (CVE-class red lines). A new row for this
  module lands in Phase 0.
- `SHELL_REBUILD.md` — the proot Debian 12 environment + IPC the chroot manager builds on.
- `AGENT_CORE.md` — the AI-backend seam that grants/denies module capabilities.
- `docs/DEFAULT_TOOLS_ARCH.md` — the tool-authoring checklist every `nh_*` tool follows.
- External substrate: `zheke32174/ryznix` (`RYZNIX-METHOD.md`, `SUPERLINUX-HOWTO.md`),
  `zheke32174/ryz` (`RYZOS.md`), hive-journey `memory/shogun-masamune-yojimbo-genji.md`.
