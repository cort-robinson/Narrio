# Narrio architecture

## Recording before URL

`Audiobook` represents a narrated recording. Internet Archive item IDs distinguish recordings, even when titles repeat. Narrator and language are read from source metadata; missing details stay visibly unknown. `AudioSource` represents a particular delivery and file layout. `AudioPart` has a stable ID, filename, optional duration, and Archive URL or TorBox torrent/file IDs. Generated CDN links never become source identity.

`RecordingDiscovery` owns search and recording/source discovery. `ArchiveDiscovery` is the first implementation, using Internet Archive advanced search and item metadata. Results are recordings with audio files, rather than general book records assumed to be playable. MP3 derivatives at reduced bitrate are excluded when matching original audio is available; numeric ordering includes disc paths. M4B remains a separate layout.

`DeliveryProvider` owns preparation, status, source files, and near-playback resolution. `TorBoxDelivery` uses the current TorBox Main API through a direct authenticated connection. It reuses an account torrent matching the recording's hash, or uploads the recording's actual `.torrent` file. A torrent URL is not passed as a magnet. Availability requires both `download_finished` and `download_present`. Preparation remains in Room so the activity can leave and return. The app polls status while that recording's detail is visible and also exposes a manual check.

`KnabenDiscovery` adds actual audiobook release search using the public v2 API and category 1003000. Torrent hashes distinguish releases. Original release titles and filenames stay visible when catalog metadata supplies a cleaner display title. Unknown recording language and abridgment remain unverified. Zero-seeder releases are included because they may still be cached. Queries contain no TorBox credential. TorBox library search remains explicit account-library browsing.

## Book metadata

`BookMetadata` enriches existing Knaben and TorBox releases through the public Audible catalog, falling back to Open Library search and work details. It retrieves full publisher descriptions, authors, named narrators, and the largest secure cover image. Search excludes podcasts and requires an exact normalized title or a matching title/author release name; conflicting authors, summaries, collections, and unrelated titles are rejected. Reversed author names and bilingual release names are supported. Existing source narrators are retained. When several catalog recordings have different narrators, a matching narrator in the original release name is required; otherwise narration stays unknown. Catalog narrators are always labeled as catalog information, and do not verify the downloaded recording's edition.

Internet Archive retains recording-specific metadata, prioritizing explicit narrator/reader fields and original front/cover artwork over thumbnail derivatives. Shared plain-text cleanup preserves initials, paragraphs, and named/numeric HTML entities. Catalog covers render without cropping or duplicate title overlays; the original Narrio art remains visible while loading and after image errors.

Playable search results publish before metadata enrichment, which updates them progressively. Detail lookup has its own loading state and never disables listening. Each metadata request has an eight-second deadline, cancellation closes pending requests, and at most two lookups run concurrently. A bounded 128-entry memory cache retains successful responses for a day and empty results for five minutes; failed requests remain retryable. Shelf books store descriptive metadata and its retrieval time in backward-compatible book JSON. Transactional updates copy only book details, preserving stored recording/source IDs, files, cache state, source histories, preparation state, and listening positions. Explicit refresh bypasses successful-cache reuse. No database schema migration is needed.

Metadata requests contain only cleaned release/book names. They never include the TorBox key, file lists, temporary playback URLs, or listening history. Metadata outages preserve existing details and never change audio availability.

Connected discovery defaults to cached audio. Knaben results and known LibriVox torrent hashes are checked in batches of at most 100 through `torrents/checkcached?format=object&list_files=true`. A cache hit needs compatible audio; Archive layouts require all chosen filenames. Non-audio/sample-only and incomplete layouts are excluded. Public metadata hydration is bounded to six simultaneous requests, with cached results published progressively. Partial provider failures remain visible without discarding other results. The default new-source playback path checks cache availability before source creation; cloud preparation is an explicit separate action. The source is registered under the account via a magnet or real torrent upload, then resolved from actual torrent/file IDs. Completion still requires both readiness flags. Cache snapshots can change, and no live authenticated cache response has been captured by development.

## Playback

`ListeningService` owns ExoPlayer, a MediaSession, playlist, speed, timer, and progress persistence. UI actions address the local service; the media session accepts the app and trusted system controllers, with playlist/URI mutation commands removed. Android handles audio focus, becoming-noisy events, foreground media notification, and network wake mode.

Playlist URIs are `narrio://audio/<stable-part-id>`. `RefreshingDataSource` resolves each part only when opened. TorBox links live in a concurrent in-memory cache. HTTP 401/403/404/410 triggers a fresh link and retries the same Media3 `DataSpec`, preserving the byte offset. A visible playback recovery action also renews links and retries from the player's current position. Provider JSON errors use generic messages instead of echoing responses that could contain secrets.

Catalog/API calls have bounded deadlines. Audio requests have no whole-request deadline, because a valid long stream can exceed it; connection/read timeouts still apply. Media3 buffers 30–90 seconds, starts after a short buffer, and re-buffers on network interruption. Nero `chpl` metadata is read using bounded HTTP ranges; ID3 chapter frames are accepted from Media3. Unsupported metadata falls back to ordered parts.

## Persistence

