# `app/src/main/assets/rootfs/`

This directory holds the trust anchors the on-device rootfs bootstrap reads.

## `operit-rootfs-pubkey.pem`

The Ed25519 public key that matches the signing key used in CI (the
`ROOTFS_SIGNING_KEY` repository secret, derived in `.github/workflows/rootfs.yml`).

**Replacement / rotation flow** (per `docs/SHELL_REBUILD.md`):

1. Generate a new keypair.
2. Set `ROOTFS_SIGNING_KEY` repository secret to the new private key.
3. Overwrite this PEM with the corresponding public key.
4. Build and ship a new app version.

On the device, `ShellRootfsKeyProvisioner` copies this asset to
`context.filesDir/rootfs-pubkey.pem` on every launch when the on-disk copy
differs. App update = key rotation; there is no runtime key-pinning flow.

## Current state

`operit-rootfs-pubkey.pem` is a **placeholder** until a real keypair is
provisioned in CI. Until then, signature verification fails at bootstrap with
`PublicKeyMissing` (the PEM is intentionally non-parseable) — surfaces a
visible error rather than silently accepting an unsigned artifact.
