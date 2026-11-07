#!/usr/bin/env python3
"""Drives Segno on an emulator with adb: cold start time, screenshots of every main screen,
a real YouTube song playing in the background, scrolling jank and memory.

Usage: emulator_check.py APK OUT_DIR
Writes OUT_DIR/results.txt and OUT_DIR/*.png. Exits non-zero only if the app crashes on launch
or background playback fails, so the screenshots are kept either way.
"""
import json
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
    try:
        p = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        # A command that hangs (an app that won't start, say) is a finding, not the end of the run.
        print(f"  timed out: {' '.join(args)[:120]}", flush=True)
        return ""
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
else:
    # The same words on plain YouTube, then back to YouTube Music for the rest of the run.
    if tap_id("chipVideos"):
        yt = wait_for(lambda: find(rid="title"), 40)
        time.sleep(3)
        chip = find(rid="chipVideos")
        log(f"YouTube results shown: {'yes' if yt else 'NO'}; YouTube button selected: {chip is not None and chip.get('selected') == 'true'}")
        shot("07b-search-youtube")
        if not yt:
            failures.append("no YouTube search results")
        tap_id("chipMusic")
        got = wait_for(lambda: find(rid="title"), 40)
        time.sleep(2)

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

# Delete from phone -------------------------------------------------------------------------------
def node_at(root, full_rid=None, text=None):
    for n in nodes(root):
        if full_rid and n.get("resource-id") != full_rid:
            continue
        if text and (n.get("text") or "").strip().lower() != text.lower():
            continue
        return dict(n.attrib)
    return None


def delete_check():
    if level < 29:
        sh(f"pm grant {PKG} android.permission.WRITE_EXTERNAL_STORAGE")
    adb("shell", "am", "start", "-n", ACT)
    time.sleep(2)
    tap_id("tabLibrary")
    time.sleep(1)
    tap_id("chipSongs")
    time.sleep(2)
    row = wait_for(lambda: find(rid="title", text="Desert Bloom"), 20)
    if row is None:
        return "test song not in Library"
    y = center(row)[1]
    root = dump()
    more = None
    for n in nodes(root):
        if n.get("resource-id") == f"{PKG}:id/more":
            a, b, c, d = map(int, re.findall(r"\d+", n.get("bounds")))
            if b <= y <= d:
                more = dict(n.attrib)
    if more is None:
        return "no menu button on the song"
    tap(more)
    time.sleep(2)
    item = wait_for(lambda: find(rid=None, text="Delete from phone"), 10)
    if item is None:
        shot("20-delete-menu-missing")
        return "no Delete from phone in the menu"
    tap(item)
    time.sleep(3)
    shot("20-delete-dialog")
    root = dump()
    texts = [(n.get("text") or "") for n in nodes(root)]
    log(f"delete dialog says: {[t for t in texts if t.strip()][:6]}")
    ok = (node_at(root, full_rid="android:id/button1")
          or node_at(root, text="Allow") or node_at(root, text="Delete"))
    if ok is None:
        return "no delete dialog"
    tap(ok)
    time.sleep(4)
    shot("20b-after-delete")
    gone = find(rid="title", text="Desert Bloom") is None
    still = "Desert Bloom" in sh("ls /sdcard/Music/")
    log(f"after delete: gone from Library={gone}, file still on the phone={still}")
    if not gone or still:
        return f"song not deleted (in list={not gone}, file={still})"
    return None


# Lyrics: a .lrc next to the song (read on Android 8-10) and saved lyrics (every version), on the
# local test song; the line being sung lights up and a tap on a line plays from there.
def fnv64(text):
    h = 0xcbf29ce484222325
    for byte in text.encode("utf-8"):
        h ^= byte
        h = (h * 0x100000001b3) & 0xFFFFFFFFFFFFFFFF
    return "%016x" % h


