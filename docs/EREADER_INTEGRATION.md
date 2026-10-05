# Integrated reader API

A/B/C/D are combined in `feature/ereader-integration`. The reader opens durable EPUB/TXT editions,
including ebook-only imports, through D's shelf/details entry. Room owns editions, shared positions,
annotations, bookmarks, source history and alignment aggregates. Playback ownership is checked at
the transaction boundary; opening, programmatic sync, relayout and Undo never count as activity.

## Together mode (E)

`ReaderViewModel.state` yields `ReaderState.Ready(session)`. `ReaderSession.controller` is the
`ReaderController` for that opened edition; `session.book` is its `ReaderBook` geometry.

- Observe `cursor: StateFlow<ContentCursor?>`, `visible: StateFlow<VisibleRange?>`,
  `ready: StateFlow<Boolean>`, and `events: SharedFlow<ReaderEvent>`.
- `goTo(cursor, animated, asReader = false)` restores programmatically; `jumpTo`, `next`,
  `previous`, `goBack` represent reader navigation.
- Observe `following`; `startFollowing`, `stopFollowing`, and `follow(cursor)` support narrated
  auto-navigation. Manual page turns stop following.
- `setDecorations(group, List<TextDecoration>)` draws exclusive `CursorRange(start, end)` ranges,
  even across resources. Use stable group/id names; `DecorationActivated` reports them.
- `EpubReaderView(controller, preferences, spread, modifier, selectionActionMode)` hosts the
  navigator. Keep publication resources inside `ReaderBook`'s sanitizing filter.
- `ReaderBook.contents`, `pages`, `layout`, `locator(cursor)`, `cursor(resource, offset)` and
  `compare` expose geometry without inventing cursor identity from progression.

`NarrioViewModel.readingSync` is `StateFlow<ReadingSyncState>`: book/edition/source identity,
pairing, audio/text `SyncJump`s, confidence, and correcting. `graph.positionMapper.textFor`
maps Media3's clock for narration decorations; `seekFromText` is the explicit sentence-tap action.
`resolveReadingStart` takes the previous cursor and optional rendered page distance. Unknown
changed distances conservatively offer Undo. `ReaderSession.undo` restores without an activity commit.
`undoSyncJump` preserves play/pause and expires after another playback navigation.

### What together mode adds (E)

Read along (`ui/ReadAlong.kt`) is built on the API above without contract changes to `Reading.kt` or Room:

- `ReaderController`: narration styles `TextDecoration.Style.NARRATED/ESTIMATED/WORD` (drawn by `NarrationMark`
  templates registered in `EpubReaderView`); `textTaps` with `ReaderEvent.TextTapped(cursor)` and `cursorAt(x, y)`;
  `follow` records the place relayouts restore while following; `leaveFollowing()`; `clearDecorations(group)`;
  `touched()` (the page container reports touches so a navigator settle is never mistaken for a page turn).
  Decorations are applied without cancellation, serialized, and redrawn after each resource layout or relayout.
  The page's Readium viewport width is refitted when the page view resizes without a configuration change.
- `ReaderSession.commitNow(cursor)`: an explicit "listen from this page" commit. `ListeningService.alignToSharedPosition()`:
  start the audio at a newer reading place with the usual Undo. `NarrioViewModel.seekFromText` now reports success.
- `ReaderRequest.together/fromListening`, `NarrioViewModel.readAlong()/setReadAlong()/listenFromPage()`;
  `ReaderSettings.wordHighlight` (default off); `LocalFold` carries the hinge to the reader.
