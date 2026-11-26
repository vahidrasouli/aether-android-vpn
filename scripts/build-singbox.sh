#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNILIBS_ARM64="${ROOT_DIR}/app/src/main/jniLibs/arm64-v8a"
SINGBOX_REPO="${SINGBOX_REPO:-https://github.com/SagerNet/sing-box.git}"
SINGBOX_VERSION="${SINGBOX_VERSION:-v1.14.4}"
SINGBOX_SRC_DIR="${SINGBOX_SRC_DIR:-${ROOT_DIR}/native/sing-box}"
SINGBOX_TAGS="${SINGBOX_TAGS:-with_utls,with_quic,with_wireguard,with_gvisor,with_clash_api,badlinkname,tfogo_checklinkname0}"

echo "[*] Building official SagerNet/sing-box (${SINGBOX_VERSION}) for ARM64 (android/arm64)..."

if ! command -v go >/dev/null 2>&1; then
  echo "[!] FATAL: 'go' toolchain not found on PATH." >&2
  exit 1
fi

mkdir -p "${JNILIBS_ARM64}"

if [ -d "${SINGBOX_SRC_DIR}/.git" ]; then
  CURRENT_REMOTE="$(git -C "${SINGBOX_SRC_DIR}" remote get-url origin 2>/dev/null || true)"
  CURRENT_TAG="$(git -C "${SINGBOX_SRC_DIR}" describe --tags --exact-match 2>/dev/null || true)"
  if [ "${CURRENT_REMOTE}" != "${SINGBOX_REPO}" ] || [ "${CURRENT_TAG}" != "${SINGBOX_VERSION}" ]; then
    echo "[*] Stale or mismatched sing-box clone detected (${CURRENT_REMOTE} @ ${CURRENT_TAG}). Re-cloning..."
    rm -rf "${SINGBOX_SRC_DIR}"
  fi
fi

if [ ! -d "${SINGBOX_SRC_DIR}/cmd/sing-box" ]; then
  echo "[*] Cloning ${SINGBOX_REPO} (tag: ${SINGBOX_VERSION}, depth 1)..."
  mkdir -p "$(dirname "${SINGBOX_SRC_DIR}")"
  git -c core.longpaths=true clone --depth 1 --branch "${SINGBOX_VERSION}" "${SINGBOX_REPO}" "${SINGBOX_SRC_DIR}"
fi

pushd "${SINGBOX_SRC_DIR}" >/dev/null

LDFLAGS="-s -w -X github.com/sagernet/sing-box/constant.Version=${SINGBOX_VERSION#v} -X runtime.godebugDefault=multipathtcp=0,tlssha1=1"
if go version | grep -qE "go1\.(2[3-9]|[3-9][0-9])"; then
  LDFLAGS="${LDFLAGS} -checklinkname=0"
fi

export GOOS=android
export GOARCH=arm64
export CGO_ENABLED=0
export GOTOOLCHAIN=local

OUTPUT_SO="${JNILIBS_ARM64}/libsing-box.so"

echo "[*] Compiling cmd/sing-box with tags '${SINGBOX_TAGS}'..."
go build -v -trimpath -tags "${SINGBOX_TAGS}" -ldflags="${LDFLAGS}" -o "${OUTPUT_SO}" ./cmd/sing-box

chmod 755 "${OUTPUT_SO}"

popd >/dev/null

echo "[+] Successfully compiled ARM64 libsing-box.so:"
ls -lh "${OUTPUT_SO}"
file "${OUTPUT_SO}" || true

if command -v readelf >/dev/null 2>&1; then
  echo "[*] Verifying 16KB ELF PT_LOAD alignment for libsing-box.so..."
  readelf -l "${OUTPUT_SO}" | grep LOAD || true
  if readelf -l "${OUTPUT_SO}" | grep LOAD | grep -q "0x1000$"; then
    echo "[!] ERROR: libsing-box.so has 4KB (0x1000) PT_LOAD alignment instead of >=16KB" >&2
    exit 1
  fi
  echo "[+] Verified: libsing-box.so satisfies 16KB ELF alignment."
fi
