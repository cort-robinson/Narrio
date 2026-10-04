package app.narrio

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.narrio.data.*
import app.narrio.playback.PlaybackHub
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class NarrioApplication : Application() {
    lateinit var graph: AppGraph
        private set
    override fun onCreate() { super.onCreate(); graph = AppGraph(this) }
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
    }, LibraryMigration3To4).build()
    val library = database.library()
    val credentials = CredentialStore(application)
    val catalog = ArchiveDiscovery(http)
    val indexedCatalog = KnabenDiscovery(http)
    val metadata = BookMetadata(http)
    val books = BookCatalog(metadata)
    val torbox = TorBoxDelivery(http, credentials::read)
    val torrentFiles = TorrentFileDiscovery(http)
    val bookSources = BookSourceDiscovery(catalog, indexedCatalog, torbox::library, torbox::checkCached, torrentFiles::recording)
    val textDiscovery = GutenbergTextDiscovery(http)
    val followAlong = FollowAlongStore(application, library, http, torbox)
    val preferences = application.getSharedPreferences("preferences", Application.MODE_PRIVATE)
    val playback = PlaybackHub()
    val offline = OfflineStore(application, http, torbox)
    val textFinder = BookTextFinder(textDiscovery, indexedCatalog, torbox)
    val speechModels = SpeechModelStore(application, http)
    val narrationSync = app.narrio.playback.NarrationSync(application, http, offline, torbox, speechModels)
    val updates = app.narrio.updates.AppUpdates(application, playback)
}
