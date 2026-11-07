# What's new in Segno for Android

<!--
How this file is used:
- The release workflow uses the section for the version being released as the release notes.
Each version starts with "## <version>" (the same as versionName in app/build.gradle.kts).
Inside, use "### New", "### Better" or "### Fixed", with one "- " line per change. Keep lines short.
-->

## 1.1.0

### New
- Lyrics in Now Playing: tap the microphone. The line being sung lights up and the lyrics follow the song. Tap a line to play from there.
- Lyrics come from YouTube Music first, then LRCLIB and NetEase, all asked at once; the best answer wins. Arabic songs too.
- Lyrics a little early or late? Earlier and Later move them a quarter of a second, and Segno remembers. Wrong lyrics? finds others.
- A .lrc file with the same name next to a song on your phone always wins, when Android lets Segno read it.
- Synced lyrics in Settings: off, and Segno never looks lyrics up.

### Better
- Spotify songs play YouTube Music's own song when it has the same one, so the length and lyrics fit.
- Lyrics are only looked up while they're open, plus the next song once one is found, to save data and battery.

## 1.0.0

### New
- Segno is on Android. It plays YouTube, Spotify links and the music on your phone, with the same dark look and pink accent as Segno for Windows.
- Search YouTube Music for songs, or YouTube for everything else; Segno remembers which. If YouTube Music isn't available where you are, one tap searches YouTube instead. Paste a YouTube or Spotify song link to play it.
- Music keeps playing with the screen off and when you leave the app. Pause and skip from the notification, the lock screen, Bluetooth or your headphones. Unplug your headphones and the music pauses.
- When the queue ends, Segno keeps going with songs like the last one. Turn it off in Settings.
- The queue: play next, add to queue, drag to reorder, swipe to remove.
- Playlists and Loved songs, kept on your phone.
- Your music files, by song, album and artist.
- Downloads: save songs from YouTube to Music/Segno as M4A files with their name, artist and cover, so other music apps see them too. Progress shows in the app and in a notification.
- Import playlists: paste Spotify playlist, album and song links, YouTube playlists, or your YouTube channel to bring in all its public playlists. Spotify songs play from a YouTube video of the same length, skipping live versions, covers and sped-up edits unless the song itself is one.
- Delete from phone: remove a music file or a download from the song's ⋮ menu. It also leaves the queue, your playlists and Loved songs.
- Back up and restore in Settings: save your playlists, Loved songs and settings to one file (in Downloads or Google Drive, say) and bring them back on any phone. Restoring adds and never deletes. Songs from your phone are found again by name, artist and length; ones that aren't there stay in your playlists, marked as not on this phone.
- Sleep timer: press the moon on Now Playing and pick 15, 30 or 45 minutes, an hour, or the end of the song. The music fades out over the last 30 seconds, then stops.
- Updates inside the app: Segno checks GitHub once a day (or when you tap Check for updates in Settings). When a new version is out, Home shows what's new, and Update downloads and installs it. Segno only installs updates signed with the same key, and Android asks you first.