def lyrics_check():
    import tempfile
    name = "Segno CI - Long Tone"
    def lrc(word):
        return "".join(f"[{(i * 4) // 60:02d}:{(i * 4) % 60:02d}.00]{word} line {i + 1}\n" for i in range(30))
    side = os.path.join(tempfile.gettempdir(), "side.lrc")
    with open(side, "w") as f:
        f.write(lrc("Sidecar"))
    adb("push", side, f"/sdcard/Music/{name}.lrc")
    saved = os.path.join(tempfile.gettempdir(), "saved.lrc")
    with open(saved, "w") as f:
        f.write("[re:Segno]\n" + lrc("Saved"))
    # Segno's saved lyrics: the cleaned title and artist and the length, hashed (Store.cacheName).
    cache = fnv64("segnocilongtone\n\n120") + ".lrc"
    adb("push", saved, "/data/local/tmp/saved.lrc")
    sh("chmod 644 /data/local/tmp/saved.lrc")
    sh(f"run-as {PKG} mkdir -p files/lyrics")
    sh(f"run-as {PKG} cp /data/local/tmp/saved.lrc files/lyrics/{cache}")
    adb("shell", "am", "start", "-n", ACT)
    time.sleep(2)
    tap_id("tabLibrary")
    time.sleep(1)
    tap_id("chipSongs")
    time.sleep(2)
    adb("logcat", "-c")
    if not tap_id("title", wait=20, text="Long Tone"):
        return "test song not in Library"
    wait_for(lambda: "playing=true" in adb("logcat", "-d", "-s", "SegnoCI:I") or None, 20)
    if not tap_id("miniCard"):
        return "no mini player"
    time.sleep(2)
    # Paused: while the time ticks the screen never goes idle and uiautomator can't read it. The
    # position log keeps coming every 5 s, paused or not.
    sh("input keyevent 127")
    time.sleep(1)
    if not tap_id("npLyrics"):
        return "no lyrics button"
    lines = wait_for(lambda: [n.get("text") for n in nodes(dump()) if " line " in (n.get("text") or "")] or None, 20)
    time.sleep(3)
    shot("23-lyrics")
    if not lines:
        texts = [(n.get("text") or "") for n in nodes(dump()) if (n.get("text") or "").strip()]
        log(f"lyrics screen shows: {texts[:12]}")
        return "no lyrics lines"
    which = "the .lrc beside the song" if lines[0].startswith("Sidecar") else "saved lyrics"
    log(f"lyrics shown from {which}: {lines[:4]}")
    if level < 29 and which != "the .lrc beside the song":
        return "the .lrc beside the song wasn't read"
    # A tap on a line plays from there.
    target = find(text="line 6")
    if target is None:
        target = next((dict(n.attrib) for n in nodes(dump()) if " line " in (n.get("text") or "")), None)
    if target is not None:
        want = (int(re.search(r"line (\d+)", target["text"]).group(1)) - 1) * 4000
        adb("logcat", "-c")
        tap(target)
        time.sleep(6)
        pos = [int(p) for p in re.findall(r"pos=(\d+)", adb("logcat", "-d", "-s", "SegnoCI:I"))]
        log(f"tapped '{target['text']}' (starts at {want} ms): position after {pos[-2:] if pos else '?'}")
        if pos and not (want - 500 <= pos[-1] <= want + 9000):
            return "a tap on a line didn't play from there"
        shot("23b-lyrics-after-tap")
    return None


def close_now_playing():
    """Back to the cover, and out of Now Playing, whatever happened."""
    sh("input keyevent 127")
    time.sleep(1)
    if find(rid="lyricsBar") is not None or find(rid="lyricsStatus") is not None:
        tap_id("npLyrics", wait=3)
    for _ in range(2):
        if find(rid="npClose") is not None:
            back()
            time.sleep(1)
    sh("rm -f '/sdcard/Music/Segno CI - Long Tone.lrc'")


why_lyrics = lyrics_check()
close_now_playing()
log(f"lyrics: {'ok' if why_lyrics is None else why_lyrics}")
if why_lyrics:
    failures.append(f"lyrics: {why_lyrics}")

why_not = delete_check()
log(f"delete from phone: {'ok' if why_not is None else why_not}")
if why_not:
    failures.append(f"delete from phone: {why_not}")

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

# Main-thread disk and network work, from StrictMode (debug builds keep a list on API 28+).
strict = sh(f"run-as {PKG} cat files/strictmode.txt 2>/dev/null")
if level >= 28:
    found = [l for l in strict.splitlines() if l.strip()]
    log(f"StrictMode on the main thread: {len(found)} warnings")
    counts = {}
    for l in found:
        counts[l] = counts.get(l, 0) + 1
    for l, n in sorted(counts.items(), key=lambda kv: -kv[1])[:12]:
        log(f"  {n} x {l}")

# Backup: some data made in the app, saved, the app's data cleared, restored, saved again, compared.
def menu_on(title_text, item_text):
    row = wait_for(lambda: find(rid="title", text=title_text), 15)
    if row is None:
        log(f"  backup: no row {title_text}")
        return False
    y = center(row)[1]
    more = None
    for n in nodes(dump()):
        if n.get("resource-id") == f"{PKG}:id/more":
            a, b, c, d = map(int, re.findall(r"\d+", n.get("bounds")))
            if b <= y <= d:
                more = dict(n.attrib)
    if more is None:
        return False
    tap(more)
    time.sleep(2)
    item = wait_for(lambda: find(text=item_text), 10)
    if item is None:
        log(f"  backup: no menu item {item_text}")
        back()
        return False
    tap(item)
    time.sleep(2)
    return True


def song_keys(songs):
    return [(s.get("source"), s.get("youtube_id", ""), s.get("spotify_id", ""), s.get("title", ""), s.get("artist", ""), s.get("path", "")) for s in songs]


def backup_view(path):
    with open(path) as f:
        d = json.load(f)
    return {
        "version": d.get("segno_backup"),
        "settings": d.get("settings"),
        "playlists": sorted((p["name"], song_keys(p.get("songs", []))) for p in d.get("playlists", [])),
        "loved": song_keys(d.get("loved", [])),
    }


