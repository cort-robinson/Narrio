package app.narrio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.narrio.data.*
import app.narrio.domain.*
import app.narrio.playback.*
import app.narrio.reader.*
import app.narrio.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Room + reader + sync + library integration, using controlled files and recognition. */
@RunWith(AndroidJUnit4::class)
class ReadingIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val seeds by lazy { ReaderSeeds(compose) }
    private val graph get() = seeds.graph
    private val clean = mutableSetOf<String>()

    @After fun cleanup() = runBlocking {
        compose.runOnIdle { seeds.vm.closeReader() }
        withContext(Dispatchers.Main) { graph.playback.service?.forget() }
        clean.forEach { graph.followAlong.remove(it); graph.library.remove(it) }
    }

    @Test fun downloadedEbookIsDurableWithoutChangingTheSelectedRecordingOrListeningPlace() = runBlocking {
        val source = AudioSource("web-import-source", "Fixture recording", "MP3", listOf(AudioPart("part", "1.mp3", "Chapter", 120_000)))
        val book = Audiobook("integration-web-import", "Website ebook", "Fixture", sources = listOf(source), detailsLoaded = true)
        clean += book.id
        graph.library.save(book)
        graph.library.progress(book.id, NarrioJson.encodeToString(source), "part", 35_000, 1)
        val before = graph.library.find(book.id)!!
        val document = graph.followAlong.importDownloaded(ReaderFixtures.sampleEpub(), "EPUB", book, "Fixture website via TorBox", "torbox-web")
        val editions = graph.editionFiles.editions(book.id)
        assertEquals(document.id, editions.single().id)
        assertEquals("Fixture website via TorBox", editions.single().attribution)
        assertEquals("torbox-web", editions.single().provider)
        assertNotNull(graph.editionFiles.original(book.id, document.id))
        val after = graph.library.find(book.id)!!
        assertEquals(before.sourceJson, after.sourceJson)
        assertEquals(before.partId, after.partId)
        assertEquals(before.positionMs, after.positionMs)
        val restarted = FollowAlongStore(compose.activity, graph.library, graph.http, graph.torbox)
        assertEquals(document.id, restarted.load(graph.library.bookText(book.id)!!).id)
        assertEquals(book.id, after.book().id)
    }

    @Test fun selectingARecordingKeepsTheParentBookAndAdoptsOldEditionsAndAnchors() = runBlocking {
        val catalog = Audiobook("integration-catalog", "Integration Book", "Fixture", provider = "catalog", detailsLoaded = true)
        val source = AudioSource("integration-source", "Recording", "MP3", listOf(AudioPart("part", "1.mp3", "Chapter", 120_000)))
        val recording = Audiobook("integration-recording", catalog.title, catalog.author, sources = listOf(source), detailsLoaded = true)
        clean += catalog.id; clean += recording.id
        val document = graph.followAlong.importLocal("Chapter One\n\nOne two three four five six seven eight.".toByteArray(), "TXT", recording)
        val passage = document.chapters.first().passages.last()
        val binding = TextBinding(document.id, source.id, "part", WHOLE_BOOK, listOf(TextAnchor(passage.id, 20_000, 0, true)))
        graph.followAlong.bind(recording.id, binding)
        graph.library.bookmark(BookmarkEntry(bookId = recording.id, sourceId = source.id, partId = "part", positionMs = 20_000, label = "Original bookmark"))
        graph.sharedPositions.commit(PositionUpdate(recording.id, PositionOrigin.READING,
            text = ContentCursor(document.id, passage.resource, passage.offset), basedOnSequence = 0))
        val associated = graph.followAlong.adoptRecording(recording, catalog)
        assertEquals(catalog.id, associated.id)
        assertEquals(recording.id, associated.recordingId)
        assertEquals(source, associated.sources.single())
        assertNull(graph.library.find(recording.id))
        assertEquals(binding, graph.library.editionBindings(catalog.id, document.id).single().binding())
        assertEquals("Original bookmark", graph.library.bookmarks(catalog.id).first().single().label)
        assertEquals(catalog.id, graph.sharedPositions.current(catalog.id)!!.bookId)
        assertNotNull(graph.editionFiles.original(catalog.id, document.id))
        val library = RoomReadingLibrary(graph)
        val catalogFormats = library.observeBook(catalog).first()
        val recordingFormats = library.observeBook(associated).first()
        assertEquals(catalogFormats.editions, recordingFormats.editions)
        assertEquals(PairingStatus.PARTIAL, catalogFormats.pairing)
        assertEquals(1, catalogFormats.textChapter)
        assertEquals(MappingConfidence.EXACT, catalogFormats.position!!.audioConfidence)
        // The chosen recording opens as the parent book's one page.
        compose.runOnIdle { seeds.vm.sourceSearch.value = SourceSearchState(catalog, listOf(recording)); seeds.vm.open(associated) }
        compose.waitUntil(10_000) { seeds.vm.selection.value.book?.id == catalog.id && !seeds.vm.selection.value.loading }
    }

    @Test fun readerWriteQueuedBehindARoomTransactionRechecksPlaybackOwnership() = runBlocking {
        val book = Audiobook("integration-queued", "Queued reader", "Fixture", detailsLoaded = true)
        clean += book.id
        graph.library.save(book)
        val transactionEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocker = launch(Dispatchers.IO) { graph.database.withTransaction { transactionEntered.complete(Unit); release.await() } }
        transactionEntered.await()
        val store = AudioOwnedPositionStore(RoomSharedPositionStore(graph.library))
        store.playingBookId = null
        val write = async(Dispatchers.IO) { store.commit(PositionUpdate(book.id, PositionOrigin.READING,
            text = ContentCursor("edition", "text", 8), basedOnSequence = 0)) }
        delay(200)
        assertFalse(write.isCompleted)
        store.playingBookId = book.id
        release.complete(Unit)
        try { assertNull(write.await()); assertNull(store.current(book.id)) }
        finally { blocker.join(); store.playingBookId = null }
    }

    @Test fun measuredDurationsSurviveRestartAndStayWithTheirRecording() = runBlocking {
        val source = AudioSource("duration-source", "Fixture", "MP3", listOf(AudioPart("shared-part", "1.mp3", "Chapter")))
        val other = source.copy(id = "other-duration-source")
        val book = Audiobook("integration-duration", "Duration", "Fixture", sources = listOf(source, other), durationMs = 600_000, detailsLoaded = true)
        clean += book.id
        graph.followAlong.importLocal("one two three four five six".toByteArray(), "TXT", book)
        assertEquals(0L, graph.mappingRepository.snapshot(book.id, source.id)!!.source.parts.single().durationMs)
        graph.mappingRepository.duration(book.id, source.id, "shared-part", 90_000)
        graph.mappingRepository.persistDuration(book.id, source.id, "shared-part", 90_000)
        assertEquals(0L, graph.mappingRepository.snapshot(book.id, other.id)!!.source.parts.single().durationMs)
        val restarted = RoomPositionMappingRepository(graph.database, graph.followAlong)
        assertEquals(90_000L, restarted.snapshot(book.id, source.id)!!.source.parts.single().durationMs)
        graph.library.save(book)
        restarted.register(book.id, source)
        graph.library.progress(book.id, NarrioJson.encodeToString(source), "shared-part", 10_000, 1)
        assertEquals(90_000L, restarted.snapshot(book.id, source.id)!!.source.parts.single().durationMs)
        assertEquals(0L, restarted.snapshot(book.id, other.id)!!.source.parts.single().durationMs)
    }

    @Test fun estimatedStartCorrectsThroughRoomAndKeepsManualTiming() = runBlocking {
        val source = AudioSource("integration-correction-source", "Fixture", "MP3", listOf(AudioPart("part", "1.mp3", "Chapter", 120_000)))
        val book = Audiobook("integration-correction", "Correction", "Fixture", sources = listOf(source), detailsLoaded = true)
        clean += book.id
        val document = graph.followAlong.importLocal("one two three four five six seven eight nine ten".toByteArray(), "TXT", book)
        val line = document.chapters.first().passages.first()
        val wanted = ContentCursor(document.id, line.resource, line.offset + 14)
        val manual = TextAnchor(line.id, 20_000, 0)
        val binding = TextBinding(document.id, source.id, "part", WHOLE_BOOK, listOf(manual))
        graph.followAlong.bind(book.id, binding)
        graph.sharedPositions.commit(PositionUpdate(book.id, PositionOrigin.READING, text = wanted, basedOnSequence = 0))
        val jump = graph.readingSync.listeningStart(book.id, source, AudioCursor(source.id, "part", 0))
        assertEquals(MappingConfidence.ESTIMATED, jump.confidence)
        val target = SyncTarget(book, source, source.parts.first(), 50_000, 120_000, document, binding)
        val result = graph.readingSync.correct(target, wanted, NarrationWindowRecognizer { _, _ ->
            RecognizedWindow(WindowOutcome.MATCHED, listOf(TextAnchor(line.id, 40_000, 3, true), TextAnchor(line.id, 60_000, 9, true)))
        }, { target }) { id, merged -> graph.followAlong.mergeNarration(id, merged, merged.anchors.filter { it.auto }, 120_000) }
        assertNotNull(result)
        assertEquals(MappingConfidence.EXACT, graph.readingSync.state.value.confidence)
        val stored = graph.library.editionBindings(book.id, document.id).single().binding()
        assertTrue(stored.anchors.contains(manual))
        assertEquals(wanted.offset, graph.positionMapper.textFor(book.id, result!!.audio)!!.text.offset)
        val key = alignmentKey(book.id, graph.mappingRepository.snapshot(book.id, source.id)!!, "part")
        val progress = AlignmentProgress(key, 90_000, 3, 3, 0, 60_000)
        graph.alignmentJobs.save(progress)
        assertEquals(progress, RoomAlignmentJobs(graph.database).progress(key))
        assertEquals(PairingStatus.MATCHES, RoomReadingLibrary(graph).observeBook(book).first().pairing)
        assertEquals(0, RoomAlignmentJobs(graph.database).progress(key.copy(layoutFingerprint = "changed")).attempted)
    }

    @Test fun listeningToReadingRestoresAMappedCursorAndOffersUndoWithoutCommitting() = runBlocking {
        val book = seeds.book("integration-reading-undo", ReaderFixtures.sampleEpub(), "EPUB", "Reader Undo")
        clean += book.id
        val source = AudioSource("integration-undo-source", "Fixture", "MP3", listOf(AudioPart("part", "1.mp3", "Book", 120_000)))
        graph.library.save(book.copy(sources = listOf(source)))
        val document = graph.followAlong.load(graph.library.bookText(book.id)!!)
        val passage = document.chapters.last().passages.first()
        val target = ContentCursor(document.id, passage.resource, passage.offset)
        graph.followAlong.bind(book.id, TextBinding(document.id, source.id, "part", WHOLE_BOOK, listOf(TextAnchor(passage.id, 60_000))))
        graph.sharedPositions.commit(PositionUpdate(book.id, PositionOrigin.LISTENING,
            audio = AudioCursor(source.id, "part", 60_000), audioConfidence = MappingConfidence.EXACT, basedOnSequence = 0))
        val controller = seeds.open(book)
        compose.waitUntil(10_000) { controller.visible.value?.contains(target) == true }
        compose.onNodeWithText("Jumped to where you listened").assertExists()
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(10_000) { controller.cursor.value?.resource == document.chapters.first().passages.first().resource }
        delay(3_500)
        assertEquals(1, graph.sharedPositions.current(book.id)!!.sequence)
        assertEquals(PositionOrigin.LISTENING, graph.sharedPositions.current(book.id)!!.origin)
    }
    @Test fun realReaderPageTurnsCannotMoveTheSharedPositionWhileAudioPlays() = runBlocking {
        val book = seeds.book("integration-audio-owns", ReaderFixtures.sampleEpub(), "EPUB", "Audio owns reading")
        clean += book.id
        compose.waitUntil(15_000) { graph.playback.service?.initialized == true }
        compose.runOnIdle { seeds.vm.setBackgroundAlignment(false); seeds.vm.setReadAlongSync(false) }
        val wave = java.io.File(compose.activity.filesDir, "integration-owned.wav")
        val size = 120 * 8000 * 2
        wave.outputStream().use { stream ->
            stream.write(java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray()); putInt(16)
                putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
            }.array()); stream.write(ByteArray(size))
        }
        val source = AudioSource("integration-owned-source", "Fixture", "WAV", listOf(
            AudioPart("integration-owned-part", wave.name, "Chapter", 120_000, android.net.Uri.fromFile(wave).toString())))
        try {
            val controller = seeds.open(book)
            withContext(Dispatchers.Main) { graph.playback.service!!.load(book.copy(sources = listOf(source)), source, true) }
            compose.waitUntil(10_000) { graph.playback.state.value.playing }
            val before = graph.sharedPositions.current(book.id)
            val start = controller.cursor.value!!
            compose.runOnIdle { controller.next(false) }
            compose.waitUntil(5_000) { controller.cursor.value?.offset?.let { it > start.offset } == true }
            val firstTurn = controller.cursor.value!!
            compose.runOnIdle { controller.next(false) }
            compose.waitUntil(5_000) { controller.cursor.value?.offset?.let { it > firstTurn.offset } == true }
            delay(3_200)
            assertEquals(before, graph.sharedPositions.current(book.id))
        } finally {
            withContext(Dispatchers.Main) { graph.playback.service?.forget() }
            wave.delete()
            compose.runOnIdle { seeds.vm.setBackgroundAlignment(true); seeds.vm.setReadAlongSync(true) }
        }
    }

    @Test fun realFileImportReturnsTheExistingBookAndSeparatesFailures() = runBlocking {
        val importer = LocalEbookImporter(compose.activity, graph.library, graph.followAlong)
        val file = java.io.File(compose.activity.cacheDir, "integration-import.epub")
        val unsupported = java.io.File(compose.activity.cacheDir, "integration-import.azw3")
        val drm = java.io.File(compose.activity.cacheDir, "integration-drm.epub")
        try {
            file.writeBytes(ReaderFixtures.sampleEpub(23))
            val first = importer.importResult(android.net.Uri.fromFile(file))
            clean += first.book.id
            val existing = importer.importResult(android.net.Uri.fromFile(file))
            assertEquals(first.book.id, existing.book.id)
            assertFalse(existing.created)
            assertEquals(1, graph.editionFiles.editions(first.book.id).size)
            unsupported.writeText("unsupported")
            suspend fun failure(file: java.io.File): EbookImportFailure {
                val error = runCatching { importer.importResult(android.net.Uri.fromFile(file)) }.exceptionOrNull()
                assertTrue(error.toString(), error is EbookImportException)
                return (error as EbookImportException).reason
            }
            assertEquals(EbookImportFailure.UNSUPPORTED, failure(unsupported))
            assertEquals(EbookImportFailure.UNREADABLE, failure(java.io.File(compose.activity.cacheDir, "missing-integration.epub")))
            val data = linkedMapOf<String, ByteArray>()
            java.util.zip.ZipInputStream(file.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) { data[entry.name] = zip.readBytes(); entry = zip.nextEntry }
            }
            data["META-INF/encryption.xml"] = "<encryption><EncryptionMethod Algorithm='https://fixture.invalid/drm'/></encryption>".toByteArray()
            java.util.zip.ZipOutputStream(drm.outputStream()).use { zip ->
                data.forEach { (path, bytes) -> zip.putNextEntry(java.util.zip.ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
            }
            assertEquals(EbookImportFailure.DRM_PROTECTED, failure(drm))
        } finally { file.delete(); unsupported.delete(); drm.delete() }
    }

}
