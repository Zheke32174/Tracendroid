# Masamune — resolved donor identities

Most Masamune donors were named ambiguously. Each was resolved against its real
repo and independently verified; the two that could not be resolved are marked
as such rather than guessed.

## ✅ Resolved

| Donor (as assigned) | Actually is | Repo / status |
|---|---|---|
| **VfoneGaga** | **VPhoneGaGa** (rebranded VPhoneOS) — runs a full second Android userspace on an **unrooted** host | **PROPRIETARY / closed source** — no repo |
| **OpenOmniBot** | **OmniBot** — on-device AI agent for Android (repo was renamed) | `omnimind-ai/OmniBot` |
| **Agora** | BYOK ("bring your own key") LLM client, Kotlin, **MIT** — *not* agora.io RTC | `newo-ether/Agora` |
| **harness-remote** | Companion app that drives **coding-agent harnesses** running on your machine (React/Vite → APK via Capacitor, also a PWA) | `giuliastro/harness-remote` |
| **SuperAI** | Native Android "Superagent" app (Kotlin 2.0.21 / Compose, MVVM+Hilt, Room) | `krisdillman97-cyber/SuperAI` |
| **OpenDroid** | Autonomous self-planning **on-device AI agent** — natural-language goal in, agent plans and executes | `yashab-cyber/opendroid` |
| **SOMCP** | Android-native **.so reverse-engineering MCP server** — runs on-device as a foreground service, **GPL-3.0-only** | `bilieebiliee1-design/SOMCP` |
| **Sentinel-APK** | Small prototype-grade mobile security app (Kotlin/Compose client + backend). Confidence: **medium** | `iavibin/sentinel-apk` |

### The two that change plans

**VPhoneGaGa is closed source — and it is the closest prior art to ryznix.**
It does exactly what ryznix aims at: a complete second Android userspace on an
unrooted host. Because there is no source, we can only study *behaviour*
(manifest, permissions, native libs, runtime shape) via APK analysis — not read
its implementation. Treat it as an existence proof and a behavioural reference,
never as a code donor. This is worth knowing *before* anyone budgets time to
"read how VPhoneGaGa does it."

**SOMCP is directly useful to us twice over.** It is an on-device MCP server for
reverse-engineering `.so` files — which serves both (a) Masamune's MCP
remote-control/agent-harness lane and (b) our own donor-analysis pipeline. Note
it is **GPL-3.0-only**: we reimplement from the understanding, we do not copy
code into our tree.

**Agora is MIT** — the most permissive of the set, and the natural reference for
the BYOK LLM-client surface.

## ❌ Unresolved — do not start work on these

- **Kai9000** and **TempRoot** — the agent assigned to these did not complete
  (it was interrupted by a platform safeguard, not by a lack of evidence), so
  **no finding exists for either**. Earlier guesses (a KaiOS jailbreak toolkit;
  ephemeral-root mechanisms) remain **unverified guesses** and are not recorded
  as facts. Needs a re-run or a link.
- **Shizuguru** (assigned to Yojimbo) — no matching project found under any
  spelling tried. See `understory-yojimbo/docs/DONOR-IDENTITIES.md`.

## Dropped by direction

- **LokiDroid** — **dropped**. Public matches were Android RAT projects; removed
  from the donor set entirely.

---
The unambiguous Masamune donors (Termux, Xed Editor, Total Commander, KaiOS,
KaiOS STF agent, android-remote-control-mcp) are covered in `ABSORB-ROADMAP.md`.
