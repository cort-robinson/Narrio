package app.narrio.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable
data class InstalledAddon(val manifest: JsonObject, val manifestUrl: String, val enabled: Boolean = true) {
    val id get() = manifest.text("id")
    val name get() = manifest.text("name")
    val contentType get() = manifest.text("contentType")
    val catalog get() = "catalog" in (manifest["provides"] as? JsonArray).orEmpty().map { it.stringValue() }
    val source get() = "source" in (manifest["provides"] as? JsonArray).orEmpty().map { it.stringValue() }
    val ebookSearch get() = "ebook-search" in (manifest["provides"] as? JsonArray).orEmpty().map { it.stringValue() }
    val purpose get() = if (catalog) "Book metadata" else if (contentType == "ebook") "Ebook sources" else "Audiobook sources"
}

/** Declarative JSON only: manifests cannot execute code or access delivery credentials. */
object AddonManifest {
    fun parse(json: String, url: String): InstalledAddon {
        require(json.length <= 256_000) { "The add-on definition is too large." }
        val root = NarrioJson.parseToJsonElement(json) as? JsonObject ?: error("Expected an add-on JSON object.")
        require(root.text("schemaVersion") == "1.0.0") { "This add-on schema version is not supported." }
        require(root.text("id").matches(Regex("[a-zA-Z0-9_-]{1,80}")) && root.text("name").isNotBlank()) { "The add-on needs a valid ID and name." }
        require(root.text("contentType") in setOf("audiobook", "ebook")) { "Only audiobook and ebook add-ons are supported." }
        val provides = (root["provides"] as? JsonArray).orEmpty().map { it.stringValue() }
        require(provides.isNotEmpty() && provides.all { it in setOf("source", "catalog", "ebook-search") }) { "This add-on capability is not supported." }
        require("ebook-search" !in provides || root.text("contentType") == "ebook") { "Browser ebook search is only available for ebook add-ons." }
        val addon = InstalledAddon(root, url)
        val adapters = root["adapters"] as? JsonObject ?: error("The add-on has no adapters.")
        provides.forEach { capability ->
            val adapter = adapters[capability] as? JsonObject ?: error("The add-on is missing its $capability adapter.")
            validateAdapter(if (capability == "catalog") adapter["search"] as? JsonObject ?: error("A catalog search adapter is required.") else adapter, capability)
            if (capability == "catalog") {
                ((adapter["discover"] as? JsonObject)?.get("sections") as? JsonArray).orEmpty().forEach { validateAdapter(it.jsonObject, capability) }
            }
        }
        return addon
    }

    private fun validateAdapter(adapter: JsonObject, capability: String) {
        val request = adapter["request"] as? JsonObject ?: error("An add-on request is required.")
        secureUrl(request.text("url"))
        require(request.text("method").ifBlank { "GET" } in setOf("GET", "POST")) { "Only GET and POST requests are supported." }
        val headers = request["headers"] as? JsonObject
        require(headers.orEmpty().keys.all { it.lowercase() in setOf("accept", "content-type") }) { "Only public JSON request headers are supported." }
        if (capability == "ebook-search") {
            require(request.text("method").ifBlank { "GET" } == "GET" && headers.isNullOrEmpty() && request["body"] == null) {
                "Browser search links must use GET without headers or a body."
            }
            require(request.text("url").contains("{TITLE}") || request.text("url").contains("{QUERY}")) { "Browser search must include the book title." }
            return
        }
        val response = adapter["response"] as? JsonObject ?: error("An add-on response mapping is required.")
        require(response.text("type") == "json") { "Only JSON responses are supported." }
        val mapping = response["mapping"] as? JsonObject ?: error("An add-on field mapping is required.")
        require(mapping.text("title").isNotBlank()) { "The add-on must map a title field." }
        require(if (capability == "catalog") mapping.text("authors").isNotBlank() else mapping.text("infoHash").isNotBlank() || mapping.text("magnetUrl").isNotBlank()) {
            "Catalogs must map authors; torrent sources must map an info hash or magnet URL."
        }
        require(mapping.values.all { it is JsonPrimitive && it.isString }) { "Field mappings must be JSON paths." }
    }

    fun secureUrl(value: String) {
        val url = value.toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.port == 443 && url.fragment == null) { "Use a public HTTPS URL without credentials." }
        val host = url.host.lowercase()
        require('.' in host && !host.endsWith(".localhost") && !host.endsWith(".local") && !host.endsWith(".internal") &&
            !host.matches(Regex("[0-9.]+")) && ':' !in host) { "Local and IP-address URLs are not supported." }
    }

    /** Supports nested fields, indexed arrays, and projected arrays used by the supplied manifests. */
    fun values(root: JsonElement, path: String): List<JsonElement> {
        if (path.isBlank()) return listOf(root)
        var current = listOf(root)
        for (segment in path.split('.')) {
            val field = segment.substringBefore('[')
            val index = segment.substringAfter('[', "").substringBefore(']')
            current = current.flatMap { node ->
                val value = (node as? JsonObject)?.get(field) ?: return@flatMap emptyList()
                when {
                    '[' !in segment -> listOf(value)
                    index.isEmpty() -> (value as? JsonArray).orEmpty()
                    else -> listOfNotNull((value as? JsonArray)?.getOrNull(index.toIntOrNull() ?: -1))
                }
            }
        }
        return current
    }
}
