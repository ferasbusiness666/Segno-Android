# Segno backup format

One JSON file that moves your Segno stuff between devices: playlists, Loved songs, settings and
the queue. Segno for Android writes and reads it today (Settings → Backup). Segno for Windows and
new PCs should read and write the same file, so every Segno can move to every other one.

The Android app saves it as `segno-backup-YYYY-MM-DD.json`. The text is UTF-8.

## Example

```json
{
  "segno_backup": 1,
  "app": "Segno for Android",
  "app_version": "1.0.0",
  "created": "2026-11-07T10:20:30Z",
  "settings": {
    "autoplay": true,
    "check_updates": true,
    "search": "youtube_music",
    "library_page": "songs",
    "recent_searches": ["daft punk", "fairuz"]
  },
  "playlists": [
    {
      "name": "Road trip",
      "source_url": "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M",
      "site": "Spotify",
      "cover": "https://i.scdn.co/image/ab67706f00000002",
      "songs": [
        { "source": "youtube", "youtube_id": "5NV6Rdv1a3I", "title": "Get Lucky", "artist": "Daft Punk", "duration_ms": 248000 },
        { "source": "spotify", "spotify_id": "69kOkLUCkxIZYexIgSG8rq", "youtube_id": "5NV6Rdv1a3I",
          "title": "Get Lucky", "artist": "Daft Punk", "duration_ms": 248413, "search": "Daft Punk Get Lucky" },
        { "source": "file", "title": "Northern Lights", "artist": "Atlas Echo", "album": "Skies",
          "duration_ms": 201000, "path": "/storage/emulated/0/Music/Atlas Echo - Northern Lights.mp3" }
      ]
    }
  ],
  "loved": [
    { "source": "youtube", "youtube_id": "5NV6Rdv1a3I", "title": "Get Lucky", "artist": "Daft Punk", "duration_ms": 248000 }
  ],
  "queue": {
    "index": 0,
    "position_ms": 61000,
    "shuffle": false,
    "repeat": "off",
    "songs": [
      { "source": "youtube", "youtube_id": "5NV6Rdv1a3I", "title": "Get Lucky", "artist": "Daft Punk", "duration_ms": 248000 }
    ]
  }
}
```

## The top

| Field | Type | Meaning |
|---|---|---|
| `segno_backup` | number | The format version. Always first. This document is version **1**. |
| `app` | string | What wrote it: `"Segno for Android"`, `"Segno for Windows"`, … |
| `app_version` | string | That app's version, like `"1.0.0"`. |
| `created` | string | When, in UTC, ISO 8601: `"2026-11-07T10:20:30Z"`. |
| `settings` | object | See below. Every field in it is optional. |
| `playlists` | array | Playlists, in the order the app shows them. |
| `loved` | array of songs | Loved songs, newest first. |
| `queue` | object | Optional. The queue when the backup was made. |

## Songs

Every song says where it comes from in `source`, and carries what another device needs to find
it again. Fields that are empty are left out.

| Field | Used by | Meaning |
|---|---|---|
| `source` | all | `"youtube"`, `"spotify"` or `"file"`. |
| `title` | all | The song's name. |
| `artist` | all | The artist, or left out when unknown. |
| `album` | all | The album, if known. |
| `duration_ms` | all | The length in milliseconds, if known. |
| `art` | youtube, spotify | A cover picture's web address. |
| `youtube_id` | youtube, spotify | The YouTube video (11 characters). For Spotify songs: the video picked to play it, if one was. |
| `spotify_id` | spotify | The Spotify track id (the part after `open.spotify.com/track/`). |
| `search` | spotify | The words to search YouTube for, when no video was picked yet. |
| `path` | file | Where the file was on the device that made the backup. **Only a hint**: paths differ between devices. |

A song needs `youtube_id` (youtube), `spotify_id` (spotify), or a `title` or `path` (file). Songs
without them, and songs with a `source` the reader doesn't know, are skipped.

### Finding phone and computer files again

A `file` song is looked for on the new device by **title and artist** (ignoring case) **and about
the same length** (within 2 seconds) first, then by **the same path**. A song that isn't found is
kept, marked as not on this device, so the playlist keeps its shape and the song is still in the
next backup.

## Playlists

| Field | Type | Meaning |
|---|---|---|
| `name` | string | Required. Playlists are matched by name, ignoring case and spaces at the ends. |
| `songs` | array of songs | In order. |
| `source_url` | string | Optional. The Spotify or YouTube link it was imported from. |
| `site` | string | Optional. `"Spotify"` or `"YouTube"`. |
| `cover` | string | Optional. The cover picture's web address. |

## Settings

| Field | Type | Meaning |
|---|---|---|
| `autoplay` | boolean | Keep playing songs like the last one when the queue ends. |
| `check_updates` | boolean | Check for a new version once a day. |
| `search` | string | Where Search looks: `"youtube_music"` or `"youtube"`. |
| `library_page` | string | The Library page last open: `"songs"`, `"playlists"`, `"albums"` or `"artists"`. |
| `recent_searches` | array of strings | Recent searches, newest first. |

An app ignores settings it doesn't have.

## Queue

| Field | Type | Meaning |
|---|---|---|
| `songs` | array of songs | The queue, in order. |
| `index` | number | The song that was playing (0 is the first). |
| `position_ms` | number | How far into it. |
| `shuffle` | boolean | Shuffle on or off. |
| `repeat` | string | `"off"`, `"all"` or `"one"`. |

## Restoring

Restoring **adds and never deletes**:

- A playlist with the same name as one on the device gets the songs it doesn't have yet, at the
  end. Other playlists are made new. Two playlists in the file with one name become one.
- Loved songs are added to the ones already loved.
- Settings are taken from the backup.
- The queue comes back only when nothing is queued on the device.

Afterwards the app says what it did, like "Restored 5 playlists and 120 Loved songs".

## Versions

- Readers must refuse a file whose `segno_backup` is **higher** than the version they know, and
  say the backup is from a newer Segno.
- A file without `segno_backup` isn't a Segno backup.
- New optional fields can be added without raising the version; readers ignore fields they
  don't know. Raise the version only for changes old readers would get wrong.
