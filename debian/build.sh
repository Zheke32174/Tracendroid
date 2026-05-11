#!/usr/bin/env bash
# Build the Debian 12 (bookworm) proot rootfs for tracendroid.
# Outputs a deterministic .tar.zst artifact + .sha256 alongside it.
#
# Usage (requires root or fakeroot, plus debootstrap, zstd):
#   sudo bash debian/build.sh
#
# Resolves AUDIT_PLAN § 1.9 (distro: Debian 12) and feeds the
# SHELL_REBUILD § Rootfs build pipeline contract.

set -euo pipefail

# ---- knobs (override via env) ----
ROOTFS_VERSION="${ROOTFS_VERSION:-2026.05.0-dev}"
TARGET_ARCH="${TARGET_ARCH:-arm64}"
DEBIAN_SUITE="${DEBIAN_SUITE:-bookworm}"
# Snapshot pin for reproducibility-as-of. snapshot.debian.org keeps
# stable mirrors for years; bumping ROOTFS_VERSION and this pin
# together is the v1 refresh flow. Strict reproducibility (hermetic,
# source-pinned) is a v2 goal per SHELL_REBUILD § Rootfs build pipeline.
DEBIAN_MIRROR="${DEBIAN_MIRROR:-https://snapshot.debian.org/archive/debian/20260511T000000Z/}"
SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-1778457600}" # 2026-05-11 00:00:00 UTC

# Conservative v1 base package set. Subscription CLIs, Python tooling,
# and Node tooling install on demand inside the proot environment (see
# SHELL_REBUILD § Subscription-CLI installation policy).
PACKAGES=(
    bash
    coreutils
    procps
    ca-certificates
    curl
    git
    openssh-client
    python3
    nodejs
    npm
)

# ---- paths ----
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
WORK_DIR="${WORK_DIR:-$ROOT_DIR/debian/build}"
ROOTFS_DIR="$WORK_DIR/rootfs"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/debian/out}"
ARTIFACT="$OUT_DIR/tracendroid-rootfs-${ROOTFS_VERSION}-${TARGET_ARCH}.tar.zst"

# ---- preflight ----
if [[ "$(id -u)" -ne 0 ]]; then
    echo "ERROR: must run as root (or via fakeroot). debootstrap needs it." >&2
    exit 1
fi

for tool in debootstrap zstd sha256sum tar; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "ERROR: missing required tool: $tool" >&2
        exit 1
    }
done

# ---- build ----
mkdir -p "$WORK_DIR" "$OUT_DIR"
rm -rf "$ROOTFS_DIR"

echo "[1/5] debootstrap $DEBIAN_SUITE ($TARGET_ARCH) from $DEBIAN_MIRROR"
debootstrap \
    --arch="$TARGET_ARCH" \
    --variant=minbase \
    --include="$(IFS=,; echo "${PACKAGES[*]}")" \
    "$DEBIAN_SUITE" "$ROOTFS_DIR" "$DEBIAN_MIRROR"

echo "[2/5] strip locales / docs / manpages / apt caches"
# Operit users get English (and Chinese via in-app i18n). The rootfs
# itself doesn't need every locale's translations.
rm -rf "$ROOTFS_DIR"/usr/share/locale/*
rm -rf "$ROOTFS_DIR"/usr/share/doc/*
rm -rf "$ROOTFS_DIR"/usr/share/man/*
rm -rf "$ROOTFS_DIR"/var/cache/apt/archives/*.deb
rm -rf "$ROOTFS_DIR"/var/lib/apt/lists/*

echo "[3/5] write operit-managed directory layout (per SHELL_REBUILD § Filesystem layout)"
install -d -m 0755 "$ROOTFS_DIR/home/operator"
install -d -m 0755 "$ROOTFS_DIR/var/lib/operit"
install -d -m 0700 "$ROOTFS_DIR/var/lib/operit/ipc"
install -d -m 0755 "$ROOTFS_DIR/opt/cli"
# /workspace is a bind-mount point. The directory exists in the
# rootfs so the bind has something to land on; its contents come
# from Android/data/<package>/files/workspace/ at launch.
install -d -m 0755 "$ROOTFS_DIR/workspace"

echo "[4/5] pack tarball (deterministic-as-of)"
# --sort=name + fixed mtime + numeric owner = reproducible-enough
# output across two CI runs of the same source. --pax-option strips
# atime/ctime which are otherwise host-dependent.
( cd "$ROOTFS_DIR" && tar \
    --sort=name \
    --mtime="@$SOURCE_DATE_EPOCH" \
    --owner=0 --group=0 --numeric-owner \
    --pax-option='exthdr.name=%d/PaxHeaders/%f,delete=atime,delete=ctime' \
    -cf - . ) | zstd -19 --long=27 -o "$ARTIFACT"

echo "[5/5] sha256"
( cd "$OUT_DIR" && sha256sum "$(basename "$ARTIFACT")" > "$ARTIFACT.sha256" )

echo
echo "rootfs built:"
echo "  $ARTIFACT"
echo "  $ARTIFACT.sha256"
echo
cat "$ARTIFACT.sha256"
