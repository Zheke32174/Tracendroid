# What's New in Tracendroid (vs. upstream Operit)

Tracendroid is a hard fork of **Operit AI** by **AAswordman** (LGPL). Deep respect and
gratitude to the original project — Tracendroid stands on the ground Operit built.
青出于蓝 (tap the About logo 7×). Attribution: NOTICE, COPYING(.LESSER), THIRD_PARTY_LICENSES.

## AI providers — bring your own access, pick your models
- 8 western vendors added on top of upstream: xAI (Grok), Groq, Perplexity, Together,
  Fireworks, DeepInfra, Cohere, Azure OpenAI — all selectable, correct auth per vendor
  (Azure uses `api-key`; the rest Bearer), correct endpoints + model lists.
- Pluggable auth: an `AuthStrategy` layer + provider-agnostic OAuth PKCE client +
  encrypted `CredentialVault`, with an OAuth-mode toggle in settings.
  (Consumer-subscription token recycling is a documented future seam.)

## Terminal — it actually works now
- New local-exec tier runs shell commands in the app sandbox on any device (no companion
  app / rootfs required) — the single biggest "it finally works" fix over upstream.
- Groundwork for a full Linux userland: a transport seam with proot + an on-device
  **ryznix** SSH backend.

## Interactive avatar ("wifeu mode")
- Multilingual emotion detection (zh/en/emoji/kaomoji + sentiment) with `<mood weight>`.
- Conversation-aware **mood** that carries across turns and decays.
- Expressive drive: lip-sync while speaking, gaze that follows touch + idle wander,
  emotion **intensity**, and smooth emotion **crossfades** (no more hard cuts).
- Touch/tap reactions, livelier idle, and user-importable avatar models with clear errors.

## AI operative & phone-pilot
- UI automation rebuilt on the **safe AccessibilityService** transport (the root/Shizuku
  stack was removed for security). A real multi-step **observe→decide→act** agent loop.
- New operative tools: `dump_ui_tree`, `find_ui_element`, `fill_form`, `wait_for_element`,
  `schedule_task`, `read_app_logs` (self-diagnosis) — plus loop resilience
  (wait-for-stable-screen, retry/backoff).

## Security & quality (inherited + extended from the fork's hardening)
- Encrypted credential vault, JS plugin capability gate, WebView lockdown, PKCE OAuth.
- Full i18n parity across zh/en/es/id/ms/pt-BR for new strings.
- Integration-audited: found and fixed real compile-breakers and dead-tool wiring before release.

## Shipping
- Signed-release CI (bundle+assemble, NDK-pinned) with web-chat build + package sync.
  See docs/SHIP-CHECKLIST.md for the exact path to a signed APK.
