# Reverse-engineering note — Total Commander for Android 3.62d

`com.ghisler.android.TotalCommander`, versionCode 2490, targetSdk 36 (current,
actively maintained), native code for 6 ABIs. Closed source, so this is static
analysis of the shipped APK: `aapt2 dump` for the manifest, `strings` over the
dex for the class/interface surface. Every symbol quoted below was recovered
from the binary.

**Why it matters:** this is the reference design for Masamune's file explorer.
Its architecture is almost exactly the shape we want, and it independently
arrives at the same privilege model we already built in Yojimbo — which is good
evidence the model is right.

## 1. Out-of-process plugin system

Plugins are **separate APKs**, not in-process modules. Discovery is by intent
action, and access is gated by a custom permission the host declares:

```
com.ghisler.tcplugin            ← the plugin intent action
com.ghisler.plugin
com.ghisler.tcplugins.restricted ← custom permission guarding plugin access
```

Shipped plugin families recovered from the dex:

```
com.ghisler.tcplugins.FTP          com.ghisler.tcplugins.SFTP
com.ghisler.tcplugins.WebDAV       com.ghisler.tcplugins.LAN
com.ghisler.tcplugins.drive        com.ghisler.tcplugins.WindowsLive
com.ghisler.tcplugins.wifitransfer com.ghisler.tcplugins.totaldrip
```

Host-side plugin classes:

```
com/ghisler/android/TotalCommander/PluginObject
com/ghisler/android/TotalCommander/FileSystemPlugin
com/ghisler/android/TotalCommander/InstalledAppsPlugin
com/ghisler/android/TotalCommander/RemoteAppPlugin
com/ghisler/android/TotalCommander/ShizukuPlugin
com/ghisler/android/TotalCommander/TcContentProviderPlugins
```

The design consequence worth stealing: **a filesystem is a plugin**. Local
storage, FTP, SFTP, WebDAV, SMB, cloud drives and *installed apps* are all the
same abstraction (`FileSystemPlugin`), so the browser UI is written once. New
backends ship independently of the host, and a broken plugin cannot take the
host down — it is a different process.

## 2. A three-tier privilege ladder — the same one we built

TC does not pick one privilege mechanism; it ladders them, exactly like
Yojimbo's Elevation broker:

- **Shizuku** — and notably it speaks the **real binder protocol**, not a
  shell-out. Recovered interfaces:
  ```
  moe.shizuku.server.IShizukuService     moe.shizuku.server.IRemoteProcess
  moe.shizuku.server.IShizukuApplication moe.shizuku.server.IShizukuServiceConnection
  moe.shizuku.api.action.BINDER_RECEIVED moe.shizuku.privileged.api.intent.extra.BINDER
  rikka.shizuku.*
  ```
  Manifest declares `moe.shizuku.manager.permission.API_V23`.
- **Root** — `ACCESS_SUPERUSER`, `Superuser` strings.
- **Unprivileged** — SAF + `MANAGE_EXTERNAL_STORAGE`, biometric unlock.

That a mature, commercial file manager converged on Shizuku-or-root-or-SAF is
independent confirmation of Yojimbo's ladder. We can go one better: our ladder
routes through **one broker** with scoped, signature-pinned grants, rather than
each app integrating Shizuku itself.

## 3. Termux delegation — the piece we should take verbatim (as a contract)

TC does **not** embed a shell. It drives **Termux** through Termux's documented
`RunCommandService` contract:

```
com.termux.app.RunCommandService          ← target service
com.termux.RUN_COMMAND                    ← action
com.termux.permission.RUN_COMMAND         ← required permission
extras: RUN_COMMAND_PATH, RUN_COMMAND_ARGUMENTS, RUN_COMMAND_WORKDIR,
        RUN_COMMAND_BACKGROUND, RUN_COMMAND_SESSION_ACTION
```

This is directly relevant to Masamune, which lists both **Termux** and **Total
Commander** as donors. It shows the clean way to hand shell work to Termux
instead of reimplementing a terminal — and it is a *contract*, so we can support
it without copying any code.

## 4. What Masamune's file explorer should build

Superior variant, mapped to what we have:

1. **Plugin-as-filesystem abstraction** — one `FileSystemPlugin`-equivalent
   interface; local, SFTP, WebDAV, SMB, cloud, *and* "installed apps" are
   implementations. We add two TC cannot have:
   - **`ContainerFileSystem`** — browse inside a ryznix/container guest as if it
     were a remote host (the local-node-treated-as-external idea).
   - **`PrivilegedFileSystem`** — read/write through the Yojimbo broker, so
     protected paths work without the app itself holding root.
2. **In-process plugins first, out-of-process later.** TC's separate-APK model
   buys independent shipping and crash isolation; we do not need that on day one,
   but the *interface* should be designed so a plugin can later move
   out-of-process without changing the UI.
3. **Termux `RUN_COMMAND` support** — implement the contract above, so Masamune
   can drive an installed Termux. Complements (does not replace) our own
   terminal work.
4. **Privilege via Yojimbo, not direct Shizuku.** TC integrates Shizuku
   per-app; Masamune should ask the broker, which already fronts Shizuku,
   Dhizuku and (soon) Stellar dialects.

## Honest boundary

Closed source and static analysis only. The recovered symbols prove these
interfaces and integrations are **present**; the exact call sequences and the
plugin IPC payload format were not disassembled at method level. The plugin API
shape above is inferred from class names and intent constants, not from a
decompiled interface definition — anyone implementing against a real TC plugin
would need to confirm the wire format.

Licence: proprietary. Nothing may be copied. This note captures *architecture
and public contracts* (Termux's `RUN_COMMAND` and Shizuku's binder API are both
publicly documented), which is what we reimplement from.
