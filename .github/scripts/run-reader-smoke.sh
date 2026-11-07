#!/usr/bin/env bash
set +e
mkdir -p reader-smoke-diagnostics/test-results
adb devices -l > reader-smoke-diagnostics/adb-devices.txt 2>&1
adb shell getprop > reader-smoke-diagnostics/device-properties.txt 2>&1
adb logcat -c

# Preserve emulator and instrumentation evidence whether the test passes or fails.
capture_reader_diagnostics() {
  status=$?
  trap - EXIT
  echo "connectedDebugAndroidTest exit code: $status" > reader-smoke-diagnostics/result.txt
  adb logcat -d -v threadtime > reader-smoke-diagnostics/logcat.txt 2>&1
  adb shell dumpsys activity activities > reader-smoke-diagnostics/activities.txt 2>&1
  adb shell dumpsys window windows > reader-smoke-diagnostics/windows.txt 2>&1
  adb shell ps -A > reader-smoke-diagnostics/processes.txt 2>&1
  cp -R app/build/outputs/androidTest-results/connected/. reader-smoke-diagnostics/test-results/ 2>/dev/null || true
  cp -R app/build/reports/androidTests/connected/. reader-smoke-diagnostics/test-results/ 2>/dev/null || true
  exit "$status"
}
trap capture_reader_diagnostics EXIT

timeout --signal=TERM --kill-after=30s 12m ./gradlew --no-daemon --stacktrace -Pandroid.testInstrumentationRunnerArguments.class=com.lagradost.quicknovel.ReaderActivitySmokeTest :app:connectedDebugAndroidTest
exit "$?"
