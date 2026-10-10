package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** A release the listener pasted: a magnet link (or bare info hash), or an HTTPS link to a .torrent file. */
sealed interface ReleaseLink {
    data class Magnet(val hash: String, val name: String, val uri: String) : ReleaseLink
    data class TorrentUrl(val url: String) : ReleaseLink
}

object ReleaseLinks {
    private const val MAX_LENGTH = 8_192
    private val hex = Regex("[0-9a-fA-F]{40}")
    private val base32 = Regex("[A-Za-z2-7]{32}")

    const val INVALID = "Paste a magnet link (it starts with magnet:?) or an https link to a .torrent file."

    /** Validates a pasted link without any network request. Throws [ProviderException] with listener-facing wording. */
    fun parse(input: String): ReleaseLink {
        val text = input.trim()
        if (text.isEmpty()) throw ProviderException(INVALID)
        if (text.length > MAX_LENGTH || text.any { it.isWhitespace() || it.isISOControl() }) throw ProviderException("That doesn't look like a single link. $INVALID")
        if (text.matches(hex)) return ReleaseLink.Magnet(text.lowercase(), "", "magnet:?xt=urn:btih:${text.lowercase()}")
        if (text.startsWith("magnet:?", ignoreCase = true)) return magnet(text)
        if (text.startsWith("http://", ignoreCase = true)) throw ProviderException("Use an https link. Narrio doesn't open unencrypted links.")
        if (text.startsWith("https://", ignoreCase = true)) {
            val url = text.toHttpUrlOrNull() ?: throw ProviderException(INVALID)
            try { AddonManifest.secureUrl(url.toString()) } catch (error: IllegalArgumentException) { throw ProviderException(error.message ?: INVALID) }
            return ReleaseLink.TorrentUrl(url.toString())
        }
        throw ProviderException(INVALID)
    }

    private fun magnet(text: String): ReleaseLink.Magnet {
        val params = text.substring("magnet:?".length).split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=').lowercase()
            val value = runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrNull() ?: return@mapNotNull null
            key to value
        }
        val hash = params.filter { it.first == "xt" }.map { it.second }.firstNotNullOfOrNull { value ->
            val id = value.substringAfter("urn:btih:", "").takeIf { value.startsWith("urn:btih:", ignoreCase = true) } ?: return@firstNotNullOfOrNull null
            when {
                id.matches(hex) -> id.lowercase()
                id.matches(base32) -> base32ToHex(id)
                else -> null
            }
        } ?: throw ProviderException("This magnet link has no BitTorrent info hash. Copy the whole link and try again.")
        val name = params.firstOrNull { it.first == "dn" }?.second.orEmpty().trim().take(300)
        return ReleaseLink.Magnet(hash, name, text)
    }

    /** RFC 4648 base32, as older magnet links write the 20-byte info hash. */
    internal fun base32ToHex(value: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0L; var bits = 0
        val out = StringBuilder()
        for (char in value.uppercase()) {
            buffer = (buffer shl 5) or alphabet.indexOf(char).toLong()
            bits += 5
            if (bits >= 8) { bits -= 8; out.append("%02x".format(((buffer shr bits) and 0xff).toInt())) }
        }
        return out.toString()
    }

    /** The recording a link becomes, before TorBox reports its files or cache state. */
    internal fun recording(hash: String, name: String, magnet: String = "", torrentUrl: String = ""): Audiobook = Audiobook(
        "knaben:$hash", name.ifBlank { "Release ${hash.take(8)}" }, "Author not verified", "Narrator not verified", "Language not verified",
        description = "A release you added from a link. Narration, language, and files aren't verified; check them before listening.",
        torrentHash = hash, provider = "knaben", detailsLoaded = true, magnetUri = magnet, torrentUrl = torrentUrl,
        releaseTitle = name, sourceAddonName = LINK_PROVIDER)

    const val LINK_PROVIDER = "Your link"

    /** One hop of a .torrent link, first or redirected: a public HTTPS URL on the standard port, without credentials. */
    fun checkedHop(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: throw ProviderException(INVALID)
        if (!parsed.isHttps) throw ProviderException("The .torrent link redirected to an unencrypted page, which Narrio doesn't open.")
        try { AddonManifest.secureUrl(parsed.toString()) } catch (error: IllegalArgumentException) { throw ProviderException(error.message ?: INVALID) }
        return parsed.toString()
    }
}

/**
 * Resolves a pasted link through the listener's TorBox account: an existing download is reused, a cached release lists
 * its files, and anything else stays uncached until the listener explicitly prepares it. Nothing is added to the
 * account here.
 */
