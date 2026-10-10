package app.narrio.data

import app.narrio.domain.*
import app.narrio.ui.SourceSearchState
import app.narrio.ui.StartChoice
import app.narrio.ui.SwitchStart
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

/** Review fixes for fixing sources yourself: link hops, phone file names, hidden results, and how a switch starts. */
class AdvancedSourcingRulesTest {
    @Test fun everyTorrentLinkHopMustBeAPublicHttpsAddress() {
        assertEquals("https://releases.example.org/a.torrent", ReleaseLinks.checkedHop("https://releases.example.org/a.torrent"))
        listOf("http://releases.example.org/a.torrent", "https://10.0.0.2/a.torrent", "https://router.local/a.torrent",
            "https://user:secret@releases.example.org/a.torrent", "https://releases.example.org:8443/a.torrent", "not a url").forEach { hop ->
            assertTrue(hop, runCatching { ReleaseLinks.checkedHop(hop) }.exceptionOrNull() is ProviderException)
        }
    }

    @Test fun onlyPublicAddressesAreResolved() {
        fun at(address: String) = InetAddress.getByName(address)
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.10", "169.254.1.1", "100.64.0.1", "0.0.0.0", "255.255.255.255", "::1", "fd00::1", "fe80::1")
            .forEach { assertFalse(it, PublicDns.public(at(it))) }
        listOf("93.184.216.34", "1.1.1.1", "2606:4700:4700::1111").forEach { assertTrue(it, PublicDns.public(at(it))) }
    }

    @Test fun sameNamedPhoneFilesKeepTheirFolderOrANumber() {
        val picked = listOf(
            "content://com.android.externalstorage.documents/document/primary%3AAudiobooks%2FDisc%201%2F01.mp3" to "01.mp3",
            "content://com.android.externalstorage.documents/document/primary%3AAudiobooks%2FDisc%202%2F01.mp3" to "01.mp3",
            "content://com.example.cloud/document/7781" to "02.mp3", "content://com.example.cloud/document/7782" to "02.mp3",
            "content://com.example.cloud/document/7783" to "intro.mp3")
        assertEquals(listOf("Disc 1/01.mp3", "Disc 2/01.mp3", "02 (3).mp3", "02 (4).mp3", "intro.mp3"), LocalAudio.paths(picked))
    }

    private val book = Audiobook("catalog:b", "Project Hail Mary", "Andy Weir", provider = "catalog")
    private fun release(id: String, hash: Char) = Audiobook(id, "Project Hail Mary", "Andy Weir", provider = "knaben", torrentHash = hash.toString().repeat(40),
        cacheState = "cached", cachedFormats = listOf("M4B"), sources = listOf(AudioSource("$id-s", "", "M4B", listOf(AudioPart("$id-p", "a.m4b", "a")))))

    @Test fun hidingLeavesCachedResultsAddedReleasesAndThePickAtOnce() {
        val wrong = release("knaben:wrong", 'a'); val right = release("knaben:right", 'b'); val linked = release("knaben:linked", 'c')
        val streamed = StreamedSourceSearch(book, listOf(SourceGroup("search", "Search", SourceGroupStatus.DONE, recordings = listOf(wrong, right))),
            BestMatch(wrong, emptyList(), "search"), complete = true)
        val state = SourceSearchState(book, listOf(wrong, right), searched = true, chosenId = wrong.id, streamed = streamed, added = listOf(linked))
        val keys = setOf(HiddenReleases.key(wrong), HiddenReleases.key(linked))
        val filtered = state.withoutHidden(keys)
        assertEquals(listOf(right), filtered.results)
        assertNull(filtered.chosenId)
        assertNull("The hidden best match is gone until the search re-ranks", filtered.streamed!!.best)
        assertEquals(listOf(right), filtered.streamed!!.groups.single().recordings)
        assertEquals(right, filtered.choice)
        assertSame(state, state.withoutHidden(emptySet()))
    }

    @Test fun boundarySleepTimersFollowTheirFileWhenFilesAreChosenOrReordered() {
        val chapterTimer = app.narrio.playback.SleepTimer(app.narrio.playback.SleepMode.END_OF_CHAPTER,
            stop = PartPlace(1, 90_000), from = PartPlace(1, 30_000))
        // The second file moves to the front: the timer still stops in it.
        val moved = app.narrio.playback.remapSleep(chapterTimer, listOf("a", "b", "c"), listOf("b", "c"))
        assertEquals(PartPlace(0, 90_000), moved.stop)
        assertEquals(PartPlace(0, 30_000), moved.from)
        // Its file is no longer chosen: the chapter timer ends rather than stopping in another file.
        assertFalse(app.narrio.playback.remapSleep(chapterTimer, listOf("a", "b", "c"), listOf("a", "c")).active)
        // Minute timers keep their deadline.
        val minutes = app.narrio.playback.SleepTimer(app.narrio.playback.SleepMode.MINUTES, 15, untilMs = 123)
        assertSame(minutes, app.narrio.playback.remapSleep(minutes, listOf("a", "b"), listOf("b")))
    }

    @Test fun aSwitchStartsNearWhenItCanElseResumesElseFromTheBeginning() {
        val near = CarryOver("p", 1, 2, 3, true)
        val resume = AudioCursor("s", "p", 5)
        assertEquals(StartChoice.NEAR, SwitchStart(near, resume).default)
        assertEquals(StartChoice.RESUME, SwitchStart(null, resume).default)
        assertEquals(StartChoice.BEGINNING, SwitchStart(null, null).default)
    }
}
