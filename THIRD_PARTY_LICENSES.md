# Third-Party Licenses

This file records third-party source vendored into the Tracendroid tree and the
license each carries. Tracendroid itself is licensed under the **GNU Lesser General
Public License v3.0** (see `LICENSE`, "Operit AI 项目许可证").

---

## Embedded terminal: `terminal-emulator/` and `terminal-view/`

**What it is:** an in-process pseudo-terminal (PTY) terminal emulator + Android view.
It lets Tracendroid run a real shell inside its own process, with no external
companion app.

**Provenance (chain of custody):**

- Ultimately derived from the **Termux** project — `terminal-emulator` and
  `terminal-view` modules of https://github.com/termux/termux-app
- Vendored here via **Xed-Editor** — https://github.com/Xed-Editor/Xed-Editor
  (the source copied on 2026-07-05 came from a local checkout of Xed-Editor's
  `terminal-emulator/` and `terminal-view/` modules).

**License: GNU General Public License, version 3 (GPL-3.0).**

- Termux is licensed under GPLv3.
- Xed-Editor's repository root `LICENSE` is GPLv3 (Copyright (C) 2025 Rohit Kushvaha).
- The vendored Java sources under `com.termux.terminal` / `com.termux.view` and the
  native code (`terminal-emulator/src/main/jni/termux.c`) are therefore GPLv3.

**What was changed when vendoring (all in the two `build.gradle` files only; the
Java/C sources are unmodified):**

- `compileSdkVersion` set to 36 (was 37 for terminal-emulator) to match Tracendroid.
- `sourceCompatibility`/`targetCompatibility` set to Java 17 (was Java 21).
- Removed the `maven-publish` plugin and the `sourceJar` task.
- Dropped the pinned `ndkVersion` so the module inherits Tracendroid's NDK.
- Replaced Gradle version-catalog aliases (`libs.androidx.annotation`, `libs.junit`)
  with explicit Maven coordinates so the module does not depend on Tracendroid's
  version catalog containing those aliases.

**IMPORTANT license interaction (read this):**

GPLv3 (these modules) and LGPLv3 (Tracendroid / Operit) are compatible — LGPLv3 is
GPLv3 plus additional permissions. However, **linking GPLv3 code into an application
and distributing that application means the whole distributed binary must be conveyed
under the terms of the GPLv3.** In other words, once these terminal modules are built
into and shipped inside the Tracendroid APK, the resulting APK is effectively governed
by the GPLv3 (the stronger copyleft), not merely the LGPLv3.

If that stronger copyleft is not acceptable for a given distribution, the alternative
is to keep the terminal in a **separate GPLv3 application/process** (the old external
`com.ai.assistance.operit.terminal` companion-app model) rather than linking it in —
but that reintroduces exactly the external-app dependency this change was made to remove.
This is a deliberate, documented trade-off, not an oversight.

Full GPLv3 text: https://www.gnu.org/licenses/gpl-3.0.txt

---

## Embedded terminal SSH transport: `com.hierynomus:sshj` (Maven dependency)

**What it is:** a pure-JVM SSH2 client. It backs the dual-rigged embedded terminal's
**Termux** and **ryznix** profiles, which open an interactive PTY shell over SSH to
Termux's own sshd on `127.0.0.1:8022` (the only on-phone environment with a real
package manager, `pkg`/`apt`). This is a **Maven Central dependency, not vendored
source** — no prebuilt `ssh`/`dropbear` binary blob is bundled. Declared in
`gradle/libs.versions.toml` (`sshj = "0.38.0"`) and wired in `app/build.gradle.kts`.

**License: Apache License 2.0** (https://github.com/hierynomus/sshj).

**Transitive dependencies it pulls (all from Maven Central):**

- `org.slf4j:slf4j-api` — MIT. (Already used elsewhere in the app.)
- `org.bouncycastle:bcprov-jdk18on` / `bcpkix-jdk18on` — Bouncy Castle License (MIT-style).
  The app **forces version 1.78** of both (matching the existing `bcprov-jdk18on:1.78`)
  and **excludes sshj's transitive Bouncy Castle** so a single, consistent BC version
  wins; the project also globally excludes the legacy `bcprov-jdk15to18` variant.
- `net.i2p.crypto:eddsa` — CC0 1.0 (public domain dedication). Used for the app-private
  Ed25519 client identity (`SshKeyManager`).
- `com.hierynomus:asn-one` — Apache License 2.0.

Apache-2.0 and CC0 are permissive and compatible with the app's licensing; they impose
no additional copyleft on the distributed APK beyond the GPLv3 already noted above for
the vendored terminal modules.
