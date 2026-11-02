#!/usr/bin/env python3
"""Drives Segno on an emulator with adb: cold start time, screenshots of every main screen,
a real YouTube song playing in the background, scrolling jank and memory.

Usage: emulator_check.py APK OUT_DIR
Writes OUT_DIR/results.txt and OUT_DIR/*.png. Exits non-zero only if the app crashes on launch
or background playback fails, so the screenshots are kept either way.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

APK, OUT = sys.argv[1], sys.argv[2]
PKG = "io.github.ferasbusiness666.segno.debug"
ACT = PKG + "/io.github.ferasbusiness666.segno.ui.MainActivity"
os.makedirs(OUT, exist_ok=True)
results = []
failures = []


def log(msg):
    print(msg, flush=True)
    results.append(msg)


def run(args, timeout=120, check=False):
    p = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    if check and p.returncode != 0:
        raise RuntimeError(f"{args}: {p.stderr}")
    return p.stdout


def adb(*args, timeout=120):
    return run(["adb", *args], timeout=timeout)


def sh(cmd, timeout=120):
    return adb("shell", cmd, timeout=timeout)


def api():
    return int(sh("getprop ro.build.version.sdk").strip())


def shot(name):
    dump()  # clears any system "isn't responding" dialog first
    path = os.path.join(OUT, name + ".png")
    with open(path, "wb") as f:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=f, timeout=60)
    print("screenshot", path, flush=True)


def dump():
    for _ in range(4):
        sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
        xml = sh("cat /sdcard/ui.xml")
        if "<hierarchy" in xml:
            try:
                root = ET.fromstring(xml[xml.index("<?xml") if "<?xml" in xml else xml.index("<hierarchy"):])
            except ET.ParseError:
                root = None
            if root is not None and dismiss_system_dialog(root):
                time.sleep(2)
                continue
            if root is not None:
                return root
        time.sleep(1)
    return None


def dismiss_system_dialog(root):
    """The emulator's own apps sometimes stop answering ("Pixel Launcher isn't responding").
    Press Wait so the dialog doesn't cover Segno."""
    texts = [(n.get("text") or "") for n in root.iter("node")]
    if not any("isn't responding" in t or "keeps stopping" in t for t in texts):
        return False
    for n in root.iter("node"):
        if (n.get("text") or "") in ("Wait", "Close app") and n.get("text") == "Wait" or n.get("resource-id") == "android:id/aerr_wait":
            a, b, c, d = map(int, re.findall(r"\d+", n.get("bounds")))
            sh(f"input tap {(a + c) // 2} {(b + d) // 2}")
            print("dismissed a system dialog", flush=True)
            return True
    sh("input keyevent 4")
    return True


def nodes(root):
    return [] if root is None else list(root.iter("node"))


def find(rid=None, text=None, desc=None, root=None, index=0):
    root = root if root is not None else dump()
    hits = []
    for n in nodes(root):
        if rid and n.get("resource-id") != f"{PKG}:id/{rid}":
            continue
        if text and text.lower() not in (n.get("text") or "").lower():
            continue
        if desc and desc.lower() not in (n.get("content-desc") or "").lower():
            continue
        # A plain dict: ElementTree elements without children count as False in "if".
        hits.append(dict(n.attrib))
    return hits[index] if len(hits) > index else None


def center(n):
    a, b, c, d = map(int, re.findall(r"\d+", n["bounds"]))
    return (a + c) // 2, (b + d) // 2


def tap(n):
    x, y = center(n)
    sh(f"input tap {x} {y}")


def tap_id(rid, wait=10, **kw):
    n = wait_for(lambda: find(rid=rid, **kw), wait)
    if n is None and rid.startswith("tab"):
        # Segno hides the tab bar while the keyboard is up.
        hide_keyboard()
        n = wait_for(lambda: find(rid=rid, **kw), wait)
    if n is None:
        log(f"  could not find {rid}")
        return False
    tap(n)
    return True


