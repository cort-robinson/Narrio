# Per-source listening search

Book details find listening sources by provider, Nuvio-style: each source gets its own section that fills in as soon as that provider answers, and one **Best match** sits above them for listeners who don't want to compare releases. Settings decide which sources take part and in what order. Shared types live in `app/src/main/java/app/narrio/domain/SourceSearch.kt`.

## Decisions

### Results

- Every enabled source gets a section immediately, showing its own state: waiting, searching, checking TorBox, done (with a count), failed (with a reason and **Retry**), or skipped (disabled, or needs TorBox while disconnected). One slow or failing provider never holds back the others.
- A release found by several providers appears once, in the highest-priority section that found it, marked "Also found by …".
- Within a section: confirmed matches first, then "possible matches" collapsed under their own heading, using the existing `SourceQuality` matching rules. Matching strictness does not change in this work.
- **Best match** leads the page, chosen across all sections:
  1. already on the phone
  2. ready to stream (TorBox cached, or a free public recording)
  3. strong title/author match
  4. remembered or preferred format (M4B by default)
  5. book language, unabridged, narrator known
  6. seeders
  
  An uncached release can be best only when nothing is ready, and then it says it needs preparing in TorBox. The card names the reasons in plain words ("Ready to stream · M4B · Read by …") and offers **Listen** (or **Prepare**). While the search is still running the card may improve, but it must not jump under the listener's finger: show a quiet "Better match found" update instead of swapping during interaction.
- The existing automatic choice and **Listen** on book details use the best match. Choosing any release in a section keeps today's behavior (open its page or make it the choice).

### Speed

- Results stream per provider. Search title variants per provider stop early once a provider has confident matches. Per-provider search timeouts drop to about 15 s (reported as "timed out" with Retry) without changing add-on rate limits.
- TorBox cache checks and Internet Archive detail hydration run per section as results arrive, not after every provider finishes. Inspecting uncached torrent files still happens only when nothing ready has been found, and only after the provider sections have reported.
- Recent results stay cached per book for 10 minutes, as today; changing source settings invalidates them.

### Settings

- Settings → Add-ons becomes a single place for sources, with sections for **Audiobook sources**, **Ebook sources**, and **Book info**. Audiobook sources list the built-in sources (Internet Archive / LibriVox, My TorBox library, TorBox search) alongside installed add-ons.
- Each source can be turned on or off. Audiobook sources can be reordered; order sets the section order and tie-breaking. Built-in sources can be disabled but not removed. Sources needing TorBox say so when it isn't connected.
- Existing add-on enable/remove/refresh/import behavior and persistence stay as they are.

## Workstreams

| | Workstream | Owns | Model |
|---|---|---|---|
| R | Regression fix | Audio lookup skipped/missing since the reader work; separate PR to `dev` | GPT-6.1-Sol |
| S | Streaming search engine | `StreamingSourceSearch`, `SourceProviderSettings` (data, persistence, built-in providers), best-match ranking, per-provider timeouts/early stop, cache invalidation, ViewModel state | GPT-6.1-Sol |
| U | Search and settings UI | Book-details results by source, Best match card, section states and Retry, settings source list with toggles and reordering | Opus 5.5 |

S and U branch from `feature/source-search` and open draft PRs into it; it then lands in `dev` as one squash merge. U builds against the contract with a clearly named fake until S lands; S keeps UI changes to what's needed to expose state. Change `SourceSearch.kt` only with a note in your PR.
