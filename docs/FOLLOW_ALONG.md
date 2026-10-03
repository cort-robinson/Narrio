# Follow along and a shared reading position

## Using the basic feature

Open **Listening room → Follow along**. Readable passages replace the cover; the current passage has a copper highlight and stronger type. Pause, seeking, and 30-second skips remain available below the text. Scrolling by hand suspends automatic scrolling until **Back to current line** is pressed. Tapping a passage seeks within the current audio part and preserves its playing/paused state.

Add text in one of three ways:

- Choose an EPUB, UTF-8 `.txt`, or WebVTT `.vtt` file through Android's document picker. Narrio copies it into private storage; it does not depend on continued access to the original URI.
- Choose a companion ebook/text/timing file from the currently playing Internet Archive or TorBox source. This is a separate small download. It never joins the audio playlist or creates/prepares another torrent.
- **Find book text** searches Project Gutenberg metadata through Gutendex. Compare the result's title, author, and language before selecting it. Narrio downloads EPUB, or UTF-8 text when EPUB is unavailable. Public lookup does not cover every indexed audiobook; newer books can use companion files or local import.

Files are limited to 20 MB. DRM, Kindle formats, PDFs, pagination, annotations, text search, images, layout styling, and standalone reader navigation are outside this increment. EPUB parsing follows the declared spine order. Scripts and remote resources never execute. Original file bytes, provenance, resource names, normalized text offsets, and a normalization version are retained for a later reader.

**Manage book text** replaces or removes text. Replacing a document clears its timing matches. Removing text keeps audio, bookmarks, and listening progress. Removing a shelf recording removes its attached text too. Saved text and adjustments work without connectivity; lookup and downloads need a connection.

## Timing today

WebVTT supplies passage start/end timestamps for the audio part explicitly selected when it was imported. Its source/part binding prevents using those cues with another layout or part. Introductions, gaps, and the end of a track have no highlighted passage. “Supplied timestamps” describes the timing source; Narrio does not verify that an imported transcript belongs to the chosen narration.

Ordinary ebooks have no narration timestamps. **Estimated timing** distributes passages by word count over the selected chapter/audio-part duration. A single-file recording uses the whole book, and its chapter menu seeks to an estimated text position without stretching one chapter across the entire recording. Matching chapter/part counts use ordered chapters; otherwise an ambiguous multipart layout asks the user to choose a chapter. Those mappings are estimates, not verified alignment.

Use **Adjust timing**, tap the line being narrated, and **Match at [audio time]** to anchor it. Multiple ordered anchors refine interpolation and can accommodate an introduction. **Choose chapter → Reset timing for this part** clears manual anchors while keeping the chosen chapter. Intermediate timing remains estimated. Different translations, editions, abridgments, introductions, commentary, and long pauses can still cause drift.

Highlighting reads `ListeningState.positionMs`, rather than wall time. Pause, resume, speed changes, slider seeks, bookmarks, and part transitions therefore use the same media clock as playback. Existing Room source positions remain the authority for listening progress. There is no second reading percentage that could disagree with them.

## Storage and identity

- `AudioSource.textFiles` holds stable provider references. TorBox ebook links resolve immediately before fetching; generated URLs and credentials are never serialized with text.
- `BookText` contains ordered chapters and passage locators `(resource, normalized offset)`. Its content fingerprint identifies imported bytes. Standalone timing-track identity additionally includes its audio layout/part. Later normalization changes must version locators explicitly.
- `book_text` attaches one chosen document to a shelf recording. `text_bindings` stores chapter choices and anchors by `(bookId, sourceId, partId)` and checks document identity. Private `files/follow-along/<hashed recording id>/` stores the original and normalized copy. Writes are atomic and attachment/anchor mutations are serialized.
- Migration 3 → 4 adds the text tables while retaining shelf entries, bookmarks, source histories, and pending preparation. Removing an attachment cascades only its text bindings.

An audiobook recording and an ebook edition are distinct. A matching title, shared torrent folder, or identical chapter count cannot establish an exact pair. Until a pairing is verified, the user chooses the text and sees estimated timing. Different recordings, formats, and multipart layouts retain independent histories.

## Planned full-reader logistics

The full reader should extend this path instead of maintaining a second progress system:

1. **Pair editions explicitly.** Introduce a work identity above audio recordings and ebook editions. Record language, translation, abridgment, edition identifiers, and a user-confirmed pairing. Version every alignment with ebook-content and audio-layout fingerprints. Replacement files, re-encodes, or edition changes invalidate assumptions.
2. **Create verified alignment once.** Prefer publisher-provided EPUB 3 Media Overlays (SMIL) when their referenced audio is actually the chosen recording. Otherwise an alignment provider consumes the exact authorized audio and ebook and produces passage/word cues, confidence, and explicit uncovered ranges. Ebook text and audiobook chapter metadata alone cannot supply this mapping. Measure the feasibility of on-device forced alignment of long recordings before choosing an engine. A remote service needs a separate privacy/product decision; this feature uploads neither text nor narration to one.
3. **Preprocess independently from streaming.** Alignment needs narration samples or the recording; an expiring stream URL does not make hours of analysis instant. Queue/cache results by both edition fingerprints, support resume, bound storage/compute, and distinguish ready chapters from pending ones. Preserve gaps and mismatch reports rather than stretching text across abridged or unaligned content. Provider cache availability is not alignment availability.
4. **Store a canonical content cursor.** Extend resource/offset locators to an EPUB CFI/DOM mapping without losing original content identity. Store the cursor with its verified audio source, part, timestamp, alignment version, and update sequence. Page number and percentage are views, not durable identities.
5. **Navigate in both directions.** Audio events map media time to the content cursor. Reader navigation maps the cursor to a verified cue, loads the right source/part, and seeks. Reading-only navigation can remember the corresponding listening position without starting audio. Commit after the player confirms a transition, and sequence updates so an older service save cannot overwrite a newer reader action. Expose estimates or unmatched locations until reliable alignment exists.
6. **Build the reader around that cursor.** Add pagination/continuous layouts, typography controls, a table of contents, search, highlights, notes, accessibility, and reader bookmarks. Reflow, font scaling, folding, and rotation preserve the same cursor. Cloud/device sync is a later account and conflict-resolution decision.

Exact syncing requires a correct edition pair and sufficient alignment coverage. This increment establishes acquisition, parsing, persistence, source identity, media-clock integration, and the highlighted interaction. It does not claim automatic sentence-perfect alignment of arbitrary audiobooks.

## References

- [Gutendex API contract](https://github.com/garethbjohnson/gutendex): search, copyright status, language, and download formats. The public deployment has no availability guarantee; local import remains available when it fails. A larger rollout should revisit catalog hosting and provider terms.
- [EPUB 3.3 Media Overlays](https://www.w3.org/TR/epub-33/#sec-media-overlays): the standard audio/text mapping for a later alignment importer. SMIL/embedded-audio playback is not implemented here.
- [Internet Archive metadata](https://archive.org/developers/metadata.html) and [TorBox API](https://api-docs.torbox.app/): companion files use existing audiobook provider boundaries.
