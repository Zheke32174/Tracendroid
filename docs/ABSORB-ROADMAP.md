# Masamune — Donor Absorption Roadmap

**Method:** for each assigned donor we reverse-engineer its real function (verified by web search, not
memory), then design a *superior native variant absorbed into Masamune* — never bundling or shipping the
donor itself, per the campaign's "understand the donor, build a better native piece" methodology. This
document is the durable design home for that absorption work. It cross-references
[`MASAMUNE.md`](../MASAMUNE.md) (the harness + ryznix second-OS architecture) and, where relevant,
`docs/ROOTLESS-XPOSED.md`.

**Confidence markers:** ✅ confident (identified + verified) · ⚠ needs user confirmation (obscure,
internal, or likely-misspelled — best guess only, no fabricated features).

---

## Verbatim donor list (as assigned)

> VfoneGaga, Kai9000, Termux, Xed Editor, OpenOmniBot, TempRoot, Agora, harness-remote, KaiOS,
> KaiOS STF agent, Sentinel-APK, SuperAI, LokiDroid, OpenDroid, SOMCP, Total Commander,
> android-remote-control-mcp

17 donors. Grouped below by theme: **(A)** terminal/dev-env, **(B)** remote-control / agent-harness
plane, **(C)** OS/root layer (ryznix), **(D)** uncertain/misc.

---

## Group A — Terminal / dev-env (the on-device dev surface)

Masamune already ships a `:terminal` module. Group A donors define what a *first-class on-device dev
surface* looks like: shell + editor + file manager, all reachable by the AI harness as tools.

### A1. Termux ✅
**What it is:** Android terminal emulator + a full Linux package ecosystem (`pkg`/`apt`, its own
`termux-packages` repo, `~/.termux`, no root required). The de-facto on-device POSIX userland.
**Key features to reverse-engineer:** the bootstrap rootfs under app-private storage; the package
manager and build repo; `termux-api` (bridge to Android intents/sensors/clipboard/notifications);
`RUN_COMMAND` intent + `termux-exec`; the add-on model (Termux:API, :Boot, :Widget, :Float).
**Superior Masamune variant:** fold the terminal into the existing `:terminal` module as a
**harness-owned shell** — the AI can spawn, stream stdout/stderr, and read exit codes as a native tool
(not screen-scraping). Package env exposed to the agent as an execution sandbox. This is also the
launcher substrate MASAMUNE.md's build-order step 1 assumes (the "Termux/qemu launcher + supervisor
pattern" for guest bring-up). **Confidence: ✅**

### A2. Xed Editor ✅
**What it is:** a lightweight mobile code/text editor for Android (syntax highlighting, multi-file,
project tree — the "code editor on your phone" niche).
**Key features:** syntax-aware editing, file-tree navigation, editing files inside the Termux/app
sandbox, extensible language support.
**Superior Masamune variant:** an **agent-editable buffer surface** — the editor is a view the harness
can also read/patch programmatically (open file → propose diff → apply), so human and agent edit the
same tree. Pairs directly with A1 (edit what the shell builds). **Confidence: ✅**

### A3. Total Commander ✅
**What it is:** the classic dual-pane file manager (desktop heritage; the Android build adds
archive handling, LAN/FTP/cloud plugins, root-aware browsing).
**Key features:** dual-pane copy/move, archive in/out, network filesystem plugins, batch ops.
**Superior Masamune variant:** a **file-plane the harness treats as a tool** — programmatic
copy/move/inspect/archive across the app sandbox, Termux rootfs, and (later) the ryznix guest's
filesystem over Yojimbo's `GuestChannel`. Dual-pane is the human UI; the same ops are agent-callable.
**Confidence: ✅**

**Group-A verdict:** most tractable, near-term. All three build on the existing `:terminal` module and
need no root and no guest OS. This is where absorption should start.

---

## Group B — Remote-control / agent-harness plane (MCP + agent integration points)

These are Masamune's **remote-control plane**: how an external brain (or Masamune-as-brain) drives an
Android device. Almost all are MCP servers or agent apps — i.e. integration points, not features to
clone wholesale. The native variant is a **single Masamune MCP/agent surface** that these donors inform.

### B1. KaiOS STF agent / openSTF (DeviceFarmer) STF ✅
**What it is:** STF (Smartphone Test Farm / openSTF, now DeviceFarmer) is a remote
device-control/device-farm platform; the **STF agent** (`STFService`/minicap/minitouch) runs on-device
to stream the screen and inject input over the network. "KaiOS STF agent" = that agent adapted to drive
a KaiOS/feature-phone target.
**Key features:** `minicap` (screen capture stream), `minitouch` (input injection), agent service
that brokers device control to a remote server; multi-device orchestration.
**Superior Masamune variant:** a native **capture+inject transport** for the harness's remote-control
plane — screen stream + input injection exposed as harness tools, so Masamune (or a remote operator)
drives the host device *and* the ryznix guest. This is the low-level plumbing under the MCP servers in
B2–B3. **Confidence: ✅**