def wait_for(fn, timeout):
    end = time.time() + timeout
    while time.time() < end:
        r = fn()
        # Element objects without children are falsy, so compare with None.
        if r is not None and r is not False:
            return r
        time.sleep(1)
    return None


def back():
    sh("input keyevent 4")
    time.sleep(1)


def keyboard_up():
    return re.search(r"mInputShown=true", sh("dumpsys input_method")) is not None


def hide_keyboard():
    """Back closes the keyboard. Press it once, and only when the keyboard is up and Segno has
    hidden its tab bar for it: an extra back on Search goes Home, and on Home it leaves the app."""
    if keyboard_up() and find(rid="tabs") is None:
        back()


def type_text(t):
    safe = t.replace(" ", "%s").replace("&", "\\&").replace("?", "\\?").replace("=", "\\=")
    sh(f"input text '{safe}'")


def crashed():
    out = adb("logcat", "-d", "-b", "crash")
    return PKG in out


# Install -----------------------------------------------------------------------------------------
level = api()
log(f"== Segno emulator check, API {level}")
log(f"screen: {sh('wm size').strip()} / {sh('wm density').strip()}")
adb("install", "-r", APK, timeout=300)
adb("logcat", "-c")
if level >= 33:
    sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")

# Cold start of the release build (R8, as people get it) ------------------------------------------
def cold_starts(pkg, act, label):
    times = []
    for _ in range(5):
        sh(f"am force-stop {pkg}")
        time.sleep(2)
        m = re.search(r"TotalTime:\s*(\d+)", sh(f"am start -W -n {act}"))
        if m:
            times.append(int(m.group(1)))
        time.sleep(3)
    sh(f"am force-stop {pkg}")
    if times:
        rest = sorted(times[1:])
        log(f"cold start, {label} (am start -W TotalTime): first {times[0]} ms, then {rest} ms, median {rest[len(rest) // 2] if rest else times[0]} ms")
    else:
        log(f"cold start, {label}: no TotalTime reported")


release_apk = os.path.join(os.path.dirname(APK), "segno-release-unsigned-key.apk")
if os.path.exists(release_apk):
    adb("install", "-r", release_apk, timeout=300)
    cold_starts("io.github.ferasbusiness666.segno", "io.github.ferasbusiness666.segno/.ui.MainActivity", "release build")
    adb("uninstall", "io.github.ferasbusiness666.segno")

# Cold start --------------------------------------------------------------------------------------
times = []
for i in range(4):
    sh(f"am force-stop {PKG}")
    time.sleep(2)
    out = sh(f"am start -W -n {ACT}")
    m = re.search(r"TotalTime:\s*(\d+)", out)
    if m:
        times.append(int(m.group(1)))
    time.sleep(3)
if times:
    first, warm = times[0], sorted(times[1:])
    log(f"cold start, debug build (am start -W TotalTime): first {first} ms, then {warm} ms, median {warm[len(warm) // 2] if warm else first} ms")
else:
    log("cold start: no TotalTime reported")
time.sleep(2)
if crashed():
    failures.append("crash on launch")
    log("CRASH on launch:\n" + adb("logcat", "-d", "-b", "crash")[-4000:])

# Home (empty) ------------------------------------------------------------------------------------
shot("01-home-empty")

# Library -----------------------------------------------------------------------------------------
def selected_chip():
    for rid in ("chipSongs", "chipPlaylists", "chipAlbums", "chipArtists"):
        n = find(rid=rid)
        if n is not None and n.get("selected") == "true":
            return rid
    return None


