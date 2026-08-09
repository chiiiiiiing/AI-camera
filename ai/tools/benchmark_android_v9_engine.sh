#!/usr/bin/env bash
# Collects non-destructive Android-side evidence while the V9 camera preview is already running.
set -euo pipefail

PACKAGE_NAME="com.example.aicamera.debug"
DURATION_SECONDS=60
OUTPUT_DIR=""
DEVICE_SERIAL=""

usage() {
  cat <<'EOF'
Usage: benchmark_android_v9_engine.sh [options]

Open the camera preview first, then run this script. It does not install, stop, clear, or modify
the app. Read PipelineState's avgInferenceMs, avgPipelineMs and effectiveFps from the in-app HUD
alongside the generated memory and UI-frame files.

Options:
  --package NAME       Application ID (default: com.example.aicamera.debug)
  --serial SERIAL      adb device serial when more than one device is connected
  --duration SECONDS   Sampling window (default: 60)
  --output-dir PATH    Output directory (default: ./output/v9-device-benchmark-<timestamp>)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --package) PACKAGE_NAME="$2"; shift 2 ;;
    --serial) DEVICE_SERIAL="$2"; shift 2 ;;
    --duration) DURATION_SECONDS="$2"; shift 2 ;;
    --output-dir) OUTPUT_DIR="$2"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

command -v adb >/dev/null || { echo "adb is not available on PATH" >&2; exit 1; }
[[ "$DURATION_SECONDS" =~ ^[1-9][0-9]*$ ]] || { echo "--duration must be a positive integer" >&2; exit 2; }

ADB=(adb)
if [[ -n "$DEVICE_SERIAL" ]]; then
  ADB+=(-s "$DEVICE_SERIAL")
fi

DEVICE_COUNT=$("${ADB[@]}" devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')
[[ "$DEVICE_COUNT" -eq 1 ]] || {
  echo "Select exactly one connected Android device with --serial" >&2
  "${ADB[@]}" devices -l >&2
  exit 1
}

PID=$("${ADB[@]}" shell pidof "$PACKAGE_NAME" | tr -d '\r')
[[ -n "$PID" ]] || {
  echo "Package $PACKAGE_NAME is not running. Open the V9 camera preview before sampling." >&2
  exit 1
}

STAMP=$(date +%Y%m%d-%H%M%S)
OUTPUT_DIR=${OUTPUT_DIR:-"output/v9-device-benchmark-$STAMP"}
mkdir -p "$OUTPUT_DIR"

"${ADB[@]}" shell getprop > "$OUTPUT_DIR/device-properties.txt"
"${ADB[@]}" shell dumpsys meminfo "$PACKAGE_NAME" > "$OUTPUT_DIR/meminfo-before.txt"
"${ADB[@]}" shell dumpsys gfxinfo "$PACKAGE_NAME" framestats > "$OUTPUT_DIR/gfxinfo-before.txt"

echo "timestamp_utc,pid,total_pss_kb" > "$OUTPUT_DIR/memory-samples.csv"
for ((sample = 1; sample <= DURATION_SECONDS; sample++)); do
  CURRENT_PID=$("${ADB[@]}" shell pidof "$PACKAGE_NAME" | tr -d '\r')
  [[ -n "$CURRENT_PID" ]] || { echo "App stopped during sampling" >&2; exit 1; }
  PSS=$("${ADB[@]}" shell dumpsys meminfo "$PACKAGE_NAME" | awk '/TOTAL PSS:/ { print $3; exit }' | tr -d '\r')
  printf '%s,%s,%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$CURRENT_PID" "${PSS:-unknown}" >> "$OUTPUT_DIR/memory-samples.csv"
  sleep 1
done

"${ADB[@]}" shell dumpsys meminfo "$PACKAGE_NAME" > "$OUTPUT_DIR/meminfo-after.txt"
"${ADB[@]}" shell dumpsys gfxinfo "$PACKAGE_NAME" framestats > "$OUTPUT_DIR/gfxinfo-after.txt"

cat > "$OUTPUT_DIR/README.txt" <<EOF
V9 device benchmark evidence

Package: $PACKAGE_NAME
PID at start: $PID
Duration: $DURATION_SECONDS seconds

Collect these values from the app's PipelineState/HUD during the same window:
- avgInferenceMs
- avgPipelineMs
- effectiveFps
- dropRate

Targets from android_runtime_config.json:
- embedding p50 <= 80 ms
- embedding p90 <= 150 ms

This script is read-only against the app: it does not install, launch, stop, clear, or alter it.
EOF

echo "Benchmark evidence written to $OUTPUT_DIR"