- `MappingEngine.audioFor` uses the timeline's measured pace beyond the outermost anchors, as `textFor` already did,
  so an estimated place mapped to audio and back stays on its sentence (C's engine; covered by `NarrationTest`).

F's selection menu, highlight groups, and search hits can coexist: read along uses its own decoration groups
(`narration`, `narration-word`, `narration-selected`) and leaves `selectionActionMode` untouched. With `textTaps` on,
taps on text seek; F's highlight activation still arrives as `DecorationActivated`.

## Annotations and search (F)

Reuse `ReaderController.selection(): CursorRange?` and the navigator's optional
`ActionMode.Callback`. Highlights/notes/search hits use independent decoration groups and parser
cursors at `normalizationVersion = 1`; passage ranges span `[offset, offset + text.length)`.

`LibraryDao.annotations(bookId, editionId)`, `putAnnotation`, and `deleteAnnotation` use
`AnnotationEntry` with start/end cursors, selected text, color, note and timestamps.
`LibraryDao.bookmarks(bookId)` returns the unified rows; `BookmarkEntry.contentCursor()` returns
an optional reader cursor, while audio source/part/time stay available. Add the UI on these rows;
do not introduce another annotation/bookmark store.

Delivered (F):

- `ReaderViewModel.marks` is the open edition's `ReaderMarks`: `highlights` in book order, `bookmarks`
  (both modes, resolved), `search` (`BookSearch`), and the highlight/bookmark edits. Highlights draw in
  `HIGHLIGHT_GROUP`, the current search match in `SEARCH_GROUP`; narration should use its own group.
- Search runs over the served `ResourceTextIndex` text, so every hit is an exact parser `CursorRange`
  (case, accents and typographic quotes fold one-to-one). Readium's string search reports its own text
  extraction, which would need mapping back.
- `AnnotationEntry` rows store durable cursors (no progression/locator cache), the colour by name
  (`yellow`, `green`, `blue`, `pink`), a plain-text note, and the parser text of the range: export-ready.
- A bookmark stores the place where it was made and the other mode's place only when narration
  confirms it exactly. `playback.BookmarkMapping` maps a missing place whenever a list is shown (never
  across a MISMATCH pair), so estimates improve with alignment and carry "≈". Text-only rows keep blank
  source/part ids; pre-ebook audio rows need no change.
- `ReaderController` additions: `jumpTo(cursor, keepReturnPoint)`, `selectedText()`,
  `clearSelection()`. `setDecorations` now applies on the main thread and registers each group's tap
  listener once per navigator (Readium keeps every listener it's given).
- The selection toolbar replaces the WebView's own, so it re-adds Copy, Share, and installed
  `PROCESS_TEXT` actions (Translate, dictionaries); the manifest declares that query.

## Contracts resolved

- `Reading.kt`: KDoc only, original-byte fingerprint and TXT internal-EPUB locator semantics.
- Reactive editions/activation: `RoomReadingLibrary.observeBook/observeShelf` and `activate`.
- Reading chapter: `RoomReadingLibrary.chapterOf` returns a 1-based parser chapter or null.
- Pairing: library flows refresh on active edition, source history, bindings and alignment changes;
  `ReadingSync.pairing(bookId, snapshot)` provides the per-pair decision.
- Import: `LocalEbookImporter.importResult(uri)` returns `EbookImportResult(book, created)`;
  compatible `import` returns its book. `EbookImportException.reason` separates unsupported,
  DRM-protected and unreadable failures; imports deduplicate original edition bytes.
- Jump: `SyncJump` retains destination/previous/confidence/offerUndo/sequence and adds `from`.
  D's shared snackbar and `EstimatedPlace` render Undo and approximation in the reader/player.
- Book identity: explicit selection adopts old recording data to the parent book. Provider
  `recordingId` and all source/part ids remain intact; unrelated titles are not merged.
- Schema 5: adds attempt/match/time counters to A's single 4-to-5 migration for durable C pairing.
- Measured durations persist by book/recording/part and survive provider refreshes and playback
  history writes. Unknown layouts remain unmapped; another recording's book duration is never used.

## Remaining scope

No open product identity decision. Annotations/search UI remain F's work. Read along can't show WebVTT-only text, so VTT import was retired with the passage list.
Fixed-layout/PDF/DRM remain unsupported. Two-page spreads do not yet use a real separating hinge; read along places its panel beyond one.
Real narration accuracy, battery/background behavior, physical devices, live delivery providers,
TalkBack and minified-release runtime acceptance remain separate from controlled integration checks.

Verification commands and results are recorded in the integration draft PR.
