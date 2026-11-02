#!/usr/bin/env python3
"""Smoothness of the release build on the CI emulator, measured with dumpsys gfxinfo.

Installs the release APK (what people get: R8, not debuggable), lets ProfileInstaller put in the
Baseline Profile and compiles it the way a phone does in the background, then measures four
moves: scrolling search results, scrolling Library Songs, opening Now Playing and opening the
queue. Each move is repeated and the frames are added up; the 90th percentile comes from the
summed frame-time histogram. The numbers go at the end of results.txt.

Usage: perf_check.py <release apk> <output dir>
"""
import math
import os
import re
import struct
import subprocess
import sys
import tempfile
import time
import wave
import xml.etree.ElementTree as ET

APK, OUT = sys.argv[1], sys.argv[2]
PKG = "io.github.ferasbusiness666.segno"
ACT = f"{PKG}/.ui.MainActivity"
os.makedirs(OUT, exist_ok=True)
RESULTS = os.path.join(OUT, "results.txt")
lines = []


def log(s):
    print(s, flush=True)
    lines.append(s)


def adb(*args, timeout=120):
    try:
        return subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout).stdout
    except subprocess.TimeoutExpired:
        return ""


def sh(cmd, timeout=120):
    return adb("shell", cmd, timeout=timeout)


def dump():
    for _ in range(3):
        sh("uiautomator dump /sdcard/perf.xml >/dev/null 2>&1")
        xml = sh("cat /sdcard/perf.xml")
        if "<hierarchy" in xml:
            try:
                return ET.fromstring(xml[xml.index("<hierarchy"):])
            except ET.ParseError:
                pass
        time.sleep(1)
    return None


def find(rid=None, text=None):
    root = dump()
    if root is None:
        return None
    for n in root.iter("node"):
        if rid and n.get("resource-id") != f"{PKG}:id/{rid}":
            continue
        if text and text.lower() not in (n.get("text") or "").lower():
            continue
        return dict(n.attrib)
    return None


def wait_for(fn, timeout):
    end = time.time() + timeout
    while time.time() < end:
        r = fn()
        if r is not None:
            return r
        time.sleep(1)
    return None


def tap(n):
    a, b, c, d = map(int, re.findall(r"\d+", n["bounds"]))
    sh(f"input tap {(a + c) // 2} {(b + d) // 2}")


def tap_id(rid, wait=10):
    n = wait_for(lambda: find(rid=rid), wait)
    if n is None:
        log(f"  perf: could not find {rid}")
        return False
    tap(n)
    return True


def gfx():
    """Frames, janky frames and the frame-time histogram since the last reset."""
    out = sh(f"dumpsys gfxinfo {PKG}")
    tot = re.search(r"Total frames rendered:\s*(\d+)", out)
    jank = re.search(r"Janky frames:\s*(\d+)", out)
    hist = {}
    h = re.search(r"HISTOGRAM:([^\n]*)", out)
    if h:
        for ms, count in re.findall(r"(\d+)ms=(\d+)", h.group(1)):
            hist[int(ms)] = hist.get(int(ms), 0) + int(count)
    return int(tot.group(1)) if tot else 0, int(jank.group(1)) if jank else 0, hist


class Score:
    def __init__(self, name):
        self.name, self.frames, self.janky, self.hist = name, 0, 0, {}

    def add(self, sample):
        f, j, h = sample
        self.frames += f
        self.janky += j
        for k, v in h.items():
            self.hist[k] = self.hist.get(k, 0) + v

    def p90(self):
        total = sum(self.hist.values())
        if total == 0:
            return None
        seen = 0
        for ms in sorted(self.hist):
            seen += self.hist[ms]
            if seen >= 0.9 * total:
                return ms
        return max(self.hist)

    def line(self):
        pct = f"{100.0 * self.janky / self.frames:.1f}%" if self.frames else "?"
        p90 = self.p90()
        return f"perf (release) {self.name}: frames={self.frames}, janky={self.janky} ({pct}), 90th percentile={p90 if p90 is not None else '?'}ms"


def measure(score, action):
    sh(f"dumpsys gfxinfo {PKG} reset")
    action()
    score.add(gfx())


size = re.search(r"(\d+)x(\d+)", sh("wm size"))
W, H = int(size.group(1)), int(size.group(2))


def swipes(n=8):
    for i in range(n):
        if i % 2 == 0:
            sh(f"input swipe {W // 2} {int(H * 0.78)} {W // 2} {int(H * 0.28)} 180")
        else:
            sh(f"input swipe {W // 2} {int(H * 0.28)} {W // 2} {int(H * 0.78)} 180")
        time.sleep(0.5)


