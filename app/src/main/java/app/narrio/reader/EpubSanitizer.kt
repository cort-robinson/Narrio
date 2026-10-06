package app.narrio.reader

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.XmlDeclaration

/**
 * Makes publication content inert before the reader serves it. Publication scripts, event handlers, script URLs,
 * frames and plugins are removed, and every reference that would load something from the network (`http:`,
 * `https:`, protocol-relative, or any other remote scheme) is dropped from markup and CSS. Hyperlinks stay: they only
 * navigate on a tap, and the reader asks before opening anything outside the book. Readium injects its own scripts
 * after this filter runs, so they are unaffected.
 */
object EpubSanitizer {
    private val removedElements = setOf(
        "script", "iframe", "frame", "frameset", "portal", "base", "applet", "handler", "listener",
    )
    // Plugins show their fallback content instead, as a browser does when it can't load them.
    private val unwrappedElements = setOf("object", "embed")
    private val linkAttributes = setOf("href", "xlink:href")
    // Attributes that make the engine fetch something without a tap.
    private val loadingAttributes = setOf(
        "src", "srcset", "poster", "background", "data", "codebase", "lowsrc", "dynsrc", "longdesc", "usemap",
        "action", "formaction", "manifest", "icon", "profile", "imagesrcset",
    )
    private val scriptSchemes = Regex("^(?:javascript|vbscript|livescript|mocha|data:\\s*text/html|data:\\s*application/(?:x-)?(?:java|ecma)script)", RegexOption.IGNORE_CASE)

    /** Sanitizes an XHTML/HTML chapter or an SVG document in place. */
    fun sanitize(document: Document) {
        document.childNodes().filterIsInstance<XmlDeclaration>().forEach { it.remove() }
        // Processing instructions (xml-stylesheet) and DTD references can name remote resources.
        document.select("*").forEach { element -> element.childNodes().filterIsInstance<XmlDeclaration>().forEach(Node::remove) }
        document.documentType()?.let { doctype -> if (doctype.publicId().isNotBlank() || doctype.systemId().isNotBlank()) doctype.remove() }
        for (element in document.getAllElements().toList()) {
            if (element.parent() == null && element !is Document) continue
            val name = element.normalName().substringAfter(':')
            when {
                name in removedElements -> { element.remove(); continue }
                name in unwrappedElements -> { element.unwrap(); continue }
                name == "meta" && element.hasAttr("http-equiv") -> { element.remove(); continue }
                name == "link" -> if (!element.attr("rel").lowercase().split(Regex("\\s+")).contains("stylesheet") || isRemote(element.attr("href"))) { element.remove(); continue }
                // SVG animation can rewrite a link into a script URL after load.
                name in setOf("set", "animate", "animatemotion", "animatetransform") && (
                    element.attr("attributeName").substringAfter(':').lowercase() in setOf("href", "src") ||
                        listOf("to", "from", "values", "by").any { scriptSchemes.containsMatchIn(compact(element.attr(it))) }) -> { element.remove(); continue }
                name == "style" -> element.dataNodes().ifEmpty { null }?.forEach { it.wholeData = sanitizeCss(it.wholeData) }
                    ?: element.textNodes().forEach { it.text(sanitizeCss(it.wholeText)) }
            }
            sanitizeAttributes(element, name)
        }
    }

    private fun sanitizeAttributes(element: Element, name: String) {
        for (attribute in element.attributes().asList()) {
            val key = attribute.key.lowercase()
            val local = key.substringAfter(':')
            val value = attribute.value
            val remove = when {
                local.startsWith("on") -> true
                key == "ping" -> true
                scriptSchemes.containsMatchIn(compact(value)) -> true
                key == "style" -> { element.attr(attribute.key, sanitizeCss(value)); false }
                key == "srcset" || key == "imagesrcset" -> {
                    val kept = value.split(',').filter { candidate -> !isRemote(candidate.trim().substringBefore(' ')) }
                    if (kept.isEmpty()) true else { element.attr(attribute.key, kept.joinToString(",")); false }
                }
                key in loadingAttributes || local in loadingAttributes -> isRemote(value)
                // Hyperlinks navigate only on a tap; other href/xlink:href uses (images, use, feImage, link) load.
                key in linkAttributes || local == "href" -> name != "a" && name != "area" && isRemote(value)
                else -> false
            }
            if (remove) element.removeAttr(attribute.key)
        }
        // Media never starts by itself while a book is open, and never prefetches.
        if (name == "audio" || name == "video") { element.removeAttr("autoplay"); element.attr("preload", "none") }
    }

