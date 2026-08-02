# A ROM launchable from Masamune

> "the fully working rom launch able from masamune" — user, 2026-08-02

## The constraint that decides the whole design

Booting a real OS means running a **second kernel**. There are exactly three
ways to do that on an Android phone, and two of them are gated by something
Masamune cannot grant itself:

| Path | Speed | What gates it |
|---|---|---|
| **AVF** — Android Virtualization Framework | native | `android.permission.MANAGE_VIRTUAL_MACHINE` is **signature**-level. Android's own Linux Terminal gets it by being platform-signed. A sideloaded APK cannot hold it |
| **KVM** via `/dev/kvm` | native | Node is absent or root-owned on stock devices. Needs root, which the suite does not assume |
| **QEMU TCG** — pure userspace emulation | 5–20× slower | **Nothing.** No root, no special permission, no kernel feature |

So the honest position, stated before any code: **a ROM that boots at native
speed requires a privilege Masamune does not have on a stock, locked device.**
Anything claiming otherwise is claiming a permission it cannot hold.

What Masamune *can* do unconditionally is boot a real kernel under emulation.
That is genuinely a ROM booting — its own kernel, its own init, its own
userland, its own uid space — just slowly.

### Not to be confused with the Linux subsystem

The per-app Termux prefix at `/data/local/tmp/masamune` (see
`understory-firewall/docs/LINUX-SUBSYSTEM.md`) runs at **full native speed** and
needs no emulation, because it shares the host kernel. It is a Linux *userland*,
not a ROM. It is the right answer for almost everything — packages, daemons,
toolchains — and the wrong answer for exactly one thing: running an OS whose
kernel differs from Android's.

Do not let the ROM surface cannibalize the subsystem surface. A user who wants
`tor` wants the subsystem; a user who wants to boot postmarketOS wants this.

## The backend chain

Same shape as the privilege fallback chain already established for Yojimbo
(Shizuku → Dhizuku → Device Owner → ADB): probe in order, take the best
available, **name the one in use**, and report absent rather than pretend.

```
AVF  →  KVM  →  QEMU TCG  →  ABSENT
```

```kotlin
sealed interface RomBackend {
    val label: String
    val nativeSpeed: Boolean
    fun probe(): Availability   // AVAILABLE | UNAVAILABLE(reason) | UNKNOWN
}
```

Every `UNAVAILABLE` carries the **reason**, and the reason is shown, not
swallowed:

- AVF → *"This device does not expose the virtualization service, or Masamune is
  not platform-signed. Native-speed VMs need a signature permission a sideloaded
  app cannot hold."*
- KVM → *"/dev/kvm is not present or not readable by uid 2000."*
- TCG → *"This build ships no QEMU payload for this device's ABI, and none is
  installed in the Masamune prefix. Without an emulator there is no kernel to
  boot."*

The probe result decides what the UI offers. It never decides it silently.

## Where the QEMU binary lives (corrected)

The TCG reason above used to read *"No QEMU binary in the Masamune prefix.
Install it from the subsystem package manager"*, and the probe searched only
`/data/local/tmp/masamune/usr/bin`. Both were written for the Termux-prefix era
and became **wrong once the shell capsule landed**: `/data/local/tmp` is not
app-writable, and Android's W^X rule refuses to execute a binary out of
app-writable storage in any case. A QEMU cross-built and dropped into that
prefix would have been found by nothing and run by no one — the surface would
have gone on reporting ABSENT with a working payload sitting on the device.

QEMU is therefore a **shipped payload**, on exactly the same footing as the
capsule's busybox and proot: it belongs in `jniLibs/<abi>/` as
`libmasamuneqemu<arch>.so`, is extracted and marked executable by the installer,
and runs from `applicationInfo.nativeLibraryDir` at the app's own uid with no
privilege rung at all. The probe searches that directory first and accepts
either the `lib*.so` payload name or a plain `qemu-system-*`.

The legacy prefix is still searched, second. On a rooted or ADB-provisioned
device a binary genuinely can be placed and executed there, and a path that is
closed by default is not the same as one closed always.

## What TCG actually gets you

QEMU full-system, running at Masamune's own app uid — no root, no uid 2000, no
ADB rung:

- **aarch64 guest on an aarch64 host** — still emulated without KVM, but
  same-arch TCG is the fast case. A console-mode Alpine or postmarketOS boots in
  a workable time. A full desktop is painful but functional.
- **x86_64 guest** (Android-x86, BlissOS) on an ARM host — cross-architecture
  emulation on top of no hardware assist. This boots, and it is slow enough that
  it should be labelled as such in the UI rather than discovered by the user.

Display and input come from the same surface the subsystem already needs: a
local framebuffer/VNC path into a Compose view. Networking goes through QEMU's
user-mode stack (SLIRP), which needs no `tun` and therefore no privilege — the
same socket-space-versus-kernel-space split that governs Godwall.

**Storage is separate from the binary.** A ROM image is measured in gigabytes
and belongs nowhere near either the APK or `/data/local/tmp`; it goes to
app-scoped external storage, while the emulator itself rides in the APK's native
library directory. The split also means the image survives an app update that
replaces the payload.

## Where AVF becomes reachable

Worth stating because it is not hypothetical for this user, who runs custom
ROMs: if Masamune is **platform-signed** or installed as a system app on a ROM
the user controls, `MANAGE_VIRTUAL_MACHINE` becomes grantable and the AVF
backend lights up with no code change — the chain already probes for it. That is
the intended upgrade path, and it is why AVF is designed in now rather than
dismissed.

The same is true of KVM on a rooted device: the chain finds it.

## Honesty ledger

| Claim | Status |
|---|---|
| ELF-repatched prefix can host QEMU binaries at uid 2000 | Follows from the verified repatcher; **not device-tested**. Superseded as the primary plan — see "Where the QEMU binary lives" |
| `nativeLibraryDir` can host and execute a QEMU payload at the app's own uid | Same mechanism as the shipped busybox and proot, which **are** built; QEMU itself is **not built yet** |
| TCG boots a guest kernel with no root and no special permission | **Not yet built or tested here** |
| AVF is unreachable for a sideloaded APK | Strong reading of the permission's protection level; **verify on-device** before the UI asserts it as fact rather than as a probe result |
| Native-speed ROM without root or platform signing | **Not possible.** Do not build a control that implies it |

Until the TCG backend is built and a guest is observed booting, the ROM surface
ships with every backend reporting `UNAVAILABLE` and a sentence naming what is
missing. That is the rule from `DONOR-ASSETS.md` and it is not relaxed for being
the flagship feature — a *Launch ROM* button that opens nothing is worse than a
disabled one that explains itself.
