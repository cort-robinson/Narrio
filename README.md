# Narrio

A native Android listening room for audiobooks. Find a book, choose a matching listening source, stream through TorBox or Internet Archive, and keep your place on a personal shelf.

## Install the update

Download the signed APK from the [latest GitHub release](https://github.com/cort-robinson/Narrio/releases/latest). Each release includes checksums, its source commit, and Android/signing details. Android 8.0 or later is required. Install over an earlier Narrio release to retain your TorBox connection, shelf, bookmarks, and listening progress.

In-app updates are available in development previews containing this feature; stable receives them when the feature is released. Install an APK with the updater once, then use **Settings → App updates** to allow Narrio to install its own updates. Stable follows normal releases; Narrio Dev follows dev previews whose full CI passed. Automatic downloads use Wi-Fi. On Android 12+, installation waits until playback is paused and the app is closed; older Android uses **Install update**. Android may ask for confirmation. You can turn automatic updates off or check manually. See the [update guide](docs/DEVELOPMENT.md#in-app-updates).

1. Download the APK directly on your Android phone and open it in Files.
2. If Android requests it, allow that app to install unknown apps, then install Narrio.
3. Tap **Connect TorBox** wherever Narrio offers it (on a book, in a source list, or on Discover), or use the TorBox section at the top of **Settings**. Paste your API key (**Find my API key** opens your TorBox settings) and tap **Connect TorBox**. The book you were looking at stays open and checks TorBox right away. Your TorBox plan must include API access. Free LibriVox recordings play without TorBox; while it isn't connected, Discover says so once, and **Not now** hides that hint for 30 days.
4. Search for a title or author and open the book. A book has one page however you reach it: from Discover, search, your shelf, or Continue. Narrio finds and chooses a recording automatically; tap **Listen**. Once you have listened, the same button says **Resume** with the time left and always plays your own recording, never a different match. The line under it names the narrator and whether it plays now, and **Change** opens **Choose a recording**, which lists the different versions in plain words, marks yours as **Listening now**, and plays, gets ready, or downloads the one you pick in one tap. A recording that isn't ready offers **Get it ready**, which asks TorBox to prepare it; switching recordings keeps the place you had in the old one. Getting a book ready keeps going after you leave it or close Narrio: Narrio checks TorBox in the background (after about 2, 5, 10, and 15 minutes, then every half hour, only while connected to a network), and the shelf shows **Getting ready in TorBox**, **Ready to listen**, or **Couldn't get it ready · Try another recording**. If TorBox stays unreachable, rejects your key, or is disconnected, checking stops and the shelf says **Stopped checking TorBox**; reconnecting or **Check again** on the book's page resumes it. Narrio asks for notification permission the first time a book starts getting ready (or plays); with it, a notification says when the book is ready to listen (**Listen** plays the recording it announced) or couldn't get ready. Without it, the shelf still updates. **Download for offline** saves the recording to your phone, after which Listen says **Play offline**. **Can't find the right one? Advanced** shows every source's results, release names, seeders, formats, and files, and lets you fix the recording yourself: search with your own words (a UK or US title, the series name, another spelling of the author), mark a release **Not this book** so it never wins again (undo it under **Hidden**), paste a magnet or `.torrent` link, pick any download from your TorBox library, add MP3/M4B/M4A files or a folder from your phone (they play offline and are never uploaded), and choose which files of a release play and in what order. Switching from a recording you've started offers **Start near** your place (approximate, marked ≈), where you stopped in that recording, or **Start from the beginning**. Streaming requires no phone download.
5. To listen offline, choose **Download to phone**. Downloads use Wi-Fi by default. **My shelf** offers progress, pause/resume/retry, **Play offline**, and removal. Settings can permit mobile-data downloads.

Browsing lists popular audiobooks from Apple's current audiobook charts by category (fiction, thriller & mystery, horror, fantasy, romance, comedy, classics, kids & teens, nonfiction, biography, history, science, self-help, business, and travel), so it favors widely published books over store exclusives such as Audible Originals; brand-new releases follow established titles because sources take time to carry them. Searching uses Apple's audiobook catalog first, then fills gaps such as store exclusives from the Audible book catalog, Google Books, and Open Library. Audible's own productions carry an "Only from Audible" banner on their artwork, so those books show the publisher's ebook cover when one exists. Editions with the same normalized title and author appear once; full descriptions and covers take priority. A book result identifies the work and does not guarantee a playable audiobook. Source providers and TorBox are contacted when you open a book you haven't listened to, or open the recording chooser on one you have.

Opening a book checks LibriVox public audio and, when connected, enabled source add-ons, plus your TorBox account audio. Only recordings whose release names or files identify both the title and author are chosen automatically; title-only, ambiguous "Series: Title" names, and unverified releases wait in the search results for your review. Matching title/author releases need usable public or cached audio, or hash-verified torrent file metadata with reported seeders. Collections appear only when their author and a separate file or folder clearly identify the requested book; other books' files are excluded. Uncached releases are labelled **Needs time to get ready** and require an explicit **Get it ready**. Wrong books, sequels, summaries, unseparated collections, samples, and unchecked cache results are excluded. Ready audio is chosen first, preferring the book's language, unabridged readings, whole-book files, and healthy releases. Seeder counts can change, so preparation time and success depend on live availability. Exact recording narration and language can remain unverified; inspect the release and audio files. Catalog narrators describe metadata editions and never identify a chosen recording automatically.

Successful metadata lookups are cached for a day and saved with shelf items. Metadata failures keep existing details and allow saved recordings to remain usable. **Refresh book details** (in the recording chooser's Advanced view) retains the recording's source, files, and listening position.

## Included

Read along is available on this development branch and its Narrio Dev preview. Stable updates are published from `master` through the [development workflow](docs/DEVELOPMENT.md); see [current validation](docs/VALIDATION.md) for feature evidence.

- Metadata-only title/author search with one result per book, followed by explicit discovery of matching narrated recordings.
- TorBox account connection, batched hash/file cache checks, cached-source filtering, existing-source reuse, torrent/magnet creation, detailed cloud preparation status with background checks and ready/failed notifications, and temporary per-file playback links.
- Explicit offline phone downloads with storage estimates, Wi-Fi preference, progress, pause/resume/retry, and removal. Ordinary streaming does not write to the offline audio cache.
- Whole-book M4B and naturally ordered multipart MP3 playback, 10-second back and 30-second forward skips, precise seeking (slide up from the bar for finer control), speed, and bookmarks. The Listening room shows the chapter playing (with previous/next chapter, or part for multipart files without chapters) and the time left in the whole book, plus the listening time at your speed; until every part's length is known it shows the time left in the current part.
- A sleep timer that pauses after 15–90 minutes, at the end of the chapter (when the recording has chapters), or at the end of the audio part (multipart recordings). The narration fades out over its last seconds. Add 15 minutes from the timer, the media notification, or by shaking the phone in the timer's last minute (Shake to keep listening can be turned off). It keeps running with the screen off and across parts; choosing another book or recording cancels a chapter or part timer, while a minute timer keeps counting.
- Room-backed library and independent saved positions for different recordings and source layouts.
- **Continue** on Discover keeps up to five books in progress in one row, newest place first, each resuming in the mode you used last. **My shelf** sorts by recent activity, title, author, progress, or date added, and searches by title or author (the search button opens the field; shelves over eight books always show it). **Mark as finished** in a book's overflow menu moves it to a folded **Finished** section and off Continue, as does listening through the final part or reading to the last page; seeking or skipping to the end doesn't count. **Mark as not finished**, or listening or reading elsewhere in the book again, brings it back.
- Focusing the empty Discover search field shows up to six recent searches; remove one or **Clear all**.
- **Listening room → Read along**: the full reader highlights the narrated sentence and turns pages with the narration, with a compact player (a side panel when unfolded, controls below the hinge in tabletop). Find ebook searches the recording's own files, your TorBox account, TorBox-cached ebook releases, and Project Gutenberg at once and shows one best match to add and read, with other choices marked when they likely match the narration; Advanced shows each source's own section and searches again with your own words (another title, subtitle, or spelling), kept for that book. Narrio recognizes the narration on the phone to sync text to audio. EPUB/text imports, chapter choice, and manual timing fixes remain available. Text and timing are saved locally.
- Media3 background service and system media controls; pause on headphone disconnection and Android audio-focus handling.
- Night, Day, and System appearance; retrieved cover art with original artwork fallbacks and bundled Newsreader/Manrope typography.

## Appearance on this branch

This development branch adds the expanded **Settings → Appearance** page. Build this branch or use its Narrio Dev preview to try it; stable releases are published from `master` through the [development workflow](docs/DEVELOPMENT.md).

- Choose **Night**, **Day**, or **System** independently of **Listening room**, **Ocean**, **Forest**, **Rosewood**, **Graphite**, **Lavender**, **Amethyst**, **Midnight**, or **Ember**, or the seasonal **Halloween** palette. Each palette has paired light and dark colors; selections apply and save immediately.
- **Pure black** turns Night backgrounds true black for OLED screens: **Reader** for book pages only, or **Everywhere**. The reader's **Aa** sheet has the same **Pure black pages** switch.
- Give any book its own colors from the reader's **Aa** sheet or the Listening room's palette button: the app theme, any palette, or **Match the cover**, which derives a palette from the book's cover. Turn on **Match each book's cover** to make that the default for books without a choice of their own.
- Choose **Custom** or **Create custom theme** to edit one named theme. Start from a preset, then edit Night and Day accent, supporting, and background colors using swatches, HSV sliders, or six-digit hex. The live preview derives readable text and controls. Tap **Save & use theme** to apply the draft; Back cancels it.
- Choose **Narrio** for the original pairing of Newsreader titles and Manrope controls, **Manrope** or **Newsreader** throughout, or **Android** for the device's default family. Fonts are bundled or built in. **Default**, **Comfort**, and **Large** text sizes use 1.0×, 1.1×, or 1.2× multipliers while retaining your device's text-size setting.
- **Restore default appearance** returns to Listening room, Night, Narrio fonts, and Default size. Your saved custom palette remains available when resetting or switching presets.

Appearance preferences stay on the device and work offline. [Appearance validation](docs/VALIDATION.md#current-branch-appearance-evidence) records the branch's build, native emulator, and visual review evidence.

## Adaptive Android layouts

Narrio targets Android 16 and adapts to current window bounds. Compact phone windows have bottom navigation and a mini-player. Windows at least 600 dp wide reveal an 80 dp navigation rail and two independently scrolling panes, including larger screens and multi-window sessions.

Foldables, including the Galaxy Z Fold 8, also receive hinge and posture support through Jetpack WindowManager. Vertical separating hinges keep discovery and playback on opposite sides. A supported horizontal half-open posture puts artwork above the hinge and transport below it.

The service owns playback, so folding, rotation, or multi-window resizing does not rebuild the audio session. Insets and enlarged system text are supported. Static artwork avoids constant decorative animation; background UI updates slow down when the activity is hidden. See [validation](docs/VALIDATION.md) for the exact emulator evidence and physical-device limits.

Settings → **Add-ons** manages Audible, Open Library, AudiobookBay, The Pirate Bay, Knaben audiobooks, and Knaben ebooks. Enable, disable, refresh, remove, or import schema 1.0.0 JSON definitions by HTTPS URL. Saved books and listening progress survive provider changes. Add-ons receive search terms but never the TorBox key or listening history. See the [add-on guide](docs/ADDONS.md).

## Local privacy

No custom server, telemetry, or account synchronization. The TorBox key is encrypted with Android Keystore, excluded from backup, and deleted on disconnect. Temporary CDN URLs stay in memory. Background checks contact only TorBox, with your key, to read the status of books you asked it to get ready; they stop when nothing is getting ready or TorBox is disconnected. Search queries go directly to catalog providers; TorBox credentials are never sent to Knaben, Internet Archive, or Gutendex; when connected, book titles are also searched with your key through TorBox's own release search. Browsing requests a category chart and its book details from Apple; searching sends your search terms to Apple, and cover lookups send an author name. No account data is sent. Metadata lookup sends book/release names to Audible, Google Books, or Open Library; artwork loads from the returned provider image URLs. TorBox credentials, file lists, listening positions, and bookmarks are never sent to metadata providers. Ebook lookup sends the book title and author, or the search words you type for that book, to enabled ebook add-ons and Gutendex; it adds an ebook release to your TorBox account only when TorBox already has it cached. Narration sync downloads a pinned speech model from alphacephei.com once and recognizes audio on the phone; audio and book text are never uploaded. Room contains recording metadata, stable part IDs, positions, finished books, bookmarks, and text attachments/timing matches. Recent searches and the shelf order are saved only in the app's private preferences, which are excluded from Android backup and device transfer; they are never sent anywhere. Original and normalized book text stay in private device storage. Disconnect pauses unfinished TorBox phone downloads; completed phone audio remains playable offline. Removing a shelf item also removes its local audio downloads, book text, and history, without deleting files from TorBox.

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

[Reading along](docs/FOLLOW_ALONG.md) explains read along, ebook lookup, on-device narration sync, estimated and supplied timing, and the shared reading/listening cursor. Narration sync is English-only for now; other languages use adjustable estimated timing.

V1 supports streaming and optional offline phone audio. It has no cloud sync, casting, Android Auto, or addon marketplace. Torrentio's current stream resources target movies, series, and anime; Narrio uses an audiobook-capable index instead of claiming compatibility with those addons. Chapters support Nero `chpl` M4B metadata and ID3 chapter frames; unsupported chapter encodings retain audio-part and elapsed-time navigation.
