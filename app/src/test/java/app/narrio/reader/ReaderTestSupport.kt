package app.narrio.reader

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Reads an EPUB fixture's entries, as the reader's container would. */
fun unzip(bytes: ByteArray): Map<String, ByteArray> {
    val files = linkedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            files[entry.name] = zip.readBytes()
        }
    }
    return files
}

/** Fails unless [bytes] is well-formed, namespace-valid XML, as the WebView requires for XHTML. */
fun assertWellFormedXml(bytes: ByteArray) {
    val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }
    val builder = factory.newDocumentBuilder()
    builder.setErrorHandler(null)
    builder.parse(ByteArrayInputStream(bytes))
}
