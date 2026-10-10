package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FileChoicesTest {
    private val book = Audiobook("catalog:carl2", "Carl's Doomsday Scenario", "Matt Dinniman", provider = "catalog")
    private fun part(name: String) = AudioPart("torbox:9:${name.hashCode()}", name, name.substringAfterLast('/'), torrentId = 9, fileId = name.hashCode().toLong())
    private val bundle = AudioSource("torbox:9:mp3", "Ordered audio parts", "MP3", listOf(
        "Dungeon Crawler Carl/Book 1 - Dungeon Crawler Carl/01.mp3", "Dungeon Crawler Carl/Book 1 - Dungeon Crawler Carl/02.mp3",
        "Dungeon Crawler Carl/Book 2 - Carl's Doomsday Scenario/1.mp3", "Dungeon Crawler Carl/Book 2 - Carl's Doomsday Scenario/2.mp3",
        "Dungeon Crawler Carl/Book 2 - Carl's Doomsday Scenario/10.mp3",
    ).map(::part), delivery = "torbox", torrentId = 9)
    private val release = Audiobook("catalog:carl2", "Carl's Doomsday Scenario", "Matt Dinniman", provider = "knaben",
        releaseTitle = "Matt Dinniman - Dungeon Crawler Carl Series", torrentHash = "c".repeat(40), recordingId = "knaben:${"c".repeat(40)}", sources = listOf(bundle))

    @Test fun choiceFiltersAndOrdersByPathKeepingSourceAndPartIds() {
        val keys = listOf(bundle.parts[4], bundle.parts[2]).map(FileChoices::fileKey)
        val chosen = FileChoices.apply(bundle, keys)!!
        assertEquals(bundle.id, chosen.id)
        assertEquals(listOf(bundle.parts[4], bundle.parts[2]), chosen.parts)
        // Account listings sometimes drop the release folder or use other separators; the same file still matches.
        val shorter = bundle.copy(parts = bundle.parts.map { it.copy(name = it.name.removePrefix("Dungeon Crawler Carl/").replace('/', '\\')) })
        assertEquals(listOf(shorter.parts[4].id, shorter.parts[2].id), FileChoices.apply(shorter, keys)!!.parts.map { it.id })
        // A choice that isn't entirely in the layout is never applied in part.
        assertNull(FileChoices.apply(bundle, keys + "Other/file.mp3"))
        assertEquals(listOf("Other/file.mp3"), FileChoices.missing(bundle, keys + "Other/file.mp3"))
    }

    @Test fun aPathMatchesBySuffixOnlyWhenExactlyOneFileDoes() {
        val twoDiscs = AudioSource("s", "", "MP3", listOf(part("Disc 1/01.mp3"), part("Disc 2/01.mp3")))
        assertNull("01.mp3 could be either disc", FileChoices.locate(twoDiscs.parts, "01.mp3"))
        assertEquals(twoDiscs.parts[1], FileChoices.locate(twoDiscs.parts, "Release/Disc 2/01.mp3"))
        assertEquals(twoDiscs.parts[0], FileChoices.locate(twoDiscs.parts, "./Disc 1//01.mp3"))
    }

    @Test fun sameNamedPhoneFilesAreDifferentFiles() {
        val files = listOf("Disc 1/01.mp3", "Disc 2/01.mp3").map { LocalAudioFile("content://docs/${it.hashCode()}", it.substringAfterLast('/')) }
        val source = LocalAudio.recording(book, files).sources.single()
        val keys = source.parts.map(FileChoices::fileKey)
        assertEquals(2, keys.distinct().size)
        assertEquals(listOf(source.parts[1]), FileChoices.apply(source, listOf(keys[1]))!!.parts)
    }

    @Test fun onlyThisBooksFilesComesFromAutomaticBundleMatchingInNaturalOrder() {
        assertEquals(listOf(bundle.parts[2], bundle.parts[3], bundle.parts[4]).map(FileChoices::fileKey), FileChoices.bookFiles(book, release, bundle))
        // When nothing tells the books apart, every file is offered.
        val unnamed = bundle.copy(parts = listOf(part("a/2.mp3"), part("a/1.mp3")))
        assertEquals(listOf("a/1.mp3", "a/2.mp3"), FileChoices.bookFiles(book, release, unnamed))
    }

    @Test fun arrangeShowsChosenFilesFirstThenTheRest() {
        val arranged = FileChoices.arrange(bundle, listOf(FileChoices.fileKey(bundle.parts[3])))
        assertEquals(bundle.parts[3], arranged.first())
        assertEquals(bundle.parts.size, arranged.size)
    }

    @Test fun keyIgnoresBookNarrowingAndFormatCase() {
        val narrowed = release.copy(torrentHash = "", recordingId = "archive_item:book:abc")
        assertEquals("catalog:carl2|archive_item|MP3", FileChoices.key("catalog:carl2", narrowed, bundle.copy(format = "mp3")))
        assertEquals("catalog:carl2|${"c".repeat(40)}|MP3", FileChoices.key("catalog:carl2", release, bundle))
    }

    private val public = bundle.copy(id = "archive:mp3", delivery = "archive", torrentId = null,
        parts = bundle.parts.map { it.copy(torrentId = null, fileId = null, archiveUrl = "https://example.org/${it.id}") })
    private val publicRecording = release.copy(provider = "archive", torrentHash = "", recordingId = "carl_bundle:book:x", sources = listOf(public))

    @Test fun savedChoicePersistsAndPlaybackFetchesFilesTheNarrowedLayoutLacks() = runBlocking {
        val values = MemoryValues()
        var lookups = 0
        val archive = object : RecordingDiscovery {
            override suspend fun search(query: String, category: String) = emptyList<Audiobook>()
            override suspend fun recording(id: String): Audiobook { lookups++; assertEquals("carl_bundle", id); return publicRecording }
        }
        val files = ReleaseFiles(FileSelectionStore(values), null, null, archive, null)
        val narrowed = public.copy(parts = public.parts.drop(2))
        // Book 2 plus book 1's opening file, which automatic matching left out.
        val keys = (listOf(public.parts[0]) + narrowed.parts).map(FileChoices::fileKey)
        files.save(publicRecording.id, publicRecording, narrowed, keys)
        val restarted = ReleaseFiles(FileSelectionStore(values), null, null, archive, null)
        val playing = restarted.apply(publicRecording, narrowed)
        assertEquals(keys, playing.parts.map(FileChoices::fileKey))
        assertEquals(narrowed.id, playing.id)
        assertEquals(1, lookups)
        // With nothing missing, the full release isn't read again.
        assertEquals(keys, restarted.apply(publicRecording, public).parts.map(FileChoices::fileKey))
        assertEquals(1, lookups)
        restarted.forget(publicRecording.id)
        assertSame(narrowed, ReleaseFiles(FileSelectionStore(values), null, null, archive, null).apply(publicRecording, narrowed))
    }

    @Test fun aChoiceThatCantBeFoundFailsClosedNamingTheFiles() = runBlocking {
        val offline = object : RecordingDiscovery {
            override suspend fun search(query: String, category: String) = emptyList<Audiobook>()
            override suspend fun recording(id: String): Audiobook = throw java.io.IOException("offline")
        }
        val files = ReleaseFiles(FileSelectionStore(MemoryValues()), null, null, offline, null)
        val narrowed = public.copy(parts = public.parts.drop(2))
        files.save(publicRecording.id, publicRecording, narrowed, (listOf(public.parts[0]) + narrowed.parts).map(FileChoices::fileKey))
        // The full release can't be read: nothing plays rather than only some of the chosen files.
        val unreachable = runCatching { files.apply(publicRecording, narrowed) }.exceptionOrNull()
        assertTrue(unreachable is ProviderException && unreachable.message!!.contains("01.mp3"))
        // The release no longer has a chosen file: also refused, not the whole bundle.
        val gone = ReleaseFiles(FileSelectionStore(MemoryValues()), null, null, object : RecordingDiscovery {
            override suspend fun search(query: String, category: String) = emptyList<Audiobook>()
            override suspend fun recording(id: String) = publicRecording.copy(sources = listOf(narrowed))
        }, null)
        gone.save(publicRecording.id, publicRecording, narrowed, (listOf(public.parts[0]) + narrowed.parts).map(FileChoices::fileKey))
        assertTrue(runCatching { gone.apply(publicRecording, narrowed) }.exceptionOrNull() is ProviderException)
    }

    @Test fun phoneFilesLeftOutCanBeChosenAgainFromTheirManifest() = runBlocking {
        val values = MemoryValues()
        val files = listOf("Disc 1/01.mp3", "Disc 1/02.mp3", "Disc 2/01.mp3").map { LocalAudioFile("content://docs/${it.hashCode()}", it, 1, 60_000) }
        val recording = LocalAudio.recording(book, files).forBook(book)
        val full = recording.sources.single()
        val manifests = LocalManifests(values)
        manifests.put(book.id, full)
        val choices = ReleaseFiles(FileSelectionStore(values), null, null, null, null, manifests)
        choices.save(book.id, recording, full, listOf(FileChoices.fileKey(full.parts[1])))
        val played = choices.apply(recording, full)
        assertEquals(listOf(full.parts[1]), played.parts)
        // The played layout holds one file; the manifest still lists all three, so they can be chosen again.
        assertEquals(full.parts, choices.fullLayout(recording, played).parts)
        choices.save(book.id, recording, played, full.parts.map(FileChoices::fileKey))
        assertEquals(full.parts, choices.apply(recording, played).parts)
        choices.forget(book.id)
        assertNull(manifests.get(book.id, full.id))
    }
}
