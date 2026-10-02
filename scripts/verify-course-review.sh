#!/usr/bin/env bash
set -euo pipefail

# scripts/verify-course-review.sh
# Verifies Course Review Records implementation on a designated Android test device.

USAGE="Usage: $0 -s <device_serial> [--skip-install] [--classes <class1,class2>]"

SERIAL=""
SKIP_INSTALL=false
CUSTOM_CLASSES=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        -s|--serial)
            if [[ -z "${2:-}" || "$2" == -* ]]; then
                echo "Error: -s requires a device serial argument." >&2
                echo "$USAGE" >&2
                exit 1
            fi
            SERIAL="$2"
            shift 2
            ;;
        --skip-install)
            SKIP_INSTALL=true
            shift
            ;;
        -c|--classes)
            if [[ -z "${2:-}" || "$2" == -* ]]; then
                echo "Error: --classes requires a comma-separated list of test classes." >&2
                echo "$USAGE" >&2
                exit 1
            fi
            CUSTOM_CLASSES="$2"
            shift 2
            ;;
        -h|--help)
            echo "$USAGE"
            echo "Options:"
            echo "  -s, --serial <serial>       Specific Android device/emulator serial (required)"
            echo "  --skip-install              Skip APK installation step"
            echo "  -c, --classes <classes>     Comma-separated list of test classes to run"
            exit 0
            ;;
        *)
            echo "Error: Unknown argument: $1" >&2
            echo "$USAGE" >&2
            exit 1
            ;;
    esac
done

ADB_BIN="adb"
if ! command -v adb >/dev/null 2>&1; then
    if [[ -x "${ANDROID_HOME:-}/platform-tools/adb" ]]; then
        ADB_BIN="$ANDROID_HOME/platform-tools/adb"
    elif [[ -x "$HOME/android-sdk/platform-tools/adb" ]]; then
        ADB_BIN="$HOME/android-sdk/platform-tools/adb"
    elif [[ -x "/home/circleci/android-sdk/platform-tools/adb" ]]; then
        ADB_BIN="/home/circleci/android-sdk/platform-tools/adb"
    else
        echo "Error: adb command not found and android-sdk not found in standard paths." >&2
        exit 1
    fi
fi

if [[ -z "$SERIAL" ]]; then
    echo "Error: Missing required -s <device_serial> argument." >&2
    echo "Available devices:" >&2
    "$ADB_BIN" devices -l >&2 || true
    echo "$USAGE" >&2
    exit 1
fi

echo "=== 1. Checking device connection for serial: $SERIAL ==="
DEVICE_LINE=$("$ADB_BIN" devices -l | grep -w "^$SERIAL" || true)
if [[ -z "$DEVICE_LINE" ]]; then
    echo "Error: Device '$SERIAL' not found in adb devices." >&2
    "$ADB_BIN" devices -l >&2
    exit 1
fi

if echo "$DEVICE_LINE" | grep -q "unauthorized"; then
    echo "Error: Device '$SERIAL' is unauthorized. Please accept USB debugging prompt on the device." >&2
    exit 1
fi

if echo "$DEVICE_LINE" | grep -q "offline"; then
    echo "Error: Device '$SERIAL' is offline." >&2
    exit 1
fi

DEVICE_API=$("$ADB_BIN" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')
DEVICE_MODEL=$("$ADB_BIN" -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')
DEVICE_MANUF=$("$ADB_BIN" -s "$SERIAL" shell getprop ro.product.manufacturer | tr -d '\r')
echo "Target device: $DEVICE_MANUF $DEVICE_MODEL (API $DEVICE_API, serial: $SERIAL)"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

DEBUG_APK="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$PROJECT_ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

if [[ ! -f "$DEBUG_APK" || ! -f "$TEST_APK" ]]; then
    echo "Error: APKs not found. Please build them first using:" >&2
    echo "  bash ./gradlew assembleDebug assembleDebugAndroidTest --console=plain" >&2
    exit 1
fi

if [[ "$SKIP_INSTALL" == false ]]; then
    echo "=== 2. Installing APKs on device ==="
    echo "Installing app-debug.apk..."
    "$ADB_BIN" -s "$SERIAL" install -r -t "$DEBUG_APK"
    echo "Installing app-debug-androidTest.apk..."
    "$ADB_BIN" -s "$SERIAL" install -r -t "$TEST_APK"
else
    echo "=== 2. Skipping APK installation (--skip-install) ==="
fi

echo "=== 3. Running targeted verification tests ==="
if [[ -n "$CUSTOM_CLASSES" ]]; then
    TEST_CLASSES="$CUSTOM_CLASSES"
else
    TEST_CLASSES="com.feiyu.notes.data.NotebookStoreTest,com.feiyu.notes.study.GeneratorTest,com.feiyu.notes.ui.UiFlowTest"
fi

OUTPUT_FILE="$PROJECT_ROOT/build/course-review-validation/device-test-$SERIAL.log"
mkdir -p "$(dirname "$OUTPUT_FILE")"

verify_runner_output() {
    local log_file="$1"
    local expected_classes="$2"

    if [[ ! -s "$log_file" ]]; then
        echo "Error: Test runner output is empty." >&2
        return 1
    fi

    if grep -q "FAILURES!!!" "$log_file" || \
       grep -q "INSTRUMENTATION_FAILED" "$log_file" || \
       grep -q "shortMsg=Process crashed" "$log_file" || \
       grep -q "Process crashed" "$log_file" || \
       grep -q "INSTRUMENTATION_ABORTED" "$log_file" || \
       grep -E -q "Failures: [1-9][0-9]*" "$log_file" || \
       grep -E -q "Errors: [1-9][0-9]*" "$log_file"; then
        echo "Error: Test runner reported failures or crashed." >&2
        return 1
    fi

    if ! grep -q "INSTRUMENTATION_CODE: -1" "$log_file"; then
        echo "Error: Test runner did not complete successfully (missing INSTRUMENTATION_CODE: -1)." >&2
        return 1
    fi

    local count
    count=$(grep -o -E 'OK \([0-9]+ tests?\)' "$log_file" | grep -o -E '[0-9]+' || true)
    if [[ -z "$count" ]]; then
        count=$(grep -o -E 'Tests run: [0-9]+' "$log_file" | head -1 | grep -o -E '[0-9]+' || true)
    fi

    if [[ -z "$count" || "$count" -le 0 ]]; then
        echo "Error: Zero tests were executed (count='$count')." >&2
        return 1
    fi

    IFS=',' read -ra ADDR <<< "$expected_classes"
    for cls in "${ADDR[@]}"; do
        cls=$(echo "$cls" | tr -d ' ')
        if ! grep -q "class=$cls" "$log_file"; then
            echo "Error: Expected test class '$cls' was not executed by runner." >&2
            return 1
        fi
    done

    echo "Validation passed: $count tests ran and passed across all expected classes ($expected_classes)."
    return 0
}

echo "Executing instrumentation tests: $TEST_CLASSES"
set +e
"$ADB_BIN" -s "$SERIAL" shell am instrument -w -r \
    -e class "$TEST_CLASSES" \
    com.feiyu.notes.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$OUTPUT_FILE"
set -e

if ! verify_runner_output "$OUTPUT_FILE" "$TEST_CLASSES"; then
    echo "=== Verification Result: FAILED ===" >&2
    echo "See log for details: $OUTPUT_FILE" >&2
    exit 1
fi

echo "=== Verification Result: SUCCESS ==="
echo "All targeted instrumented tests passed on device $SERIAL."
echo "Log saved to: $OUTPUT_FILE"