tap_id("tabLibrary")
time.sleep(2)
log(f"library opens on: {selected_chip()} (should be chipSongs)")
shot("02-library-songs-no-access")
tap_id("chipPlaylists")
time.sleep(2)
shot("03-library-playlists")
# Some songs on the phone, then allow access.
try:
    import wave, struct, math, tempfile
    for k, name in enumerate(["Lumen - Paper Planes", "Kora Sun - Desert Bloom", "Atlas Echo - Northern Lights", "Segno CI - Long Tone"]):
        path = os.path.join(tempfile.gettempdir(), f"song{k}.wav")
        secs = 120 if k == 3 else 3
        with wave.open(path, "w") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(8000)
            frames = b"".join(struct.pack("<h", int(3000 * math.sin(2 * math.pi * (220 + 110 * k) * t / 8000))) for t in range(8000 * secs))
            w.writeframes(frames)
        adb("push", path, f"/sdcard/Music/{name}.wav")
        sh(f"am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d 'file:///sdcard/Music/{name}.wav' >/dev/null 2>&1")
except Exception as e:  # noqa: BLE001
    log(f"  sample songs: {e}")
perm = "android.permission.READ_MEDIA_AUDIO" if level >= 33 else "android.permission.READ_EXTERNAL_STORAGE"
sh(f"pm grant {PKG} {perm}")
time.sleep(4)
tap_id("chipSongs")
time.sleep(3)
shot("04-library-songs")
tap_id("chipAlbums")
time.sleep(2)
shot("05-library-albums")
# The last page picked comes back after leaving Library.
tap_id("tabHome")
time.sleep(1)
tap_id("tabLibrary")
time.sleep(2)
log(f"library after Home and back: {selected_chip()} (should be chipAlbums)")
tap_id("chipSongs")
time.sleep(1)

# Search ------------------------------------------------------------------------------------------
tap_id("tabSearch")
time.sleep(1)
hide_keyboard()
shot("06-search-idle")
tap_id("query")
time.sleep(3)  # let the keyboard come up, or the typing gets lost
for attempt in range(3):
    type_text("daft punk get lucky")
    time.sleep(1)
    q = find(rid="query")
    if q is not None and "lucky" in (q.get("text") or ""):
        break
    tap_id("query")
    time.sleep(2)
sh("input keyevent 66")
got = wait_for(lambda: find(rid="title"), 40)
time.sleep(3)
hide_keyboard()
time.sleep(1)
log(f"search results shown: {'yes' if got else 'NO'}")
shot("07-search-results")
if not got:
    failures.append("no search results")

# Play a song -------------------------------------------------------------------------------------
playing = False
if got:
    tap(got)
    mini = wait_for(lambda: find(rid="miniTitle"), 20)
    started = wait_for(lambda: "playing=true" in adb("logcat", "-d", "-s", "SegnoCI:I"), 60)
    playing = bool(started)
    log(f"playback started: {'yes' if playing else 'NO'}")
    time.sleep(2)
    shot("08-mini-player")

    # Now Playing, queue, sleep timer.
    if tap_id("miniCard"):
        time.sleep(3)
        shot("09-now-playing")
        if tap_id("npQueue"):
            time.sleep(3)
            shot("10-queue")
            back()
        if tap_id("npSleep"):
            time.sleep(2)
            shot("11-sleep-timer")
            back()
        back()  # close Now Playing

# Background playback -----------------------------------------------------------------------------
def background_check(label):
    """Home screen for 30 s: the session must stay PLAYING, the position must move on, and the
    media notification must be there."""
    adb("logcat", "-c")
    sh("input keyevent 3")  # Home
    time.sleep(2)
    time.sleep(30)
    ms1 = sh("dumpsys media_session")
    lines = re.findall(r"pos=(\d+) playing=(\w+) state=(\d+)", adb("logcat", "-d", "-s", "SegnoCI:I"))
    pos = [(int(p), pl) for p, pl, _ in lines]
    st = re.findall(r"state=PlaybackState \{state=(\w+)\((\d)\)|state=PlaybackState \{state=(\d+)", ms1)
    states = [x[1] or x[2] for x in st]
    log(f"background ({label}): media_session has Segno: {PKG in ms1}; playback states: {states}")
    log(f"background ({label}): positions every 5 s with the app in the background: {[p for p, _ in pos]}")
    advancing = len(pos) >= 3 and pos[-1][0] - pos[0][0] >= 15000 and all(pl == "true" for _, pl in pos[-3:])
    playing_state = "3" in states
    notif = sh("dumpsys notification --noredact")
    has_notif = PKG in notif and ("MediaStyle" in notif or "mediaSession" in notif or "transport" in notif)
    log(f"background ({label}): PLAYING={playing_state}, position advancing={advancing}, media notification={has_notif}")
    with open(os.path.join(OUT, f"media_session-{label}.txt"), "w") as f:
        f.write(ms1)
    sh("cmd statusbar expand-notifications")
    time.sleep(2)
    shot(f"12-notification-{label}")
    sh("cmd statusbar collapse")
    adb("shell", "am", "start", "-n", ACT)
    time.sleep(3)
    return playing_state and advancing and has_notif