def hook(mode, name):
    sh(f"am start -n {ACT} -a io.github.ferasbusiness666.segno.TEST_BACKUP --es mode {mode} --es file {name}")


def backup_check():
    files = f"/sdcard/Android/data/{PKG}/files"
    adb("shell", "am", "start", "-n", ACT)
    time.sleep(2)
    tap_id("tabLibrary")
    time.sleep(1)
    tap_id("chipSongs")
    time.sleep(2)
    menu_on("Lumen - Paper Planes", "Love")
    if menu_on("Atlas Echo - Northern Lights", "Add to playlist"):
        n = wait_for(lambda: find(text="New playlist"), 5)
        if n is not None:
            tap(n)
            time.sleep(2)
        type_text("CI Mix")
        time.sleep(1)
        ok = wait_for(lambda: find(text="Save"), 5)
        if ok is not None:
            tap(ok)
        time.sleep(2)
    hide_keyboard()
    # Settings, scrolled to the Backup card.
    tap_id("tabHome")
    time.sleep(1)
    if tap_id("settings"):
        time.sleep(2)
        for _ in range(8):
            if find(rid="backupSave") is not None:
                break
            sh(f"input swipe {w // 2} {int(h * 0.7)} {w // 2} {int(h * 0.4)} 300")
            time.sleep(1)
        shot("22-settings-backup")
        back()
    sh(f"rm -f {files}/b1.json {files}/b2.json")
    hook("save", "b1.json")
    if wait_for(lambda: "b1.json" in sh(f"ls {files}") or None, 20) is None:
        return "the backup wasn't saved"
    time.sleep(1)
    one = os.path.join(OUT, "backup-1.json")
    adb("pull", f"{files}/b1.json", one)
    before = backup_view(one)
    log(f"backup made: {len(before['playlists'])} playlists, {len(before['loved'])} Loved songs, settings {before['settings']}")
    if not before["playlists"] or not before["loved"]:
        return "the test data (a playlist and a Loved song) wasn't made"

    # Everything gone, as after uninstalling.
    sh(f"am force-stop {PKG}")
    sh(f"pm clear {PKG}")
    perm = "android.permission.READ_MEDIA_AUDIO" if level >= 33 else "android.permission.READ_EXTERNAL_STORAGE"
    sh(f"pm grant {PKG} {perm}")
    if level >= 33:
        sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    adb("shell", "am", "start", "-W", "-n", ACT)
    time.sleep(3)
    sh(f"mkdir -p {files}")
    adb("push", one, f"{files}/b1.json")
    hook("restore", "b1.json")
    summary = wait_for(lambda: find(text="Backup restored"), 30)
    time.sleep(1)
    shot("22b-restore-summary")
    if summary is None:
        return "no restore summary"
    root = dump()
    log(f"restore summary: {[(n.get('text') or '') for n in nodes(root) if (n.get('text') or '').startswith('Restored')]}")
    ok = find(text="OK")
    if ok is not None:
        tap(ok)
    time.sleep(1)
    hook("save", "b2.json")
    if wait_for(lambda: "b2.json" in sh(f"ls {files}") or None, 20) is None:
        return "the second backup wasn't saved"
    time.sleep(1)
    two = os.path.join(OUT, "backup-2.json")
    adb("pull", f"{files}/b2.json", two)
    after = backup_view(two)
    for k in ("version", "settings", "playlists", "loved"):
        if before[k] != after[k]:
            log(f"  backup: {k} differs after restoring:\n    before {before[k]}\n    after  {after[k]}")
            return f"{k} differ after restoring"
    # Restoring again adds nothing.
    hook("restore", "b1.json")
    wait_for(lambda: find(text="Backup restored"), 30)
    ok = find(text="OK")
    if ok is not None:
        tap(ok)
    time.sleep(1)
    hook("save", "b3.json")
    if wait_for(lambda: "b3.json" in sh(f"ls {files}") or None, 20) is not None:
        time.sleep(1)
        three = os.path.join(OUT, "backup-3.json")
        adb("pull", f"{files}/b3.json", three)
        if backup_view(three)["playlists"] != before["playlists"] or backup_view(three)["loved"] != before["loved"]:
            return "restoring twice changed the playlists"
    return None


why_backup = backup_check()
log(f"backup and restore: {'ok' if why_backup is None else why_backup}")
if why_backup:
    failures.append(f"backup: {why_backup}")

if crashed():
    failures.append("crash during the run")
    log("CRASH:\n" + adb("logcat", "-d", "-b", "crash")[-6000:])

with open(os.path.join(OUT, "logcat.txt"), "w") as f:
    f.write(adb("logcat", "-d", "-t", "3000"))
log("failures: " + (", ".join(failures) if failures else "none"))
with open(os.path.join(OUT, "results.txt"), "w") as f:
    f.write("\n".join(results) + "\n")
sys.exit(1 if failures else 0)
