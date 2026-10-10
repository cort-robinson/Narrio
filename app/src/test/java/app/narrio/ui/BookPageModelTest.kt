package app.narrio.ui

import app.narrio.data.NarrioJson
import app.narrio.data.OfflineBook
import app.narrio.data.PreparationRecord
import app.narrio.data.playedRecording
import app.narrio.data.preparingRecording
import app.narrio.data.sameRecording
import app.narrio.data.ShelfEntry
import app.narrio.domain.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class BookPageModelTest {
    private val book = Audiobook("catalog:phm", "Project Hail Mary", "Andy Weir", provider = "catalog", description = "A lone astronaut.")
    private fun part(id: String, ms: Long = 3_600_000) = AudioPart(id, "$id.mp3", id, durationMs = ms)
    private fun release(id: String, narrator: String = "", cached: Boolean = true, hash: String = "", title: String = "Project Hail Mary - Andy Weir") = Audiobook(
        id, "Project Hail Mary", "Andy Weir", provider = "knaben", cacheState = if (cached) "cached" else "uncached", cachedFormats = if (cached) listOf("M4B", "MP3") else emptyList(),
        releaseTitle = if (narrator.isBlank()) title else "$title (read by $narrator)", torrentHash = hash,
        sources = listOf(AudioSource("$id-m4b", "Whole book", "M4B", listOf(part("$id-1", 36_000_000))), AudioSource("$id-mp3", "Parts", "MP3", listOf(part("$id-a"), part("$id-b")))))
    /** A recording adopted for [book], as the shelf stores it. */
    private fun adopted(recording: Audiobook) = recording.forBook(book)
    private fun shelf(saved: Audiobook, played: AudioSource? = null, partId: String = "", positionMs: Long = 0, state: String = "listening") =
        ShelfEntry(book.id, NarrioJson.encodeToString(saved), played?.let { NarrioJson.encodeToString(it) }.orEmpty(), partId, positionMs, state = state)
    private fun copy(recording: Audiobook, source: AudioSource, complete: Boolean = true) =
        OfflineBook(recording, source, if (complete) source.parts.size else 0, source.parts.size, 0, 0, false, false, false)

    @Test fun aShelfBookResumesItsOwnRecordingEvenWhenAnotherRanksHigher() {
        val mine = adopted(release("ray", "Ray Porter", cached = false))
        val played = mine.sources[1]
        val current = currentRecording(book, shelf(mine, played, "ray-b", 600_000), mine, emptyList())!!
        assertTrue(sameRecording(current.recording, release("ray")))
        // The search's best match is another release; the page's action still names and plays the listener's recording.
        val search = SourceSearchState(book, listOf(release("better", "Someone Else"), release("ray", "Ray Porter")))
        assertNotEquals("ray", search.choice?.id)
        assertEquals("Resume · 50 m left", resumeLabel(current))
        assertEquals("Ray Porter · Needs time to get ready", recordingLine(current.recording, book, connected = true))
        assertTrue(current.started)
        assertEquals(70f / 120f, current.fraction!!, .001f)
        // In the chooser it leads, once, above the other versions.
        val rows = chooserRows(current.recording, search, showAll = false)
        assertEquals(listOf(book.id, "better"), rows.map { it.id })
    }

    @Test fun aRecordingSavedButNeverPlayedListensAndOneNeverChosenHasNone() {
        val saved = adopted(release("ray", "Ray Porter"))
        val current = currentRecording(book, shelf(saved, state = "saved"), null, emptyList())!!
        assertFalse(current.started)
        assertEquals("Listen", resumeLabel(current))
        assertNull(currentRecording(book, shelf(book, state = "saved"), null, emptyList()))
        assertNull(currentRecording(book, null, null, emptyList()))
    }

    @Test fun aDownloadedCopyPlaysOfflineOnlyWhenItIsTheAudioBeingListenedTo() {
        val mine = adopted(release("ray", "Ray Porter"))
        val played = mine.sources[0]
        val sameAudio = currentRecording(book, shelf(mine, played, "ray-1", 0), mine, listOf(copy(mine, played)))!!
        assertEquals("Play offline", resumeLabel(sameAudio))
        assertEquals("Ray Porter · On this phone", recordingLine(sameAudio.recording, book, connected = false, onPhone = true))
        // A copy of another format doesn't take over Resume; it keeps its place on the offline row instead.
        val otherFormat = currentRecording(book, shelf(mine, played, "ray-1", 120_000), mine, listOf(copy(mine, mine.sources[1])))!!
        assertNull(otherFormat.offline)
        assertTrue(resumeLabel(otherFormat).startsWith("Resume"))
        assertEquals(mine.sources[1].id, offlineDownload(otherFormat.recording, otherFormat, listOf(copy(mine, mine.sources[1])))?.source?.id)
        // Not started yet: a finished copy is what Listen plays.
        assertNotNull(currentRecording(book, shelf(mine, state = "saved"), null, listOf(copy(mine, mine.sources[1])))!!.offline)
    }

    @Test fun preparingAnotherRecordingKeepsTheOneBeingListenedTo() {
        val listening = adopted(release("ray", "Ray Porter"))
        val preparing = adopted(release("kim", "Kim Doe", cached = false))
        val entry = shelf(preparing, listening.sources[0], "ray-1", 60_000, state = "preparing")
        val current = currentRecording(book, entry, listening.copy(sources = emptyList()), emptyList())!!
        assertTrue(sameRecording(current.recording, listening))
        assertFalse(current.preparing)
        assertTrue(sameRecording(pendingRecording(entry, current)!!, preparing))
        assertEquals("This one starts from the beginning. Your place in Ray Porter's recording stays saved.",
            switchNotice(RecordingSwitch(current.recording, null, preparing)))
    }

    @Test fun playedAndPreparingRecordingsStayApartOnOneShelfRow() {
        val ray = adopted(release("ray", "Ray Porter", hash = "a".repeat(40)))
        val kim = adopted(release("kim", "Kim Doe", cached = false, hash = "b".repeat(40)))
        val rayAudio = ray.sources[0].copy(id = "torbox:5:M4B", delivery = "torbox", parts = ray.sources[0].parts.map { it.copy(torrentId = 5) })
        // Getting Kim's ready replaced the row's book; Ray's audio is what played.
        val preparingKim = shelf(kim, rayAudio, "ray-1", 60_000, state = "preparing").copy(preparationId = 9)
        assertTrue(sameRecording(playedRecording(preparingKim, ray, null)!!, ray))
        assertTrue(sameRecording(preparingRecording(preparingKim, null)!!, kim))
        // An older shelf without the remembered recording never borrows Kim's narrator for Ray's audio.
        val unknown = playedRecording(preparingKim, null, null)!!
        assertTrue(unknown.recordingId.startsWith("played:"))
        assertEquals("Narrator not confirmed · Ready now", recordingLine(unknown, book, connected = true))
        // Unless the audio that played is the prepared torrent's: then the row's recording did play.
        val playedKim = preparingKim.copy(sourceJson = NarrioJson.encodeToString(rayAudio.copy(parts = rayAudio.parts.map { it.copy(torrentId = 9) })))
        assertTrue(sameRecording(playedRecording(playedKim, null, null)!!, kim))
        // An older row still holding the recording that played (another one getting ready, no record) keeps its narrator.
        val rayRow = shelf(ray.copy(sources = ray.sources + rayAudio), rayAudio, "ray-1", 60_000, state = "preparing").copy(preparationId = 9)
        assertTrue(sameRecording(playedRecording(rayRow, null, null)!!, ray))
        // Resuming Ray puts Ray back in the row; the remembered pending recording is still Kim.
        val resumed = shelf(ray, rayAudio, "ray-1", 61_000, state = "preparing").copy(preparationId = 9)
        val pending = PreparationRecord(book.id, "generation", kim, 9, "M4B")
        assertTrue(sameRecording(playedRecording(resumed, null, pending)!!, ray))
        assertTrue(sameRecording(preparingRecording(resumed, pending)!!, kim))
        val current = currentRecording(book, resumed, ray, emptyList(), pending)!!
        assertFalse(current.preparing)
        assertTrue(sameRecording(pendingRecording(resumed, current, pending)!!, kim))
        assertNull(preparingRecording(resumed.copy(state = "listening"), pending))
    }

    @Test fun freshResultsUpdateTheListenersRecordingButNotItsPlace() {
        val mine = adopted(release("ray", "Ray Porter", cached = false, hash = "c".repeat(40)))
        val played = mine.sources[1]
        val current = currentRecording(book, shelf(mine, played, "ray-b", 600_000), mine, emptyList())!!
        val fresh = release("ray-again", "Ray Porter", hash = "C".repeat(40)).copy(sources = listOf(AudioSource("new-m4b", "Whole book", "M4B", listOf(part("n1")))), cachedFormats = listOf("M4B"))
        val updated = refreshed(current, SourceSearchState(book, listOf(fresh)))
        assertEquals("cached", updated.recording.cacheState)
        assertEquals(listOf("M4B"), readyFormats(updated.recording))
        assertEquals(book.id, updated.recording.id)
        assertEquals(current.source, updated.source)
        assertEquals(current.place, updated.place)
        assertSame(current, refreshed(current, SourceSearchState(book, listOf(release("other")))))
    }

    @Test fun switchingSaysWhereTheOtherRecordingPicksUp() {
        val from = release("ray", "Ray Porter")
        val to = release("kim", "Kim Doe")
        assertEquals("This one starts from the beginning. Your place in Ray Porter's recording stays saved.", switchNotice(RecordingSwitch(from, null, to)))
        assertEquals("This one picks up where you left it, at Part 2 of 2 · 58%. Your place in Ray Porter's recording stays saved.",
            switchNotice(RecordingSwitch(from, null, to, PlaceSummary(PositionOrigin.LISTENING, "Part 2 of 2 · 58%", .58f))))
    }

    @Test fun catalogIdentityKeepsTheBookAndDropsTheRecording() {
        val saved = adopted(release("ray", "Ray Porter")).copy(narrator = "Ray Porter", narratorFromCatalog = false)
        val identity = saved.catalogIdentity()
        assertEquals(book.id, identity.id)
        assertEquals("catalog", identity.provider)
        assertTrue(identity.sources.isEmpty())
        assertEquals("Narrator not listed", identity.narrator)
        assertSame(book, book.catalogIdentity())
    }

    @Test fun formatsAndLabelsSayOnlyWhatIsKnown() {
        val mixed = release("x").copy(cachedFormats = listOf("MP3"))
        assertEquals(listOf("MP3"), readyFormats(mixed))
        assertEquals("MP3", defaultFormat(mixed, remembered = "M4B"))
        assertEquals(listOf("M4B", "MP3"), readyFormats(release("y")))
        assertEquals("MP3", defaultFormat(release("y"), remembered = "MP3"))
        assertEquals("M4B", defaultFormat(release("y"), remembered = ""))
        // Unabridged only when the release says so; a missing narrator says so too.
        assertEquals("Narrator not confirmed · Unabridged · Ready now", recordingLine(release("u", title = "Project Hail Mary [Unabridged]"), book, connected = true))
        assertEquals("Narrator not confirmed · Needs TorBox", recordingLine(release("v"), book, connected = false))
        assertEquals("1 m left", resumeTimeLeft(30_000))
        assertEquals("42 m left", resumeTimeLeft(42 * 60_000L))
        assertNull(timeLeftMs(AudioCursor("s", "a", 0), AudioSource("s", "", "MP3", listOf(part("a", 0)))))
    }
}
