# Narrio validation

## Verified uncached source discovery: patch candidate

v1.4.1 still required every indexed source to be cached and rejected collections before inspecting their cached files. The matcher now accepts a collection only when author evidence and an exact book file/folder identify a separate recording. A public manifest for `Eragon, Eldest, Brisingr - Christopher Paolini` (hash `ded827306725eb1f65e98d8642b74264a9bad752`) contains a separate `Eragon/Eragon.m4b`; controlled cache tests now select that file and exclude all Eldest/Brisingr audio. Different books in the same torrent receive distinct recording identities. The user's previously streamed release is unknown, so this is a confirmed rejection path rather than a verified account diagnosis.

Source discovery also reads hash-verified public torrent file metadata for uncached releases with reported seeders, rechecks book identity, and presents them with explicit TorBox preparation. Cached sources stay first. Partial cached file lists cannot mark an entire verified recording ready. Streaming and phone downloads remain disabled until the selected source is ready.

A separate read-only live probe ran the new production discovery code against Knaben and iTorrents on October 3, 2026, with a **controlled** TorBox cache miss. It returned `Christopher Paolini - Eragon`, hash `b027dbb27615ab2ec9a434ad4cb7d74455d28be6`, ten reported seeders, and 141 non-sample MP3 files. The bencoded metadata's original info hash matched the indexed hash. No torrent was created, no audio was fetched, and no authenticated TorBox request was made. This confirms live public metadata discovery, not the user's cache state or playback.

Controlled regression tests cover the full index/cache-miss/file-metadata path, absence of account creation/audio requests, credential separation, hash mismatch, malformed/oversized metadata, unsafe paths, book-specific collection selection/identity, other titles/authors, samples, dead releases, cache ordering, and partial cache readiness. All 71 JVM tests, debug/instrumentation builds, and lint pass locally (zero errors, 39 existing warnings). Native CI includes an uncached source label and explicit-preparation check. Physical-device and authenticated-provider playback remain unverified; the local T3 Android surface lacks SDK command-line tools and no ADB device is attached.

## Source-discovery matching regression: v1.4.1

The Eragon investigation reproduced false rejections using the public Knaben release names `Christopher Paolini.Eragon.The Inheritance Cycle 1` and `Paolini - Eragon`. Release-name matching now accepts explicit series annotations and surname/initials author segments for distinctive titles. Cached audio paths can supply full title/author evidence omitted from a release name; author validation runs again after cache checks or public metadata hydration. Catalog subtitles receive one bounded base-title lookup, and account recordings are matched locally rather than discarded by provider substring filtering.

Local JVM regression coverage includes those live release-name examples, wrong Eragon volumes, a multi-book bundle, incorrect author initials, ready account/indexed sources, filename author evidence, deduplication, subtitle lookup, and partial-provider failure. All 66 JVM tests, debug build, and lint pass. Cache state, compatible non-sample audio, and direct HTTPS availability remain required; no bitrate floor was introduced.

The public lookup verifies release names and catalog responses. It does not verify authenticated TorBox cache availability or streaming on a physical device. Those checks were not performed for this fix.

## Metadata-first discovery: v1.4.0

Initial discovery identifies books through catalog metadata and collapses known editions by title/author. Source lookup is explicit from book details and requires matching recording evidence, usable non-sample audio, and direct public or checked cached availability. Selecting a recording retains its original playback identity.

