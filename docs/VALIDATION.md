# Narrio 1.1 validation

Validated on Windows on October 2, 2026. Narrio is a native Android application. The signed 1.1.0 update adds cache-first discovery and optional phone downloads. This document distinguishes real network/audio evidence, controlled provider fixtures, and checks that still require the user's account and phone.

## Current build and contract evidence

| Area | Result and evidence |
| --- | --- |
| Build | Debug, instrumentation, and R8-optimized signed release APKs build successfully against SDK 36. Version 1.1.0, code 2, retains the 1.0 signing identity. |
| Unit contracts | All 17 JVM tests pass: 12 provider contracts and five audio identity/chapter tests. Coverage includes cache response shapes, selected-format completeness, rejection of uncached streaming before torrent creation, readiness flags, magnet/torrent creation, natural ordering, uncertain indexed metadata, narrator parsing, and sanitized errors. |
| Android lint | Zero errors and 38 warnings, principally dependency upgrade notices and optional Kotlin/SDK suggestions. The exported MediaSession service restricts controller access in its callback. |
| Live discovery | Native search finds a distinct Pride and Prejudice recording narrated by Karen Savage. A real Knaben audiobook search returns multiple distinct Secret Garden release hashes. Indexed narrator, author, language, and abridgment are not treated as verified metadata. |
| Playback regression | Six existing listening/persistence cases pass across the baseline run and targeted corrections. The first run passed four cases; two test assumptions were updated to wait for the actual search result and explicitly select MP3 now that format selection is remembered. Both corrected cases then passed. See `verification/listening-1.1-baseline.txt` and `listening-1.1-corrections.txt`. |
| Public multipart audio | Real Secret Garden MP3 playback supports later-part seeking, stable bookmarks, speed, background continuation, activity recreation, and the end-of-part timer. Whole-book M4B seeking to 90 minutes also passes. |
| Persistence and credentials | Native tests preserve independent source/part histories, verify encryption and removal of a synthetic key, and renew an expired synthetic CDN URL while retaining byte offset 543,210. No live account key was available to the development environment. |
| Cache-first actions | Two native tests verify cached-format streaming/download actions, disabled streaming for an uncached alternative, explicit cloud preparation, pending M4B selection, and old playback updates preserving pending preparation. These use synthetic cache/progress fixtures. See `verification/source-experience-phone-final.txt`. |
| Adaptive states | The source-state case passes at font scale 1.3 and unfolded dimensions. The supported WindowManager hinge test also passes at unfolded size. See `verification/source-experience-large-text-final.txt`, `source-experience-fold-final.txt`, and `source-experience-fold.txt`. |

## Real phone-download and release evidence

Three native offline/discovery tests pass in `verification/offline-native-final.txt`:

- A real LibriVox Raven M4B downloads completely, plays with Wi-Fi and mobile data disabled, seeks to five minutes, and advances in the background.
- Two real Secret Garden MP3 parts support pause/resume, complete downloading, and later-part playback without connectivity. Streaming the same source first leaves the offline audio cache unchanged.
- The public Knaben audiobook API returns real, distinct release identities on Android.

Downloads use the durable Media3 download index and private audio cache. Completion means all files in the selected source are downloaded; shelf metadata alone does not make a book available offline. Ordinary streaming reads completed phone audio when available and does not write new audio into that cache.

The final signed R8 release was installed and cold-started with both networks disabled. The downloaded Raven M4B entered PLAYING at 75,229 ms; `verification/release-offline.json` records the result. A debug cold restart also played downloaded M4B at 311,068 ms; see `verification/offline-restart.json`.

A signed 1.0-to-1.1 replacement install retained the existing shelf and Secret Garden part index 5. Playback advanced from the previous 166,997 ms to 170,293 ms; see `verification/upgrade-release.json`. The package signature, size, and SHA-256 are recorded in `verification/package-1.1.json` and `apk-signature-1.1.txt`.

## Native visual review

The 1.1 review includes phone 1080×2400/density420 at font scale 1.0 and 1.3, unfolded 1848×2448/density360, and a real release shelf at 1248×1972/density420. All 13 captures are in `.impeccable/review/v1.1/`.

The 12 cache/preparation/settings captures are explicitly synthetic native test states; they establish layout and action behavior, not a live TorBox cache result. `offline-shelf-release-phone.png` shows an actual completed M4B download in the signed release. Scrolled captures show the named review region rather than claiming a full-page screenshot.

The fresh Impeccable finish reviewer returned **ship**, with no material fixes for the introduced regions. The report is `.impeccable/review/v1.1/finish-review.md`. The existing Listening room typography, Night/Day palette, artwork, adaptive navigation, and native controls remain the design contract. No approved decision comp or QUALITY BAR card was available; no browser/CSS detector ran for this native application.

The earlier 1.0 review corrected typography mapping and tabletop artwork sizing. Its evidence remains in `.impeccable/review/finish-review.md` and `finish-verdict.md`.

## Retained 1.0 release evidence

The original optimized release accepted system pause/play commands and continued playback while the screen was off. Network-loss tests recovered at the requested position and cleared the recovery panel. See `verification/release-smoke.json`, `network-recovery.json`, and `network-recovery-final.json`. These are baseline release checks; they are not represented as newly repeated 1.1 device tests.

Injected vertical and horizontal half-open WindowManager features verified pane separation, tabletop controls below the hinge, and retained source/part position. Generic emulator sensors did not produce a platform FoldingFeature. Neither injected posture evidence nor screenshots establish Samsung hardware geometry, gestures, refresh rate, or runtime performance.

## Acceptance requiring the user's account and phone

The user installed 1.0, connected TorBox, selected M4B, and reported an uncached source with a one-hour dashboard ETA. That establishes an observed preparation flow. Successful authenticated cache discovery, cached-source registration/reuse, resolved playback URLs, and TorBox audio playback remain unverified in this development environment.

1. Install 1.1 over the existing app and confirm the account, shelf, bookmarks, and listening position remain present.
2. Search **Ready to stream**, inspect the exact release/narration and selected format, then choose **Stream now**. Confirm playback starts without a phone download or uncached preparation wait. If there are no cached matches, **All sources** exposes alternatives and their availability; it does not promise that a particular title is cached.
3. Choose **Download to phone** on a ready source, wait for **Available offline**, disable connectivity, close/reopen Narrio, and play/seek into the downloaded recording.
4. Seek into a later part, bookmark a moment, lock the phone, fold/unfold, resize a multi-window session, and use headset/system controls. Confirm the recording and position remain consistent.
5. Exercise a network interruption and the independent M4B/MP3 saved positions. Uncached cloud preparation should occur only after choosing **Prepare in TorBox**; its progress belongs to TorBox and does not download audio to the phone.

Physical Fold 8 posture reporting, Samsung taskbar/multi-window behavior, Bluetooth routing, and sustained listening remain pending. The installable update is delivered with those limits visible. Search coverage and cache availability depend on the providers; Narrio does not promise contemporary commercial coverage or audiobook support from movie/TV addons.
