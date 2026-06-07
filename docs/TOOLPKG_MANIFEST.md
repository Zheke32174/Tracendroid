# TOOLPKG_MANIFEST.md

> Plugin manifest format for `.toolpkg`, MCP server, and Skill bundles distributed for
> Operit. Companion to [`AUDIT_PLAN.md § 1.1`](./AUDIT_PLAN.md) (trust anchor decision)
> and [`THREAT_MODEL.md § 4.3`](./THREAT_MODEL.md) (plugin marketplaces). 中文镜像见
> [`TOOLPKG_MANIFEST.zh.md`](./TOOLPKG_MANIFEST.zh.md).

## What ships with a plugin

Every plugin package the device installs carries two files alongside its payload:

```
plugin-bundle/
├── manifest.json          # signed bytes
├── manifest.sig           # detached Ed25519 signature over manifest.json's canonical form
└── … plugin payload …    # script files, assets, whatever the plugin needs
```

The device-side trust pipeline (`app/src/main/java/com/ai/assistance/operit/core/plugintrust/`)
reads both files before any of the payload is loaded into the runtime. No manifest, no
verification, no install. The check is independent of the per-call capability gate
(`THREAT_MODEL.md § 4.2`) — signature verification answers "is this update the same
publisher I trusted?"; the capability gate answers "what is this plugin allowed to do?".

## `manifest.json` schema

```json
{
  "pluginId":            "io.example.weather",
  "version":             "1.2.0",
  "publisherName":       "Example Weather Co.",
  "publisherKeyPem":     "-----BEGIN PUBLIC KEY-----\nMCowBQYDK2VwAyEA…\n-----END PUBLIC KEY-----\n",
  "declaredCapabilities": ["NETWORK", "FILE_READ", "METADATA"]
}
```

Field reference:

| Field | Type | Required | Notes |
|---|---|---|---|
| `pluginId` | string | yes | Stable identifier. The TOFU store keys on this. A reverse-DNS pattern is encouraged but not enforced. Cannot change between versions; if you change it the device treats you as a different plugin. |
| `version` | string | yes | Free-form semantic version. The platform does not compare or order versions. |
| `publisherName` | string | yes | Display text for the install prompt. Not unique. Not the trust anchor — the public-key fingerprint is. |
| `publisherKeyPem` | string | yes | PEM-wrapped X.509 SubjectPublicKeyInfo for the Ed25519 public key. The detached `manifest.sig` is verified against this key. |
| `declaredCapabilities` | string[] | yes | Capability classes the plugin intends to use, drawn from [`JsCapabilityClass`](../app/src/main/java/com/ai/assistance/operit/core/tools/javascript/JsCapabilityClassifier.kt). Informative — declaration is not a grant. The gate is still authoritative at call time. |

### Allowed capability class names

These are the only valid `declaredCapabilities` entries. Anything else fails parse:

`METADATA`, `FILE_READ`, `FILE_WRITE`, `SHELL`, `NETWORK`, `SYSTEM_READ`, `SYSTEM_WRITE`,
`UI_AUTOMATION`, `CHAT_READ`, `CHAT_WRITE`.

`UNCLASSIFIED` is **not** declarable — it's a runtime-only fallback for unknown tool
names; a manifest that declares it is rejected outright.

## Canonical bytes for signing

The signature covers the manifest's **canonical bytes**, not the raw file. Canonical
form is what `PluginManifest.canonicalBytes()` produces in the app code:

- A JSON object whose keys are emitted in alphabetical order:
  `declaredCapabilities`, `pluginId`, `publisherKeyPem`, `publisherName`, `version`.
- `declaredCapabilities` array is sorted alphabetically.
- No extra whitespace between tokens (compact `JSONObject.toString()` output).
- UTF-8 encoded.

Two builds of the same logical manifest produce byte-identical canonical bytes, so the
signature is reproducible.

### Signing in CI

Reference openssl flow:

```bash
# Compute the canonical JSON. The simplest path is to feed your manifest through
# the same canonicalizer the app uses; for one-off signs `jq -S -c` approximates it.
jq -S -c '{declaredCapabilities: (.declaredCapabilities | sort), pluginId, publisherKeyPem, publisherName, version}' manifest.json > manifest.canonical.json

# Sign with Ed25519 (openssl 3.0+).
openssl pkeyutl \
    -sign \
    -inkey publisher-private.pem \
    -rawin -in manifest.canonical.json \
    -out manifest.sig

# Verify locally before shipping.
openssl pkeyutl \
    -verify \
    -pubin -inkey publisher-public.pem \
    -rawin -in manifest.canonical.json \
    -sigfile manifest.sig
```

Bundle `manifest.json` (not the canonical form) + `manifest.sig` with your plugin. The
device computes canonical bytes itself.

## Trust on first use

On first install of a given `pluginId`, the device records:

```
(pluginId, SHA-256(publisherKeyPem.encoded), publisherName, recordedAtMillis)
```

The user sees a TOFU prompt with the publisher name and the key fingerprint and accepts
or rejects. On accept, the record is written.

On every subsequent install of the same `pluginId`:

- **Same publisher key fingerprint** → silent install (no prompt needed; capability
  grants from the previous install still apply).
- **Different publisher key fingerprint** → install refused. The user is told this looks
  like a different publisher claiming the same plugin id. The only way to allow it is to
  explicitly forget the existing TOFU record (which also forgets every capability grant
  on that pluginId) — a deliberate action via the Plugin trust settings screen.
- **Bad signature** → install refused unconditionally.
- **Malformed manifest** → install refused unconditionally.

The user cannot "approve a mismatch" mid-flow; the flow only offers forget-and-re-TOFU
as an out-of-band action, never a one-click bypass.

## Capability declaration

`declaredCapabilities` is informative. It tells the install prompt what the plugin
intends to ask for — useful for the user to decide whether to install in the first
place. It is **not** a grant.

At runtime, every tool call the plugin makes is classified by
`JsCapabilityClassifier.classify()` based on the tool name; the gate
(`JsPluginGate.shouldAllow()`) then checks whether the user has granted that
(pluginId × capability) pair. The first call hits the per-call confirmation overlay; the
user grants or denies and the decision persists.

A plugin that declares more than it uses is harmless. A plugin that uses more than it
declares hits the gate the same as anything else — declaration is not a free pass.

## MCP servers

MCP servers don't carry a manifest in the same shape; they're packaged as `npm` /
`uvx` runnables managed by their respective package ecosystems. The trust shape is
parallel but built from different signals — see
[`AUDIT_PLAN.md § 1.2`](./AUDIT_PLAN.md) for the per-package allowlist + publisher
fingerprint pinning that mirrors what this manifest does for `.toolpkg` bundles.

## When the format changes

Any change to this manifest format that would break existing signatures requires a
version bump on the manifest schema itself (not on individual plugins). The Android side
will need to accept both the old and new shape for a transition window — at minimum two
shipped app versions — before the old shape is rejected. Per `AGENTS.md`, the project
does not maintain forever-compatible parsers, but it also does not silently break
already-installed plugins.
