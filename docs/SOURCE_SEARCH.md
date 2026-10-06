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

## Engine integration for U

The shared `SourceSearch.kt` contract is unchanged. `AppGraph.streamingSourceSearch` implements it; `AppGraph.sourceProviderSettings` implements the persistent settings registry. The registry's IDs are `archive`, `torbox-library`, `torbox-search`, and `addon:<manifest-id>`.

Bind details to `NarrioViewModel.streamedSourceSearch` (nullable before a search), or `sourceSearch.value.streamed`. Call `findSources(book, force = true)` to search again and `retrySource(providerId)` to retry a failed group. `SourceGroup.alsoFoundBy` maps the displayed recording ID to other provider **names**. Group order already follows settings priority. Use `sourceSearch.choice` for the listener's choice: an explicit `chooseVersion(recording)` survives later snapshots and duplicate ownership changes; otherwise `snapshot.best.recording` leads. `chooseRecording` and `listenToChoice` retain their existing adoption/playback behavior. The flat `recordings`, `possible`, `versions`, `loading`, and `error` fields remain for the current screens.

Bind audiobook settings to `sourceProviderSettings.providers`, and call its `setEnabled` and `move` methods. Add-on switches forward to `AddonManager`; no separate enabled-state migration can overwrite an existing disabled add-on. Refresh, import, and remove still use `graph.addons` with the original manifest ID (without `addon:`). Ebook sources and book-info providers still come from `graph.addons.installed`. Registry `lastStatus` reports the last actual lookup in this app process; it is not a persisted health probe. Status changes do not invalidate search results, while selection, ordering, definition changes, completed phone downloads, and remembered-format changes invalidate the recent-results cache.

Completed phone downloads participate without a network lookup, using the original Archive or TorBox-library section and the same title/author and collection-file checks. A skipped section describes its **network lookup**; it may still contain an existing phone recording. `ON_PHONE` distinguishes those recordings without claiming a TorBox cache hit. A download completing during a session reranks it without restarting providers. Listen checks the actual completed source before requesting delivery.

Each provider receives 15 seconds of active search time across title variants. Declared add-on rate-limit/lock waits remain cancellable and report `WAITING`, without spending that budget. Cache checks/hydration and the final optional file pass also have bounded 15-second checks; total section time can exceed 15 seconds when waiting or checking. Strong identity evidence stops title variants even when uncached audio still needs file inspection. Once every section has reported, up to eight distinct uncached releases are inspected only if no qualified ready or phone recording exists. Failed groups can retain useful releases for review and retry independently.

Controlled virtual-clock timing fixture: a public provider answers at 250 ms, another provider fails at 1,000 ms, and a slow provider takes 30,000 ms. The previous `BookSourceDiscovery` returns its first result and completes at 30,000 ms. The streaming engine exposes the fast best match at 250 ms while the slow group is still searching, then completes at 15,000 ms with that group failed as timed out. This is deterministic fixture evidence, not a live-provider latency measurement. The ported discovery/automatic-selection fixtures retain matched releases and possible matches; duplicate display IDs follow priority ownership instead of the old account-first flat ordering.

Additional controlled fixtures address the existing costs identified in regression PR #56:

- Serialized add-on variants: with two title variants, a 250 ms request and a declared 60,000 ms wait between requests, the old pipeline completes at 60,500 ms. A confident first match stops the streamed provider at 250 ms with one request. The declared wait is unchanged when a second request is needed; existing request/client limits of up to 40/60 seconds are superseded by the cancellable 15-second active search budget.
- Delayed delivery checks: with public search/hydration taking 250/500 ms, indexed search/cache checks taking 100/750 ms, and another search taking 10,000 ms, the old pipeline publishes at 11,250 ms. Streamed hydration starts at 250 ms and cache checking at 100 ms; the public result appears at 750 ms and the cloud result at 850 ms, while the slow provider is still searching. Completion is 10,000 ms. Ready results skip the optional uncached-file phase (zero calls). The 12-recording/four-at-a-time hydration cap and final eight-release file cap remain.
- Partial failure recovery: a successful 5,000 ms provider plus a failing 1,000 ms provider requires another 5,000 ms full lookup in the old pipeline. Retrying just the failed streamed group takes 1,000 ms and keeps the existing ready result visible; successful-provider calls across initial lookup and recovery fall from two to one. Reopening a book with failed groups still performs a full lookup: only complete error-free results enter the ten-minute cache, as before. Session Retry avoids that cost without caching failed outcomes.

These measurements use coroutine virtual time and controlled provider fixtures, not production network timings. R's automatic audio lookup for catalog books with ebooks and its independence from reading-library hydration remain intact.

## Screens (U)

Book details render only engine snapshots (`sourceSearch.value.streamed`); before the first snapshot, or when the lookup itself throws, every enabled source shows as searching (or failed with that error), and skipped sources say why. The pinning rule lives in `PinnedBest` (`ui/SourceResultsModel.kt`): a new best replaces the shown one only until the listener first touches or scrolls the page, or never while TalkBack is on; after that it waits behind "Better match found". Showing it also makes it the choice. Listen calls `chooseVersion` with the shown release, then `listenToChoice`; Prepare chooses it and opens Listening options, where TorBox preparation stays explicit. Rows open the recording page through `chooseRecording`. Settings bind audiobook rows to `sourceProviderSettings` (which restarts an open search on change) and ebook/book-info rows to `graph.addons`.
