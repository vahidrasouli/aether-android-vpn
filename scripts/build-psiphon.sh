#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNILIBS_ARM64="${ROOT_DIR}/app/src/main/jniLibs/arm64-v8a"
BUILD_JOBS="${BUILD_JOBS:-2}"
PSIPHON_REPO="${PSIPHON_REPO:-https://github.com/CluvexStudio/psiphon-tunnel-core.git}"
PSIPHON_BRANCH="${PSIPHON_BRANCH:-shirokhorshid}"
PSIPHON_COMMIT="${PSIPHON_COMMIT:-83aa73b9b982e7421e00117f5b0c5aceb5dda452}"
PSIPHON_SRC_DIR="${PSIPHON_SRC_DIR:-${ROOT_DIR}/native/psiphon-tunnel-core}"

echo "[*] Building Aether-patched Psiphon tunnel core for ARM64 (android/arm64)..."

if ! command -v go >/dev/null 2>&1; then
  echo "[!] FATAL: 'go' toolchain not found on PATH." >&2
  exit 1
fi

mkdir -p "${JNILIBS_ARM64}"

# Verify existing clone matches target repo and commit; otherwise re-clone
if [ -d "${PSIPHON_SRC_DIR}/.git" ]; then
  CURRENT_REMOTE="$(git -C "${PSIPHON_SRC_DIR}" remote get-url origin 2>/dev/null || true)"
  CURRENT_HEAD="$(git -C "${PSIPHON_SRC_DIR}" rev-parse HEAD 2>/dev/null || true)"
  if [ "${CURRENT_REMOTE}" != "${PSIPHON_REPO}" ] || ([ -n "${PSIPHON_COMMIT}" ] && [ "${CURRENT_HEAD}" != "${PSIPHON_COMMIT}" ]); then
    echo "[*] Stale or mismatched psiphon clone detected (${CURRENT_REMOTE} @ ${CURRENT_HEAD}). Re-cloning..."
    rm -rf "${PSIPHON_SRC_DIR}"
  fi
fi

# Clone or update shallow copy of psiphon-tunnel-core
if [ ! -d "${PSIPHON_SRC_DIR}/ConsoleClient" ]; then
  echo "[*] Cloning ${PSIPHON_REPO} (branch: ${PSIPHON_BRANCH}, depth 50)..."
  mkdir -p "$(dirname "${PSIPHON_SRC_DIR}")"
  git -c core.longpaths=true clone --depth 50 --branch "${PSIPHON_BRANCH}" "${PSIPHON_REPO}" "${PSIPHON_SRC_DIR}"
  if [ -n "${PSIPHON_COMMIT}" ]; then
    echo "[*] Checking out pinned commit ${PSIPHON_COMMIT}..."
    git -C "${PSIPHON_SRC_DIR}" checkout --quiet "${PSIPHON_COMMIT}" 2>/dev/null ||
      echo "[!] Warning: pinned commit not found, using ${PSIPHON_BRANCH} as-is" >&2
  fi
fi

# Ensure extraneous sample apps and nested wrapper JARs are purged
rm -rf "${PSIPHON_SRC_DIR}/MobileLibrary"

pushd "${PSIPHON_SRC_DIR}" >/dev/null

LDFLAGS="-s -w -buildid="
if go version | grep -qE "go1\.(2[3-9]|[3-9][0-9])"; then
  LDFLAGS="${LDFLAGS} -checklinkname=0"
fi

BUILDDATE=$(git log -1 --format=%cI HEAD 2>/dev/null || echo "2026-01-01T00:00:00Z")
BUILDREPO="${PSIPHON_REPO}"
BUILDREV="${PSIPHON_COMMIT:-$(git rev-parse --short HEAD 2>/dev/null || echo "83aa73b")}"
GOVERSION=$(go version | awk '{print $3}')

LDFLAGS="${LDFLAGS} -X github.com/Psiphon-Labs/psiphon-tunnel-core/psiphon/common/buildinfo.buildDate=${BUILDDATE} -X github.com/Psiphon-Labs/psiphon-tunnel-core/psiphon/common/buildinfo.buildRepo=${BUILDREPO} -X github.com/Psiphon-Labs/psiphon-tunnel-core/psiphon/common/buildinfo.buildRev=${BUILDREV} -X github.com/Psiphon-Labs/psiphon-tunnel-core/psiphon/common/buildinfo.goVersion=${GOVERSION}"

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

if grep -a -q "FRONTED-MEEK-CDN-OSSH" "${OUTPUT_SO}"; then
  echo "[+] Verified: 'FRONTED-MEEK-CDN-OSSH' protocol string present in compiled binary."
else
  echo "[!] ERROR: 'FRONTED-MEEK-CDN-OSSH' protocol string NOT found in ${OUTPUT_SO}!" >&2
  exit 1
fi
