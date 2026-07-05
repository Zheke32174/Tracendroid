# Tracendroid — Ship Checklist

Single source of truth for taking `tracendroid/p0-ship-foundation` from "authored" to a
signed, shippable APK/AAB. Written after the fork's feature build-out; read top to bottom.

## 0. Status caveat (read first)
Every code brick on this branch was authored + structurally verified on a Windows box with
**no Android toolchain** — none of it is compiler-verified. The first off-box build WILL
surface issues (one was already found + fixed pre-emptively: `AdminUITools` → `a09d032`).
Treat the first green build as the real acceptance gate.

## 1. What was built (branch inventory)
- **Foundation:** LGPL compliance + Operit/AAswordman attribution (NOTICE, COPYING*), rebrand
  Operit→Tracendroid, README, Gradle identity, i18n English-fill.
- **Shell/terminal:** `ShellChannel` seam + MINA-sshd **ryznix** SSH transport; **local-exec
  tier** (`LocalShellSession`/`LocalTerminalCommandExecutor`) so `execute_*_terminal` tools
  finally run on any device (ToolGetter defaults to it). proot/ryznix are upgrades.
- **Providers:** `AuthStrategy` (bearer/header/query) + provider-agnostic `ModelOAuthClient`
  PKCE + OAuth-mode settings UI; **8 western vendors** (Grok/Groq/Perplexity/Together/
  Fireworks/DeepInfra/Cohere/Azure). Subscription-OAuth = documented CLI-token-recycle seam
  (no fabricated endpoints).
- **Avatar ("wifeu"):** multilingual emotion drive (zh/en/emoji/kaomoji + sentiment fallback),
  DragonBones tap/idle reactions, and a conversation-aware **mood state machine** (decay +
  carry-over). No controller-interface changes.
- **Phone-pilot:** UI automation routed to the live **AccessibilityService** path; `runUiSubAgent`
  is now a **bounded multi-step observe→decide→act loop** (model wired via `EnhancedAIService`,
  JSON action vocabulary, ≤12 steps, fully guarded). Accessibility-only (no root/Shizuku/shell).
- **Homage:** 青出于蓝 easter egg (tap About logo ×7) honoring AAswordman.
- **CI:** `.github/workflows/release.yml` (signed bundle+assemble, NDK 29.0.14206865, tag-gated
  draft release) + `docs/RELEASE.md`.

## 2. Provision the vendored blobs (REQUIRED — no build works without these)
All gitignored + currently empty (`.keep` only). Source: the Google-Drive links in
`docs/BUILDING.md`. Drop into:
- `app/src/main/jniLibs/` — prebuilt native `.so` (ncnn/sherpa/proot etc.; some also produced by NDK)
- `app/src/main/assets/models/` — on-device ML model weights (MNN/llama)
- `app/libs/` — prebuilt `.aar`/`.jar`
- `app/src/main/assets/pets/` — avatar/pet models (the "wifeu" assets)

## 3. Pre-build asset generation (scriptable — should move into CI, task #8)
- **web-chat:** `cd web-chat && pnpm i && pnpm build` → `web-chat/dist` → copy to
  `app/src/main/assets/web-chat/` (both gitignored/generated).
- **example packages:** `python sync_example_packages.py` (see its `--help`; syncs `.toolpkg`/`.js`).

## 4. Off-box build (Linux — no MAX_PATH limit)
Either **push** the branch and let `release.yml` run, or build on the codespace:
```
git submodule update --init --recursive        # 10 submodules incl. MNN/llama.cpp/ncnn
# NDK 29.0.14206865 + cmake 3.22.1 + platforms;android-36 (see release.yml)
./gradlew :app:bundleRelease :app:assembleRelease --stacktrace
```
First run compiling the native modules (MNN/llama/ncnn/sherpa/quickjs/ufbx/saba) is
unexercised in this fork's CI — expect iteration.

## 5. Signing (for a *signed* artifact)
`app/build.gradle.kts` reads these from `local.properties` only; CI supplies them from secrets:
`RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`
(+ `GITHUB_CLIENT_ID`). GitHub secrets: `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, optional `GH_OAUTH_CLIENT_ID`. Keystore generation
+ agent-vault storage: `docs/RELEASE.md`. Without them the build still yields an UNSIGNED apk.

## 6. Post-compile runtime validation (needs a real device)
- Terminal: run a command via the agent (local-exec tier) — the original never worked; this must.
- Phone-pilot: enable the Tracendroid accessibility service, run a `runUiSubAgent` task, watch the loop.
- Providers: add a western vendor key/OAuth, fetch model list, send a message.
- Avatar: confirm emotion/mood/touch reactions; tap the About logo ×7 for the homage.

## 7. Known follow-ups (tracked)
- CI build-input automation (§3) — task #8.
- Avatar: TTS→lipsync + user-loadable model gallery (need `AvatarController` interface work).
- Phone-pilot: form-autofill, notification-listener read tool, scheduled execution.
- Subscription-OAuth: implement the CLI-token-recycle credential source for ChatGPT/Claude/Gemini.
