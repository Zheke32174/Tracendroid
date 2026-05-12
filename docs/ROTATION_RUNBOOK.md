# ROTATION_RUNBOOK.md

> How to rotate the rootfs signing key. Companion to
> [`THREAT_MODEL.md § 4.5`](./THREAT_MODEL.md) and
> [`SHELL_REBUILD.md`](./SHELL_REBUILD.md). Local-dev signing material lives in
> [`signing/README.md`](../signing/README.md). 中文镜像见
> [`ROTATION_RUNBOOK.zh.md`](./ROTATION_RUNBOOK.zh.md).

The rootfs trust chain has exactly one anchor: the Ed25519 public key bundled inside
the APK at `app/src/main/assets/rootfs/operit-rootfs-pubkey.pem`. The matching private
key signs the rootfs `.tar.zst` in CI. The device never reads, fetches, or accepts a
public key from anywhere but the APK itself.

Rotation is therefore an **app-update event**, not a runtime negotiation. There is no
"trust this new key now" path on the device.

## When to rotate

Rotate when:
- A previously-trusted private key is suspected compromised.
- A maintainer holding the private key leaves the project.
- Routine periodic rotation (no fixed cadence in v1).

Do **not** rotate to "test the flow." If you want to test, generate a personal dev
keypair per `signing/README.md` and side-load a rootfs you signed against it. The
production keypair is touched only when production rotation is intended.

## Procedure

The whole procedure ships as a single PR. Reviewing the PR is the security checkpoint.

### 1. Generate a new keypair

On a maintainer's local machine, in a directory that is **not** the repo:

```bash
cd ~/operit-keystore   # or wherever you keep maintainer key material
openssl genpkey -algorithm Ed25519 -out new-rootfs-signing.pem
openssl pkey -in new-rootfs-signing.pem -pubout -out new-rootfs-pubkey.pem

# Compute the fingerprint so you can confirm the same value lands in CI logs
# and in the device-side bootstrap surface.
openssl pkey -in new-rootfs-pubkey.pem -pubin -outform DER \
    | sha256sum | awk '{print $1}'
```

Save both files locally. The private key never enters the repo or a CI commit. The
public key will become a tracked file in the next step.

### 2. Replace the public key asset in the repo

```bash
cd /path/to/operit/repo
cp ~/operit-keystore/new-rootfs-pubkey.pem \
   app/src/main/assets/rootfs/operit-rootfs-pubkey.pem
```

Open a PR with **only** this change. The reviewer's job is to confirm:

- The fingerprint computed in step 1 matches the fingerprint computed from the file in
  the PR diff (`openssl pkey -in app/src/main/assets/rootfs/operit-rootfs-pubkey.pem
  -pubin -outform DER | sha256sum`).
- No private key snuck in (`git diff` shows only the public PEM under `assets/rootfs/`
  + this runbook + the ZH mirror if relevant).
- The PR description states the rotation reason (compromise / maintainer change /
  scheduled rotation).

### 3. Update the GitHub Actions secret

Once the PR is merged:

```
Repository → Settings → Secrets and variables → Actions → ROOTFS_SIGNING_KEY
→ Update secret
→ paste the contents of ~/operit-keystore/new-rootfs-signing.pem
```

The signing workflow (`.github/workflows/rootfs.yml`) reads `ROOTFS_SIGNING_KEY`,
derives the public half via `openssl pkey -pubout` inside the runner, and **only
trusts the just-derived public key** for that build's signing. There is no
externally-supplied "matched public key" trust pair — the runner re-derives every time
to remove that foot-gun.

### 4. Trigger a fresh rootfs build

```
Repository → Actions → rootfs-build → Run workflow
```

The workflow:
- Builds the rootfs from the pinned Debian snapshot per `debian/build.sh`.
- Materializes the new private key into the runner.
- Derives the public half and signs the `.tar.zst`.
- Re-verifies the signature inside the runner (`debian/sign.sh`'s sanity check) before
  uploading.
- Shreds the private key from the runner via `shred -u`.

### 5. Publish the artifact

The artifact + `.sig` + `.pubkey.pem` are uploaded to a GitHub Release. The
`ShellRootfsRelease.kt` constants `EXPECTED_VERSION` + `EXPECTED_SHA256` get bumped
in a follow-up PR pinning the new release.

### 6. Ship the app update

Build + release a new app version. Users update via Play Store / sideload as usual.

On first launch after the update:
- `ShellRootfsKeyProvisioner` notices the bundled public-key asset differs from the
  on-disk copy and overwrites the on-disk copy. (See
  `app/src/main/java/com/ai/assistance/operit/shell/ShellRootfsKeyProvisioner.kt`.)
- The next time the user runs the rootfs bootstrap, the verifier uses the new public
  key. An existing extracted rootfs signed against the old key continues to work — the
  signature was verified once at install time, not on every launch — but a re-install
  or version-bumped rootfs goes through the new key.

## What does *not* rotate

- The on-device TOFU records for plugin publishers (`PluginPublisherTofuStore`) are
  unrelated to this key. Plugin trust is per-plugin and rotates per-plugin, see
  `TOOLPKG_MANIFEST.md`.
- The IPC auth secret (`ShellIpcAuth`) is unrelated. It rotates on every shell session
  start via `ShellRootfsDispatcherInstaller.rotateForSessionStart()`.
- The user's chat / memory data, OAuth tokens stored in `CredentialVault`, and any
  other app-side data are unrelated.

## Compromise response

If the private key is confirmed compromised:

1. Revoke the GitHub Actions secret immediately. The next rootfs build then refuses
   to sign (the workflow's `if: env.ROOTFS_SIGNING_KEY != ''` branch goes silent).
2. Execute the rotation procedure above with the lowest possible delay between key
   generation and app-update release.
3. **Do not** publish a "revocation" mechanism that lets the device dynamically
   distrust the old key — that would be a runtime trust path, which the threat model
   forbids (`SECURITY.md`). The defense is: the new app version distrusts the old key
   automatically, and any rootfs install attempt that presents an old-key signature
   fails verification on a device running the new app.
4. Communicate to users: any user who installs a rootfs while running the old app
   version is still trusting the compromised key. The mitigation is encouraging the
   app update, not anything on-device.

## Audit

Every rotation PR is preserved in `git log -- app/src/main/assets/rootfs/operit-rootfs-pubkey.pem`.
That is the public audit trail. The reviewer signature on the PR is the human gate.