Room has `shelf`, `positions`, and `bookmarks` tables. Position identity is `(recordingId, sourceId, stablePartId)`, with milliseconds inside that part. Source-format changes have independent histories, so an MP3 timestamp is never copied to M4B. Restoring a source locates the stable part in its current ordered list. Bookmarks retain their source ID and can restore an earlier layout. Progress is saved periodically while playing and on relevant player events. Reopening the app reconstructs the last playlist paused; audio preparation waits for an explicit resume.

The database is schema 3. Migration 1→2 adds per-source histories; 2→3 adds the pending audio format. Old-player progress cannot overwrite an independently preparing source, and choosing M4B survives reopening the recording. Removing a shelf item clears its histories, bookmarks, and phone downloads; it does not delete provider files.

## Appearance

`AppearanceSettings` keeps mode, palette, font, text size, and one optional named custom palette independent. `AppearanceStore` saves normalized JSON under the local SharedPreferences key `appearance.v1`. Its codec ignores future optional fields, coerces unknown enum values to defaults, and falls back safely on damaged data. Reading can migrate the legacy `theme` mode; the next save removes that old key while preserving unrelated preferences. Appearance requires no provider, account, font download, or network request.

The ViewModel publishes saved settings through a StateFlow. `NarrioApp` passes them to `NarrioTheme`, which resolves Night/Day/System mode, then supplies app-wide Material color and typography roles. Listening room retains the authored original schemes. Ocean, Forest, Rosewood, Graphite, and Custom use separate Night/Day accent, supporting, and background seeds; the generator derives tonal surfaces and readable foreground/action/container roles, with minimum ratios of 4.5 for content and 3.0 for the `outline` role. Tertiary follows secondary. System-bar icon choice uses foreground contrast against the actual scheme background. Font choices reuse bundled Newsreader/Manrope or Android's default family; app text-size multipliers remain in sp so system scaling is retained.

Preset, mode, font, and text-size selections save immediately. The custom editor holds a `rememberSaveable` draft and renders it inside a contained `NarrioTheme` preview. Only **Save & use theme** publishes and persists that draft; cancellation leaves the applied settings intact. Preset switching and default reset retain the saved custom palette.

## Offline audio

Media3 `DownloadService`, `DownloadManager`, a durable `DownloadIndex`, and `SimpleCache` handle explicit phone downloads. The cache lives in private app files with `NoOpCacheEvictor`; the user removes audio explicitly. Requests persist only stable audio URIs and recording/source metadata. Their resolving data source renews TorBox links near transfer time, including after process recreation. Downloads run with a data-sync foreground service and network-constrained scheduling, at most two transfers in parallel. Wi-Fi/unmetered is the default. Pause/resume uses persisted stop reasons; foreground timeouts pause downloads so they can be resumed explicitly.

Playback wraps the refreshing network source in a **read-only** `CacheDataSource`: its cache-write sink is null. Streaming therefore does not populate the offline cache. Explicit completed downloads can play and seek without an account connection or network. M4B chapter reading tries bounded cached head/tail data for downloaded files. Disconnect preserves completed audio but pauses unfinished TorBox downloads and clears in-memory links. UI download progress polls only while the activity is visible; Media3 owns transfers and their foreground notification independently.

## Fold and window state

Compose uses safe drawing/navigation insets, live window constraints, and WindowManager `FoldingFeature`. Compact windows show one destination. Expanded windows show a rail and two panes. A separating vertical hinge defines the pane boundary; a half-open horizontal hinge divides the full player into artwork and controls. Search, selection, and destination live in the ViewModel; playback lives in the service. Window resizing therefore changes presentation without changing the recording/session.

## Credentials and release

Android Keystore AES/GCM protects the TorBox key. SharedPreferences stores ciphertext and an IV only. The app rejects cleartext traffic, disables backup/device transfer of its local files, and has no network logger or analytics. Disconnect pauses online TorBox playback, clears temporary links, deletes the stored key, and removes its Keystore alias. Completed phone downloads continue to work.

The supplied release is R8 optimized and locally signed. The ignored signing identity is retained for subsequent app updates. The repository contains no TorBox account credential.

## Primary references

- [TorBox Main API](https://api-docs.torbox.app/) and [official SDK torrent contract](https://github.com/TorBox-App/torbox-sdk-py/blob/main/documentation/services/TorrentsService.md).
- [Internet Archive metadata](https://archive.org/developers/metadata.html) and [search](https://archive.org/developers/search.html).
- [Media3 background playback](https://developer.android.com/media/media3/session/background-playback).
- [Media3 downloading and read-only offline playback](https://developer.android.com/media/media3/exoplayer/downloading-media).
- [Knaben v2 search contract](https://knaben.org/api/v2/).
- [Audible catalog integration used by Audiobookshelf](https://github.com/advplyr/audiobookshelf/blob/master/server/providers/Audible.js), verified against live public responses for this update.
- [Open Library search](https://openlibrary.org/dev/docs/api/search), [work metadata](https://openlibrary.org/dev/docs/api/restful), and [covers](https://openlibrary.org/dev/docs/api/covers).
- [Torrentio current stream manifest](https://torrentio.strem.fun/manifest.json).
- [Fold-aware Compose apps](https://developer.android.com/develop/ui/compose/layouts/adaptive/foldables/make-your-app-fold-aware) and [WindowManager test hooks](https://developer.android.com/reference/kotlin/androidx/window/testing/layout/package-summary).
