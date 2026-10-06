# Reading along and a shared reading position

## Reading along

Open **Listening room → Read along**, or turn on **Read along with the narration** (the headphones in the reader's top bar) when the book has a recording. The full reader becomes the listening surface: the narrated sentence carries a soft copper wash, pages turn or scroll to keep narration on screen, and a compact player stays in reach (a tray with speed, 30-second skips, play/pause, and the sleep timer on phones; a side panel with the player and chapters when unfolded; the lower half in tabletop posture). The decision record is [EREADER.md](EREADER.md#reading-and-listening-together).

- **Following.** A page turn by hand pauses following; **Back to narration** returns, and turning back to the narrated page resumes it. Rotation and folding keep the narrated sentence on screen and never interrupt playback.
- **Taps.** Tapping a sentence plays from its start and keeps the playing/paused state. Taps beside the text show the reader's controls; the outer edges still turn pages.
- **Certainty.** Where narration anchors place the text, the status reads **Synced with narration**. Elsewhere the place is estimated: a dotted sage underline replaces the wash and the status shows **≈ Estimated place**. **Highlight each word** (Read along options, off by default) marks the narrated word only where narration is matched word by word. An edition that doesn't match the narration shows no highlight, and a part not yet placed in the book says so.
- **Who leads.** From the Listening room, or while the book plays, audio leads and the page moves to the narration. From a reader page with nothing playing, the page leads: it becomes the shared place and the recording is positioned there, paused. Moves of more than about a page or 30 seconds offer **Undo**. Back from read along returns to the Listening room; if reading moved the shared place while paused, the audio starts there, again with Undo.
- **Timing fixes.** Read along options offer **Fix the timing** (tap the sentence you hear, then **Match at [audio time]**), **Choose this part's chapter** for multipart layouts that can't be placed automatically, and **Reset timing for this part**.

Ebooks are found or added from the book's **Find ebook** sheet (also reachable as **Find the ebook** in the Listening room). It lists editions on this phone (each can be removed; audio, bookmarks, and listening progress stay), matching ebooks from the existing providers, and a file choice:

1. An EPUB or text file shipped with the playing recording (Internet Archive or TorBox).
2. With TorBox connected, an ebook already in your TorBox account whose release or file name has the book's exact title and every author name.
3. With TorBox connected, ebook releases indexed by Knaben's **Books / EBooks** category (`9001000`) that match the same title and author rules and that TorBox reports as **cached**. Narrio adds a cached release to your TorBox account only when it fetches the text, after rechecking the cache. Uncached releases are never added.
4. Project Gutenberg through Gutendex, when its title and author match.

Summaries, study guides, collections, box sets, sequels, and releases naming another title are rejected. A name match cannot prove the same edition; narration sync detects text that doesn't follow the audio and leaves estimated timing in place. Files are limited to 20 MB. DRM, Kindle formats, and PDFs are unsupported. EPUB parsing follows the declared spine order. Scripts and remote resources never execute.

The earlier Follow along passage list and its WebVTT import were retired with this change. Existing VTT timing tracks keep working in the sync engine, but a VTT file can no longer be added from the app, because the reader shows EPUB and text editions only.

**Settings → Reading & listening → Sync while reading along** (on by default) turns on-device narration recognition on or off while read along is open. Saved text and timing work without connectivity; lookup and downloads need a connection.

## Timing

WebVTT supplies passage start/end timestamps for the audio part explicitly selected when it was imported. Its source/part binding prevents using those cues with another layout or part. Introductions, gaps, and the end of a track have no highlighted passage. "Supplied timestamps" describes the timing source; Narrio does not verify that an imported transcript belongs to the chosen narration.

### Narration sync

Ebooks have no narration timestamps, so Narrio listens to the recording itself. While **Read along** is on screen, it decodes 20-second windows of the current audio part, recognizes the spoken words on the phone, and finds them in the book text:

- Windows are chosen around the playhead first (the current position, then up to 15 minutes ahead and a few minutes behind), so the passage being heard is placed within seconds. Each window is decoded with Media3's own extractors through the same offline-cache/TorBox data path as playback, so its timestamps use the player's seek map.
- Recognition uses [Vosk](https://alphacephei.com/vosk/) with its small English model (Apache-2.0). The 41 MB model downloads once from alphacephei.com on an unmetered network, or after **Sync with narration** on a metered one, and is checked against a pinned size and SHA-256 before it is unpacked into private storage. Audio and text are never uploaded.
- Recognized words are matched to the text by runs of shared three-word phrases in the same order, tolerating missed and misheard words. A window must match at least four phrases in one place and clearly better than anywhere else; repeated or unrecognized speech (introductions, credits, music) produces nothing rather than a guess. Matches become word-level timing anchors saved with the part's binding.
- Between anchors, highlighting is interpolated; beyond them it continues at the narrator's measured pace. A part that covers only a slice of the book highlights only that slice, so multipart recordings no longer need a chapter choice. Passages narrated in another part can't be tapped to seek.
- Recognized anchors that contradict the text order, a plausible speaking pace, or a manual match are discarded. **Fix the timing** still works, and a manual match outranks recognized anchors. **Reset timing for this part** clears both and recognizes the part again.

Read along's status reads **Synced with narration** where anchors place the narration exactly, **≈ Syncing with narration…** while a window is being recognized, and **≈ Estimated place** otherwise. Sync is English-only for now; other languages keep estimated timing. A different edition, translation, or abridgment produces fewer anchors.

### Estimated timing

Without anchors, **Estimated timing** distributes passages by word count over the selected chapter/audio-part duration. A single-file recording uses the whole book. Matching chapter/part counts use ordered chapters; otherwise an ambiguous multipart layout waits for **Choose this part's chapter** until narration sync places the part. Use **Fix the timing**, tap the sentence being narrated, and **Match at [audio time]** to anchor it manually.

Highlighting reads `ListeningState.positionMs`, rather than wall time. Between the service's once-a-second updates, read along advances that clock at the playback speed (never more than 1.5 s ahead) so the word mark keeps time. Pause, resume, speed changes, slider seeks, bookmarks, and part transitions therefore use the same media clock as playback. Room source positions retain listening history. Schema 5 also stores one shared reading/listening position per book; reader and listening activity commits use its sequence guard. Listening commits after about ten seconds of playback; audio owns the position while playing.

### Ebook sync engine

The sync engine maps the active edition and a chosen recording in both directions. Text starts use
anchors first, then unambiguous chapter/part estimates. An estimated start tries one 20-second
recognition window using an already installed English model; a locally confirmed target corrects
the seek and becomes exact. Unavailable models/audio and unsupported languages keep the estimate.
Pairing status derives from attempted narration windows and recognized anchor coverage; mismatched
pairs keep independent positions. Mode/recording jumps above one page or thirty seconds expose Undo
state for the reader/library workstreams.

**Settings → Reading & listening → Sync narration in the background** is on by default. Phone
downloads align using cache-only reads; streamed alignment waits for an unmetered connection while
charging. WorkManager saves progress after each window and resumes in bounded batches. The English
model downloads once on an unmetered connection. See [sync integration](EREADER_SYNC.md) for the
Room persistence, UI state, bounds, and the reader/together-mode integration API.

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