    /** Control characters and whitespace are ignored by URL parsers, so "java\tscript:" is still a script URL. */
    private fun compact(value: String) = value.filter { it > ' ' }

    /** True for anything that isn't a reference into the publication itself or inline `data:` content. */
    fun isRemote(reference: String): Boolean {
        val value = compact(decodeCssEscapes(reference)).trim('"', '\'')
        if (value.isEmpty() || value.startsWith("#")) return false
        if (value.startsWith("//") || value.startsWith("\\\\") || value.startsWith("/\\") || value.startsWith("\\/")) return true
        val scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):").find(value)?.groupValues?.get(1)?.lowercase() ?: return false
        return scheme != "data"
    }

    private val comments = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    private val imports = Regex("@import\\s+(?:url\\(\\s*(?:\"[^\"]*\"|'[^']*'|[^)]*)\\s*\\)|\"[^\"]*\"|'[^']*')[^;]*;?", RegexOption.IGNORE_CASE)
    private val urls = Regex("(?:url|src)\\(\\s*(\"[^\"]*\"|'[^']*'|[^)]*?)\\s*\\)", RegexOption.IGNORE_CASE)
    private val imageSets = Regex("(?:-webkit-)?image-set\\(([^()]*(?:\\([^()]*\\)[^()]*)*)\\)", RegexOption.IGNORE_CASE)
    private val strings = Regex("\"[^\"]*\"|'[^']*'")
    private val dangerous = Regex("(?:expression\\s*\\(|-moz-binding|behavior\\s*:)", RegexOption.IGNORE_CASE)

    /**
     * Removes remote `@import` rules, remote `url()`/`src()`/`image-set()` references, and legacy script hooks.
     * Escaped letters are decoded first, so `\75rl(` can't hide a function name; every reference is checked fully
     * decoded. Other escapes keep their meaning (`.md\:flex` stays a class name).
     */
    fun sanitizeCss(css: String): String {
        var value = decodeCssEscapes(css, lettersOnly = true).replace(comments, "")
        // @namespace names a namespace; it never loads anything.
        val namespaces = mutableListOf<String>()
        value = namespaceRules.replace(value) { namespaces += it.value; "\u0000ns${namespaces.size - 1}\u0000" }
        value = imports.replace(value) { match ->
            val target = match.value.substringAfter("@import").trim().removePrefix("url(").removePrefix("URL(")
            if (isRemote(target.trim().substringBefore(')').substringBefore(' ').trim())) "" else match.value
        }
        value = imageSets.replace(value) { match ->
            if (strings.findAll(match.groupValues[1]).any { isRemote(it.value) } || urls.findAll(match.groupValues[1]).any { isRemote(it.groupValues[1]) }) "none" else match.value
        }
        value = urls.replace(value) { match -> if (isRemote(match.groupValues[1])) "none" else match.value }
        value = dangerous.replace(value) { match ->
            when {
                match.value.startsWith("expression", ignoreCase = true) -> "invalid("
                match.value.startsWith("behavior", ignoreCase = true) -> "-narrio-removed:"
                else -> "-narrio-removed"
            }
        }
        return namespaceMarker.replace(value) { namespaces[it.groupValues[1].toInt()] }
    }

    private val namespaceRules = Regex("@namespace[^;{}]*;", RegexOption.IGNORE_CASE)
    private val namespaceMarker = Regex("\u0000ns(\\d+)\u0000")

    private val cssEscape = Regex("\\\\(?:([0-9a-fA-F]{1,6})[ \\t\\n\\r\\u000C]?|([^\\n\\r\\u000C0-9a-fA-F]))")

    private fun decodeCssEscapes(value: String, lettersOnly: Boolean = false): String = cssEscape.replace(value) { match ->
        val hex = match.groupValues[1]
        val decoded = if (hex.isNotEmpty()) hex.toInt(16).let { if (it == 0 || it > 0x10FFFF || it in 0xD800..0xDFFF) 0xFFFD else it } else match.groupValues[2].codePointAt(0)
        when {
            lettersOnly && decoded !in 'a'.code..'z'.code && decoded !in 'A'.code..'Z'.code -> match.value
            decoded == '"'.code || decoded == '\''.code || decoded == '\\'.code || decoded == '\n'.code || decoded == '\r'.code || decoded == 0x0C -> match.value
            else -> String(Character.toChars(decoded))
        }
    }
}
