# Build Instructions (ARM64 Android)

## Prerequisites

- **JDK 17** (Temurin / OpenJDK 17)
- **Android SDK** (API 35) & **Android NDK r26d** (`26.3.11579264`) with `ANDROID_NDK_HOME` set
- **Rust** (stable) with ARM64 Android target:
  ```bash
  rustup target add aarch64-linux-android
  cargo install cargo-ndk --locked
  ```
- **Gradle 8.9+** (or open the project root directly in Android Studio Ladybug+)
- **Git** (used by `scripts/fetch-hev.sh` to clone `heiher/hev-socks5-tunnel`)

## Step 1: Build ARM64 Native Libraries

`scripts/build-natives.sh` builds both required shared libraries with bounded parallelism (`BUILD_JOBS=2` by default) to protect host CPU and RAM:

1. `app/src/main/jniLibs/arm64-v8a/libaether_jni.so` (CluvexStudio/Aether v2.4.0 + JNI bridge, default features only, LTO + stripped)
2. `app/src/main/jniLibs/arm64-v8a/libhev-socks5-tunnel.so` (`heiher/hev-socks5-tunnel`, `PKGNAME=hev/htproxy`, `CLSNAME=TProxyService`)

```bash
export ANDROID_NDK_HOME=/path/to/android-sdk/ndk/26.3.11579264
bash scripts/build-natives.sh
```

## Step 2: Run Unit Tests & Build Debug APK

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Step 3: Build Signed Release APK (Optional)

Create `keystore.properties` in the project root (never commit secrets):

```properties
storeFile=/path/to/release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

Then run:

```bash
gradle :app:assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk` (minified with R8 + resource shrinking enabled).

## GitHub Actions CI

Push the project to GitHub and run `.github/workflows/android-build.yml`. It compiles both ARM64 `.so` libraries, runs `:app:testDebugUnitTest`, and uploads the Debug APK (and Release APK if `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` secrets are set).
