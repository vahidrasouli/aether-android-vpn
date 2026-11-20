#!/usr/bin/env bash
set -euo pipefail

# Fetches a reproducible, SHA-256 verified release of heiher/hev-socks5-tunnel (MIT License).
# Never clones an unpinned floating branch.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HEV_DIR="${ROOT_DIR}/native/hev-socks5-tunnel"
HEV_VERSION="2.20.0"
HEV_TARBALL="hev-socks5-tunnel-${HEV_VERSION}.tar.xz"
HEV_URL="https://github.com/heiher/hev-socks5-tunnel/releases/download/${HEV_VERSION}/${HEV_TARBALL}"
HEV_SHA256="bc2ce746a0beeb71030d3ffe299f5a9d48e2895902d3883c2cac1a551ee128e9"
STAMP_FILE="${HEV_DIR}/.hev-version"

if [ -f "${STAMP_FILE}" ] && [ "$(cat "${STAMP_FILE}")" = "${HEV_VERSION}" ] && [ -d "${HEV_DIR}/jni" ]; then
  echo "[+] hev-socks5-tunnel ${HEV_VERSION} already verified at ${HEV_DIR}/jni"
  exit 0
fi

rm -rf "${HEV_DIR}"
mkdir -p "${HEV_DIR}"

TMP_ARCHIVE="${HEV_DIR}/${HEV_TARBALL}"
echo "[*] Downloading pinned heiher/hev-socks5-tunnel ${HEV_VERSION}..."
curl -fsSL --retry 3 --retry-delay 2 -o "${TMP_ARCHIVE}" "${HEV_URL}"

echo "[*] Verifying SHA-256 checksum..."
echo "${HEV_SHA256}  ${TMP_ARCHIVE}" | sha256sum -c -

echo "[*] Extracting into ${HEV_DIR}/jni..."
mkdir -p "${HEV_DIR}/jni"
tar -xJf "${TMP_ARCHIVE}" --strip-components=1 -C "${HEV_DIR}/jni"
rm -f "${TMP_ARCHIVE}"
echo "${HEV_VERSION}" > "${STAMP_FILE}"

echo "[+] Verified and staged hev-socks5-tunnel ${HEV_VERSION} at ${HEV_DIR}/jni"
