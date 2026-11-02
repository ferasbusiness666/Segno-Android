#!/bin/bash
# Makes the Baseline Profile on the emulator: music files for Library Songs, then the generator in
# the baselineprofile module. The profile ends up in profile-out/ (CI pushes it to ci-out).
set -u
python3 - <<'PY'
import math, struct, wave
with wave.open("/tmp/profile.wav", "w") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(8000)
    w.writeframes(b"".join(struct.pack("<h", int(2000 * math.sin(2 * math.pi * 330 * t / 8000))) for t in range(8000 * 20)))
PY
adb shell mkdir -p /sdcard/Music/Profile
adb push /tmp/profile.wav /sdcard/Music/Profile/base.wav
adb shell 'cd /sdcard/Music/Profile && for i in $(seq -w 1 120); do cp base.wav "Profile Song $i.wav"; done; rm base.wav'
sleep 20
./gradlew --stacktrace :app:generateReleaseBaselineProfile
status=$?
mkdir -p profile-out
cp -r app/src/release/generated/baselineProfiles/. profile-out/ 2>/dev/null || true
cp -r baselineprofile/build/outputs/androidTest-results profile-out/test-results 2>/dev/null || true
find baselineprofile/build/outputs -name "*.txt" -size -2M -exec cp {} profile-out/ \; 2>/dev/null || true
wc -l profile-out/*.txt 2>/dev/null || echo "no profile made"
exit $status
