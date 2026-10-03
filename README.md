# Narrio

A native Android listening room for audiobooks. Find a book, choose a matching listening source, stream through TorBox or Internet Archive, and keep your place on a personal shelf.

## Install the update

Download the signed APK from the [latest GitHub release](https://github.com/cort-robinson/Narrio/releases/latest). Each release includes checksums, its source commit, and Android/signing details. Android 8.0 or later is required. Install over an earlier Narrio release to retain your TorBox connection, shelf, bookmarks, and listening progress.

1. Download the APK directly on your Android phone and open it in Files.
2. If Android requests it, allow that app to install unknown apps, then install Narrio.
3. Open **Settings → TorBox**, enter your API key, and tap **Connect TorBox**. Your TorBox plan must include API access.
4. Search for a title or author, open the book, and tap **Find sources**. Choose a matching recording, tap **Listen**, choose an audio format, then **Stream now**. A verified uncached release instead offers **Prepare in TorBox**; streaming becomes available after preparation. Streaming requires no phone download.
5. To listen offline, choose **Download to phone**. Downloads use Wi-Fi by default. **My shelf** offers progress, pause/resume/retry, **Play offline**, and removal. Settings can permit mobile-data downloads.

Discovery uses the Audible book catalog, Google Books, and Open Library. Editions with the same normalized title and author appear once; full descriptions and covers take priority. A book result identifies the work and does not guarantee a playable audiobook. Source providers and TorBox are contacted only when you request sources from book details, or open an existing saved recording.

**Find sources** checks LibriVox public audio and, when connected, Knaben releases and your TorBox account audio. Matching title/author releases need usable public or cached audio, or hash-verified torrent file metadata with reported seeders. Collections appear only when their author and a separate file or folder clearly identify the requested book; other books' files are excluded. Uncached releases are labelled **Requires TorBox preparation** and require an explicit action. Wrong books, sequels, summaries, unseparated collections, samples, and unchecked cache results are excluded. Cached audio appears first. Seeder counts can change, so preparation time and success depend on live availability. Exact recording narration and language can remain unverified; inspect the release and audio files. Catalog narrators describe metadata editions and never identify a chosen recording automatically.

Successful metadata lookups are cached for a day and saved with shelf items. Metadata failures keep existing details and allow saved recordings to remain usable. **Refresh book details** on a recording retains its source, files, and listening position.

## Included

Follow along is available on this development branch and its Narrio Dev preview. Stable updates are published from `master` through the [development workflow](docs/DEVELOPMENT.md); see [current validation](docs/VALIDATION.md) for feature evidence.

- Metadata-only title/author search with one result per book, followed by explicit discovery of matching narrated recordings.
- TorBox account connection, batched hash/file cache checks, cached-source filtering, existing-source reuse, torrent/magnet creation, detailed cloud preparation status, and temporary per-file playback links.
- Explicit offline phone downloads with storage estimates, Wi-Fi preference, progress, pause/resume/retry, and removal. Ordinary streaming does not write to the offline audio cache.
- Whole-book M4B and naturally ordered multipart MP3 playback, 30-second skip controls, speed, a sleep timer, and bookmarks.
- Room-backed library and independent saved positions for different recordings and source layouts.
- **Listening room → Follow along**: highlighted, scrolling passages from EPUB/text imports, companion source files, or public ebook lookup. Tap a passage to seek, choose the narrated chapter, and adjust estimated timing. WebVTT tracks use supplied timestamps for their attached audio part. Text and timing matches are saved locally.
- Media3 background service and system media controls; pause on headphone disconnection and Android audio-focus handling.
- Night, Day, and System appearance; retrieved cover art with original artwork fallbacks and bundled Newsreader/Manrope typography.

## Appearance on this branch

This development branch adds the expanded **Settings → Appearance** page. Build this branch or use its Narrio Dev preview to try it; stable releases are published from `master` through the [development workflow](docs/DEVELOPMENT.md).

- Choose **Night**, **Day**, or **System** independently of **Listening room**, **Ocean**, **Forest**, **Rosewood**, or **Graphite**. Each palette has paired light and dark colors; selections apply and save immediately.
- Choose **Custom** or **Create custom theme** to edit one named theme. Start from a preset, then edit Night and Day accent, supporting, and background colors using swatches, HSV sliders, or six-digit hex. The live preview derives readable text and controls. Tap **Save & use theme** to apply the draft; Back cancels it.
- Choose **Narrio** for the original pairing of Newsreader titles and Manrope controls, **Manrope** or **Newsreader** throughout, or **Android** for the device's default family. Fonts are bundled or built in. **Default**, **Comfort**, and **Large** text sizes use 1.0×, 1.1×, or 1.2× multipliers while retaining your device's text-size setting.
- **Restore default appearance** returns to Listening room, Night, Narrio fonts, and Default size. Your saved custom palette remains available when resetting or switching presets.

Appearance preferences stay on the device and work offline. [Appearance validation](docs/VALIDATION.md#current-branch-appearance-evidence) records the branch's build, native emulator, and visual review evidence.

## Adaptive Android layouts

Narrio targets Android 16 and adapts to current window bounds. Compact phone windows have bottom navigation and a mini-player. Windows at least 600 dp wide reveal an 80 dp navigation rail and two independently scrolling panes, including larger screens and multi-window sessions.

Foldables, including the Galaxy Z Fold 8, also receive hinge and posture support through Jetpack WindowManager. Vertical separating hinges keep discovery and playback on opposite sides. A supported horizontal half-open posture puts artwork above the hinge and transport below it.

The service owns playback, so folding, rotation, or multi-window resizing does not rebuild the audio session. Insets and enlarged system text are supported. Static artwork avoids constant decorative animation; background UI updates slow down when the activity is hidden. See [validation](docs/VALIDATION.md) for the exact emulator evidence and physical-device limits.

## Local privacy

No custom server, telemetry, or account synchronization. The TorBox key is encrypted with Android Keystore, excluded from backup, and deleted on disconnect. Temporary CDN URLs stay in memory. Search queries go directly to catalog providers; TorBox credentials are never sent to Knaben, Internet Archive, or Gutendex. Metadata lookup sends book/release names to Audible, Google Books, or Open Library; artwork loads from the returned provider image URLs. TorBox credentials, file lists, listening positions, and bookmarks are never sent to metadata providers. Room contains recording metadata, stable part IDs, positions, bookmarks, and text attachments/timing matches. Original and normalized book text stay in private device storage. Disconnect pauses unfinished TorBox phone downloads; completed phone audio remains playable offline. Removing a shelf item also removes its local audio downloads, book text, and history, without deleting files from TorBox.

## Build

For phone testing, bookmark [Narrio Dev downloads](https://github.com/cort-robinson/Narrio/releases?q=dev-&expanded=true). Each preview provides a direct APK link and its automated test status, and installs alongside stable Narrio. Builds run in parallel with tests. The [development guide](docs/DEVELOPMENT.md#test-a-development-build) also covers quick local installs over Wi-Fi using the separate **Narrio Local** app.

Coding agents should read [AGENTS.md](AGENTS.md) before making changes.

[Development and releases](docs/DEVELOPMENT.md) describes feature branches, `dev` preview APKs, Conventional Commits, and automatic signed releases from `master`. Versions are supplied by automation; local builds use the latest release tag plus `-dev`.

Use JDK 17 or 21, Android SDK platform 36, and the included Gradle wrapper. Set `ANDROID_HOME` or add an ignored `local.properties` containing your SDK directory.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

To generate your own signing identity and a release APK:

```powershell
.\scripts\create-release-key.ps1
.\gradlew.bat :app:assembleRelease
```

The script preserves an existing key. `.signing/` and `signing.properties` are ignored: retain them privately if you want future releases to update the installed app. Losing or replacing the signing key requires uninstalling that app before installing a differently signed package. Never commit these files.

Instrumented listening tests use real Internet Archive audio and need network access. Run them on one emulator with `adb -s <serial> shell am instrument -w app.narrio.test/androidx.test.runner.AndroidJUnitRunner` after installing the debug app and test APK. `FoldWindowTest` uses the supported WindowManager testing library and needs a window at least 600 dp wide.

## Architecture and limits

[Architecture](docs/ARCHITECTURE.md) describes provider seams, source identity, persistence, and playback. [Validation](docs/VALIDATION.md) records completed signed-release phone checks, native audio and layout tests, and the precise validation limits. [Artwork](docs/ART.md) contains provenance and font licenses.

[Follow along](docs/FOLLOW_ALONG.md) explains acquiring text, estimated versus supplied timing, and the plan for a full reader with a shared reading/listening cursor. Ordinary ebooks have no audio timestamps; their highlighting is adjustable estimation. Automatic exact alignment and full reader functionality are future work.

V1 supports streaming and optional offline phone audio. It has no cloud sync, casting, Android Auto, or addon marketplace. Torrentio's current stream resources target movies, series, and anime; Narrio uses an audiobook-capable index instead of claiming compatibility with those addons. Chapters support Nero `chpl` M4B metadata and ID3 chapter frames; unsupported chapter encodings retain audio-part and elapsed-time navigation.