- Local `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `assembleDebugAndroidTest` pass: 63 JVM tests, zero failures, zero lint errors, and 39 lint warnings. Fifteen new JVM cases cover catalog deduplication/fallback/cancellation, edition aliases, source matching and partial-provider failures, and recording-identity retention. Provider contract fixtures also cover preview/sample exclusion.
- All 16 release-tooling tests pass with `npm test`; `git diff --check` passes.
- CI includes the controlled `BookDiscoveryExperienceTest` for metadata-only book details, explicit source choices, and returning from the recording to its book. Local Android execution and rendered review were unavailable: T3 reports missing SDK command-line tools and `adb devices` reports no attached device. Building the instrumentation APK is not an emulator test pass.
- Read-only live public checks returned complete Audible metadata for Project Hail Mary, Knaben release names for that book, and Internet Archive metadata for Pride and Prejudice recordings. These checks verify representative responses, not authenticated TorBox availability or end-to-end streaming.
- No physical-device, bitrate measurement, authenticated TorBox, enlarged-text, or rendered-layout validation was performed for this increment. Source quality here means positive book matching and usable checked audio; it does not assert a minimum measured bitrate.

The independent code reviewer scored the four identified matching/category/edition fixes resolved. That verdict does not certify visual layouts. Existing listening, follow-along, and appearance evidence below remains historical to its stated builds.

## Follow-along increment: unreleased branch

The new **Listening room → Follow along** increment was initially verified on the 1.1 source baseline, before integration with the newer metadata, appearance, and release automation. Those feature runs used version 1.1.0/code 2 and an unsigned release APK. The integrated branch retains automated versioning and the separate Narrio Dev preview identity. No feature update was installed on the user's physical phone.

| Area | Current feature evidence |
| --- | --- |
| Build and JVM checks | Debug and instrumentation APKs and the R8-optimized release build succeed. All 28 JVM tests pass, including 11 new parsing, timing, source-binding, public-lookup, and companion-file cases. See `verification/follow-along-build.txt`. |
| Android lint | Zero errors and the same 38 pre-existing warnings. |
| Phone | Five native tests pass: Android URI import and actual Media3 playback/seek/speed, durable timing adjustments and recreation, part-specific VTT and removal, real public EPUB acquisition, whole-recording chapter navigation, and the 3 → 4 database migration. See `verification/follow-along-phone.txt`. |
| Large text and expanded window | All three introduced UI cases pass at font scale 1.3 on the phone and at 1848 × 2448/density 360. See `verification/follow-along-large-text.txt` and `follow-along-expanded.txt`. |
| Existing audio behavior | The real multipart Secret Garden regression passes: later-part seek, speed, bookmark, end-of-part sleep, background advancement, and restoration after activity recreation. See `verification/follow-along-playback-regression.txt`. |
| Live public text | Android calls Gutendex and downloads a real Gutenberg Secret Garden EPUB, parses its chapter structure and text, and reloads the saved document. This is live acquisition evidence, separate from synthetic provider contracts. |
| Migration and isolation | The native migration test opens an actual version-3 database containing a shelf entry, bookmark, independent source history, and pending M4B preparation. Version 4 preserves those records; removing text cascades only text bindings. |

After integrating `dev` at `f7b5a31` on October 3, all **48 JVM tests**, **16 release-tooling tests**, debug/instrumentation builds, and the R8-optimized unsigned release build pass. Lint has zero errors and 39 warnings. All **four controlled native follow-along/migration cases** pass on the private phone emulator with the current typed appearance API. See [integration build](../verification/follow-along-integration-build.txt), [release tooling](../verification/follow-along-integration-release.txt), and [native checks](../verification/follow-along-integration-native.txt). CI now includes those four controlled cases alongside the five existing smoke tests; live public lookup stays separate. The original capture and live-provider matrix below remains its separately scoped baseline.

The 24 original native screenshots in `.impeccable/review/follow-along/` cover acquisition, estimated text, matching, chapter choice, Day theme, management, supplied cues, and the wrong-part warning at phone, large-text phone, and expanded sizes. They use synthetic Garden Walk prose and real locally generated WAV playback. The `fold` filename suffix denotes a generic expanded emulator window, not physical Samsung geometry. The capture README records dimensions and provenance. Live authenticated TorBox ebook fetching was not exercised; companion references and separation from audio are covered by provider contracts.

The fresh Impeccable finish reviewer returned **ship** for the introduced surface, with valid evidence and no material fixes. Its report is `.impeccable/review/follow-along/finish-review.md`. The existing Night/Day palette, semantic typography, native controls, and adaptive player remain the design authority. No HTML/CSS detector, image comp, or QUALITY BAR card applies to this native local extension.

These checks establish a basic highlighted listening surface with persistent text and adjustments. Untimed ebooks use explicitly estimated timing. Supplied VTT cues are limited to their selected audio source/part. Arbitrary exact narration alignment, standalone ebook reading, and the full reader remain future work; their acquisition, identity, alignment, and shared-cursor logistics are in [FOLLOW_ALONG.md](FOLLOW_ALONG.md).

## Current branch Appearance evidence

The Appearance update has a **ship** finish-review disposition. The initial checks below used the 1.1.0 source baseline before integration with the latest `dev` metadata and release workflow. Signed 1.1/1.2 installation, audio, and physical-phone results below remain historical release evidence.

| Area | Result and evidence |
| --- | --- |
| Build and lint | `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, and `lintDebug` pass. See [appearance-build.txt](../verification/appearance-build.txt) and [appearance-build-final.txt](../verification/appearance-build-final.txt). |
| JVM tests | All 22 pass: the existing 17 provider/audio-identity tests plus five Appearance tests. Appearance covers codec fallback/normalization and generated contrast across 256 grey and 500 random backgrounds. |
| Native executions | Ten pass in `AppearanceExperienceTest`: four on phone 1080×2400/density420, four on expanded 1848×2448/density360, and two complete interaction workflows on the phone at system font scale 1.3. The enlarged run includes Android font and app Large (1.2). See [appearance-phone.txt](../verification/appearance-phone.txt), [appearance-expanded.txt](../verification/appearance-expanded.txt), and [appearance-large-text.txt](../verification/appearance-large-text.txt). |
| Interaction and persistence | Native checks cover legacy-mode migration, unrelated-preference preservation, independent settings, local store round-trip, activity recreation, preset starters, swatches, HSV, strict hex validation, separate Day/Night edits, save/cancel, retained custom palettes on switch/reset, and saveable drafts. Contrast checks include all five presets and eight extreme custom seed sets in both modes. |
| Visual review | All 18 named region captures in [.impeccable/review/appearance](../.impeccable/review/appearance/) were reviewed across phone, expanded, and enlarged-text layouts. Synthetic Aurora colors and story previews establish Appearance rendering. These are scrolled window captures of named regions. The [finish review](../.impeccable/review/appearance/finish-review.md) returns **ship** with no material fixes. |

