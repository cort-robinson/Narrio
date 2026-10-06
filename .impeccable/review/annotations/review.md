# Search, highlights, notes, and bookmarks — emulator review

Plan: [annotations-plan.png](../../reader/annotations-plan.png). Captures come from `ReaderAnnotationsVisualQa`
(not part of CI) on the project API 36 emulator with synthetic fixtures: phone (1248×1972 @ 420) Night and Day,
system font scale 1.3, and an unfolded-sized window (2448×1848 @ 395). Overrides were restored afterwards.

| State | Capture |
| --- | --- |
| Reading with a highlight, a noted highlight (underlined), and the page ribbon | `phone-night-reading.png` |
| Selection toolbar: Highlight, Note, Copy, Share, then installed text actions | `phone-night-selection.png` |
| Highlight tray after tapping a highlight | `phone-night-tray.png` |
| Note sheet | `phone-night-note-sheet.png` |
| Search results grouped by chapter (Day) | `phone-day-search-results.png` |
| Jumped to a match: copper match highlight, pill, "Back to" | `phone-day-search-match.png` |
| Bookmarks tab | `phone-night-bookmarks.png` |
| Highlights tab (Day) | `phone-day-highlights.png` |
| No matches, large text | `phone-large-search-empty.png` |
| Unfolded search side sheet; unfolded highlights tab | `unfolded-night-search.png`, `unfolded-night-highlights.png` |
| Listening room bookmarks: exact, "≈" mapped, and part-only rows | `phone-*-listening-bookmarks.png` |

Fixed during review: a pending search briefly reported "No matches"; bookmark snippets repeated the chapter
heading; listening rows repeated the part name. Decoration taps follow Readium's line boxes, so a tap must land on
the highlighted line. These are emulator renders, not physical Fold, TalkBack, or real-book acceptance.
