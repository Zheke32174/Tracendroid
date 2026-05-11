#!/usr/bin/env bash
# Sign a rootfs artifact with the project Ed25519 release key.
#
# Inputs (env):
#   ARTIFACT        — path to the .tar.zst produced by build.sh
#   SIGNING_KEY     — path to the Ed25519 private key in PEM PKCS8 format
#   PUBLIC_KEY      — path to the matching X.509 SubjectPublicKeyInfo PEM
#
# Outputs:
#   $ARTIFACT.sig   — detached raw Ed25519 signature (64 bytes binary)
#   $ARTIFACT.pubkey.pem — copy of the public key alongside the artifact
#                          (for sanity-check; the device-bundled copy is
#                          what's actually trusted, see PR 4/N)
#
# Per docs/SHELL_REBUILD.md § Rootfs hosting and integrity, signatures are
# verified by the on-device ShellRootfsSignatureVerifier against the public
# key shipped inside the APK. Rotation = app update.

set -euo pipefail

ARTIFACT="${ARTIFACT:?set ARTIFACT to the .tar.zst path}"
SIGNING_KEY="${SIGNING_KEY:?set SIGNING_KEY to the Ed25519 private-key PEM path}"
PUBLIC_KEY="${PUBLIC_KEY:?set PUBLIC_KEY to the matching public-key PEM path}"

if [[ ! -f "$ARTIFACT" ]]; then
    echo "error: $ARTIFACT does not exist" >&2
    exit 1
fi
if [[ ! -f "$SIGNING_KEY" ]]; then
    echo "error: $SIGNING_KEY does not exist" >&2
    exit 1
fi
if [[ ! -f "$PUBLIC_KEY" ]]; then
    echo "error: $PUBLIC_KEY does not exist" >&2
    exit 1
fi

# OpenSSL ≥ 3.0 understands `-rawin` for Ed25519. Older builds may need
# `-pkeyopt digest:null` instead; the project pins openssl 3.0+ in CI.
echo "[sign] Ed25519 detached signature for $ARTIFACT"
openssl pkeyutl \
    -sign \
    -inkey "$SIGNING_KEY" \
    -rawin \
    -in "$ARTIFACT" \
    -out "$ARTIFACT.sig"

cp "$PUBLIC_KEY" "$ARTIFACT.pubkey.pem"

# Verify what we just produced before exiting so a bad signing key never
# propagates downstream silently.
echo "[verify] re-checking signature with the matched public key"
openssl pkeyutl \
    -verify \
    -pubin -inkey "$PUBLIC_KEY" \
    -rawin -in "$ARTIFACT" \
    -sigfile "$ARTIFACT.sig"

echo
echo "signed:"
echo "  $ARTIFACT.sig         ($(wc -c < "$ARTIFACT.sig") bytes)"
echo "  $ARTIFACT.pubkey.pem  ($(wc -c < "$ARTIFACT.pubkey.pem") bytes)"