[appearance.json](../verification/appearance.json) contains the complete matrix, five log references, capture inventory, and exact scope. A test-only side-by-side application ID protected the installed Narrio app. This update adds no physical-device, live-audio, signed-release, or performance validation claims.

After integrating `dev` at `94154c3` on October 3, **37 JVM tests**, **16 release-tooling tests**, debug/instrumentation builds, and lint pass. All **four Appearance native cases** also pass on the private phone emulator at 1080×2400/density420 and system font scale 1.0. The new metadata UI test now uses the typed appearance mode API. See [appearance-integration.json](../verification/appearance-integration.json), [integration build](../verification/appearance-integration-build.txt), [release-tooling tests](../verification/appearance-integration-release.txt), and [native tests](../verification/appearance-integration-native.txt). The original matrix above remains its separately scoped baseline.

## 1.2 metadata update

Validated on October 2, 2026. These observations apply to the manually built signed 1.2.0 update (version code 3), before release automation. Automated releases derive larger Android version codes from SemVer; their manifest and linked Actions run record the exact package and CI checks. CI emulator checks do not establish a new physical-phone/provider acceptance result. The metadata update preserves recording/source identity and the Room schema.

- All **32 JVM tests** pass, including 15 new metadata contracts. They cover title/author ambiguity, summaries and collections, podcast rejection, multiple narrators, release narrator hints, bilingual/reversed-author names, source narrator preservation, secure high-resolution images, cached information across independent releases, Open Library fallback, partial work-detail failure, outages, forced refresh, cancellation, HTML entities/initials, and backward-compatible saved book JSON.
- Debug, instrumentation, and R8-optimized signed release APKs build successfully. Android lint reports **zero errors and 39 warnings**, primarily the existing dependency/SDK/Kotlin suggestions and URI helper suggestions.
- Five final native cases pass on the phone emulator: two new metadata cases, the two existing source-experience cases, and independent durable source positions. See `verification/metadata-native-phone-final.txt`. The earlier native run in `metadata-native-phone.txt` preceded the cover overlay correction.
- Live Android requests retrieve **Project Hail Mary**, author **Andy Weir**, catalog narrator **Ray Porter**, the full publisher description, and an HTTPS cover image. Transactional shelf enrichment preserves the synthetic test's 123,000 ms position, source layout, part ID, and per-source history. These requests use the actual public catalog; they do not use a TorBox account or establish availability of that recording.
- The real artwork is rendered successfully, without the generated title/author overlay. Native UI checks verify metadata attribution, original release visibility, refresh controls, failed-artwork fallback, and an enabled Listen action while metadata is loading. Audio availability in these UI states is synthetic.
- The same metadata UI case passes with **font scale 1.3** on a compact phone and with **1848×2448/density360** expanded bounds in Day mode. See `verification/metadata-native-large-text.txt` and `metadata-native-expanded.txt`. The compact and large-text checks use Night mode. Nine final screenshots are in `.impeccable/review/v1.2/`; emulator font scale, size, and density were restored afterward.
- `apksigner verify --print-certs` confirms the retained signing identity. A signed 1.1.0-to-1.2.0 replacement install and cold launch succeed on the release emulator; see `verification/apk-signature-1.2.txt` and `metadata-release-upgrade.txt`. The package digest and delivery paths are recorded in `verification/package-1.2.json`.

This update was verified on emulators. It does not claim a new physical-phone playback, fold, or battery test. The T3 Device panel could not attach because Android SDK Command-line Tools were missing; native checks used the already installed SDK/emulators. Live Open Library fallback availability was not required by the successful Audible smoke test; its fallback behavior is covered by deterministic provider contracts. Catalog narrator/cover metadata identifies a catalog edition, and indexed recording language and abridgment remain explicitly unverified.

## Retained 1.1 validation

