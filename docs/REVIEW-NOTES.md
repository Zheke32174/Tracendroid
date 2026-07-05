# Tracendroid — Review Notes / Open Questions / Concerns

Running log of concerns, decisions, and follow-ups, kept for a joint review pass
**after** the app reaches a finished state (per operator instruction — logged, not blocking).

---

## ON-DEVICE FIRST RUN — 2026-07-05 (verified on hardware)

**Milestone: the signed release APK installs and launches on a real device.**
- Device: Samsung SM-S948U (Galaxy S24 Ultra class), Android 16. Installed via USB (`adb install`, 14 s).
- Package `com.ai.assistance.operit`, `versionName=1.10.1+12` (`versionCode=41`), 181 MB base.apk.
- Launches to the first-run **User Preferences** onboarding; `topResumedActivity=MainActivitySimpleAlias`.
  No crash, no blank screen, no FATAL in logcat. This is the first *runtime* confirmation (all prior
  verification was compile-only).
- Onboarding strings are **translated to English** (occupation chips: Business Owner / Sales /
  Customer Service / Freelancer / Retired / Other) — the i18n pass is visibly working.

**NOTE — this is the FFmpeg-stubbed proof build:** no bundled avatar model, FFmpeg disabled. Review the
UI / providers / terminal here; avatar + media land once the FFmpeg `.aar` (branch `ffmpeg-kit-ci`) and
avatar assets are folded in.

### New concerns from the live launch
- **[i18n / phone-home] Untranslated maintenance dialog on first run.** A modal "插件市场维护通知"
  (plugin-market maintenance notice, with an *invalid* date "6月31日") appears over onboarding. The string
  is **not in source** → it is **fetched from the upstream (Operit) backend**. Two problems: (a) untranslated
  Chinese shown to the user; (b) our fork still calls the original author's server for announcements /
  plugin-market data. DECISION NEEDED: point the announcement/plugin-market endpoint at our own backend (or
  disable the notice), and localize its renderer. This is the clearest "still tied to upstream infra" signal.
- **[assets] Promo/video banner** plays top-center over onboarding (looks like leftover upstream
  release-info content). Identify its source (asset / pet / Live2D / remote) and decide keep vs remove.

---

## Decisions the operator will make (deferred, NOT blocking build-out)
- [BUILD] Vendored blobs required for a *full* build: `app/src/main/jniLibs`, `assets/models`,
  `app/libs`, `assets/pets` — the Google-Drive files in `docs/BUILDING.md`. (Proof build stubs these.)
- [IDENTITY] `applicationId` still `com.ai.assistance.operit`. Changing to `com.zheke.tracendroid` needs an
  audit: ~40 hardcoded appId literals + 2 FileProvider authorities + the `operit://` OAuth redirect scheme
  (the GitHub OAuth app expects it). Deferred to its own careful brick. `rootProject.name` already = Tracendroid.

## Correctness caveats (resolve at on-device run — now partly in progress)
- Phone-pilot loop: model wired via `EnhancedAIService(FunctionType.UI_CONTROLLER)` — needs that FunctionType
  configured on-device; runtime gesture / LLM-JSON behavior still to be exercised.
- Provider default model IDs (grok-2-latest, sonar*, fireworks/deepinfra namespaced) may churn; app has a live
  `ModelListFetcher` so the API list is source of truth, but hardcoded seeds could be stale.

## Deferred feature depth (documented follow-ups)
- Avatar: TTS→lipsync + user-loadable-model gallery + gaze-follow. Lipsync/intensity need `AvatarController`
  interface changes across all 6 runtimes → own careful brick.
- Phone-pilot: form-autofill, scheduled/delayed exec, retry/backoff, logcat stream, OCR fallback.
- Subscription-OAuth (ChatGPT/Claude/Gemini consumer): documented CLI-token-recycle seam, not implemented
  (no official 3rd-party OAuth-to-API). `ModelOAuthConfigRegistry` intentionally empty (no fake endpoints).
- CI build-inputs: web-chat vite build + `sync_example_packages.py` not yet scripted into `release.yml`.

## Environment notes
- Broken plugin hook `validate_antipatterns.py` (unresolved `CLAUDE_PLUGIN_ROOT`) errors on every Write/Edit
  (non-blocking; all writes land). Worth fixing in the operator's hooks config.
- git worktree isolation fails on the Windows build box (MAX_PATH) → sequential Agent subagents in main clone.

## Machine-generated i18n (flag for native review before release)
- es / id / ms / pt-rBR translations are machine-generated (commit 99fe999) — native-speaker review pending.

## Build/compile status
- **Compiles on GitHub CI.** Fixed ~10 real compile blockers the fork never caught (it never compiled — CI
  died at wrapper validation). Entire codebase type-checks; native modules (MNN / Llama / Fbx) build from source.
- **FFmpeg-Kit** (retired upstream, no Maven artifact) builds from source in CI: 26/27 libraries compile;
  **gnutls** (HTTPS/TLS stream I/O only — not needed for the app's local transcode/probe) is disabled to let
  the `.aar` finish. Branch `ffmpeg-kit-ci`.
- Release signing (keystore, v2/v3) proven on branch `debug-apk-proof` → produced the installed signed APK.

## Verified clean (read-only audit, no defects found)
- Terminal/shell (ryznix + proot): all 7 executor methods covered; all terminal tools in the model catalog.
- OAuth/AuthStrategy: used by all 3 provider families; OAuth toggle degrades gracefully (no crash on empty registry).
- Avatar wiring: mood accumulator + interface default no-ops compile across all 6 controllers.
- Providers chat path: all 8 Western vendors wired end-to-end (Azure model-list fix included).
- All 5 new phone-pilot tools present in both `SystemToolPromptsInternal` catalog sections (were dead until added).
