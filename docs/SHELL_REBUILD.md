# SHELL_REBUILD.md

> Scope and decisions for the proot environment rebuild. The current `:terminal` submodule (`AAswordman/OperitTerminalCore`) and its missing-blob dependency are slated for removal; this document defines what replaces them. 中文镜像见 [`SHELL_REBUILD.zh.md`](./SHELL_REBUILD.zh.md)。

## Why

The current shell subsystem is broken in two ways at once:

1. **Hard dependency on a missing external blob.** `app/src/main/assets/subpack/.keep` literally contains `pls download from drive`. The chroot rootfs lives in `subpack.zip` on a Google Drive folder maintained by the upstream project. Without it, the bootstrap silently fails — which is what users experience as "shell won't set up."
2. **Trust dependence on an unreadable submodule.** The `:terminal` Gradle module points at `AAswordman/OperitTerminalCore`. We can't inspect it, can't audit it against `SECURITY.md`, and can't enforce the default-deny / halt-control / audit-log requirements through it.

Both are blockers for the long-haul reconstruction. The patch path ("Gradle task pulling blobs from a mirror") is rejected per the project's no-fallback rule and per the call to take the slow route.

## What this rebuild replaces

- The `:terminal` Gradle submodule.
- The `app/src/main/assets/subpack/` blob dependency.
- All call sites of `Terminal.kt::initialize()` that delegate into the submodule.
- The current bootstrap UX (which surfaces nothing on failure).

## What this rebuild keeps

- The user-facing chat experience (it never directly references the submodule).
- The terminal toolbox UI screens in `app/src/main/java/com/ai/assistance/operit/ui/features/toolbox/screens/`.
- The `shellexecutor` screen for one-shot ADB-style commands (distinct from the proot environment).

## Decisions

### Distro: Debian 12 (bookworm)

Resolves `AUDIT_PLAN.md § 1.9`.

| Considered | Verdict |
|---|---|
| Ubuntu 24 LTS | Rejected. Larger rootfs (~200 MB), more rough edges (snap, telemetry hooks), wider PPA temptation (which itself is a trust risk). |
| **Debian 12 (bookworm)** | **Adopted.** Leaner stable, same apt ecosystem, longest support track record, no telemetry. |
| Alpine | Rejected. musl-libc breaks Python wheels and many Node packages — incompatible with the codex / gemini-cli / claude-code targets. |

Rootfs size target: ~80–100 MB compressed, ~250–300 MB extracted. Subscription CLIs and Python / Node tooling install on demand inside the environment, not in the base image.

### Launcher: proot only

The launcher is proot. `chroot()` requires `CAP_SYS_CHROOT`, which is root-only. The project doesn't have root, isn't taking root, and per `THREAT_MODEL.md § 4.4` isn't taking Shizuku or Shower either. The chroot path is removed from v1 scope.

Trade-offs accepted:

- **Syscall translation overhead.** Measurable in microbenchmarks, not a blocker for the CLIs and dev tools we host. The subscription CLIs are network-bound; the local model runtimes are compute-bound and don't traverse the proot boundary heavily.
- **No kernel-enforced isolation.** The threat model already accounts for this: the proot environment is a *soft* boundary, not a sandbox. Confidentiality of subscription tokens is enforced by Android-side encryption (the environment lives in app-private storage) and by Linux file ACLs inside the rootfs, not by kernel isolation.
- **More Android-side tooling than a chroot-with-init would need:**
  - Foreground service architecture to keep the proot process alive while the app is backgrounded (see `AGENT_CORE.md § Background operation`).
  - The IPC bridge bears the soft-boundary weight (see § IPC protocol below).
  - Explicit lifecycle handling — proot doesn't auto-recover from OOM kills the way a chroot-with-init would. Session resumption is handled by the agent-core layer.

### Rootfs build pipeline

Built in CI from a `debian/build.sh` (or `Dockerfile.rootfs`) that runs `debootstrap` against bookworm's stable mirror, layers our base package set, strips locales / docs / manpages we don't ship, and packs the result into a `.tar.zst` (better compression than gzip, fast on-device decompression).

Output: one artifact per supported ABI. v1 ships `arm64-v8a` only, matching the app's current ABI filter. The build is reproducible enough to verify byte-for-byte across two CI runs — strict reproducibility (hermetic, source-pinned) is a v2 goal, not v1.

### Rootfs hosting and integrity

- **Hosting.** GitHub Release artifact on a sibling repo (likely `zheke32174/tracendroid` for v1; can move to a dedicated assets repo later). One release per rootfs version; releases are immutable.
- **Identity.** Each release artifact is signed with the project release key. The signature is verified on the device before extraction. Unverified signatures abort bootstrap with a user-visible error — no silent retry, no "extract anyway" path.
- **Pin.** The app embeds the expected SHA-256 of the rootfs version it was built against. Mismatch aborts bootstrap. To upgrade the rootfs, the app updates.
- **Privacy.** The download request carries no telemetry beyond what GitHub's CDN sees by default. No "phone home" before, during, or after.

