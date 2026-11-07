#!/bin/bash
# The emulator checks on the debug build, the in-app update on the release build, and on API 34
# also the release build's smoothness numbers.
api="$1"
python3 ci/emulator_check.py build-out/segno-debug.apk emulator-out
status=$?
if [ "$api" = "34" ]; then
  python3 ci/perf_check.py build-out/segno-release-before.apk build-out/segno-release.apk emulator-out || status=1
fi
python3 ci/update_check.py build-out/segno-release.apk build-out/segno-release-next.apk build-out/segno-debug.apk emulator-out || status=1
exit $status