if playing:
    if not background_check("youtube"):
        failures.append("background playback (YouTube) check failed")
else:
    # Usually YouTube refusing the CI runner's data-center address, not a bug: report it.
    why = re.findall(r"Couldn't get the audio of \S+: (.*)", adb("logcat", "-d", "-s", "Segno:W"))
    cause = re.findall(r"Caused by: (org\.schabi\S+: [^\n]*)", adb("logcat", "-d"))
    log(f"YouTube playback on this runner: did not start. Segno said: {why[-1] if why else '?'}")
    log(f"  extractor error: {cause[-1] if cause else '?'}")
    if (cause or why) and not any("bot" in c.lower() or "sign in" in c.lower() for c in cause + why):
        failures.append("YouTube song did not play (not a bot check)")
    elif not (cause or why):
        log("  warning: no extractor error was logged; the player is checked with a local file below")
    # The playback service itself is checked with a music file from the phone instead.
    adb("shell", "am", "start", "-n", ACT)  # make sure Segno is in front
    time.sleep(2)
    tap_id("tabLibrary")
    time.sleep(1)
    tap_id("chipSongs")
    time.sleep(2)
    adb("logcat", "-c")
    if tap_id("title", wait=30, text="Long Tone"):
        ok = wait_for(lambda: "playing=true" in adb("logcat", "-d", "-s", "SegnoCI:I"), 30)
        log(f"local file playback started: {'yes' if ok else 'NO'}")
        if ok:
            time.sleep(2)
            shot("08b-mini-player-local")
            if not background_check("local-file"):
                failures.append("background playback (local file) check failed")
        else:
            failures.append("local file did not play")
    else:
        failures.append("local test song not found")

# Jank: scroll the search results -----------------------------------------------------------------
tap_id("tabSearch")
time.sleep(2)
sh(f"dumpsys gfxinfo {PKG} reset")
size = re.search(r"(\d+)x(\d+)", sh("wm size"))
w, h = int(size.group(1)), int(size.group(2))
for i in range(12):
    if i % 2 == 0:
        sh(f"input swipe {w // 2} {int(h * 0.75)} {w // 2} {int(h * 0.3)} 250")
    else:
        sh(f"input swipe {w // 2} {int(h * 0.3)} {w // 2} {int(h * 0.75)} 250")
    time.sleep(0.6)
gfx = sh(f"dumpsys gfxinfo {PKG}")
tot = re.search(r"Total frames rendered:\s*(\d+)", gfx)
jank = re.search(r"Janky frames:\s*(\d+)\s*\(([\d.]+)%\)", gfx)
p90 = re.search(r"90th percentile:\s*(\d+)ms", gfx)
log(f"jank after scrolling search results: frames={tot.group(1) if tot else '?'}, janky={jank.group(1) + ' (' + jank.group(2) + '%)' if jank else '?'}, 90th percentile={p90.group(1) + 'ms' if p90 else '?'}")
# The same scroll in Android's own Settings app, to show what this emulator manages at all.
sh("am start -a android.settings.SETTINGS")
time.sleep(4)
settings_pkg = "com.android.settings"
sh(f"dumpsys gfxinfo {settings_pkg} reset")
for i in range(12):
    if i % 2 == 0:
        sh(f"input swipe {w // 2} {int(h * 0.75)} {w // 2} {int(h * 0.3)} 250")
    else:
        sh(f"input swipe {w // 2} {int(h * 0.3)} {w // 2} {int(h * 0.75)} 250")
    time.sleep(0.6)
