# debian/

Build the Debian 12 (bookworm) rootfs that the proot environment
runs inside. See [`docs/SHELL_REBUILD.md`](../docs/SHELL_REBUILD.md)
for the architectural context.

中文说明见 [`README.zh.md`](./README.zh.md)。

## Build locally

Requires a Linux host with `debootstrap`, `zstd`, and root
(or `fakeroot`) privileges. arm64-native is recommended; on x86_64
you need `qemu-user-static` and a kernel with binfmt_misc.

```bash
sudo bash debian/build.sh
```

Outputs:

- `debian/out/tracendroid-rootfs-<version>-arm64.tar.zst`
- `debian/out/tracendroid-rootfs-<version>-arm64.tar.zst.sha256`

Override via env: `ROOTFS_VERSION`, `TARGET_ARCH`, `DEBIAN_SUITE`,
`DEBIAN_MIRROR`, `SOURCE_DATE_EPOCH`. Defaults are the v1 pin.

## Build in CI

Triggered via `workflow_dispatch` from
[`.github/workflows/rootfs.yml`](../.github/workflows/rootfs.yml).
Native arm64 runner (`ubuntu-24.04-arm`), no qemu involved. Artifact
uploads with 90-day retention.

The workflow also runs automatically on push to this branch when
`debian/**` or the workflow file itself changes — so iterating on
the build script doesn't need a manual trigger.

## Refresh flow

To cut a new rootfs version:

1. Bump `ROOTFS_VERSION` (default in `build.sh`) and the
   `DEBIAN_MIRROR` snapshot pin in lockstep.
2. Update `SOURCE_DATE_EPOCH` to match the snapshot timestamp.
3. PR the change. CI publishes the artifact.
4. Subsequent PR wires the new SHA-256 into the on-device
   verifier and lands the matching app version. (See
   `SHELL_REBUILD.md § Rootfs hosting and integrity`.)

## Out of scope for v1

- **Strict reproducibility** (hermetic, source-pinned).
  Today's build is deterministic-as-of (same inputs across two
  CI runs of the same source produce byte-identical output);
  bit-for-bit reproducibility independent of build host is a v2
  goal per `SHELL_REBUILD.md § Rootfs build pipeline`.
- **Signing with the project release key.** The artifact carries a
  SHA-256 but no signature. Signing infrastructure lands in a
  follow-up PR alongside the on-device verifier.
- **Delta updates.** v1 ships full rootfs replacements; delta
  updates are v2.
- **x86_64 ABI support.** v1 ships `arm64` only, matching the app's
  current ABI filter. x86_64 follows emulator-development demand.