class LinkedReleases(
    http: OkHttpClient,
    private val torbox: TorBoxDelivery,
    private val library: TorBoxLibrary,
    private val torrentFiles: TorrentFileDiscovery,
) {
    /**
     * Redirects are followed by hand, so every hop is checked (HTTPS, no credentials, the standard port, no IP or local
     * host names), and host names must resolve only to public addresses.
     */
    private val client = http.newBuilder().callTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .dns(PublicDns).build()

    /** Verified .torrent bytes by info hash, so preparation uploads exactly what was inspected. */
    private val verified = object : LinkedHashMap<String, ByteArray>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 8
    }

    private fun remember(hash: String, bytes: ByteArray) = synchronized(verified) { verified[hash.lowercase()] = bytes }

    /**
     * The .torrent file to upload when preparing a release added from a link: the bytes inspected when it was added, else
     * the link fetched again through the same checks. Either way its info hash must still be the release's, so TorBox
     * never receives a different torrent from the one the listener saw. Null for releases not added from a .torrent link.
     */
    suspend fun torrentFor(recording: Audiobook): ByteArray? {
        if (recording.sourceAddonName != ReleaseLinks.LINK_PROVIDER || recording.torrentUrl.isBlank()) return null
        val hash = recording.torrentHash.lowercase()
        synchronized(verified) { verified[hash] }?.let { return it }
        val bytes = download(recording.torrentUrl)
        if (TorrentFiles.read(bytes)?.first != hash)
            throw ProviderException("The .torrent file at this link has changed since you added it. Paste the link again to check the new release.")
        remember(hash, bytes)
        return bytes
    }

    suspend fun resolve(link: ReleaseLink, connected: Boolean): Audiobook {
        if (!connected) throw ProviderException("Connect TorBox to use a link. Narrio plays linked releases through your TorBox account.")
        val (hash, recording, files) = when (link) {
            is ReleaseLink.Magnet -> Triple(link.hash, ReleaseLinks.recording(link.hash, link.name, magnet = link.uri), null)
            is ReleaseLink.TorrentUrl -> {
                val bytes = download(link.url)
                val (hash, files) = TorrentFiles.read(bytes) ?: throw ProviderException("This link didn't return a .torrent file. Check the link and try again.")
                remember(hash, bytes)
                Triple(hash, ReleaseLinks.recording(hash, files.name, torrentUrl = link.url), files)
            }
        }
        library.items().firstOrNull { it.kind == TorBoxKind.TORRENT && it.hash.equals(hash, true) }?.let { return library.recording(it) }
        val cachedFiles = withContext(Dispatchers.IO) {
            TorBoxDelivery.parseCached(torbox.request("torrents/checkcached", mapOf("hash" to hash, "format" to "object", "list_files" to "true"))["data"]) { true }[hash]
        }
        if (cachedFiles != null) {
            if (cachedFiles.objects("files").none { isBookAudioFile(it.text("name")) }) throw noAudio()
            return torbox.checkCached(listOf(recording)).first().copy(filesVerified = true)
        }
        if (files != null) {
            val sources = TorrentFileDiscovery.sources(hash, files)
            if (sources.isEmpty()) throw noAudio()
            return recording.copy(sources = sources, filesVerified = true, cacheState = "uncached")
        }
        // Public torrent metadata, when a mirror has it; otherwise TorBox lists the files once the listener prepares it.
        val inspected = try { torrentFiles.recording(recording) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        return (inspected ?: recording).copy(cacheState = "uncached")
    }

    private fun noAudio() = ProviderException("This release has no audio files Narrio can play (MP3, M4B, M4A, AAC, FLAC, OGG, Opus, or WAV).")

    private suspend fun download(link: String): ByteArray = try {
        var url = ReleaseLinks.checkedHop(link)
        var hops = 0
        while (true) {
            val (location, bytes) = client.readCancellable(Request.Builder().url(url).build()) { response ->
                if (response.isRedirect) return@readCancellable response.header("Location") to null
                if (!response.isSuccessful) throw ProviderException("The .torrent link didn't open (${response.code}). Check that it's still valid.")
                val body = response.body ?: throw ProviderException("The .torrent link returned nothing.")
                if (body.contentLength() > TorrentFiles.MAX_BYTES) throw ProviderException("This .torrent file is too large.")
                val source = body.source()
                if (source.request(TorrentFiles.MAX_BYTES.toLong() + 1)) throw ProviderException("This .torrent file is too large.")
                null to source.readByteArray()
            }
            if (bytes != null) return bytes
            if (++hops > MAX_REDIRECTS) throw ProviderException("The .torrent link redirects too many times.")
            url = ReleaseLinks.checkedHop(url.toHttpUrl().resolve(location.orEmpty())?.toString()
                ?: throw ProviderException("The .torrent link redirected somewhere Narrio can't open."))
        }
        @Suppress("UNREACHABLE_CODE") ByteArray(0)
    } catch (error: ProviderException) { throw error }
    catch (_: PrivateAddressException) { throw ProviderException("This link leads to a private or local network address, which Narrio doesn't open.") }
    catch (error: java.io.IOException) { throw ProviderException("The .torrent link couldn't be opened. Check your connection and the link, then try again.") }

    private companion object { const val MAX_REDIRECTS = 5 }
}

/** A host name that resolves to a loopback, private, link-local, or otherwise non-public address. */
class PrivateAddressException(host: String) : java.net.UnknownHostException("$host is not a public address")

/** Resolves only to public addresses, so a link can't reach the phone's own network through DNS. */
object PublicDns : okhttp3.Dns {
    override fun lookup(hostname: String): List<java.net.InetAddress> {
        val addresses = okhttp3.Dns.SYSTEM.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !public(it) }) throw PrivateAddressException(hostname)
        return addresses
    }

    fun public(address: java.net.InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        return when (bytes.size) {
            // Carrier-grade NAT (100.64/10), benchmarking (198.18/15), and reserved/broadcast (240/4) ranges.
            4 -> !(bytes[0].toInt() and 0xff == 100 && bytes[1].toInt() and 0xc0 == 64) && !(bytes[0].toInt() and 0xff == 198 && bytes[1].toInt() and 0xfe == 18) &&
                bytes[0].toInt() and 0xff < 240 && bytes[0].toInt() and 0xff != 0
            // Unique local addresses (fc00::/7).
            else -> bytes[0].toInt() and 0xfe != 0xfc
        }
    }
}
