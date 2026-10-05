# Library and entry experience: design plan and emulator review

Workstream D of [docs/EREADER.md](../../../docs/EREADER.md). Scope: shelf filters, format marks, and shared progress; Continue in the last mode; book-details Read, Listen, Find ebook, and Find audiobook; edition match status; local ebook import; the shared "≈" place and jump/Undo snackbar.

## Plan against DESIGN.md

- **Shelf.** "My shelf" keeps displaySmall; a labelled **Add ebook** text action sits beside it and wraps below at large text. FilterChips **All / Audiobooks / Ebooks / Both** match Discover's chips (8 dp gaps, check icon). Audiobooks and Ebooks include books that have both; Both narrows to pairs. Rows stay open on the ground: format marks are sage 14 dp icons with labelMedium words, not badges in containers. One labelSmall progress line and the existing 3 dp bar come from the shared place: "Ch 12 · 43%" for reading, "Part 3 of 12 · 20%" for listening (time-in when lengths are unknown). The trailing action is the last mode: play (or narration bars) for listening, a book for reading. Ebook-only rows omit narration and delivery lines. Each empty filter says how to fill it and offers Show all.
- **Details.** Save moves to a labelled top-app-bar action so the content row holds two format slots. The last-used mode leads as the filled copper button; the other available format is tonal sage; a missing format is an outlined **Find ebook** / **Find audiobook** in the same slot. Slots sit side by side only when both labels fit at their natural width, otherwise they stack full width (narrow panes, long labels such as "Choose a recording", large text). Beneath: the shared place ("Reading · Ch 12 · 43%") with its bar, and the other mode's mapped place with "≈" when estimated. The edition line ("Ebook · EPUB · Project Gutenberg · ebook 1342") carries the pairing status (matches / partly matches / doesn't match / not checked) only when a recording exists, plus **Choose another edition**.
- **Format combinations.** Audio only: Listen + Find ebook. Ebook only: Read + Find audiobook; audio lookup runs only when asked, with copy naming what it checks. Both: last mode leads; mismatch suppresses the mapped place because sync stays off. Catalog books keep automatic recording lookup and its Finding audio / Choose a recording / Search again states inside the audio slot.
- **Find ebook sheet.** Native sheet on surfaceContainer, headlineMedium title ("Find an ebook" / "Choose an edition"). Editions on this phone use the selected-row treatment with radio semantics. Found results list title, author · language · format, and provider attribution, in the existing finder's evidence order. Loading names the provider being checked; empty, incomplete, and error states keep Search again and the file action reachable.
- **Import.** A functional container (surfaceContainer, 14 dp, 20 dp inset) reports Adding your ebook, then opens the new book's details; failures name the format problem (Kindle, PDF, comic, timing track) and offer Choose another file.
- **Shared feedback.** `EstimatedPlace` prefixes "≈" and is read as "about Chapter 12, 43%, estimated". `JumpSnackbar` is the Material inverse snackbar with a mode icon, the place in inversePrimary, and Undo; it uses Long duration, which SnackbarHost extends for accessibility services.

## Emulator evidence

`LibraryFormatsExperienceTest` (4 tests) passed on the API 36 emulator at three window states, capturing Night and Day within each run:

| Window | Override | Result |
| --- | --- | --- |
| Phone | 1080×2340 @ 450 dpi (384 dp wide), font scale 1.0 | 4/4 passed |
| Unfolded | 1848×2050 @ 395 dpi (748 dp, expanded panes) | 4/4 passed |
| Phone, large text | 1080×2340 @ 450 dpi, font scale 1.3 | 4/4 passed |

Formats, editions, positions, pairing, ebook results, and imports come from a controlled `ReadingLibrary` fake. These images establish layout, copy, states, and navigation for this workstream only. They don't establish the reader (the Read target is a temporary placeholder), real sync mapping, pairing detection, provider results, or physical-device behavior.
