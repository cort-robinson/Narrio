package app.narrio.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** An offline speech-recognition model pinned by size and digest; narration never leaves the device. */
data class SpeechModel(val name: String, val url: String, val sha256: String, val bytes: Long, val languages: Set<String>)

class SpeechModelStore(context: Context, http: OkHttpClient) {
    private val root = File(context.filesDir, "speech-models")
    private val client = http.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val installs = Mutex()

    /** Book text or recording language; unlisted values are treated as English, the catalog default. */
    fun modelFor(language: String): SpeechModel? {
        val value = language.trim().lowercase()
        val listed = value.isNotBlank() && !value.startsWith("language not")
        return MODELS.firstOrNull { model -> !listed || model.languages.any { value == it || value.startsWith("$it-") || value.startsWith("$it ") } }
    }

    fun installed(model: SpeechModel): File? = File(root, model.name).takeIf { File(it, COMPLETE).isFile }

    suspend fun install(model: SpeechModel, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) { installs.withLock {
        installed(model)?.let { return@withLock it }
        root.mkdirs()
        val archive = File(root, "${model.name}.download")
        val staging = File(root, "${model.name}.staging")
        try {
            client.newCall(Request.Builder().url(model.url).build()).execute().use { response ->
                if (!response.isSuccessful) throw ProviderException("The narration sync model is unavailable (${response.code}). Try again later.")
                val body = response.body ?: throw ProviderException("The narration sync model download was empty.")
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                body.byteStream().use { input -> archive.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > model.bytes) throw ProviderException("The narration sync model download was larger than expected.")
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                        progress(received.toFloat() / model.bytes)
                    }
                } }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (received != model.bytes || hash != model.sha256) throw ProviderException("The narration sync model failed its integrity check. Try again.")
            }
            staging.deleteRecursively()
            unzip(archive, staging, model.name)
            val target = File(root, model.name)
            target.deleteRecursively()
            if (!staging.renameTo(target)) throw ProviderException("The narration sync model couldn't be saved.")
            File(target, COMPLETE).writeText(model.sha256)
            target
        } finally {
            archive.delete(); staging.deleteRecursively()
        }
    } }

    /** Extracts only the model folder's regular files, rejecting traversal and oversized archives. */
    private suspend fun unzip(archive: File, target: File, prefix: String) {
        var total = 0L
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = zip.nextEntry ?: break
                val name = entry.name.removePrefix("$prefix/")
                if (entry.isDirectory || name.isBlank() || name == entry.name) continue
                if (name.startsWith('/') || name.contains('\\') || name.split('/').any { it == ".." || it.isBlank() }) throw ProviderException("The narration sync model archive is invalid.")
                val file = File(target, name).also { it.parentFile?.mkdirs() }
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_EXPANDED_BYTES) throw ProviderException("The narration sync model archive is invalid.")
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
        if (!File(target, "am/final.mdl").isFile) throw ProviderException("The narration sync model archive is incomplete.")
    }

    companion object {
        private const val COMPLETE = ".complete"
        private const val MAX_EXPANDED_BYTES = 200L * 1024 * 1024

        // Apache-2.0 Vosk model. Pinned so a changed upstream file is rejected rather than executed.
        val MODELS = listOf(
            SpeechModel("vosk-model-small-en-us-0.15", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
                "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498", 41_205_931, setOf("en", "eng", "english")),
        )
    }
}
