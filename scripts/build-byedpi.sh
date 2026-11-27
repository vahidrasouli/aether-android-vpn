#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNILIBS_ARM64="${ROOT_DIR}/app/src/main/jniLibs/arm64-v8a"
BUILD_JOBS="${BUILD_JOBS:-2}"
ANDROID_API="${ANDROID_API:-26}"
BYEDPI_REPO="${BYEDPI_REPO:-https://github.com/hufrea/byedpi.git}"
BYEDPI_VERSION="${BYEDPI_VERSION:-v0.17.3}"
BYEDPI_SRC_DIR="${BYEDPI_SRC_DIR:-${ROOT_DIR}/native/byedpi}"

echo "[*] Building hufrea/byedpi (${BYEDPI_VERSION}) for ARM64 (android/arm64-v8a)..."

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  echo "[!] ANDROID_NDK_HOME is not set. Point it to your Android NDK (r26d recommended)." >&2
  exit 1
fi

CC=""
for candidate in "${ANDROID_NDK_HOME}"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android"${ANDROID_API}"-clang; do
  if [ -x "${candidate}" ]; then
    CC="${candidate}"
    break
  fi
done

if [ -z "${CC}" ]; then
  echo "[!] FATAL: Could not locate aarch64-linux-android${ANDROID_API}-clang under ${ANDROID_NDK_HOME}" >&2
  exit 1
fi

LLVM_BIN_DIR="$(dirname "${CC}")"
STRIP="${LLVM_BIN_DIR}/llvm-strip"

mkdir -p "${JNILIBS_ARM64}"

if [ -d "${BYEDPI_SRC_DIR}/.git" ]; then
  CURRENT_REMOTE="$(git -C "${BYEDPI_SRC_DIR}" remote get-url origin 2>/dev/null || true)"
  CURRENT_TAG="$(git -C "${BYEDPI_SRC_DIR}" describe --tags --exact-match 2>/dev/null || true)"
  if [ "${CURRENT_REMOTE}" != "${BYEDPI_REPO}" ] || [ "${CURRENT_TAG}" != "${BYEDPI_VERSION}" ]; then
    echo "[*] Stale or mismatched byedpi clone detected (${CURRENT_REMOTE} @ ${CURRENT_TAG}). Re-cloning..."
    rm -rf "${BYEDPI_SRC_DIR}"
  fi
fi

if [ ! -f "${BYEDPI_SRC_DIR}/Makefile" ]; then
  echo "[*] Cloning ${BYEDPI_REPO} (tag: ${BYEDPI_VERSION}, depth 1)..."
  mkdir -p "$(dirname "${BYEDPI_SRC_DIR}")"
  git -c core.longpaths=true clone --depth 1 --branch "${BYEDPI_VERSION}" "${BYEDPI_REPO}" "${BYEDPI_SRC_DIR}"
fi

pushd "${BYEDPI_SRC_DIR}" >/dev/null

make clean >/dev/null 2>&1 || true

echo "[*] Compiling ciadpi with ${CC}..."
make -j "${BUILD_JOBS}" \
  CC="${CC}" \
  CFLAGS="-I. -std=c99 -Os -ffunction-sections -fdata-sections -fPIE -Wall -Wno-unused -Wextra -Wno-unused-parameter" \
  LDFLAGS="-pie -Wl,--gc-sections -Wl,-z,max-page-size=16384"

if [ -x "${STRIP}" ]; then
  echo "[*] Stripping ciadpi with ${STRIP}..."
  "${STRIP}" --strip-all ciadpi
fi

OUTPUT_SO="${JNILIBS_ARM64}/libbyedpi.so"
cp -f ciadpi "${OUTPUT_SO}"
chmod 755 "${OUTPUT_SO}"

popd >/dev/null

SIZE_BYTES=$(wc -c < "${OUTPUT_SO}" | tr -d '[:space:]')
echo "[+] Successfully compiled ARM64 libbyedpi.so (${SIZE_BYTES} bytes):"
ls -lh "${OUTPUT_SO}"
file "${OUTPUT_SO}" || true

if [ "${SIZE_BYTES}" -ge 358400 ]; then
  echo "[!] WARNING: libbyedpi.so (${SIZE_BYTES} bytes) exceeds 350 KB target." >&2
fi

if command -v readelf >/dev/null 2>&1; then
  echo "[*] Verifying 16KB ELF PT_LOAD alignment for libbyedpi.so..."
  readelf -l "${OUTPUT_SO}" | grep LOAD || true
  if readelf -l "${OUTPUT_SO}" | grep LOAD | grep -q "0x1000$"; then
    echo "[!] ERROR: libbyedpi.so has 4KB (0x1000) PT_LOAD alignment instead of >=16KB" >&2
    exit 1
  fi
  echo "[+] Verified: libbyedpi.so satisfies 16KB ELF alignment."
fi