Validated on Windows on October 2, 2026. Narrio is a native Android application. The signed 1.1.0 update adds cache-first discovery and optional phone downloads. This document distinguishes real network/audio evidence, controlled provider fixtures, and checks that still require the user's account and phone.

## Signed 1.1 build and contract evidence

| Area | Result and evidence |
| --- | --- |
| Build | Debug, instrumentation, and R8-optimized signed release APKs build successfully against SDK 36. Version 1.1.0, code 2, retains the 1.0 signing identity. |
| Unit contracts | All 17 JVM tests pass: 12 provider contracts and five audio identity/chapter tests. Coverage includes cache response shapes, selected-format completeness, rejection of uncached streaming before torrent creation, readiness flags, magnet/torrent creation, natural ordering, uncertain indexed metadata, narrator parsing, and sanitized errors. |
| Android lint | Zero errors and 38 warnings, principally dependency upgrade notices and optional Kotlin/SDK suggestions. The exported MediaSession service restricts controller access in its callback. |
| Live discovery | Native search finds a distinct Pride and Prejudice recording narrated by Karen Savage. A real Knaben audiobook search returns multiple distinct Secret Garden release hashes. Indexed narrator, author, language, and abridgment are not treated as verified metadata. |
| Playback regression | Six existing listening/persistence cases pass across the baseline run and targeted corrections. The first run passed four cases; two test assumptions were updated to wait for the actual search result and explicitly select MP3 now that format selection is remembered. Both corrected cases then passed. See `verification/listening-1.1-baseline.txt` and `listening-1.1-corrections.txt`. |
| Public multipart audio | Real Secret Garden MP3 playback supports later-part seeking, stable bookmarks, speed, background continuation, activity recreation, and the end-of-part timer. Whole-book M4B seeking to 90 minutes also passes. |
| Persistence and credentials | Native tests preserve independent source/part histories, verify encryption and removal of a synthetic key, and renew an expired synthetic CDN URL while retaining byte offset 543,210. The real user credential remained on the phone and was not read during the subsequent media-session checks. |
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

## Completed user and physical-phone acceptance

The user installed 1.0, connected TorBox, selected M4B, and initially reported an uncached source with a one-hour dashboard ETA. After receiving 1.1, the user replied **"It works!"** to the requested **Ready to stream → cached M4B → Stream now** and fold/unfold flow. That contextual user report is retained in `verification/user-device-1.1.json`.

The user then authorized testing the connected phone and asked that it be quick. The installed signed release was 1.1.0/code 2 on SM-F971U1, Android 17/SDK 37, at 1248×1972/density420. Narrio showed 41 ready sources. Direct Android media-session observations established the following results in `verification/physical-android-1.1.json`:

- Live TorBox streaming of the 30-part MP3 **Andy Weir – Project Hail Mary**, including playback of later parts and a two-minute seek in part 2.
- Android headset-hook events toggled PLAYING → PAUSED. Playback advanced with Narrio hidden and with the screen observed asleep.
- Disabling Wi-Fi and mobile data produced an actual unvalidated network and player error on a newly requested part 5. Restoring connectivity and playback recovered on that same part. The initial connectivity sample was taken before teardown propagated; the later error-state sample proves the interruption. A controller seek issued before this new part's timeline loaded did not establish an offset, so this check does not claim retention of that pre-load seek.
- Force-stopping and reopening the signed app restored part 5 at the exact saved 1,228 ms, paused, and resumed playback on that part. This complements the native later-part and independent-source-history tests.
- The original part 1 position **26,791 ms** was restored and left paused. Wi-Fi and mobile data returned to their original enabled settings. The self-targeted, test-only controller was removed. It did not access account storage, credentials, or media URLs.

V1 acceptance is complete using the combined user, physical-phone, real-audio emulator, contract, and native visual evidence. The complete brief-to-evidence map is `verification/completion-audit-1.1.json`.

## Validation limits

The short phone borrow covered functional smoke checks, not battery endurance, Bluetooth route changes, exact Samsung hinge/taskbar geometry, thermal behavior, or refresh-rate performance. System headset-button events were exercised; physical Bluetooth routing was not separately measured. Basic folding is user-confirmed and separating/tabletop layouts are covered with WindowManager test features.

Natural live CDN expiry was not awaited. Controlled native tests exercise an expired CDN response and renewed playback at byte offset 543,210. Phone downloads were tested with actual MP3/M4B files and a signed-release offline cold start on the emulator; a fresh large download was not queued on the user's phone during this short borrowing window.

Individual live torrent creation/reuse branches were not separately observed; their provider contracts are tested. Search coverage and cache availability depend on the providers. Indexed narration, language, and abridgment remain visibly uncertain. Narrio does not promise contemporary commercial coverage or audiobook support from movie/TV addons.
