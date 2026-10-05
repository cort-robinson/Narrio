# Narrio

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Native Kotlin, Jetpack Compose, Media3 background playback, and Room local persistence, as requested in the supplied brief. Build and design decisions are delegated by the user's request to build and iterate to an installable v1.

## Users

Android listeners who want to discover narrated books, stream cached sources, save books for offline listening, and return to their saved place.

## Product Purpose

Identify a book through metadata-only search, with one result per title/author and descriptions and cover art. From its details, explicitly discover matching recordings with usable public or cached audio, identify narrator and language when verified, and stream without downloading the book to the phone. Offer explicit phone downloads for offline listening. Preserve progress, a personal shelf, bookmarks, playback speed, and a sleep timer on the device.

Offer highlighted follow-along passages in the Listening room using local EPUB/text imports, companion files, or public ebook lookup. Supplied timestamps are specific to their audio part; other text uses explicitly estimated timing and saved manual matches. A book with attached EPUB or text opens in a full reader (pages or scrolling, typography and Appearance colours, contents, page numbers or percentage, time left in the chapter, footnotes, image zoom, a two-page spread when unfolded) that shares one content cursor with follow along; publication scripts and remote content never load. Automatic exact alignment and together mode are later work; see docs/EREADER.md.

## Operating Context

Direct connections to catalog and delivery providers; no custom backend. The user connects TorBox in the app. Physical-phone testing uses Android media controls without reading the credential or generated media links.

## Capabilities and Constraints

- Catalog, recording discovery, delivery, playback, and library are separate modules.
- Recording and part identifiers own progress; temporary URLs do not.
- V1 needs source-backed discovery, single-file and multipart playback, preparation state, background media controls, and local persistence.
- Layouts must adapt to the current window size on Android phones, larger screens, and multi-window sessions, while honoring system insets and text scaling. Foldables, including the Galaxy Z Fold 8, receive hinge/posture support and retain playback across folding; this is a compatibility requirement within the broader Android experience.
- Working installation package and honest validation evidence are required.
- Book identification uses Audible with Google Books and Open Library fallbacks, collapsing metadata editions by normalized title and author. Recording discovery from book details combines Knaben's audiobook release index, Internet Archive's LibriVox catalog, and the user's TorBox audio. Indexed recording metadata remains visibly unverified; matching identity, audio files, and checked availability determine which sources appear.
- Matched book descriptions, authors, catalog narrators, and cover art enrich indexed/account releases through Audible with an Open Library fallback. Recording identity and source availability remain separate; catalog narration is labeled, original releases stay visible, and metadata failures preserve listening and saved details.
- Initial search does not check audio availability. Source lookup is explicit from details and shows matching usable public/cached audio with ready sources first. Existing saved uncached cloud preparation and phone downloads require separate explicit actions. Phone downloads use Wi-Fi by default and can be paused, resumed, retried, or removed.
- Local Appearance settings offer five paired palettes, one named custom palette with separate Night/Day colors, four bundled or system font choices, and three app text sizes. Mode, palette, font, and size remain independent; custom edits require an explicit save, and restoring defaults retains the saved custom palette. These settings preserve the Listening room defaults, system text scaling, artwork, and listening behavior without requiring network access.

## Brand Commitments

Narrio. The user asks for a special and impressive UI/UX and delegates aesthetic decisions.

## Evidence on Hand

The supplied architecture brief is at C:/Users/cortr/.codex/attachments/75da23ca-e27c-428b-b83e-85a017da7589/pasted-text-1.txt. The implemented native app has emulator evidence for real public audio, offline downloads, playback persistence, and injected fold postures. The user installed 1.0, connected TorBox, selected M4B, and reported an uncached source preparing with a one-hour dashboard ETA. The user subsequently confirmed that the requested 1.1 cached-M4B phone/folding flow works. Direct checks of the signed 1.1 release on the connected Android 17 phone verified live 30-part TorBox MP3 playback, later-part seeking, system headset-hook controls, background and screen-off playback, network recovery, and process restart at the saved part/position. The phone was returned paused at its original offset with both networks restored and the temporary test helper removed. See verification/physical-android-1.1.json for exact scope and limits. The user's later cache-first and phone-download instructions supersede the brief's original offline-download deferral.

## Product Principles

- Identify the book first; verify listening sources separately.
- Keep uncertain recording details visible.
- Preserve a listener's place and control across screen changes.
- Keep credentials and listening history local.
- Earn visual quality through thoughtful content and native interaction.

## Accessibility & Inclusion

Honor font scaling, system motion settings, screen-reader descriptions, minimum 48 dp touch targets, and high-contrast text. Implement dark and light reading environments.
