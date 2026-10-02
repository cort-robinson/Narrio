# Narrio v1 validation

Validation performed on Windows on October 2, 2026. The package is a native Android app, not a web wrapper. This records evidence and limits rather than claiming access to a connected Samsung or TorBox account.

## Automated and native evidence

| Area | Result and evidence |
| --- | --- |
| Build | Debug, instrumentation, and R8-optimized signed release APKs build successfully against SDK 36. |
| Unit contracts | 11 tests passed: recording metadata, MP3/M4B separation, natural ordering including discs, chapter parsing, real torrent multipart upload contract, readiness flags, source/file identity, and sanitized errors. |
| Android lint | Zero errors; 35 warnings, principally available dependency upgrades and optional Kotlin/SDK lint suggestions. Stable versions are pinned. The exported MediaSession service restricts controller access in its callback. |
| Live discovery | Native UI search for `pride prejudice` found a distinct version and Karen Savage narration. Curated metadata includes actual narrator, language, playable MP3/M4B files, and torrent information. |
| Real multipart audio | Internet Archive Secret Garden, 27 MP3 parts. Native test jumped to part two at two minutes, bookmarked the stable part, changed speed, continued behind the activity, saved progress, and recreated the activity. An end-of-part timer stopped on the next file boundary. |
| Real M4B | Whole-book Secret Garden played natively; a seek to 90 minutes succeeded. |
| Device persistence | Native Room tests preserve independent MP3/M4B histories. A full `am force-stop` followed by launch restored Secret Garden part two at 126,410 ms. See `verification/restart.json`. |
| Credential storage | Android instrumentation verified that stored preferences do not contain the synthetic test key and that disconnect removes both encrypted data and Keystore alias. No actual account key was used. |
| Temporary URL renewal | Native test returned HTTP 403 for an expired synthetic CDN link, resolved a fresh link through the TorBox API parser, and retried the same byte offset, 543,210. Player identity never included the API key. This is a controlled contract test, not live TorBox streaming. |
| Screen-off and system controls | Optimized release accepted Android system pause/play commands. With the screen off for seven seconds, playback remained PLAYING and position advanced more than five seconds. See `verification/release-smoke.json`. |
| Network interruption | Optimized release: Wi-Fi and mobile data disabled, seek outside the current buffer, then connectivity restored. Playback resumed at the requested 592,160 ms. A final signed-release check jumped to unbuffered part five while offline, restored connectivity, and tapped **Try again**. Playback recovered and the old error panel cleared. See `verification/network-recovery.json` and `verification/network-recovery-final.json`. |
| Hinge behavior | WindowManager test hooks injected vertical and horizontal half-open hinges. Assertions verified discovery/transport separation, controls below the tabletop hinge, and unchanged source/part/two-minute position. Generic emulator sensor configuration did not produce a platform FoldingFeature, so physical sensor validation is not claimed. |
| Native suite | Phone run: six tests passed, one wide-window hinge test skipped by its size assumption. The hinge test passed separately at unfolded dimensions. Logs are in `verification/instrumentation-final.txt` and `verification/fold-posture-final.txt`. |
| APK | Android APK Signature Scheme v2 verified with a 3072-bit RSA signer. Installation and real public audio playback succeeded on the API 36 emulator. |

## Visual review

Native screenshots cover 1080×2400/density420, unfolded 1848×2448/density360, and cover 1248×1972/density420. Day, Night, and system font scale 1.3 were captured. There are detail, source, player, bookmark, shelf, settings, and synthetic hinge captures in `.impeccable/review/`.

The first batched inspection corrected the font variant/weight mapping. A final batched inspection validated those changes. A fresh Impeccable reviewer checked all captures and asked for the tabletop upper pane to use substantially more of its available artwork area. The revised pane places a substantial cover and recording context above the hinge. The reviewer marked that correction resolved, with no visible regression in either replacement capture. The review and bounded final verdict are saved in `.impeccable/review/finish-review.md` and `finish-verdict.md`. No HTML/CSS design detector was used for the native implementation.

## Acceptance requiring your phone and account

Authenticated TorBox creation, cache reuse, preparation, file URLs, and playback have not been exercised against a live account. The user chose to connect inside the app. Physical Fold 8 hinge reporting, Samsung taskbar/multi-window behavior, Bluetooth routing, and sustained real-device listening remain unverified. These are the final acceptance checks for the complete search-to-TorBox path in the supplied brief.

1. Install the signed APK and connect TorBox through Settings.
2. Search for The Secret Garden, choose the Ashleighjane recording, MP3 and TorBox delivery. Confirm preparation completes and part one plays.
3. Seek into part two, bookmark a moment, lock the phone, then fold/unfold it and use headset/system controls. Confirm position and narration stay unchanged.
4. Retry after a network interruption. Close/reopen the app and confirm the same later part resumes.
5. Try the M4B alternative. Its saved position should remain independent from MP3. Test a source already present in your account and one requiring preparation.

An installable v1 is delivered, with these live-account/physical-device checks explicitly outstanding. The launch catalog is public-domain LibriVox plus your existing TorBox audio. It does not claim contemporary commercial audiobook coverage. Missing chapter encodings fall back to parts; local saved state is not offline audio.