### Bootstrap UX

First run flow:

1. User opens the terminal feature (or any feature that needs the environment).
2. App checks for an installed rootfs at the expected version. If present and signature-valid, proceed. Otherwise:
3. User sees a clear panel: what's about to happen, download size, where the file comes from, what runs inside.
4. User confirms. Download proceeds. Progress visible.
5. Signature + SHA-256 verified. Failure aborts with the actual error (no "something went wrong").
6. Extraction proceeds to `Android/data/<package>/files/rootfs/` (app-private storage; survives uninstall only if the user opts to back it up).
7. First-launch initialization runs — generates host config, sets up the Unix-socket endpoint, opens a shell.
8. Subsequent launches skip steps 2–6.

No step in this flow has a "skip if missing" path. Per `AGENTS.md` and `SECURITY.md`, failures are surfaced, not papered over.

### IPC protocol

Resolves `AUDIT_PLAN.md § 1.5`.

**Unix domain socket** in a shared bind-mount. The Android side and the proot side both see the socket file; Linux file ACLs scope access. No network surface, no loopback exposure — the `THREAT_MODEL.md § 5 ClawJacked` failure mode is not reachable on this surface.

Wire format: length-prefixed JSON messages, one logical request / response per envelope. Each envelope carries:

- A monotonic request id (for matching responses).
- An origin tag (`user` / `ai-agent` / `plugin:<id>`) so the proot side knows who's calling.
- A capability claim (which `AUDIT_PLAN § 1.3` class is being invoked).
- The actual command.

The proot side enforces capability classes (the same set declared in `AUDIT_PLAN § 1.3`) before executing. The Android side enforces them again before sending. Defense in depth.

Auth on every call: each connection is authenticated via a per-session secret minted at bootstrap, stored on the Android side in `EncryptedSharedPreferences`, presented as the first frame of every connection. The secret rotates on app upgrade and on user-initiated rotation. No exemption for any caller class.

### Subscription-CLI installation policy

The rebuild **does not bundle** codex, gemini-cli, claude-code, aider, cline, or continue.dev in the rootfs. Each:

- Installs on demand via its own published installer or via the environment's package manager.
- Handles its own OAuth flow inside the environment.
- Persists session state inside the environment, per `THREAT_MODEL.md § 4.5`.
- Updates on its own cadence (its update channel, not ours).

This keeps their security updates with them and keeps our build pipeline out of the business of vendoring third-party CLIs.

### Filesystem layout

Inside the rootfs:

```
/                       Debian root (read-only after bootstrap; replaced by rootfs upgrade)
/home/operator/         User home — chat-session-scoped, not user-personal
/var/lib/operit/        App-managed state (per-session config, audit-log mirror)
/var/lib/operit/ipc/    Unix socket lives here
/opt/cli/               Subscription CLIs install here when user opts in
/workspace              Bind-mount from Android/data/<package>/files/workspace/ — the only path that persists across sessions
```

That layout is the contract between the Android side and any code that runs inside the environment. Plugins, CLIs, and ad-hoc shell sessions all see the same paths in the same places.

### Migration from `:terminal` submodule

This rebuild lands across multiple PRs, in this order:

1. **Rootfs build pipeline + hosting.** Internal artifact only; no app-side code changes.
2. **Launcher and IPC code** as a parallel module (`:shell` or similar — name TBD). The old `:terminal` continues to exist.
3. **Switch one entry point at a time** from `Terminal.kt` to the new module. Each switch is a reviewable PR that updates `THREAT_MODEL.md § 4.5` status.
4. **Delete `:terminal` and the `subpack` asset path** once no call sites reference them. That commit removes the Google Drive dependency entirely.
5. **Remove the `terminal` git submodule** from `.gitmodules`.

Per `AGENTS.md`, this is iterative addition followed by deletion — no fallback patterns, no parallel-mode toggle that lingers.

## Open items deferred to implementation PRs

- The exact `debian/build.sh` (or `Dockerfile.rootfs`) content.
- The exact base package set (likely: `bash`, `coreutils`, `procps`, `ca-certificates`, `curl`, `git`, `openssh-client`, `python3`, `nodejs`, `npm` — final list lives in the build-script PR).
- Reproducible-build hardening (v2).
- Rootfs delta updates rather than full re-downloads (v2 — initial users pay the ~80 MB on first run and on version bumps).
- x86_64 ABI support, if/when emulator-driven development becomes a priority.

## Cross-references

- Resolves `AUDIT_PLAN.md § 1.5` (proot ↔ Android IPC protocol).
- Resolves `AUDIT_PLAN.md § 1.9` (Distro choice).
- Targets `THREAT_MODEL.md § 4.5` (Subscription OAuth in proot environment) for `design` → `closed` once implementation lands.
- Honors `SECURITY.md` red lines: no loopback exemption, no auto-pair, no fallback patterns, halt control hooks into all proot processes, no third-party privileged-binder dependency.
