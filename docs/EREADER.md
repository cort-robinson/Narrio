# Ebook reading and reading/listening sync

Narrio is for audiobooks and ebooks. Either can be used alone or together, with one shared position per book. This document is the agreed product decision record and the work breakdown for the parallel implementation. Shared types live in `app/src/main/java/app/narrio/domain/Reading.kt`; change them only through the contract owner (workstream A) and note the change in its PR.

## Decisions

### Library

- A book can have any number of audio recordings and ebook editions; ebook-only and audio-only books are first-class. `Audiobook` stays the book-level metadata record and its id the book identity (no rename in this effort). One edition per book is active for sync.
- Migration preserves shelf entries, bookmarks, per-recording positions, pending preparation, and existing follow-along text and anchors. Existing anchors use `(resource, offset)` locators and carry over unchanged; the reader maps Readium Locators to that space.
- Shelf: **All / Audiobooks / Ebooks / Both** filters, format badges, and one progress bar from the shared position. **Continue** reopens the mode used last.
- Book details: **Read** and **Listen** for available formats; **Find ebook** / **Find audiobook** for a missing one, using the existing ebook providers (shipped companion file, TorBox account, cached Knaben ebooks, Gutenberg, local import). Importing a local EPUB can create a book, with metadata from the file enriched by the catalog lookup.

### Finding an ebook

