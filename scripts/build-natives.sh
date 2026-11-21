#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNILIBS_ARM64="${ROOT_DIR}/app/src/main/jniLibs/arm64-v8a"
BUILD_JOBS="${BUILD_JOBS:-2}"
ANDROID_API="${ANDROID_API:-26}"

echo "[*] Inspecting host resources before native compilation..."
nproc || true
free -h || true

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  echo "[!] ANDROID_NDK_HOME is not set. Point it to your Android NDK (r26d recommended)." >&2
  exit 1
fi

command -v cargo-ndk >/dev/null 2>&1 || {
  echo "[!] cargo-ndk not found. Install with: cargo install cargo-ndk" >&2
  exit 1
}

mkdir -p "${JNILIBS_ARM64}"

echo "[1/3] Building libaether_jni.so (arm64-v8a, 16KB page-aligned, -j ${BUILD_JOBS})..."
pushd "${ROOT_DIR}/native/aether-jni" >/dev/null
rustup target add aarch64-linux-android >/dev/null 2>&1 || true
RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,defs ${RUSTFLAGS:-}" cargo ndk \
  -t arm64-v8a \
  -p "${ANDROID_API}" \
  -o "${ROOT_DIR}/app/src/main/jniLibs" \
  build --release -j "${BUILD_JOBS}"
popd >/dev/null

echo "[2/3] Building libhev-socks5-tunnel.so (arm64-v8a, -j ${BUILD_JOBS})..."
if [ ! -d "${ROOT_DIR}/native/hev-socks5-tunnel/jni" ]; then
  bash "${ROOT_DIR}/scripts/fetch-hev.sh"
fi

pushd "${ROOT_DIR}/native/hev-socks5-tunnel" >/dev/null
"${ANDROID_NDK_HOME}/ndk-build" \
  -j "${BUILD_JOBS}" \
  APP_ABI=arm64-v8a \
  APP_PLATFORM="android-${ANDROID_API}" \
  APP_CFLAGS="-O2 -DPKGNAME=hev/htproxy -DCLSNAME=TProxyService"
cp -f libs/arm64-v8a/libhev-socks5-tunnel.so "${JNILIBS_ARM64}/libhev-socks5-tunnel.so"
popd >/dev/null

echo "[3/3] Staging NDK libc++_shared.so (arm64-v8a)..."
LIBCXX_SO=""
for candidate in "${ANDROID_NDK_HOME}"/toolchains/llvm/prebuilt/*/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so; do
  if [ -f "${candidate}" ]; then
    LIBCXX_SO="${candidate}"
    break
  fi
done

if [ -z "${LIBCXX_SO}" ] || [ ! -f "${LIBCXX_SO}" ]; then
  echo "[!] FATAL: Could not locate libc++_shared.so for aarch64-linux-android under ${ANDROID_NDK_HOME}" >&2
  exit 1
fi

echo "[*] Found NDK libc++_shared.so at: ${LIBCXX_SO}"
cp -f "${LIBCXX_SO}" "${JNILIBS_ARM64}/libc++_shared.so"
chmod 644 "${JNILIBS_ARM64}/libc++_shared.so"

echo "[+] Verifying ARM64 shared libraries:"
ls -lh "${JNILIBS_ARM64}/libaether_jni.so" "${JNILIBS_ARM64}/libhev-socks5-tunnel.so" "${JNILIBS_ARM64}/libc++_shared.so"
file "${JNILIBS_ARM64}/libaether_jni.so" "${JNILIBS_ARM64}/libhev-socks5-tunnel.so" "${JNILIBS_ARM64}/libc++_shared.so" || true
