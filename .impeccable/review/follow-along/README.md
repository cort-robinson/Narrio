# Follow-along native evidence

Captured October 2, 2026 from the unreleased feature branch on an isolated API 36 Android emulator. These are original native screenshots saved by `FollowAlongTest`; `overview.png` is a contact sheet for navigation.

The prose is an explicitly synthetic **A Garden Walk** fixture. The transport plays a real, locally generated 120-second WAV through Media3. These captures establish interface and interaction behavior, not alignment of a commercial recording or authenticated TorBox ebook delivery. Live public EPUB acquisition is verified separately by `publicLookupDownloadsARealEpubWithChapters`.

| Suffix | Configuration |
| --- | --- |
| `phone` | 1080 × 2400, density 420, font scale 1.0 |
| `phone-large-text` | 1080 × 2400, density 420, font scale 1.3 |
| `fold` | Expanded window at 1848 × 2448, density 360, font scale 1.0; generic emulator, not a physical folding device |

Each configuration has eight captures:

| State | What the capture shows |
| --- | --- |
| `empty` | Text acquisition actions while audio is selected |
| `estimated` | Highlighted current passage and compact transport |
| `adjust` | A selected line awaiting an audio-time match |
| `chapters` | Whole-recording text chapter navigation with estimated-seek guidance |
| `day` | The same text in Narrio's Day theme |
| `sources` | Text management and public lookup sheet |
| `timed` | Supplied VTT cues on their attached audio part |
| `other-part` | Explicit warning when the timing track belongs to a different part |

The tests wait for settled native state and dismiss transient success snackbars before capture. Scrollable reading regions show their current viewport, rather than claiming a capture of the entire book.

Test logs are in `verification/follow-along-phone.txt`, `follow-along-large-text.txt`, and `follow-along-expanded.txt`. The scope and design authority are recorded in `.impeccable/follow-along-contract.md`, `DESIGN.md`, and `ui/Theme.kt`. This native surface has no browser/CSS detector result, approved image comp, or QUALITY BAR card.
