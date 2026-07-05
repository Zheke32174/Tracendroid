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
