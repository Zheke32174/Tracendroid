# Masamune (Tracendroid)

> **Rename:** this project is now **Masamune**. The repo slug stays `tracendroid` and the Gradle
> module names are unchanged for build stability; the user-facing app is **Masamune**
> (`app_name`). This file is the architecture of what Masamune is meant to become and how it fits
> the wider stack. It is deliberately written down because this piece is the **furthest from
> done** — most of what follows is target, not current state.

## What Masamune is

Two things in one:

1. **An AI harness.** The base is a fork of an on-device AI-assistant app (llama.cpp via the
   `:llama` module, an MNN backend via `:mnn`, a terminal via `:terminal`, a web chat UI in
   `web-chat/`). Masamune is the agent surface — the on-device brain the rest of the stack runs
   under and reports to.
2. **A second operating system — "ryznix".** The long-horizon goal is a full guest Android
   userspace running on the device: an emulator/VM hosting a ROM (candidate base: **Evolution
   X**), with its own kernel carrying **KernelSU**. This is the "second OS" the campaign refers
   to, and it is the host for the capabilities the host Android cannot provide rootlessly.

## Why the second OS matters to the rest of the stack

The guest is where the **global rootless-Xposed** path lands. As captured in Genji's
`docs/ROOTLESS-XPOSED.md`, per-app Xposed works on the host today (LSPatch/NPatch, no root), but
**global** hooking stalls on the host at the `setuid`/UID wall: a nested zygote rootless on the
host can only fork as the adb-shell UID, never as an app's own UID. Inside the ryznix guest there
is real root (KernelSU), so the wall is gone: a **nested LSPosed daemon with a nested second
zygote** — the guest's zygote, nested inside the host — can fork apps as their real UIDs with the
Xposed framework preloaded. Global Xposed there is the ordinary rooted install.

So Masamune/ryznix is not a side project; it is the missing host for the campaign's hardest goal.

## How the pieces connect

```
  Masamune (this repo)  ── AI harness + the ryznix guest (second OS, KSU root)
        ▲   │
        │   └── hosts ──►  nested LSPosed daemon + nested 2nd zygote  = GLOBAL rootless Xposed
        │
  Yojimbo  ── privilege brain on the HOST (absorbs Dhizuku + Shizuku) and the CHANNEL to the
              guest (kernelsu/channel/GuestChannel → the ryznix ksud)
        ▲
        │
  Genji   ── the patcher/loader: per-app Xposed on the host (NPatch), and the shared Xposed core
              (de.robv.android.xposed.* API → ArtCore SEAM C) that the guest zygote also preloads
        ▲
        │
  Godwall / EMI ── consumer apps that draw privilege from Yojimbo
```

## Current state (honest)

- **Present:** the AI-assistant base (llama/mnn/terminal/web-chat), building as its own app.
- **Not present:** the ryznix guest itself — the VM/emulator, the ROM integration (Evolution X
  base), the KSU-carrying guest kernel, and the nested-zygote LSPosed daemon. None of that is
  built. Yojimbo already scaffolds the *host side* of the channel to it (`StagedGuestChannel`,
  which honestly reports "no guest kernel present").

## Build order toward the second OS

1. **Guest bring-up** — a runnable ryznix Android userspace in a VM/emulator on-device (the
   Termux/qemu launcher + supervisor pattern Yojimbo's `RyzvmStatus` already assumes it does not
   own). ROM base decision: Evolution X.
2. **KSU in the guest kernel** — so the guest has real root; this is what
   `GuestChannel.status().kernelPresent` will finally be able to report true.
3. **Nested LSPosed daemon + second zygote** in the guest — global Xposed, driven from the host
   over Yojimbo's `GuestChannel`, loading the same Xposed core Genji produces.
4. **Masamune-as-brain** — the AI harness orchestrates the guest and the host privilege tiers as
   tools.

## Rename note

Gradle modules and the repo slug are intentionally left as `tracendroid` — renaming Android
package identifiers breaks signing and every reference for no functional gain. The rebrand is the
user-facing identity (`app_name` → "Masamune") plus this document.
