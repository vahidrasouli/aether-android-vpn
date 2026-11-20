#!/usr/bin/env bash
set -euo pipefail

# Aether Android VPN — 4-Scenario Real-Device Benchmark & Verification Harness (ARM64)
# Usage:
#   bash scripts/benchmark-device.sh <scenario> [apk_path]
# Scenarios:
#   A  - Disconnected baseline (RAM, CPU, background activity, batterystats)
#   B  - VPN Connected, Idle (RAM, CPU, wakeups, idle network bytes, batterystats)
#   C  - VPN Connected, Active Traffic (throughput, latency, RAM, CPU, WARP trace)
#   D  - Long-Running Session snapshot (memory growth, wakeups, stability, batterystats)

SCENARIO="${1:-C}"
APK_PATH="${2:-app/build/outputs/apk/debug/app-debug.apk}"
PKG="io.aethervpn.android"

command -v adb >/dev/null 2>&1 || { echo "[!] adb not found"; exit 1; }

echo "=== Device & APK Metadata ==="
adb shell getprop ro.product.model
adb shell getprop ro.product.cpu.abi
adb shell getprop ro.build.version.release
[ -f "${APK_PATH}" ] && ls -lh "${APK_PATH}"

snapshot_mem_cpu() {
  echo "--- Memory (dumpsys meminfo) ---"
  adb shell dumpsys meminfo "${PKG}" | grep -E "TOTAL PSS|TOTAL RSS|Native Heap|Dalvik Heap" || true
  echo "--- CPU (top 1-shot) ---"
  adb shell top -b -n 1 -o PID,%CPU,RSS,ARGS | grep "${PKG}" || true
}

case "${SCENARIO}" in
  A)
    echo "=== SCENARIO A: DISCONNECTED BASELINE ==="
    adb shell dumpsys batterystats --reset
    snapshot_mem_cpu
    echo "Leave app disconnected for 15-30 min unplugged, then run:"
    echo "  adb shell dumpsys batterystats ${PKG} | grep -E 'Wake lock|Job|Service|Cpu|Estimated power|Uid'"
    ;;
  B)
    echo "=== SCENARIO B: VPN CONNECTED, IDLE ==="
    adb shell ip addr show tun0 || true
    adb shell dumpsys batterystats --reset
    snapshot_mem_cpu
    echo "Leave VPN connected with screen off (no foreground traffic) for 30 min unplugged, then run:"
    echo "  adb shell dumpsys batterystats ${PKG} | grep -E 'Wake lock|Cpu|Mobile|Wifi|Estimated power'"
    ;;
  C)
    echo "=== SCENARIO C: VPN CONNECTED, ACTIVE TRAFFIC ==="
    adb shell ip addr show tun0 || true
    echo "--- End-to-End WARP Trace & Latency ---"
    adb shell "curl -sS -w '\ntime_total=%{time_total}s\n' --max-time 10 https://www.cloudflare.com/cdn-cgi/trace" || true
    echo "--- Throughput Probe (10 MB Cloudflare speed endpoint) ---"
    adb shell "curl -o /dev/null -sS -w 'speed_download=%{speed_download} B/s time=%{time_total}s\n' --max-time 30 https://speed.cloudflare.com/__down?bytes=10000000" || true
    snapshot_mem_cpu
    ;;
  D)
    echo "=== SCENARIO D: LONG-RUNNING SESSION AUDIT ==="
    adb shell ip addr show tun0 || true
    snapshot_mem_cpu
    echo "--- Battery & Wakeup Summary ---"
    adb shell dumpsys batterystats "${PKG}" | head -n 70 || true
    ;;
  *)
    echo "Unknown scenario '${SCENARIO}'. Use A, B, C, or D." >&2
    exit 1
    ;;
esac
