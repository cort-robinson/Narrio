# Follow along and a shared reading position

## Using the basic feature

Open **Listening room → Follow along**. Readable passages replace the cover; the current passage has a copper highlight and stronger type. Pause, seeking, and 30-second skips remain available below the text. Scrolling by hand suspends automatic scrolling until **Back to current line** is pressed. Tapping a passage seeks within the current audio part and preserves its playing/paused state.

When a recording has no text yet, Narrio looks for it automatically, stopping at the first matching ebook that opens:

1. An EPUB or text file shipped with the playing recording (Internet Archive or TorBox). Timing tracks aren't chosen automatically because each belongs to one audio part.
2. With TorBox connected, an ebook already in your TorBox account whose release or file name has the book's exact title and every author name.
3. With TorBox connected, ebook releases indexed by Knaben's **Books / EBooks** category (`9001000`) that match the same title and author rules and that TorBox reports as **cached**. Narrio adds a cached release to your TorBox account only when it fetches the text, after rechecking the cache. Uncached releases are never added.
4. Project Gutenberg through Gutendex, when its title and author match.

Summaries, study guides, collections, box sets, sequels, and releases naming another title are rejected. A name match cannot prove the same edition; narration sync detects text that doesn't follow the audio and leaves estimated timing in place. If nothing matches, the screen says so and the manual options remain:

- Choose an EPUB, UTF-8 `.txt`, or WebVTT `.vtt` file through Android's document picker. Narrio copies it into private storage; it does not depend on continued access to the original URI.
- Choose a companion ebook/text/timing file from the currently playing Internet Archive or TorBox source.
- **Find book text** searches Project Gutenberg by any title or author.

**Manage book text → Find and sync automatically** turns automatic lookup and narration sync off or on.

Files are limited to 20 MB. DRM, Kindle formats, and PDFs are unsupported. Attached EPUB and text also open in the full reader (**Read** in book details, or the book icon beside the Follow along heading), which positions by the same `(resource, offset)` locators; see [EREADER.md](EREADER.md). Annotations and text search belong to later reader work. EPUB parsing follows the declared spine order. Scripts and remote resources never execute. Original file bytes, provenance, resource names, normalized text offsets, and a normalization version are retained for a later reader.

**Manage book text** attaches or removes text. Attaching a different document makes it active and retains earlier editions and their timing matches. Data APIs support activating or removing an individual edition; edition-picker UI is a separate reader workstream. Removing text keeps audio, bookmarks, and listening progress. Removing a shelf recording removes its attached text too. Saved text and adjustments work without connectivity; lookup and downloads need a connection.

## Timing

WebVTT supplies passage start/end timestamps for the audio part explicitly selected when it was imported. Its source/part binding prevents using those cues with another layout or part. Introductions, gaps, and the end of a track have no highlighted passage. "Supplied timestamps" describes the timing source; Narrio does not verify that an imported transcript belongs to the chosen narration.

### Narration sync

Ebooks have no narration timestamps, so Narrio listens to the recording itself. While **Follow along** is on screen, it decodes 20-second windows of the current audio part, recognizes the spoken words on the phone, and finds them in the book text:

- Windows are chosen around the playhead first (the current position, then up to 15 minutes ahead and a few minutes behind), so the passage being heard is placed within seconds. Each window is decoded with Media3's own extractors through the same offline-cache/TorBox data path as playback, so its timestamps use the player's seek map.
- Recognition uses [Vosk](https://alphacephei.com/vosk/) with its small English model (Apache-2.0). The 41 MB model downloads once from alphacephei.com on an unmetered network, or after **Sync with narration** on a metered one, and is checked against a pinned size and SHA-256 before it is unpacked into private storage. Audio and text are never uploaded.
- Recognized words are matched to the text by runs of shared three-word phrases in the same order, tolerating missed and misheard words. A window must match at least four phrases in one place and clearly better than anywhere else; repeated or unrecognized speech (introductions, credits, music) produces nothing rather than a guess. Matches become word-level timing anchors saved with the part's binding.
- Between anchors, highlighting is interpolated; beyond them it continues at the narrator's measured pace. A part that covers only a slice of the book highlights only that slice, so multipart recordings no longer need a chapter choice. Passages narrated in another part can't be tapped to seek.
- Recognized anchors that contradict the text order, a plausible speaking pace, or a manual match are discarded. **Adjust timing** still works, and a manual match outranks recognized anchors. **Reset timing for this part** clears both and recognizes the part again.

The status line reads **Synced with narration** when recognized anchors are close to the position, **Syncing with narration…** while a window is being recognized, and **Estimated timing** otherwise. Sync is English-only for now; other languages keep estimated timing. A different edition, translation, or abridgment produces fewer anchors.

### Estimated timing

Without anchors, **Estimated timing** distributes passages by word count over the selected chapter/audio-part duration. A single-file recording uses the whole book, and its chapter menu seeks to an estimated text position. Matching chapter/part counts use ordered chapters; otherwise an ambiguous multipart layout asks for a chapter until narration sync places the part. Use **Adjust timing**, tap the line being narrated, and **Match at [audio time]** to anchor it manually.

Highlighting reads `ListeningState.positionMs`, rather than wall time. Pause, resume, speed changes, slider seeks, bookmarks, and part transitions therefore use the same media clock as playback. Room source positions retain listening history. Schema 5 also stores one shared reading/listening position per book; reader and listening activity commits use its sequence guard. The sync workstream owns activity thresholds and mapping.

## Storage and identity

- `AudioSource.textFiles` holds stable provider references. TorBox ebook links resolve immediately before fetching; generated URLs and credentials are never serialized with text.
- `BookText` contains ordered chapters and passage locators `(resource, normalized offset)`. Its content fingerprint identifies imported bytes. Standalone timing-track identity additionally includes its audio layout/part. Later normalization changes must version locators explicitly.
- `ebook_editions` retains every attached document, while `book_text` identifies the active edition. `text_bindings` stores chapter choices and anchors by `(bookId, editionId, sourceId, partId)` and checks document identity. Private `files/follow-along/<hashed recording id>/` stores the original and normalized copy. Writes are atomic and attachment/anchor mutations are serialized.
- Migration 3 → 4 adds the text tables while retaining shelf entries, bookmarks, source histories, and pending preparation. Migration 4 to 5 preserves those attachments, original files, anchors, bookmarks, listening history, and preparation state. Removing an edition clears its bindings, annotations, and alignment jobs; other editions keep theirs. Removing all book text preserves audio bookmarks and listening history.

- `TextAnchor` carries a word offset within its passage and whether it was recognized (`auto`). Both default for anchors saved before narration sync, so existing bindings load unchanged.

An audiobook recording and an ebook edition are distinct. A matching title, shared torrent folder, or identical chapter count cannot establish an exact pair; recognized narration is the evidence that text and audio agree, window by window. Different recordings, formats, and multipart layouts retain independent histories.

## Planned full-reader logistics

The full reader should extend this path instead of maintaining a second progress system:

1. **Pair editions explicitly.** Introduce a work identity above audio recordings and ebook editions. Record language, translation, abridgment, edition identifiers, and a user-confirmed pairing. Version every alignment with ebook-content and audio-layout fingerprints. Replacement files, re-encodes, or edition changes invalidate assumptions.
2. **Extend alignment.** On-device narration sync anchors text near the playhead while follow along is open. Remaining work: import publisher EPUB 3 Media Overlays (SMIL) when their audio is the chosen recording, whole-book background alignment on Wi-Fi and charging, more language models, and explicit reports of unmatched ranges.
3. **Preprocess independently from streaming.** Whole-book alignment should queue by both edition fingerprints, support resume, bound storage/compute, and distinguish ready chapters from pending ones. Provider cache availability is not alignment availability.
4. **Store a canonical content cursor.** Extend resource/offset locators to an EPUB CFI/DOM mapping without losing original content identity. Store the cursor with its verified audio source, part, timestamp, alignment version, and update sequence. Page number and percentage are views, not durable identities.
5. **Navigate in both directions.** Audio events map media time to the content cursor. Reader navigation maps the cursor to a verified cue, loads the right source/part, and seeks. Reading-only navigation can remember the corresponding listening position without starting audio. Commit after the player confirms a transition, and sequence updates so an older service save cannot overwrite a newer reader action. Expose estimates or unmatched locations until reliable alignment exists.
6. **Build the reader around that cursor.** Add pagination/continuous layouts, typography controls, a table of contents, search, highlights, notes, accessibility, and reader bookmarks. Reflow, font scaling, folding, and rotation preserve the same cursor. Cloud/device sync is a later account and conflict-resolution decision.

Sync quality depends on the edition pair and recognition coverage: a matching edition with clear narration anchors every few seconds near the playhead, while a different edition or unsupported language falls back to estimated timing.

## References

- [Gutendex API contract](https://github.com/garethbjohnson/gutendex): search, copyright status, language, and download formats. The public deployment has no availability guarantee; local import remains available when it fails. A larger rollout should revisit catalog hosting and provider terms.
- [EPUB 3.3 Media Overlays](https://www.w3.org/TR/epub-33/#sec-media-overlays): the standard audio/text mapping for a later alignment importer. SMIL/embedded-audio playback is not implemented here.
- [Vosk speech recognition](https://github.com/alphacep/vosk-api) (Apache-2.0) and its [model list](https://alphacephei.com/vosk/models). Model files are pinned by size and SHA-256 in `SpeechModelStore`.
- [Knaben API](https://knaben.org/api/v2): ebook releases use category `9001000`.
- [Internet Archive metadata](https://archive.org/developers/metadata.html) and [TorBox API](https://api-docs.torbox.app/): companion files use existing audiobook provider boundaries.
