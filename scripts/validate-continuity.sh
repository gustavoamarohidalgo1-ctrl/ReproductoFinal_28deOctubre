#!/usr/bin/env bash
# Runs on a dedicated test device: the instrumentation replaces its playback queue.
set -euo pipefail
if [[ $# -lt 1 || $# -gt 4 ]]; then
  echo "Usage: $0 DEVICE_SERIAL [SECONDS=86400] [debug|benchmark=benchmark] [DEVICE_AUDIO_PATH]" >&2
  exit 2
fi
serial=$1
duration=${2:-86400}
variant=${3:-benchmark}
source_path=${4:-}
[[ $duration =~ ^[0-9]+$ && $duration -ge 10 && $duration -le 604800 ]] || { echo "Duration must be 10..604800 seconds" >&2; exit 2; }
[[ $variant == debug || $variant == benchmark ]] || { echo "Variant must be debug or benchmark" >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 1; }
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_dir"
sdk_path=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -n $sdk_path ]]; then adb_bin="$sdk_path/platform-tools/adb"; else adb_bin=$(command -v adb); fi
[[ $("$adb_bin" -s "$serial" get-state) == device ]] || { echo "Selected device is not ready" >&2; exit 1; }
if [[ $variant == benchmark ]]; then
  ./gradlew :app:assembleBenchmark :validation:assembleBenchmark --console=plain
  test_apk=validation/build/outputs/apk/benchmark/validation-benchmark.apk
  test_package=com.reproductor.audiolibros.validation
else
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
  test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  test_package=com.reproductor.audiolibros.test
fi
"$adb_bin" -s "$serial" install -r -t "app/build/outputs/apk/$variant/app-$variant.apk"
"$adb_bin" -s "$serial" install -r -t "$test_apk"
output_dir="app/build/reports/continuity-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$output_dir"
run_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
remote_report=/sdcard/Android/data/com.reproductor.audiolibros/files/validation/continuity-soak.json
"$adb_bin" -s "$serial" shell rm -f "$remote_report"
remote=(am instrument -w -r -e class com.reproductor.audiolibros.LongPlaybackSoakInstrumentedTest -e continuitySoakSeconds "$duration" -e continuityRunId "$run_id")
if [[ -n $source_path ]]; then remote+=(-e continuitySoakSourcePath "$source_path"); fi
remote+=("$test_package/androidx.test.runner.AndroidJUnitRunner")
# adb joins remote arguments; quote every value for Android's shell, including paths.
remote_command=""
for argument in "${remote[@]}"; do
  escaped=${argument//\'/\'\\\'\'}
  remote_command+=" '$escaped'"
done
echo "Assisted soak: instrumentation and controller remain connected; this does not measure physical audio." | tee "$output_dir/run-info.txt"
set +e
python3 scripts/run-instrumentation.py "$((duration + 120))" "$output_dir/instrumentation.txt" \
  "$adb_bin" -s "$serial" shell "$remote_command"
instrument_status=$?
"$adb_bin" -s "$serial" pull "$remote_report" "$output_dir/continuity-soak.json" > "$output_dir/pull.txt" 2>&1
pull_status=$?
set -e
if [[ $instrument_status -ne 0 || $pull_status -ne 0 ]] || ! grep -Eq 'OK \(1 test\)' "$output_dir/instrumentation.txt"; then
  if [[ $instrument_status -eq 124 ]] || grep -qi 'timed out\|timeout' "$output_dir/instrumentation.txt"; then
    echo "Instrumentation reported a timeout; report may be incomplete. Evidence: $repo_dir/$output_dir" >&2
  else
    echo "Instrumentation failed or was interrupted; report may be incomplete. Evidence: $repo_dir/$output_dir" >&2
  fi
  exit 1
fi
if ! python3 - "$output_dir/continuity-soak.json" "$run_id" <<'PY'
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as f:
        report = json.load(f)
    if report.get("runId") != sys.argv[2] or report.get("status") != "passed":
        raise ValueError("report belongs to another run or is incomplete/failed")
except (OSError, ValueError) as error:
    print(f"Invalid or incomplete report: {error}", file=sys.stderr)
    sys.exit(1)
PY
then
  echo "Evidence: $repo_dir/$output_dir" >&2
  exit 1
fi
echo "Continuity evidence: $repo_dir/$output_dir"
