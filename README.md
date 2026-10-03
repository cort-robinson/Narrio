# Narrio

A native Android listening room for audiobooks. Real recording discovery, MP3 and M4B streaming, a personal shelf, and playback that stays with you when a Fold opens.

## Install the update

The signed package is `artifacts/Narrio-1.1.0.apk`. A copy is also delivered to the Windows Downloads folder. Android 8.0 or later is required. Install over 1.0 to retain your TorBox connection, shelf, bookmarks, and listening progress.

1. Transfer the APK to your Android phone and open it in Files.
2. If Android requests it, allow that app to install unknown apps, then install Narrio.
3. Open **Settings → TorBox**, enter your API key, and tap **Connect TorBox**. Your TorBox plan must include API access.
4. Search for a title or author in **Ready to stream**, open the exact recording/release, tap **Listen**, choose a cached audio format, then **Stream now**. Narrio rechecks availability before adding a new source to TorBox. Streaming requires no phone download.
5. To listen offline, choose **Download to phone**. Downloads use Wi-Fi by default. **My shelf** offers progress, pause/resume/retry, **Play offline**, and removal. Settings can permit mobile-data downloads.

**All sources** includes uncached releases from Knaben's audiobook index and LibriVox. Indexed narration, language, and abridgment are explicitly unverified; inspect the release and filenames. **Public books** streams LibriVox recordings directly through Internet Archive without an account. **My TorBox** searches your existing account audio. Availability depends on search coverage and the TorBox cache. An uncached release can be prepared only through its explicit **Prepare in TorBox** action; this can take hours and does not save audio to the phone.

## Included

- Search by title and author, with separate narrated recordings and visible language/narrator metadata.
- TorBox account connection, batched hash/file cache checks, cached-source filtering, existing-source reuse, torrent/magnet creation, detailed cloud preparation status, and temporary per-file playback links.
- Explicit offline phone downloads with storage estimates, Wi-Fi preference, progress, pause/resume/retry, and removal. Ordinary streaming does not write to the offline audio cache.
- Whole-book M4B and naturally ordered multipart MP3 playback, 30-second skip controls, speed, a sleep timer, and bookmarks.
- Room-backed library and independent saved positions for different recordings and source layouts.
- Media3 background service and system media controls; pause on headphone disconnection and Android audio-focus handling.
- Night, Day, and System appearance; original cover art and bundled Newsreader/Manrope typography.

## Galaxy Z Fold 8 and adaptive behavior

Narrio targets Android 16 and uses current window bounds and Jetpack WindowManager posture data rather than a device-name check. The cover display has bottom navigation and a compact player. Unfolded windows at least 600 dp wide reveal an 80 dp navigation rail and two independently scrolling panes. Vertical separating hinges keep discovery and playback on opposite sides. A horizontal half-open posture puts artwork above the hinge and transport below it.

The service owns playback, so folding, rotation, or multi-window resizing does not rebuild the audio session. Insets and enlarged system text are supported. Static artwork avoids constant decorative animation; background UI updates slow down when the activity is hidden. See [validation](docs/VALIDATION.md) for the exact emulator evidence and physical-device limits.

## Local privacy

No custom server, telemetry, or account synchronization. The TorBox key is encrypted with Android Keystore, excluded from backup, and deleted on disconnect. Temporary CDN URLs stay in memory. Search queries go directly to the selected discovery providers; TorBox credentials are never sent to Knaben or Internet Archive. Room contains recording metadata, stable part IDs, positions, and bookmarks. Disconnect pauses unfinished TorBox phone downloads; completed phone audio remains playable offline. Removing a shelf item also removes its local audio downloads and history, without deleting files from TorBox.

## Build

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

V1 supports streaming and optional offline phone audio. It has no cloud sync, casting, Android Auto, or addon marketplace. Torrentio's current stream resources target movies, series, and anime; Narrio uses an audiobook-capable index instead of claiming compatibility with those addons. Chapters support Nero `chpl` M4B metadata and ID3 chapter frames; unsupported chapter encodings retain audio-part and elapsed-time navigation.