### B2. android-remote-control-mcp ✅
**What it is:** `danielealbano/android-remote-control-mcp` — an MCP server that **runs on the phone**,
lets an AI model fully control the device via accessibility services + screenshot capture, is optimized
for token usage, supports file downloads, and does automated Cloudflare/ngrok tunnelling.
**Key features:** on-device MCP endpoint; accessibility-driven UI actions; screenshot observation;
token-frugal payloads; built-in tunnelling for reaching the phone from outside.
**Superior Masamune variant:** the **canonical shape** for Masamune's on-device MCP server — accessibility
+ screenshot control, token-frugal by design, with tunnelling. Masamune's harness both *hosts* this
(so remote brains drive it) and *consumes* it (so Masamune-as-brain drives other devices).
**Confidence: ✅**

### B3. SOMCP ⚠
**What it is:** *not confidently identified.* No repository matched "SOMCP" directly. Given the
company it's assigned in, best guess: **an Android MCP server variant** ("S… O… MCP" / "Some/Simple/
System-Ops MCP") in the same family as B2 (minhalvp/android-mcp-server, CursorTouch/Android-MCP,
mobile-next/mobile-mcp were the near neighbors).
**Superior Masamune variant (if the guess holds):** folds into the same single Masamune MCP surface as
B2 — no second server. **Confidence: ⚠ needs user confirmation** (which repo / is this ADB-side or
on-device?).

### B4. harness-remote ✅
**What it is:** `giuliastro/harness-remote` — a companion app to **control coding-agent harnesses from
phone or desktop** (supports OpenCode, PI, Oh-My-Pi/OMP, and Claude Code). Web app (GitHub Pages,
installable PWA) + Android APK; browse/monitor agent sessions, drive prompts/commands, manage multiple
server configs.
**Key features:** multi-harness session browser; remote prompt/command control; multi-server config;
PWA + APK delivery.
**Superior Masamune variant:** the **operator console** for Masamune's own harness — a thin remote UI
that speaks to Masamune-as-brain (start/monitor/steer agent sessions from a second device), reusing the
B1/B2 transport rather than a bespoke protocol. This is the "drive the harness remotely" piece as
opposed to B1/B2's "drive the device remotely." **Confidence: ✅**

### B5. OpenOmniBot ✅
**What it is:** `omnimind-ai/OpenOmniBot` — an on-device mobile AI agent (native Android Kotlin +
Flutter) built around the full understand→decide→execute→reflect loop.
**Key features:** VLM-driven UI understanding + operation; extensible tool ecosystem (skills, an
**Alpine** environment, browser, MCP, Android system-level tools); system actions (scheduled tasks,
alarms, calendar, audio); short- + long-term memory with embeddings; file/workspace/terminal tools.
**Superior Masamune variant:** a reference for Masamune's **agent loop + tool ecosystem** — Masamune
already has the on-device LLM (llama/mnn) and terminal; absorb the *loop shape* (reflect step, embedded
memory, skills/Alpine/MCP tool taxonomy) rather than the app. Its Alpine env overlaps A1 (Termux) — pick
one userland (Termux) and mount the skill/tool model on top. **Confidence: ✅**

### B6. Agora ✅
**What it is:** `newo-ether/Agora` — an Android **BYOK LLM client** with multi-provider access,
agentic workflows, and **remote device control** (MIT). 8+ built-in providers + custom endpoints,
non-linear branching conversations, local models via llama.cpp, encrypted-shell control of remote
machines, everything on-device.
**Key features:** BYOK multi-provider routing; local llama.cpp inference (direct overlap with Masamune's
`:llama`); conversation branching; encrypted remote shell.
**Superior Masamune variant:** informs Masamune's **provider-routing + BYOK layer** and the
encrypted-remote-shell transport (a cleaner alternative/complement to tunnelled MCP for machine control).
Note: NOT the same as `agora.io` (the RTC SDK) — this is the newo-ether agent client. **Confidence: ✅**

### B7. OpenDroid ✅
**What it is:** `yashab-cyber/opendroid` — "Your Open Autonomous Android Agent": a self-planning AI
assistant powered by local/remote LLMs with **accessibility-driven screen automation**.
**Key features:** self-planning agent loop; local + remote LLM backends; accessibility-service screen
automation (same control primitive as B2).
**Superior Masamune variant:** overlaps B2/B5 heavily — absorb the **self-planning loop** into the
harness; its accessibility automation is redundant with B2's and should collapse into the one native
control layer. **Confidence: ✅**

