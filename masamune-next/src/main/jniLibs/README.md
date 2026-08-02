# Masamune's shell capsule — bundled payloads

These are **executables**, not shared libraries. They live under `jniLibs/<abi>/`
and are named `lib*.so` because that is the one directory whose contents the
Android platform extracts on install **and marks executable**. Shipping a binary
as `assets/` and copying it out cannot make it `+x` on modern Android; this can.

## Why they are here at all

Masamune's Shell used to send every command to an **installed Termux** over
`com.termux.RUN_COMMAND`. That failed this project's own rule —

> If it has to ask the thing it replaces, it has not replaced it.

— so the shell is now Masamune's own: its own userland, staged into its own
prefix, with no second app required.

| File | What it is | Licence |
| --- | --- | --- |
| `arm64-v8a/libmasamunebusybox.so` | busybox 1.36.1, **static**, 66 applets | GPL-2.0 |
| `arm64-v8a/libmasamuneproot.so` | proot 5.4.0, PIE, talloc 2.4.2 linked in | GPL-2.0 |

Both are built **from unmodified upstream source** with the NDK (API 26,
`aarch64-linux-android`), plus the small patches recorded below. Under GPL-2.0
the corresponding source is the upstream release plus those patches; keep this
file with the binaries so that offer stays accurate.

## Verified, and what "verified" means for each

**busybox — executed.** Run under `qemu-aarch64` *using the shipped filename*:
`sh -c` works, `uname -m` → `aarch64`, awk arithmetic, sed, grep, tar+gzip
round-trip, wget over HTTP. 66 applets.

**proot — statically verified only.** Correct aarch64 PIE, `NEEDED` is exactly
`libc.so`/`libdl.so` (both Android-provided), the embedded loader ELF is present
at the aarch64 `LOADER_ADDRESS`, and the cross-build feature probes
(`HAVE_PROCESS_VM`, `HAVE_SECCOMP_FILTER`) passed. It is **not** runtime-tested:
proot's mechanism is `ptrace`, which `qemu-user` does not emulate, so it needs a
real device or emulator. Stated plainly rather than implied — an untested binary
is not a working one.

## Build blockers worth keeping (they all cost real time)

The four in `understory-firewall/tools/donor-assets/fetch.sh :: fetch_busybox_android`
still apply (start from `allnoconfig`; disable SHADOWPASSWDS/UTMP/WTMP/TC/MOUNT/
UMOUNT/DF; pass `AR=llvm-ar …` because NDK r27 removed the binutils wrappers;
patch `libbb/platform.c` for Bionic's `strchrnul`). Two more, both fatal here and
**not** covered by that recipe:

1. **argv[0] dispatch.** busybox only dispatches an applet when `argv[0]` is
   *prefixed* with `busybox` (`run_applet_and_exit`). Shipped as a `lib*.so`
   name, every invocation dies `applet not found` (exit 127). Fixed by enabling
   `CONFIG_BUSYBOX` and widening the test in `libbb/appletlib.c` to a substring
   match — so **the filename must contain `busybox`**, which is why this one is
   `libmasamunebusybox.so` and not, say, `libmasamuneshell.so`. Recursion is
   bounded because `busybox_main()` shifts argv before re-dispatching.
   *This also means the reference recipe's own `libbusybox.so` never dispatched.*

2. **awk SIGSEGV at any `-O` above `-O0`.** busybox declares
   `extern struct globals *const ptr_to_globals` and then stores through a cast;
   clang ≥ 9 legally drops a store to `const` storage, so awk reads a NULL
   `ptr_to_globals` and faults at `si_addr=0x4` before printing anything. ash,
   sed and grep happen to survive; awk does not. busybox documents the escape
   hatch itself (`include/libbb.h`): build with `-DBB_GLOBAL_CONST=`.

proot needed three of its own: build **in-tree** (the out-of-tree `$(SRC)$<`
double-prefixes under modern GNU make), `git submodule update --init` for
`lib/uthash`, and a missing `#include "tracee/mem.h"` in `tracee/tracee.c` —
upstream calls `peek_word()` without declaring it, which clang 19 makes a hard
error rather than a warning.

## Known gap

**arm64-v8a only.** An APK carrying just this ABI gives no capsule on
armeabi-v7a or x86_64 devices. The build is the same recipe with a different
triple; it simply has not been run for the other ABIs yet.
