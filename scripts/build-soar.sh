#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNILIBS_ARM64="${ROOT_DIR}/app/src/main/jniLibs/arm64-v8a"
WRAPPER_DIR="${ROOT_DIR}/soar-wrapper"
OUTPUT_SO="${JNILIBS_ARM64}/libsoar.so"

echo "[*] Building soar-wrapper (github.com/getlantern/soar) for ARM64 (android/arm64)..."

if ! command -v go >/dev/null 2>&1; then
  echo "[!] FATAL: 'go' toolchain not found on PATH." >&2
  exit 1
fi

mkdir -p "${JNILIBS_ARM64}"

pushd "${WRAPPER_DIR}" >/dev/null

export GOOS=android
export GOARCH=arm64
export CGO_ENABLED=0
export GOTOOLCHAIN=local

go mod tidy
go build -v -trimpath -ldflags="-s -w" -o "${OUTPUT_SO}" .

chmod 755 "${OUTPUT_SO}"

popd >/dev/null

echo "[+] Successfully compiled ARM64 libsoar.so:"
ls -lh "${OUTPUT_SO}"
file "${OUTPUT_SO}" || true

if command -v readelf >/dev/null 2>&1; then
  echo "[*] Verifying 16KB ELF PT_LOAD alignment for libsoar.so..."
  readelf -l "${OUTPUT_SO}" | grep LOAD || true
  if readelf -l "${OUTPUT_SO}" | grep LOAD | grep -q "0x1000$"; then
    echo "[!] ERROR: libsoar.so has 4KB (0x1000) PT_LOAD alignment instead of >=16KB" >&2
    exit 1
  fi
  echo "[+] Verified: libsoar.so satisfies 16KB ELF alignment."
fi