# Install and compile like a phone would ------------------------------------------------------------
log("")
log("== Smoothness of the release build (gfxinfo)")
sh(f"am force-stop {PKG}.debug")  # the debug build from the main check may still be playing
adb("install", "-r", APK, timeout=300)
level = int(sh("getprop ro.build.version.sdk").strip() or "0")
for perm in ("android.permission.READ_MEDIA_AUDIO", "android.permission.POST_NOTIFICATIONS") if level >= 33 else ("android.permission.READ_EXTERNAL_STORAGE",):
    sh(f"pm grant {PKG} {perm}")

# A few hundred short songs, so Library Songs has something to scroll.
path = os.path.join(tempfile.gettempdir(), "perf.wav")
with wave.open(path, "w") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(8000)
    w.writeframes(b"".join(struct.pack("<h", int(2000 * math.sin(2 * math.pi * 330 * t / 8000))) for t in range(8000 * 2)))
sh("mkdir -p /sdcard/Music/Perf")
adb("push", path, "/sdcard/Music/Perf/base.wav")
sh("cd /sdcard/Music/Perf && for i in $(seq -w 1 240); do cp base.wav \"Perf Song $i.wav\"; done; rm base.wav", timeout=300)
if level < 30:
    sh("for f in /sdcard/Music/Perf/*.wav; do am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d \"file://$f\" >/dev/null 2>&1; done", timeout=600)


def scanned():
    out = sh("content query --uri content://media/external/audio/media --projection _id --where \"_data LIKE '%/Music/Perf/%'\"")
    return out.count("Row:")


have = 0
for _ in range(60):
    have = scanned()
    if have >= 200:
        break
    time.sleep(2)
log(f"songs for the Library scroll: {have}")

# First launch: ProfileInstaller writes the Baseline Profile; then compile as the phone would in
# the background (speed-profile), so the numbers match a phone that has had the app for a day.
sh(f"am start -W -n {ACT}")
time.sleep(6)
sh(f"am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE -n {PKG}/androidx.profileinstaller.ProfileInstallReceiver")
time.sleep(3)
sh(f"am force-stop {PKG}")
compiled = sh(f"cmd package compile -f -m speed-profile {PKG}", timeout=300).strip()
log(f"compile with the profile: {compiled or '?'}")
status = re.search(r"\[status=([^\]]+)\]", sh(f"dumpsys package dexopt | grep -A 2 '\\[{PKG}\\]'"))
log(f"dexopt status: {status.group(1) if status else '?'}")

starts = []
for _ in range(3):
    sh(f"am force-stop {PKG}")
    time.sleep(1)
    m = re.search(r"TotalTime:\s*(\d+)", sh(f"am start -W -n {ACT}"))
    if m:
        starts.append(int(m.group(1)))
    time.sleep(3)
starts.sort()
log(f"perf (release) cold start: {starts} ms, median {starts[len(starts) // 2] if starts else '?'} ms")

# Search results -------------------------------------------------------------------------------------
search = Score("scrolling search results")
tap_id("tabSearch")
time.sleep(1)
if tap_id("query"):
    time.sleep(2)
    sh("input text 'daft%spunk'")
    time.sleep(1)
    sh("input keyevent 66")
    if wait_for(lambda: find(rid="title"), 40) is not None:
        time.sleep(3)
        for _ in range(2):
            measure(search, swipes)
    else:
        log("  perf: no search results (YouTube unreachable?)")
log(search.line())

# Library Songs --------------------------------------------------------------------------------------
library = Score("scrolling Library Songs")
tap_id("tabLibrary")
time.sleep(1)
tap_id("chipSongs")
time.sleep(2)
if wait_for(lambda: find(rid="title", text="Perf Song"), 20) is not None:
    time.sleep(2)
    for _ in range(2):
        measure(library, swipes)
log(library.line())

# Now Playing and the queue --------------------------------------------------------------------------
now = Score("opening Now Playing")
queue = Score("opening the queue")
song = find(rid="title", text="Perf Song")
if song is not None:
    tap(song)
    if wait_for(lambda: find(rid="miniCard"), 15) is not None:
        time.sleep(2)
        for _ in range(3):
            measure(now, lambda: (tap_id("miniCard"), time.sleep(2)))
            measure(queue, lambda: (tap_id("npQueue"), time.sleep(2)))
            sh("input keyevent 4")  # close the queue
            time.sleep(1.5)
            sh("input keyevent 4")  # close Now Playing
            time.sleep(1.5)
    else:
        log("  perf: the song didn't start, so Now Playing wasn't measured")
log(now.line())
log(queue.line())

sh(f"am force-stop {PKG}")
with open(RESULTS, "a") as f:
    f.write("\n".join(lines) + "\n")