- The **Find ebook** sheet is simple first: editions on this phone (choose or remove), one best match with **Add and read**, **Other choices · N** (one flat list of every other ebook found, each with its title, author, language when it differs from the book's, format, and source), and **Choose an EPUB or text file**. **Can't find the right one? Advanced** holds each ebook source's own section and status with Retry, searching with different words, and ebook websites.
- When the book has a recording, ebooks are ranked by how likely their text follows the narration, from names and metadata alone (`NarrationMatch`): the recording's own files first, then the same language as the recording, the same translator when both name one, and "unabridged"; another language or translator, abridgments, excerpts and samples, retellings and adaptations, and single volumes count against an ebook. **Likely matches the narration** needs positive evidence and no caution; a caution reads **May not follow the narration: …**. File size and page counts aren't available from the current sources, so they aren't used. These are hints, never a verified match: after adding, the pairing status from narration sync is authoritative.
- **Search with different words** (Advanced) re-runs every ebook source with the reader's own words, prefilled with title and author, with quick **Title only**, **Without subtitle**, and **Original title** (when the description names one) choices. The words are kept for that book on this phone, reused by later searches and retries, and shown on the simple layer with **Use book details** to reset. Ebooks found only by the reader's words are possible matches that need the reader's check; a match is confirmed only by the book's own title and author. The recording's files are unaffected, and website searches still use the book's details.

### Reader

- Readium Kotlin Toolkit renders reflowable EPUB 2/3. TXT is converted to a minimal internal EPUB and shown by the same reader. Fixed-layout EPUB and PDF are later. MOBI/AZW3, CBZ, and DRM are unsupported.
- Features: paginated and continuous scroll; page turns by tap, swipe, or volume keys; typography (publisher default, the four Appearance fonts, and OpenDyslexic; size, line spacing, margins, justification, hyphenation, publisher-styles toggle); Appearance palettes and Night/Day; table of contents and position scrubber; full-text search; highlights and notes with an annotations list; one bookmark list shared with audio; footnote popups and image zoom; dictionary/translate through Android text-selection actions.
- Progress: print page numbers when the EPUB has a page list, otherwise a percentage; "X min left in chapter" from a reading speed measured and kept on the phone; narration time left when paired.
- Safety: publication scripts, inline event handlers, and remote resource references are removed from every resource before Readium loads it. Readium's own injected scripts are unaffected. A hostile fixture EPUB proves the filter. No per-book override.
- Layouts: unfolded landscape shows a two-page spread when reading alone. Rotation, folding, and font changes keep the same content cursor.

### Sync

- One shared position per book on this phone (`SharedPosition`), carrying an update sequence for a future cross-device sync. Listening progress is no longer the only progress record; per-recording positions remain stored as history.
- Real activity: reading moves the position after about 3 s on a page or two or more page turns; listening after about 10 s of playback (`SyncThresholds`).
- While audio is playing, audio owns the position. Browsing the reader doesn't move it; tapping a sentence seeks.
- Switching modes or recordings starts at the shared position. Moves larger than about one page or 30 s of audio show **Jumped to where you read/listened · Undo**; smaller ones sync silently.
- Pairing is automatic, with a status (matches / partly matches / doesn't match) derived from narration-sync coverage, and one-tap edition switching. Mode-to-mode position sync stays off for a pair that doesn't match.
- Text → audio: always estimate from the nearest anchors and chapter/part matching, then correct with a 20-second recognition window when playback starts; "≈" shows until confirmed. Background whole-book alignment runs for phone-downloaded audio or on Wi-Fi while charging, with an off switch.

### Reading and listening together

- The reader replaces the Follow along passage list. The narrated sentence is highlighted, with an optional word highlight where exact anchors exist (a reader setting).
- Pages turn or scroll automatically; a manual turn pauses that until **Back to narration**. A compact player bar has play/pause, ±30 s, speed, and the sleep timer.
- Unfolded: one text column and a side panel with the player and chapters. Tabletop: text above, controls below.

### Deferred

Text-to-speech for ebook-only books, highlight/note export, fixed-layout EPUB and PDF, cross-device sync, interactive EPUB content.

## Workstreams

Each workstream has its own branch and draft PR. Stay inside your ownership; if you need something from another workstream, depend on the contract interface and note the gap in your PR instead of implementing it.

| | Workstream | Owns | Depends on |
|---|---|---|---|
| A | Library data foundation | All Room schema and the single migration (editions, shared positions, annotations, bookmark cursors, alignment job state), `SharedPositionStore`, `EditionFiles`, multi-edition storage in `FollowAlongStore`, ebook-only books, local EPUB import (data), format/filter queries | contract |
| B | Reader core | Readium dependency and `FragmentActivity` host, sanitizing resource filter and hostile fixture, TXT → EPUB, reader screen, typography/fonts/themes, navigation, TOC/scrubber, progress indicators, footnotes/images/selection actions, Locator ↔ `ContentCursor`, reading activity commits, two-page spread | contract (A's interfaces) |
| C | Sync engine | `PositionMapper`, estimate-and-correct, background alignment worker and its setting, pairing status, listening activity commits, audio-owns-position rule, jump/Undo decision (pure logic), multi-recording mapping | contract (A's interfaces) |
| D | Library and entry experience | Shelf filters/badges/progress, details Read/Listen/Find ebook/Find audiobook, Continue in last mode, import flow UI, match-status display, shared Undo snackbar and "≈" components | contract (A, C interfaces) |
| E | Together mode | Narration highlight decorations, auto-follow, player bar, Follow along replacement, fold side panel and tabletop | B, C |
| F | Annotations and search | Search UI, highlights/notes and annotations list, merged bookmarks | A, B |

E and F start once B's reader component exists. Until A lands, other workstreams may use an in-memory implementation of the contract interfaces inside tests or behind a clearly named temporary adapter; remove it when rebasing onto A.

## Constraints for every workstream

- Follow `AGENTS.md` and `docs/DEVELOPMENT.md`. Conventional Commits. Open a **draft** PR against `feature/ereader-contract` with `## Release notes` and `## Validation`; do not merge.
- Don't modify `Reading.kt` without coordinating; report needed contract changes in the PR.
- Add focused JVM tests and emulator smoke tests for new behavior. Report physical-device checks separately.
- Update `PRODUCT.md`, `docs/FOLLOW_ALONG.md`, or `docs/ARCHITECTURE.md` only for behavior your workstream changes.

## Integrated contracts

The integration branch combines A/B/C/D; its adapters are real storage and sync implementations.
`Reading.kt` keeps its source-compatible interfaces. `EbookEdition.id` fingerprints original bytes;
TXT `ContentCursor.locatorJson` caches a locator into the deterministic internal EPUB, while the
`("text", offset)` parser cursor remains authoritative.

`RoomReadingLibrary.observeBook/observeShelf` provides reactive editions, active selection,
shared progress and per-pair status. `activate` selects a retained edition.
`RoomReadingLibrary.chapterOf(bookId, cursor)` returns the 1-based parser chapter, or null for
unavailable/foreign cursors. `LocalEbookImporter.importResult` returns a created/existing book;
`EbookImportException.reason` distinguishes unsupported, DRM-protected and unreadable files.
`SyncJump` carries destination, previous, confidence, offerUndo, sequence, and the origin that set
the shared place. D's `PositionJump` displays it; programmatic restoration and Undo are not reading activity.

An explicit recording choice keeps its parent `Audiobook.id` and retains the original provider id
as `recordingId`. Sources/parts keep their own ids. Existing data saved under that recording id is
adopted into the parent book transactionally; originals are copied before the transaction. This is
an explicit association, not title-only merging of unrelated library entries. Room retains each
recording's history and edition's bindings. Alignment attempt/coverage counters are part of the
single, not-yet-released v4-to-v5 migration. No schema/version bump or release identity changes.

See [integration API and verification](EREADER_INTEGRATION.md) for E/F's component entry points.
