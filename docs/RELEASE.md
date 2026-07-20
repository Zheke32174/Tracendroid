# Cutting a Tracendroid signed release

> Tracendroid is an LGPL-3.0 derivative of Operit AI
> (https://github.com/AAswordman/Operit). Keep the upstream attribution in
> `NOTICE` / `LICENSE` when you ship a build.

This document describes the signed-release pipeline in
`.github/workflows/release.yml`: how to create a release keystore, where to
store it, which GitHub secrets to set, and how to cut a release.

## What the pipeline reads (ground truth)

`app/build.gradle.kts` creates the `release` signing config **only** when all
four of these properties are present in **`local.properties`** *and* the
keystore file on disk exists. It does not read env vars or `-P` project
properties for signing — it reads `local.properties`:

| Property               | Meaning                                  |
| ---------------------- | ---------------------------------------- |
| `RELEASE_STORE_FILE`     | Absolute path to the `.jks`/`.keystore`  |
| `RELEASE_STORE_PASSWORD` | Keystore (store) password                |
| `RELEASE_KEY_ALIAS`      | Key alias inside the keystore            |
| `RELEASE_KEY_PASSWORD`   | Password for that key                    |

If any are missing (or the file is absent) the build **still succeeds** but
emits an **UNSIGNED** `.apk`/`.aab` — installable only via `adb install -t` /
not acceptable to the Play Store. See the caveat at the bottom.

Relevant build coordinates (from `app/build.gradle.kts`, keep in sync):
`compileSdk 36`, `minSdk 26`, `targetSdk 34`, pinned `ndkVersion 29.0.14206865`,
CMake external build at `src/main/cpp/CMakeLists.txt`, ABI `arm64-v8a` only,
`versionCode 41`, `versionName 1.10.1+12`. The CI installs JDK 17 (Temurin) to
match `app-build.yml`.

## (a) Generate a release keystore

Do this **once**, offline, on a trusted machine. Keep the resulting keystore
for the life of the app — losing it means you can never ship an update that
Play/Android will accept as the same app.

```bash
keytool -genkeypair -v \
  -keystore tracendroid-release.jks \
  -alias tracendroid \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype JKS \
  -dname "CN=Tracendroid, O=Tracendroid contributors, C=US"
# You will be prompted for the store password (RELEASE_STORE_PASSWORD) and the
# key password (RELEASE_KEY_PASSWORD). They MAY be the same, but distinct is
# fine — the pipeline supplies both independently.
```

The alias you pass to `-alias` becomes `RELEASE_KEY_ALIAS` (here: `tracendroid`).

## (b) Store it in agent-vault — NEVER commit it

The keystore and its passwords are secrets. `local.properties`, `*.jks`, and
`*.keystore` are already gitignored — **do not** force-add them.

Store the material in agent-vault (handles, never plaintext in chat/repo):

```bash
# The keystore file itself (as base64, which is also the form CI consumes):
base64 -w0 tracendroid-release.jks > tracendroid-release.jks.b64
agent-vault write tracendroid-release-keystore-b64  --file tracendroid-release.jks.b64
agent-vault write tracendroid-release-store-password --stdin   # RELEASE_STORE_PASSWORD
agent-vault write tracendroid-release-key-alias      --value tracendroid
agent-vault write tracendroid-release-key-password   --stdin   # RELEASE_KEY_PASSWORD
# Then shred the local plaintext copies you no longer need on disk:
shred -u tracendroid-release.jks.b64
```

Keep the original `.jks` itself in cold, offline backup (e.g. the vault + an
offline USB copy) — see the knowledge-survival backup practice. Do **not** keep
it in the repo or any synced cloud folder.

## (c) GitHub secrets to set

Set these under **Settings → Secrets and variables → Actions** in the GitHub
repo. The names must match `.github/workflows/release.yml` exactly:

| Secret name                | Value                                                        |
| -------------------------- | ------------------------------------------------------------ |
| `RELEASE_KEYSTORE_BASE64`    | `base64 -w0` of the `.jks` (the `.b64` from step b)          |
| `RELEASE_STORE_PASSWORD`     | Keystore store password                                      |
| `RELEASE_KEY_ALIAS`          | Key alias (e.g. `tracendroid`)                               |
| `RELEASE_KEY_PASSWORD`       | Key password                                                 |
| `GH_OAUTH_CLIENT_ID`         | *(optional)* GitHub OAuth App client id → `BuildConfig.GITHUB_CLIENT_ID`. If unset, the in-app GitHub OAuth login is inert but the build still succeeds. |

Getting the base64 to the secret without it touching chat:

```bash
gh secret set RELEASE_KEYSTORE_BASE64  < tracendroid-release.jks.b64
gh secret set RELEASE_STORE_PASSWORD           # paste when prompted
gh secret set RELEASE_KEY_ALIAS  --body tracendroid
gh secret set RELEASE_KEY_PASSWORD             # paste when prompted
```

## (d) Cut a release

The workflow triggers on `workflow_dispatch` (build only, no Release object)
**and** on pushing a tag matching `v*`.

```bash
# From a clean, reviewed commit on the release branch:
git tag v1.10.1
git push origin v1.10.1
```

On the tag push the workflow builds `:app:bundleRelease` (the `.aab` for Play)
and `:app:assembleRelease` (the `.apk` for sideload), uploads both as workflow
artifacts, and creates a **draft** GitHub Release with both files attached.
The release is a draft on purpose — a human reviews the artifacts and clicks
**Publish**. Nothing auto-publishes, and the Release step never runs on
`workflow_dispatch` or on a branch push.

To do a dry run without creating a Release, use **Actions → release → Run
workflow** (`workflow_dispatch`): it builds + uploads artifacts only.

Outputs:
- AAB: `app/build/outputs/bundle/release/*.aab`
- APK: `app/build/outputs/apk/release/*.apk`

## (e) Caveat: unsigned builds

Without `RELEASE_KEYSTORE_BASE64` (and the three password/alias secrets), the
`release` signing config is not created and the pipeline produces an
**UNSIGNED** `.apk`/`.aab`. That is intentional so contributors can produce a
build without the signing secrets, but such an artifact is **not shippable** —
it cannot be uploaded to Play and installs only with `adb install -t`. Set the
secrets before cutting a real release.

## (f) Caveat: native NDK build is unproven in CI

This is the **first** CI path in the fork that compiles the native modules
(MNN, llama.cpp, ncnn/sherpa-ncnn, quickjs, ufbx, bullet3, saba) via NDK
`29.0.14206865` + CMake. The existing `app-build.yml` only runs
`:app:compileDebugKotlin` — it never links native code or assembles a release.
Additionally, `docs/BUILDING.md` documents manually-provisioned assets
(`app/libs`, `jniLibs`, `models`/`subpack` zips, the `web-chat` build, and
`sync_example_packages.py`) that the release workflow does **not** yet fetch.
**Expect the first real run to fail and need iteration** on NDK toolchain
availability, submodule build scripts, and those vendored blobs.
