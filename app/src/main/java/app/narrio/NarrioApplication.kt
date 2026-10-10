package app.narrio

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.narrio.data.*
import app.narrio.playback.PlaybackHub
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

class NarrioApplication : Application() {
    lateinit var graph: AppGraph
        private set
    override fun onCreate() { super.onCreate(); graph = AppGraph(this); graph.bookAlignment.start(); graph.preparationChecks.start() }
}

class AppGraph(application: Application) {
    val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "Narrio/${BuildConfig.VERSION_NAME} Android audiobook player").build())
        }.build()
    val database = Room.databaseBuilder(application, LibraryDatabase::class.java, "narrio.db").addMigrations(object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS positions (bookId TEXT NOT NULL, sourceId TEXT NOT NULL, sourceJson TEXT NOT NULL, partId TEXT NOT NULL, positionMs INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(bookId, sourceId))")
        }
    }, object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE shelf ADD COLUMN pendingFormat TEXT NOT NULL DEFAULT ''") }
    }, LibraryMigration3To4, LibraryMigration4To5, LibraryMigration5To6).build()
    val library = database.library()
    val credentials = CredentialStore(application)
    val catalog = ArchiveDiscovery(http)
    val indexedCatalog = KnabenDiscovery(http)
    val addons = AddonManager.create(application, http)
    private val sourceSettingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sourceProviderSettings = DeviceSourceProviderSettings.create(application, addons, sourceSettingsScope)
    val ebookProviderSettings = DeviceSourceProviderSettings.create(application, addons, sourceSettingsScope, SourceCatalog.EBOOK)
    val metadata = BookMetadata(http, addonSearch = addons::catalog, addonRevision = { addons.revision })
    val books = BookCatalog(metadata, addonSearch = addons::catalog, addonRevision = { addons.revision }, apple = AppleBooks(metadata))
    val torbox = TorBoxDelivery(http, credentials::read)
    val webEbooks = TorBoxWebEbooks(torbox)
    val webEbookAcquisition = EbookWebAcquisition(http, webEbooks::acquire, { source -> torbox.webTextLink(source.torrentId!!, source.fileId!!) })
    val torrentFiles = TorrentFileDiscovery(http)
    val bookSources = BookSourceDiscovery(catalog, listOf(addons), torbox::library, torbox::checkCached, torrentFiles::recording)
    val textDiscovery = GutenbergTextDiscovery(http)
    val followAlong = FollowAlongStore(application, library, http, torbox)
    val editionFiles: app.narrio.domain.EditionFiles = followAlong
    val ebookImporter = LocalEbookImporter(application, library, followAlong, metadata::enrich)
    val preferences = application.getSharedPreferences("preferences", Application.MODE_PRIVATE)
    val playback = PlaybackHub()
    val offline = OfflineStore(application, http, torbox)
    val listeningRecordings = ListeningRecordings(preferences)
    // Advanced sourcing: the listener's own search words, hidden releases, file choices, links, and phone audio.
    private val advancedValues = PreferenceValues(application.getSharedPreferences("advanced-sources", Application.MODE_PRIVATE))
    val hiddenReleases = HiddenReleaseStore(advancedValues)
    val sourceWords = SourceWordsStore(advancedValues)
    val torboxLibrary = TorBoxLibrary(torbox)
    val localManifests = LocalManifests(advancedValues)
    val releaseFiles = ReleaseFiles(FileSelectionStore(advancedValues), torbox, torboxLibrary, catalog, torrentFiles, localManifests)
    val linkedReleases = LinkedReleases(http, torbox, torboxLibrary, torrentFiles).also { torbox.linkedTorrent = it::torrentFor }
    val localAudio = LocalAudioImporter(application, LocalAudioGrants(advancedValues))
    val streamingSourceSearch: app.narrio.domain.StreamingSourceSearch = ProviderSourceSearch(sourceProviderSettings, { provider ->
        when (provider.id) {
            DeviceSourceProviderSettings.ARCHIVE -> RecordingSourceLookup(catalog)
            DeviceSourceProviderSettings.LIBRARY -> AccountSourceLookup(torbox::library)
            else -> if (provider.kind == app.narrio.domain.SourceProviderKind.ADDON) AddonSourceLookup(addons, provider.id.removePrefix("addon:")) else null
        }
    }, torbox::checkCached, torrentFiles::recording, sourceProviderSettings::recordStatus,
        phoneRecordings = { offline.books.value.filter { it.complete }.map { it.book.copy(id = it.book.recordingId.ifBlank { it.book.id }, sources = listOf(it.source)) } },
        rankingChanges = merge(offline.books.map { Unit }, hiddenReleases.hidden.drop(1).map { Unit }),
        preferredFormat = { preferences.getString("format:${it.id}", "M4B").orEmpty() },
        listening = { listeningRecordings.keys(it.id) }, hidden = hiddenReleases::keys)
    val annasArchive = AnnasArchive(http, WebViewPages(application))
    val textFinder = BookTextFinder(textDiscovery, indexedCatalog, torbox, addons::ebooks, webEbooks::accountText)
    val streamingEbookSearch: app.narrio.domain.StreamingEbookSearch = ProviderEbookSearch(ebookProviderSettings, { provider ->
        when (provider.id) {
            DeviceSourceProviderSettings.RECORDING_FILES -> RecordingEbookLookup
            DeviceSourceProviderSettings.TORBOX_EBOOKS -> AccountEbookLookup(textFinder)
            DeviceSourceProviderSettings.GUTENBERG -> GutenbergEbookLookup(textFinder)
            else -> if (provider.kind != app.narrio.domain.SourceProviderKind.ADDON) null
                else provider.id.removePrefix("addon:").let { id ->
                    if (addons.installed.value.any { it.id == id && it.searchedInApp }) AnnasArchiveEbookLookup(annasArchive, addons, id) else AddonEbookLookup(textFinder, addons, id)
                }
        }
    }, ebookProviderSettings::recordStatus)
    val speechModels = SpeechModelStore(application, http)
    val narrationSync = app.narrio.playback.NarrationSync(application, http, offline, torbox, speechModels)
    val sharedPositions = app.narrio.domain.AudioOwnedPositionStore(RoomSharedPositionStore(library))
    val mappingRepository = RoomPositionMappingRepository(database, followAlong)
    val positionMapper: app.narrio.domain.PositionMapper = app.narrio.domain.NarrationPositionMapper(mappingRepository)
    val alignmentJobs: app.narrio.domain.AlignmentJobRepository = RoomAlignmentJobs(database)
    val readingSync = app.narrio.playback.ReadingSync(sharedPositions, positionMapper, mappingRepository, alignmentJobs)
    val bookAlignment = app.narrio.playback.BookAlignmentScheduler(application)
    val updates = app.narrio.updates.AppUpdates(application, playback)
    /** Starts, checks, and settles TorBox preparations; see [TorBoxPreparations]. */
    /** The one answer to which recording [entry]'s played audio belongs to; the row's book alone never says. */
    suspend fun recordingFor(entry: ShelfEntry): app.narrio.domain.Audiobook? =
        playedRecording(entry, listeningRecordings[entry.bookId], preparations.current(entry.bookId))
    val preparations = TorBoxPreparations(RoomPreparationShelf(database),
        PreferencePreparationRecords(application.getSharedPreferences("torbox-preparation-records", Application.MODE_PRIVATE)), torbox::account)
    val preparationChecks = app.narrio.preparation.PreparationChecks(application, library.observeShelf(), preparations) { credentials.read() != null }
    /** A preparation notification's tap or Listen, waiting for Narrio's screen to act on it. */
    val preparationRequests = kotlinx.coroutines.flow.MutableStateFlow<app.narrio.preparation.PreparationRequest?>(null)
    init { preparations.announce = { change -> if (!playback.visible) app.narrio.preparation.PreparationNotifications.show(application, change) } }
}
