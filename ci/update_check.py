#!/usr/bin/env python3
"""The in-app update, end to end, on the CI emulator.

Installs the release build (signed with the test key in app/debug.keystore) and uses its
CI-only hook (builds without the real key) to offer APKs copied into Segno's files folder:
  1. the same APK: Update, Android's "install unknown apps" step, then a refusal (not newer);
  2. the debug APK: refused (another package);
  3. a build one version higher: Update, Android's install question, and it's installed.

Usage: update_check.py <release apk> <release apk one version higher> <debug apk> <output dir>
The versions come from version.txt and next-version.txt next to the release APK.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

APK, APK_NEXT, APK_DEBUG, OUT = sys.argv[1:5]
_here = os.path.dirname(APK)
CUR = open(os.path.join(_here, "version.txt")).read().strip()
NEXT = open(os.path.join(_here, "next-version.txt")).read().strip()
PKG = "io.github.ferasbusiness666.segno"
ACT = f"{PKG}/.ui.MainActivity"
FILES = f"/sdcard/Android/data/{PKG}/files"
os.makedirs(OUT, exist_ok=True)
lines = []
failures = []


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
        sh("uiautomator dump /sdcard/upd.xml >/dev/null 2>&1")
        xml = sh("cat /sdcard/upd.xml")
        if "<hierarchy" in xml:
            try:
                return ET.fromstring(xml[xml.index("<hierarchy"):])
            except ET.ParseError:
                pass
        time.sleep(1)
    return None


def texts():
    root = dump()
    return [] if root is None else [(n.get("text") or "") for n in root.iter("node") if (n.get("text") or "").strip()]


def find_text(*want, exact=True, other_app=False):
    root = dump()
    if root is None:
        return None
    for n in root.iter("node"):
        t = (n.get("text") or "").strip().lower()
        if other_app and n.get("package") == PKG:
            continue
        if any((t == w.lower()) if exact else (w.lower() in t) for w in want):
            return dict(n.attrib)
    return None


def wait_text(*want, timeout=20, exact=True, other_app=False):
    end = time.time() + timeout
    while time.time() < end:
        n = find_text(*want, exact=exact, other_app=other_app)
        if n is not None:
            return n
        time.sleep(1)
    return None


def tap(n):
    if n is None:
        log("  update: a button wasn't there")
        return
    a, b, c, d = map(int, re.findall(r"\d+", n["bounds"]))
    sh(f"input tap {(a + c) // 2} {(b + d) // 2}")


def shot(name):
    sh(f"screencap -p /sdcard/{name}.png")
    adb("pull", f"/sdcard/{name}.png", os.path.join(OUT, f"{name}.png"))


def version():
    m = re.search(r"versionName=(\S+)", sh(f"dumpsys package {PKG}"))
    return m.group(1) if m else "?"


def refusal(text, verdict, timeout=30):
    """The refusal on screen (a short message) or, if it already went, in the log."""
    end = time.time() + timeout
    while time.time() < end:
        if find_text(text, exact=False) is not None:
            return "on screen"
        if f"Update refused: {verdict}" in adb("logcat", "-d", "-s", "Segno:W"):
            return "in the log"
        time.sleep(1)
    return None


def offer(apk_name, ver):
    sh(f"am start -n {ACT} -a {PKG}.TEST_UPDATE --es apk {apk_name} --es version {ver}")


log("")
log("== In-app update (release build, CI hook)")
level = int(sh("getprop ro.build.version.sdk").strip() or "0")
adb("install", "-r", APK, timeout=300)
sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS" if level >= 33 else "true")
sh(f"appops set {PKG} REQUEST_INSTALL_PACKAGES default")
sh(f"am start -W -n {ACT}")
time.sleep(3)
sh(f"mkdir -p {FILES}")
adb("push", APK, f"{FILES}/same.apk")
adb("push", APK_DEBUG, f"{FILES}/other.apk")
adb("push", APK_NEXT, f"{FILES}/next.apk")
log(f"installed: {version()}; files for the test: {sh(f'ls {FILES}').split()}")
adb("logcat", "-c")

# 1. Same version: offer, the install permission step, then "not newer".
offer("same.apk", CUR)
dialog = wait_text(f"Segno {CUR} is out")
shot("21-update-offer")
if dialog is None:
    failures.append("update: no offer dialog")
else:
    tap(wait_text("Update"))
    explain = wait_text("Allow Segno to install its updates", timeout=10)
    shot("21b-update-allow")
    log(f"install permission explained: {explain is not None}")
    if explain is None:
        failures.append("update: no explanation before Android's install setting")
    else:
        tap(wait_text("Open settings"))
        time.sleep(3)
        shot("21c-update-android-setting")
        sh(f"appops set {PKG} REQUEST_INSTALL_PACKAGES allow")
        time.sleep(1)
        sh("input keyevent 4")
        refused = refusal("isn't newer", "NotNewer")
        shot("21d-update-not-newer")
        log(f"same version refused: {refused or 'no'}")
        if refused is None:
            failures.append("update: the same version wasn't refused")

# 2. Another app (the debug build): refused.
time.sleep(4)
offer("other.apk", "9.9.9")
if wait_text("Segno 9.9.9 is out") is not None:
    tap(wait_text("Update"))
    refused = refusal("isn't Segno", "WrongApp")
    shot("21e-update-other-app")
    log(f"other app refused: {refused or 'no'}")
    if refused is None:
        failures.append("update: another app's APK wasn't refused")
else:
    failures.append("update: no offer for the second test")

# 3. The real thing: one version higher, same key.
time.sleep(4)
offer("next.apk", NEXT)
if wait_text(f"Segno {NEXT} is out") is not None:
    tap(wait_text("Update"))
    ask = wait_text("Update", "Install", timeout=40, other_app=True)
    shot("21f-update-android-asks")
    log(f"Android's install question: {texts()[:6]}")
    if ask is not None:
        tap(ask)
    end = time.time() + 90
    while time.time() < end and version() != NEXT:
        time.sleep(3)
    now = version()
    log(f"after the update: {now}")
    time.sleep(2)
    shot("21g-update-done")
    if now != NEXT:
        failures.append(f"update: still {now} after installing {NEXT}")
else:
    failures.append(f"update: no offer for {NEXT}")

log(f"update check: {'ok' if not failures else ', '.join(failures)}")
with open(os.path.join(OUT, "results.txt"), "a") as f:
    f.write("\n".join(lines) + "\n")
sys.exit(1 if failures else 0)