### B8. SuperAI ⚠
**What it is:** *not confidently identified* — the name is generic and no single repo matched. Best
guess: an **on-device LLM agent/assistant app** (the space of LLM-Hub / AppAgent / Octopus-v2-style
on-phone agents). No verified feature set; nothing asserted here.
**Superior Masamune variant:** deferred until identified — likely collapses into the B5/B7 agent-loop
work. **Confidence: ⚠ needs user confirmation** (which SuperAI — repo/vendor?).

### B9. LokiDroid ⚠
**What it is:** *low confidence.* Public references point to **LokiDroid as an Android RAT**
(remote-access trojan: SMS/calls/contacts/location/mic/camera exfil) catalogued in Android-RAT lists —
i.e. a covert remote-control tool. It's plausible the assignment intends its **remote-access capability
set** as a donor for the remote-control plane, but this could equally be a different internal tool by
the same name.
**Superior Masamune variant (if RAT-capability is the intent):** the *capabilities* (remote
sensor/IO access) already belong, consent-gated and privilege-brokered, to the harness's control plane
via Yojimbo — Masamune would implement them as **authorized, auditable** device access, explicitly NOT
a covert RAT. **Confidence: ⚠ needs user confirmation** (is this the RAT, and is the intent its
capability surface, not its stealth?).

**Group-B verdict:** the second-most tractable theme. The confident donors (B1, B2, B4, B5, B6, B7) all
converge on **one native Masamune surface**: an on-device MCP/accessibility control server + a
provider-routing agent loop + a remote operator console. Do NOT build one integration per donor — build
the single surface and let each donor inform a facet. Three donors here (B3, B8, B9) need user
confirmation before design.

---

## Group C — OS / root layer (ryznix second OS)

