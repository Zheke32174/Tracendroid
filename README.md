<div align="center">
  <img src="app/src/main/res/playstore-icon.png" width="120" height="120" alt="Tracendroid logo">
  <h1>Tracendroid</h1>
  <p><b>A security-hardened, on-device AI-agent app for Android.</b></p>
  <img src="https://img.shields.io/github/last-commit/Zheke32174/Tracendroid" alt="Last commit">
  <img src="https://img.shields.io/badge/Platform-Android_8.0%2B_(arm64)-brightgreen.svg" alt="Platform">
  <img src="https://img.shields.io/badge/License-LGPL--3.0-blue.svg" alt="License">
  <a href="https://github.com/Zheke32174/Tracendroid/issues"><img src="https://img.shields.io/badge/🐛-Issues-orange.svg" alt="Issues"></a>
</div>

---

## Attribution

**Tracendroid is a modified derivative of [Operit AI](https://github.com/AAswordman/Operit)**
by **[AAswordman](https://github.com/AAswordman)**, used and redistributed under the
**GNU Lesser General Public License v3.0 (LGPL-3.0)**.

Tracendroid keeps Operit's on-device agent architecture and re-focuses it around a
hardened security posture. For the original project, its feature showcase, and the
end-user guide, please see upstream. The full attribution, list of changes, and
corresponding-source statement are in [`NOTICE`](NOTICE); the license texts are in
[`LICENSE`](LICENSE), [`COPYING.LESSER`](COPYING.LESSER) (LGPL-3.0) and
[`COPYING`](COPYING) (GPL-3.0).

## What it is

Tracendroid runs an AI agent **on the phone**: it talks to a model provider of your
choice and executes local tools (files, shell, HTTP, media/FFmpeg, on-device
inference via MNN / llama.cpp / ncnn, a JS plugin runtime via QuickJS, and a
WebView chat UI). It targets **arm64-v8a only** — the vendored native runtimes are
built exclusively for 64-bit ARM.

## What this fork changes

Tracendroid is a ~50-commit security-reconstruction of Operit. Highlights:

- **Removed** the Shizuku / libsu / root / Shower / AutoGLM privileged transports
  and the ROOT/DEBUGGER permission levels — a large attack-surface reduction.
- **Added** a JavaScript-plugin capability gate plus a signature + trust-on-first-use
  plugin trust pipeline.
- **Locked down** the WebView used for AI output with a link allowlist (closing a
  remote-code-execution path) and fixed a workspace `DocumentsProvider` path
  traversal.
- **Encrypted** credential storage (`EncryptedSharedPreferences`), a
  broadcast-receiver sender allowlist, and cleartext-traffic default-deny.
- **Migrated** the GitHub OAuth flow off an embedded client secret to PKCE (RFC 7636).
- **Rebuilt** the shell subsystem on `proot` instead of the Shizuku transport.

The full record lives in [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md),
[`docs/SECURITY.md`](docs/SECURITY.md) and the honest status log
[`docs/STATUS.md`](docs/STATUS.md).

## Building

> This is a large NDK/CMake project (MNN, llama.cpp, ncnn/sherpa, QuickJS,
> FFmpegKit). It needs a real build host with the Android SDK + NDK; it is **not**
> expected to build on a low-memory machine. Prefer CI (GitHub Actions) for release
> builds.

```bash
git clone --recurse-submodules https://github.com/Zheke32174/Tracendroid.git
cd Tracendroid
# Toolchain: JDK 17, Android SDK (compileSdk 36), NDK 29.0.14206865, CMake 3.22.1
./gradlew :app:assembleDebug
```

Two binary surfaces are gitignored and must be materialized before a full build:

- **FFmpegKit** — build the LGPL AAR (no `--enable-gpl`, no `--enable-nonfree`) via
  `tools/ffmpeg/build_ffmpeg_kit_wsl.sh` and place it in `app/libs/`.
- **web-chat** — build the WebView UI so its assets are packaged:
  `npm --prefix web-chat ci && npm run build:webchat`.

### Release signing

`assembleRelease` is signed only when `local.properties` provides
`RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and
`RELEASE_KEY_PASSWORD` (see [`local.properties.example`](local.properties.example)).
No keystore is committed. In CI, inject these from secrets.

## License

Licensed under **LGPL-3.0-or-later**. See [`LICENSE`](LICENSE), [`NOTICE`](NOTICE),
and [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md). The bundled FFmpeg is an
LGPL build (no GPL codecs), so the combined work remains LGPL-distributable.
