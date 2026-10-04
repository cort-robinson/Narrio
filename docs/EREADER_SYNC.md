# Sync engine integration (workstream C)

`Reading.kt` is unchanged. C adds `NarrationPositionMapper`, `PositionMappingRepository`,
`AlignmentJobRepository`, `ReadingSync`, and pure `SyncDecisions`/`ListeningActivityGate`.
No Room schema or migration is added.

## Integration boundaries

- A: replace `TemporaryFileSharedPositions`, `TemporaryFileAlignmentJobs`, and
  `TemporaryLegacyMappingRepository` in `AppGraph` with the durable multi-edition repositories.
  Keep `AudioOwnedPositionStore` around A's shared-position store; implement `GuardedSharedPositionStore`
  to check ownership at the transaction commit boundary, including already queued reader saves. B and the service must use
  the same gated instance. The temporary adapter treats the existing follow-along attachment
  as the single active edition and supports recording history plus registered recordings.
- A: `PositionMappingRepository.snapshot(bookId, sourceId)` must return the active normalized
  `BookText`, chosen `AudioSource` (including known part durations), and edition-scoped bindings.
  Return null for unavailable text/recordings. Persist alignment progress using `AlignmentKey`
  and `AlignmentProgress`; source layout and normalization/algorithm versions invalidate old jobs.
  Apply `mergeNarration` as an atomic read/merge/write against manual timing adjustments.
- B/D: `NarrioViewModel.resolveReadingStart(bookId, editionId, previous, pagesMoved)` returns a
  `SyncJump<ContentCursor>`. Supply rendered page distance when known; unknown changed locations
  conservatively offer Undo. B renders/restores the destination and implements text Undo by
  restoring `previous` without an immediate activity commit. Reader activity goes through
  `graph.sharedPositions`, including its observed sequence.
- D: observe `NarrioViewModel.readingSync` (`ReadingSyncState`) for pair identity, pairing status,
  audio/text jumps, confidence and `correcting`. Render the shared snackbar for `offerUndo`, use
  `undoSyncJump()` for audio Undo, and `clearSyncJump()` on dismissal. Render `≈` for ESTIMATED.
  Audio Undo preserves pause/play state and expires after another navigation action.
- B/E: `seekFromText(ContentCursor)` is an explicit sentence tap, not a browsing callback. It
  selects the mapped part, preserves pause/play state, and corrects an estimated start on playback.
  `graph.positionMapper.textFor(...)` maps the Media3 media clock for narration decorations.
  Reading navigation and together-mode rendering remain owned by B/E.

## Behavior and bounds

Exact word anchors and supplied passage timestamps retain pair identity. Audio between nearby
anchors maps exactly; large gaps and extrapolation remain estimated. Text-to-audio interpolation
is estimated. Multipart selection prefers anchor points/ranges, then unambiguous chapter/part
bindings. Overlapping ranges, unknown parts, wrong editions/normalization versions and ambiguous
layouts return null (UNMAPPED); progression/Readium JSON never supplies evidence.

An estimated text start recognizes one 20-second window using the installed English model. A
locally bracketed target corrects the seek and becomes EXACT. Playback elapsed during recognition
is included in the corrected seek. If the target is outside the recognized range, the model is
missing, the language is unsupported, or audio cannot decode, playback stays estimated. No model
download is initiated by the correction path. New navigation/recording/edition changes invalidate
the pending correction; concurrently rejected anchors cannot establish EXACT.

Listening commits occur after about ten seconds of elapsed playing activity, not seek deltas or
media-clock speed. Pauses/buffering add no activity. Navigation resets the dwell; the writer's
observed sequence rejects stale commits. Per-recording listening history is still saved separately.

Background alignment is on by default. WorkManager queues downloaded parts without network or
charging requirements (battery/storage must not be low), and streams only on unmetered power while
charging. Missing English models download on unmetered connections; correction never initiates
this download. Local alignment reads cache only, so eviction/removal cannot start an unconstrained
stream. It probes unknown durations using Media3, processes at most six windows/four minutes per
batch, persists after each window, and queues a continuation. Missing/unreadable audio is retryable
and is excluded from pairing evidence. Turning the setting off cancels queued/running work.

Recognition is serialized across foreground/correction/background paths; each decode checks
cancellation and has a 45-second decoder deadline plus bounded network timeouts. A window retains
at most four million mono samples; background PCM/transcripts are not written to disk. Saved auto
anchors are sampled down to 2,048 per part, preserving manual matches. Temporary progress retains
512 recent pair/part aggregates; A's Room implementation should retain resumability for the full
library without this temporary eviction policy. Foreground sync retains at most 4,096 attempt keys.

Pairing uses successfully decoded attempts and accepted recognized anchor coverage. Three or more
attempts with at least 80% coverage yields MATCHES; any lesser positive coverage yields PARTIAL.
At least five unmatched attempts spread over two minutes or multiple parts yields MISMATCH.
Insufficient evidence stays UNCHECKED. Foreground anchors provide PARTIAL evidence before background
jobs run. Resetting timing resets that part's background progress. MISMATCH disables automatic mode
and recording mapping and sentence-to-audio seeks, without disabling independent playback/reading.

## Contract change requests / gaps

No edits to `Reading.kt` are required for mapping. A must adopt or relocate the repository interfaces
above and supply active-edition/binding lookup plus atomic alignment-progress persistence. A's
store should also preserve the ownership gate's serialized commit boundary when replacing the
temporary store. Reading Undo/rendered page-distance calculation belongs to B/D, not this worker.
Publisher SMIL/media overlays remain unimplemented; supplied WebVTT is supported through the
existing pair-bound timing path. Recognition accuracy, latency, battery cost, and background
constraint behavior on physical phones require physical-device/provider acceptance separately
from controlled CI fixtures.
