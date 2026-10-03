package app.narrio.updates

import app.narrio.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class UpdateRepository(private val http: OkHttpClient, private val policy: UpdatePolicy,
                       private val api: String = UpdatePolicy.API,
                       private val metadata: (String) -> String = { it }) {
    private fun json(url: String, limit: Int = 1024 * 1024): JsonElement {
        http.newCall(Request.Builder().url(metadata(url)).header("Accept", "application/vnd.github+json").build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException(if (response.code == 403 || response.code == 429) "Update service is busy. Try again later." else "Could not check for updates. Try again.")
            val body = response.body ?: throw IOException("Empty update response")
            if (body.contentLength() > limit) throw IOException("Update response is too large")
            val bytes = body.byteStream().use { input -> java.io.ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count == -1) break; require(output.size() + count <= limit) { "Update response is too large" }; output.write(buffer, 0, count) }
                output.toByteArray()
            } }
            return NarrioJson.parseToJsonElement(bytes.toString(Charsets.UTF_8))
        }
    }

    suspend fun latest(): AppUpdate? = withContext(Dispatchers.IO) {
        val releases = if (policy.channel == UpdateChannel.STABLE) listOf(json("$api/releases/latest").jsonObject)
        else json("$api/releases?per_page=30").jsonArray.mapNotNull { it as? JsonObject }
        val candidates = releases.filter(policy::acceptsRelease)
            .sortedByDescending { if (policy.channel == UpdateChannel.DEV) it.text("tag_name").removePrefix("dev-").toLongOrNull() ?: 0 else 0 }
            .filter { policy.channel != UpdateChannel.DEV || (it.text("tag_name").removePrefix("dev-").toLongOrNull() ?: 0) > policy.installedCode }
            .take(4)
        for (release in candidates) {
            coroutineContext.ensureActive()
            val tag = release.text("tag_name")
            val manifestUrl = "${UpdatePolicy.REPOSITORY}/releases/download/$tag/release-manifest.json"
            if (release.objects("assets").count { it.text("name") == "release-manifest.json" && it.text("browser_download_url") == manifestUrl } != 1) continue
            val manifest = json(manifestUrl, 64 * 1024).jsonObject
            if (manifest.updateNumber("versionCode") <= policy.installedCode) continue
            val update = policy.candidate(release, manifest)
            if (policy.channel == UpdateChannel.STABLE || policy.passedPreview(update, json("$api/actions/runs/${update.runUrl.substringAfterLast('/')}").jsonObject)) return@withContext update
        }
        null
    }

    suspend fun download(update: AppUpdate, directory: File, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        require(update.bytes in 1..UpdatePolicy.MAX_APK_BYTES && Regex("Narrio-\\d+\\.\\d+\\.\\d+(?:-dev\\.\\d+)?\\.apk").matches(update.apk))
        directory.mkdirs()
        val target = File(directory, update.apk)
        if (target.exists() && verifiesBytes(target, update)) return@withContext target
        if (target.exists()) require(target.delete()) { "Could not replace an incomplete update download" }
        val partial = File(directory, "${update.apk}.part")
        try {
            http.newCall(Request.Builder().url(update.downloadUrl).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Could not download the update. Try again.")
                val body = response.body ?: throw IOException("Empty update download")
                require(body.contentLength() == -1L || body.contentLength() == update.bytes) { "Update download size differs" }
                body.byteStream().use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        require(total <= update.bytes) { "Update download exceeds its declared size" }
                        output.write(buffer, 0, count)
                        progress(total.toFloat() / update.bytes)
                    }
                } }
            }
            require(verifiesBytes(partial, update)) { "Update verification failed. Download it again." }
            require(partial.renameTo(target)) { "Could not save the update" }
            target
        } finally { partial.delete() }
    }

    companion object {
        fun verifiesBytes(file: File, update: AppUpdate): Boolean {
            if (!file.isFile || file.length() != update.bytes) return false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val count = input.read(buffer); if (count == -1) break; digest.update(buffer, 0, count) } }
            return digest.digest().joinToString("") { "%02x".format(it) } == update.sha256
        }
    }
}