This is the theme MASAMUNE.md flags as **furthest from done**. Nothing here is buildable near-term; this
section ties each donor to the existing second-OS build order (MASAMUNE.md §"Build order toward the
second OS").

### C1. KaiOS ✅
**What it is:** a feature-phone operating system with **Gecko / B2G (Boot-to-Gecko) / Firefox OS
lineage** — a web-tech (HTML/JS) userspace on a Linux/Android-derived base, running on low-end hardware.
**Key features:** Gecko runtime; web-app (packaged-app) model; KaiStore; Gaia-derived UI; small
footprint.
**Superior Masamune variant:** *long-horizon.* A candidate **guest-userspace profile** the ryznix VM
could host (a lightweight web-OS target) OR a compatibility layer for running KaiOS packaged apps under
Masamune. Secondary to the Evolution-X-based ryznix guest that MASAMUNE.md names as the primary ROM base
— KaiOS is the "small web-OS" alternative/experiment, not the main line. **Confidence: ✅ (identity)** /
absorption plan is exploratory.

### C2. Kai9000 ⚠
**What it is:** *not confidently identified.* No direct match. Given the "Kai" prefix and this repo's
root/OS context, best guess: a **KaiOS jailbreak / temp-root / custom-firmware toolkit** in the
BananaHackers lineage (Wallace Toolbox / OmniSD / cache-injection temp-root for MediaTek KaiOS phones).
No feature set asserted.
**Superior Masamune variant (if the guess holds):** feeds the C-layer root story for a KaiOS guest —
same role TempRoot (C3) plays for Android. **Confidence: ⚠ needs user confirmation** (is Kai9000 a
KaiOS root tool, a device, or something else?).

### C3. TempRoot ✅
**What it is:** a **temporary-root mechanism** — root that lasts until reboot, obtained without a
permanent system modification. Two established shapes: (a) exploit-based temp-root shells (e.g. the
`cve-2019-2215`-style kernel R/W → temporary root), and (b) boot-time temp root (`fastboot boot` a
GKI/KernelSU kernel to get a temporary rooted boot, as KernelSU documents).
**Key features:** ephemeral privilege escalation; no persistent partition changes; used to bootstrap a
manager or a further install.
**Superior Masamune variant:** the **bootstrap path into the ryznix guest's root**. MASAMUNE.md build
order step 2 is "KSU in the guest kernel"; TempRoot is how you *reach* that state on a device whose host
kernel isn't already KSU-carrying — a temporary rooted boot to stage the guest, which then holds
persistent KernelSU root. Native variant: a controlled, auditable temp-root bootstrap owned by Yojimbo's
host-side channel, never an exploit shipped to users. **Confidence: ✅ (concept)** / exact donor repo
⚠.

### C4. VfoneGaga ⚠ (strong best-guess: VPhoneGaGa)
**What it is:** almost certainly a misspelling of **VPhoneGaGa** — an **Android-on-Android
virtualization app** (a VM that runs a second Android userspace on an unmodified host, in the
VMOS/VirtualXposed family; community forks add Magisk-in-the-VM, "400+ activities", multi-instance).
**Key features:** guest Android instance without host root; app-level virtualization; community Magisk/
root-inside-the-VM setups; multiple concurrent virtual machines.
**Superior Masamune variant:** **directly relevant to ryznix.** This is a working reference for the
exact thing MASAMUNE.md build-order step 1 needs — "a runnable guest Android userspace in a
VM/emulator on-device." Reverse-engineer how VPhoneGaGa hosts a guest Android rootlessly, then build the
*superior* ryznix guest (Evolution X base + KSU guest kernel + nested-zygote LSPosed) rather than
adopting VPhoneGaGa's closed VM. Marked ⚠ **only** because of the name spelling — the match and its
relevance are strong. **Confidence: ⚠ needs user confirmation (spelling); design intent is clear.**

**Group-C verdict:** long-horizon, gated on the guest bring-up that MASAMUNE.md itself says is unbuilt.
VfoneGaga/VPhoneGaGa is the single most useful reference here (it's a concrete guest-hosting prior).
KaiOS and Kai9000 are a secondary web-OS experiment line. TempRoot is the root-bootstrap primitive that
connects host → guest root. **Do not start C-layer absorption before Group A is real and the guest VM
launcher (step 1) exists.**

---

## Group D — Uncertain / misc

### D1. Sentinel-APK ⚠
**What it is:** *not confidently identified* — "Sentinel" collides with several unrelated Android
projects. The `-APK` suffix makes the best guess **an APK static-analysis / security scanner** (in the
`AppSentinel` mold: scans APKs for hard-coded secrets, API keys, misconfigurations), with a distant
alternative being infinum's `android-sentinel` (a dev/QA diagnostics entry-point UI). No feature set
asserted beyond the name-family.
**Superior Masamune variant (if APK-scanner is the intent):** a harness tool that **inspects APKs**
(the guest ROM's apps, the Xposed modules Genji patches, sideloaded payloads) for secrets/permissions/
misconfig before install — a security gate on the C-layer app pipeline. **Confidence: ⚠ needs user
confirmation** (APK scanner vs dev-diagnostics UI vs internal tool?).

---

## Prioritization + honest boundary

**Reality check (from MASAMUNE.md):** what exists today is the AI-assistant base (llama/mnn/terminal/
web-chat). The ryznix guest — VM, Evolution-X ROM integration, KSU guest kernel, nested-zygote LSPosed —
**is not built.** Absorption must respect that ordering.

| Tier | Theme | Donors | Why now / why later |
|---|---|---|---|
| **1 — near-term, tractable** | A: terminal/dev-env | Termux, Xed Editor, Total Commander | Build on existing `:terminal`; no root, no guest. Start here. |
| **2 — tractable next** | B: remote-control / MCP plane | android-remote-control-mcp, harness-remote, OpenOmniBot, Agora, OpenDroid, (STF agent) | Converge on ONE native MCP/accessibility control surface + agent loop. On-device, no guest OS. |
| **3 — long-horizon** | C: OS/root layer | VfoneGaga/VPhoneGaGa, TempRoot, KaiOS | Gated on guest bring-up (MASAMUNE.md step 1). VPhoneGaGa is the key prior. |
| **Blocked on user confirmation** | across themes | **SOMCP, SuperAI, LokiDroid, Kai9000, Sentinel-APK** (+ VfoneGaga spelling) | Cannot design responsibly until identified. |

**Honest boundary:** Tier 1 and most of Tier 2 are real work Masamune can start against its current
codebase. Tier 3 (ryznix second OS + KaiOS emulation) is **design-only until the guest VM launcher and
KSU guest kernel exist** — this doc records intent, not readiness. And five donors below are unresolved;
building against a guessed identity risks fabricating a feature set, which this methodology forbids.

---

## ⚠ Donors that need user confirmation (please clarify)

1. **SOMCP** — no repo matched. Guess: an Android MCP-server variant. Which one / on-device vs ADB-side?
2. **SuperAI** — name too generic to pin. Guess: an on-device LLM agent app. Which SuperAI (repo/vendor)?
3. **LokiDroid** — public matches say Android RAT. Is that the intended donor, and is the intent its
   *authorized* capability surface (not stealth/covert use)?
4. **Kai9000** — no direct match. Guess: a KaiOS jailbreak/temp-root/custom-firmware toolkit
   (BananaHackers lineage). Confirm?
5. **Sentinel-APK** — collides with several projects. Guess: an APK static-analysis/security scanner
   (AppSentinel-style). Confirm which Sentinel?
6. **VfoneGaga** — strong guess it's **VPhoneGaGa** (Android-on-Android VM). Confirm the spelling so the
   ryznix guest-hosting reference is nailed down.

All other donors (Termux, Xed Editor, Total Commander, android-remote-control-mcp, harness-remote,
OpenOmniBot, Agora, OpenDroid, KaiOS STF agent, KaiOS, TempRoot) are identified with confidence ✅.
