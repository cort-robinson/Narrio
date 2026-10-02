# Narrio

A native Android listening room for audiobooks. Real recording discovery, MP3 and M4B streaming, a personal shelf, and playback that stays with you when a Fold opens.

## Install the first edition

The signed package is `artifacts/Narrio-1.0.0.apk`. A copy is also delivered to the Windows Downloads folder. Android 8.0 or later is required.

1. Transfer the APK to your Android phone and open it in Files.
2. If Android requests it, allow that app to install unknown apps, then install Narrio.
3. Open **Settings → TorBox**, enter your API key, and tap **Connect TorBox**. Your TorBox plan must include API access.
4. Search for a book or author, open a narrated recording, tap **Listen**, choose an audio format and **TorBox**, then **Start listening**. If preparation takes time, the recording stays on **My shelf**. Open it later to check its status.

You can immediately try a public-domain recording through **Internet Archive** delivery without an account. The launch catalog contains real LibriVox recordings; **My TorBox audio** also searches audio already in your own account. Contemporary commercial audiobook discovery is outside this launch catalog.

## Included

- Search by title and author, with separate narrated recordings and visible language/narrator metadata.
- TorBox account connection, existing-source reuse, torrent upload, preparation status, file listing, and temporary per-file playback links.
- Whole-book M4B and naturally ordered multipart MP3 playback, 30-second skip controls, speed, a sleep timer, and bookmarks.
- Room-backed library and independent saved positions for different recordings and source layouts.
- Media3 background service and system media controls; pause on headphone disconnection and Android audio-focus handling.
- Night, Day, and System appearance; original cover art and bundled Newsreader/Manrope typography.

## Galaxy Z Fold 8 and adaptive behavior

Narrio targets Android 16 and uses current window bounds and Jetpack WindowManager posture data rather than a device-name check. The cover display has bottom navigation and a compact player. Unfolded windows at least 600 dp wide reveal an 80 dp navigation rail and two independently scrolling panes. Vertical separating hinges keep discovery and playback on opposite sides. A horizontal half-open posture puts artwork above the hinge and transport below it.

The service owns playback, so folding, rotation, or multi-window resizing does not rebuild the audio session. Insets and enlarged system text are supported. Static artwork avoids constant decorative animation; background UI updates slow down when the activity is hidden. See [validation](docs/VALIDATION.md) for the exact emulator evidence and physical-device limits.

## Local privacy

No custom server, telemetry, or account synchronization. The TorBox key is encrypted with Android Keystore, excluded from backup, and deleted on disconnect. Temporary CDN URLs stay in memory. Room contains recording metadata, stable part IDs, positions, and bookmarks. Disconnecting does not remove your TorBox downloads. Removing an item from My shelf clears only its local history and bookmarks.

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

[Architecture](docs/ARCHITECTURE.md) describes provider seams, source identity, persistence, and playback. [Validation](docs/VALIDATION.md) records what was exercised and what still needs an authenticated account and physical device. [Artwork](docs/ART.md) contains provenance and font licenses.

V1 streams audio and saves listening state locally. It has no offline audio downloads, cloud sync, casting, Android Auto, or addon marketplace. Chapters support Nero `chpl` M4B metadata and ID3 chapter frames; unsupported chapter encodings retain audio-part and elapsed-time navigation.
