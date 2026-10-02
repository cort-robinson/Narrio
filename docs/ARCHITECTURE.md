# Narrio architecture

## Recording before URL

`Audiobook` represents a narrated recording. Internet Archive item IDs distinguish recordings, even when titles repeat. Narrator and language are read from source metadata; missing details stay visibly unknown. `AudioSource` represents a particular delivery and file layout. `AudioPart` has a stable ID, filename, optional duration, and Archive URL or TorBox torrent/file IDs. Generated CDN links never become source identity.

`RecordingDiscovery` owns search and recording/source discovery. `ArchiveDiscovery` is the first implementation, using Internet Archive advanced search and item metadata. Results are recordings with audio files, rather than general book records assumed to be playable. MP3 derivatives at reduced bitrate are excluded when matching original audio is available; numeric ordering includes disc paths. M4B remains a separate layout.

`DeliveryProvider` owns preparation, status, source files, and near-playback resolution. `TorBoxDelivery` uses the current TorBox Main API through a direct authenticated connection. It reuses an account torrent matching the recording's hash, or uploads the recording's actual `.torrent` file. A torrent URL is not passed as a magnet. Availability requires both `download_finished` and `download_present`. Preparation remains in Room so the activity can leave and return. The app polls status while that recording's detail is visible and also exposes a manual check.

TorBox library search is explicitly account-library browsing. It does not claim global audiobook discovery. Adding a new discovery provider should supply recording-specific identity, credible narration/language metadata, and compatible source files before enriching with general book metadata.

## Playback

`ListeningService` owns ExoPlayer, a MediaSession, playlist, speed, timer, and progress persistence. UI actions address the local service; the media session accepts the app and trusted system controllers, with playlist/URI mutation commands removed. Android handles audio focus, becoming-noisy events, foreground media notification, and network wake mode.

Playlist URIs are `narrio://audio/<stable-part-id>`. `RefreshingDataSource` resolves each part only when opened. TorBox links live in a concurrent in-memory cache. HTTP 401/403/404/410 triggers a fresh link and retries the same Media3 `DataSpec`, preserving the byte offset. A visible playback recovery action also renews links and retries from the player's current position. Provider JSON errors use generic messages instead of echoing responses that could contain secrets.

Catalog/API calls have bounded deadlines. Audio requests have no whole-request deadline, because a valid long stream can exceed it; connection/read timeouts still apply. Media3 buffers 30–90 seconds, starts after a short buffer, and re-buffers on network interruption. Nero `chpl` metadata is read using bounded HTTP ranges; ID3 chapter frames are accepted from Media3. Unsupported metadata falls back to ordered parts.

## Persistence

Room has `shelf`, `positions`, and `bookmarks` tables. Position identity is `(recordingId, sourceId, stablePartId)`, with milliseconds inside that part. Source-format changes have independent histories, so an MP3 timestamp is never copied to M4B. Restoring a source locates the stable part in its current ordered list. Bookmarks retain their source ID and can restore an earlier layout. Progress is saved periodically while playing and on relevant player events. Reopening the app reconstructs the last playlist paused; audio preparation waits for an explicit resume.

The database is currently schema 2, with a migration from schema 1 that adds per-source histories. Sources, metadata, progress, and bookmarks stay on the device. Removing a shelf item clears its histories and bookmarks; it does not delete provider files.

## Fold and window state

Compose uses safe drawing/navigation insets, live window constraints, and WindowManager `FoldingFeature`. Compact windows show one destination. Expanded windows show a rail and two panes. A separating vertical hinge defines the pane boundary; a half-open horizontal hinge divides the full player into artwork and controls. Search, selection, and destination live in the ViewModel; playback lives in the service. Window resizing therefore changes presentation without changing the recording/session.

## Credentials and release

Android Keystore AES/GCM protects the TorBox key. SharedPreferences stores ciphertext and an IV only. The app rejects cleartext traffic, disables backup/device transfer of its local files, and has no network logger or analytics. Disconnect pauses TorBox playback, clears temporary links, deletes the stored key, and removes its Keystore alias.

The supplied release is R8 optimized and locally signed. The ignored signing identity is retained for subsequent app updates. The repository contains no TorBox account credential.

## Primary references

- [TorBox Main API](https://api-docs.torbox.app/) and [official SDK torrent contract](https://github.com/TorBox-App/torbox-sdk-py/blob/main/documentation/services/TorrentsService.md).
- [Internet Archive metadata](https://archive.org/developers/metadata.html) and [search](https://archive.org/developers/search.html).
- [Media3 background playback](https://developer.android.com/media/media3/session/background-playback).
- [Fold-aware Compose apps](https://developer.android.com/develop/ui/compose/layouts/adaptive/foldables/make-your-app-fold-aware) and [WindowManager test hooks](https://developer.android.com/reference/kotlin/androidx/window/testing/layout/package-summary).