sgfx = sh(f"dumpsys gfxinfo {settings_pkg}")
stot = re.search(r"Total frames rendered:\s*(\d+)", sgfx)
sjank = re.search(r"Janky frames:\s*(\d+)\s*\(([\d.]+)%\)", sgfx)
sp90 = re.search(r"90th percentile:\s*(\d+)ms", sgfx)
log(f"for comparison, Android Settings with the same swipes: frames={stot.group(1) if stot else '?'}, janky={sjank.group(1) + ' (' + sjank.group(2) + '%)' if sjank else '?'}, 90th percentile={sp90.group(1) + 'ms' if sp90 else '?'}")
sh("am force-stop com.android.settings")
adb("shell", "am", "start", "-n", ACT)
time.sleep(2)

mem = sh(f"dumpsys meminfo {PKG}")
pss = re.search(r"TOTAL PSS:\s*(\d+)", mem) or re.search(r"TOTAL\s+(\d+)", mem)
log(f"memory (TOTAL PSS): {int(pss.group(1)) // 1024 if pss else '?'} MB")
with open(os.path.join(OUT, "gfxinfo.txt"), "w") as f:
    f.write(gfx)
with open(os.path.join(OUT, "meminfo.txt"), "w") as f:
    f.write(mem)

# Home with history, Import, Settings, empty states -----------------------------------------------
tap_id("tabHome")
time.sleep(3)
shot("13-home")
if tap_id("tileLoved"):
    time.sleep(2)
    shot("14-loved-empty")
    back()
if tap_id("tileImport"):
    time.sleep(2)
    shot("15-import")
    tap_id("paste")
    type_text("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
    hide_keyboard()
    tap_id("find")
    wait_for(lambda: find(rid="foundTitle"), 40)
    time.sleep(2)
    shot("16-import-found")
    back()
tap_id("tabHome")
time.sleep(1)
if tap_id("settings"):
    time.sleep(2)
    shot("17-settings")
    back()

# Home's Songs tile opens Library on Songs.
tap_id("tabLibrary")
time.sleep(1)
tap_id("chipPlaylists")
time.sleep(1)
tap_id("tabHome")
time.sleep(2)
if tap_id("tileSongs"):
    time.sleep(2)
    log(f"Home's Songs tile opens: {selected_chip()} (should be chipSongs)")
    shot("13b-songs-tile-opens-library")
tap_id("tabHome")
time.sleep(1)

# Landscape Now Playing ---------------------------------------------------------------------------
if tap_id("miniCard"):
    sh("settings put system accelerometer_rotation 0")
    sh("settings put system user_rotation 1")
    time.sleep(3)
    shot("18-now-playing-landscape")
    sh("settings put system user_rotation 0")
    time.sleep(2)
    back()

# Arabic titles: search through a shared text (adb can't type Arabic).
sh(f"am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT 'فيروز' -n {ACT}")
time.sleep(4)
wait_for(lambda: find(rid="title"), 40)
time.sleep(4)
hide_keyboard()
shot("19-search-arabic")

if crashed():
    failures.append("crash during the run")
    log("CRASH:\n" + adb("logcat", "-d", "-b", "crash")[-6000:])

with open(os.path.join(OUT, "logcat.txt"), "w") as f:
    f.write(adb("logcat", "-d", "-t", "3000"))
log("failures: " + (", ".join(failures) if failures else "none"))
with open(os.path.join(OUT, "results.txt"), "w") as f:
    f.write("\n".join(results) + "\n")
sys.exit(1 if failures else 0)
