#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNILIBS_ARM64="${ROOT_DIR}/app/src/main/jniLibs/arm64-v8a"
BUILD_JOBS="${BUILD_JOBS:-2}"
PSIPHON_REPO="${PSIPHON_REPO:-https://github.com/Psiphon-Labs/psiphon-tunnel-core.git}"
PSIPHON_BRANCH="${PSIPHON_BRANCH:-master}"
PSIPHON_SRC_DIR="${PSIPHON_SRC_DIR:-${ROOT_DIR}/native/psiphon-tunnel-core}"

echo "[*] Building Psiphon tunnel core for ARM64 (android/arm64)..."

if ! command -v go >/dev/null 2>&1; then
  echo "[!] FATAL: 'go' toolchain not found on PATH." >&2
  exit 1
fi

mkdir -p "${JNILIBS_ARM64}"

# Clone or update shallow copy of psiphon-tunnel-core
if [ ! -d "${PSIPHON_SRC_DIR}/ConsoleClient" ]; then
  echo "[*] Cloning ${PSIPHON_REPO} (branch: ${PSIPHON_BRANCH}, depth 1)..."
  mkdir -p "$(dirname "${PSIPHON_SRC_DIR}")"
  git clone --depth 1 --single-branch --branch "${PSIPHON_BRANCH}" "${PSIPHON_REPO}" "${PSIPHON_SRC_DIR}"
fi

pushd "${PSIPHON_SRC_DIR}" >/dev/null

LDFLAGS="-s -w"
if go version | grep -qE "go1\.(2[3-9]|[3-9][0-9])"; then
  LDFLAGS="${LDFLAGS} -checklinkname=0"
fi

export GOOS=android
export GOARCH=arm64
export CGO_ENABLED=0
export GOTOOLCHAIN=local
export GOFLAGS=-mod=vendor

OUTPUT_SO="${JNILIBS_ARM64}/libpsiphon-tunnel-core.so"

echo "[*] Compiling ConsoleClient with -trimpath and ldflags: ${LDFLAGS}..."
go build -trimpath -ldflags="${LDFLAGS}" -o "${OUTPUT_SO}" ./ConsoleClient

chmod 755 "${OUTPUT_SO}"

popd >/dev/null

echo "[+] Successfully compiled ARM64 libpsiphon-tunnel-core.so:"
ls -lh "${OUTPUT_SO}"
file "${OUTPUT_SO}" || true
