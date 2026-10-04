package app.narrio.reader

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** EPUB fixtures for reader tests. Built in code so every hostile construct is visible in review. */
object ReaderFixtures {
    const val HOSTILE_HOST = "evil.example"

    /** Every way a publication could run script or reach the network. None of it may survive the reader's filter. */
    fun hostileEpub(): ByteArray = epub(
        title = "Hostile fixture",
        chapters = listOf(
            "chapter.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
<?xml-stylesheet type="text/css" href="https://$HOSTILE_HOST/pi.css"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "https://$HOSTILE_HOST/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xmlns:xlink="http://www.w3.org/1999/xlink" style="color:red">
<head><title>Hostile</title>
<base href="https://$HOSTILE_HOST/"/>
<meta http-equiv="refresh" content="0;url=https://$HOSTILE_HOST/refresh"/>
<link rel="stylesheet" type="text/css" href="https://$HOSTILE_HOST/remote.css"/>
<link rel="prefetch" href="https://$HOSTILE_HOST/prefetch"/>
<link rel="stylesheet" type="text/css" href="style.css"/>
<script type="text/javascript">window.narrioHostile = "inline script"; document.title = "pwned";</script>
<script type="text/javascript" src="hostile.js"></script>
<script src="https://$HOSTILE_HOST/remote.js"></script>
<style type="text/css">@import url("https://$HOSTILE_HOST/import.css");
body { background: url(https://$HOSTILE_HOST/body.png); }
p.escaped { background-image: u\72l(https://$HOSTILE_HOST/escaped.png); }
.set { background-image: image-set("https://$HOSTILE_HOST/set.png" 1x); }
.legacy { width: expression(alert(1)); }</style>
</head>
<body onload="window.narrioHostile = 'onload'">
<h1 onclick="window.narrioHostile = 'onclick'">A hostile chapter</h1>
<p class="escaped" onmouseover="window.narrioHostile = 'mouseover'">Readable text stays readable while everything active is removed.</p>
<p><a href="javascript:window.narrioHostile='javascript url'">A script link</a> and <a href="JaVa&#x09;ScRiPt:window.narrioHostile='obfuscated'">an obfuscated one</a>.</p>
<p><img src="https://$HOSTILE_HOST/beacon.png" alt="remote beacon"/><img src="//$HOSTILE_HOST/relative.png" alt="protocol relative"/><img srcset="https://$HOSTILE_HOST/a.png 1x, images/cover.png 2x" src="images/cover.png" alt="local"/><img src="missing.png" onerror="window.narrioHostile = 'onerror'" alt="error"/></p>
<div style="background: url('https://$HOSTILE_HOST/inline.png')"><p>Inline styles lose their remote images.</p></div>
<iframe src="https://$HOSTILE_HOST/frame"></iframe>
<object data="https://$HOSTILE_HOST/plugin.swf"><p>Plugin fallback text.</p></object>
<embed src="https://$HOSTILE_HOST/embed"/>
<video src="https://$HOSTILE_HOST/video.mp4" autoplay="autoplay" poster="https://$HOSTILE_HOST/poster.png"></video>
<audio controls="controls"><source src="https://$HOSTILE_HOST/audio.mp3"/></audio>
<form action="https://$HOSTILE_HOST/post"><button formaction="javascript:window.narrioHostile='form'">Send</button></form>
<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><script>window.narrioHostile = 'svg script'</script><image xlink:href="https://$HOSTILE_HOST/svg.png" width="10" height="10"/><a xlink:href="javascript:window.narrioHostile='svg link'"><text x="0" y="10">x</text></a><a href="#x"><set attributeName="href" to="javascript:window.narrioHostile='svg set'"/></a></svg>
<p><a href="https://example.org/outside" ping="https://$HOSTILE_HOST/ping">An outside link</a> stays a link.</p>
</body></html>""",
        ),
        extra = mapOf(
            "hostile.js" to "window.narrioHostile = 'local script file';".toByteArray(),
            "style.css" to """@import "https://$HOSTILE_HOST/sheet-import.css";
@namespace epub url(http://www.idpf.org/2007/ops);
@font-face { font-family: Remote; src: url(https://$HOSTILE_HOST/font.woff) format("woff"); }
.local { background: url(images/cover.png); }
p { margin: 0 0 1em; }""".toByteArray(),
            "images/cover.png" to png(32, 48),
            "images/hostile.svg" to """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" onload="window.narrioHostile='svg file'"><script>window.narrioHostile='svg file script'</script><image xlink:href="https://$HOSTILE_HOST/in-svg.png"/></svg>""".toByteArray(),
        ),
        manifestExtra = """<item id="js" href="hostile.js" media-type="application/javascript"/><item id="css" href="style.css" media-type="text/css"/><item id="cover" href="images/cover.png" media-type="image/png"/><item id="svg" href="images/hostile.svg" media-type="image/svg+xml"/>""",
    )

    val sampleParagraphs = listOf(
        "When Mary Lennox was sent to Misselthwaite Manor to live with her uncle everybody said she was the most disagreeable-looking child ever seen. It was true, too.",
        "She had a little thin face and a little thin body, thin light hair and a sour expression. Her hair was yellow, and her face was yellow because she had been born in India and had always been ill in one way or another.",
        "Her father had held a position under the English Government and had always been busy and ill himself, and her mother had been a great beauty who cared only to go to parties and amuse herself with gay people.",
        "She had not wanted a little girl at all, and when Mary was born she handed her over to the care of an Ayah, who was made to understand that if she wished to please the Mem Sahib she must keep the child out of sight as much as possible.",
        "So when she was a sickly, fretful, ugly little baby she was kept out of the way, and when she became a sickly, fretful, toddling thing she was kept out of the way also.",
        "She never remembered seeing familiarly anything but the dark faces of her Ayah and the other native servants, and as they always obeyed her and gave her her own way in everything, because the Mem Sahib would be angry if she was disturbed by her crying, by the time she was six years old she was as tyrannical and selfish a little pig as ever lived.",
    )

    /** Chapter titles of [sampleEpub], in reading order. */
    val sampleChapters = listOf("There Is No One Left", "Mistress Mary Quite Contrary", "Across the Moor", "Martha", "The Cry in the Corridor", "The Key to the Garden")

    /**
     * An ordinary reflowable book: six chapters with a table of contents, a page list whose markers sit inside
     * paragraphs, self-closing anchors, an EPUB 3 footnote, a Gutenberg-style footnote, and an image.
     */
    fun sampleEpub(paragraphsPerChapter: Int = 18): ByteArray {
        var page = 1
        val pages = mutableListOf<Pair<String, String>>()
        val chapters = sampleChapters.mapIndexed { index, title ->
            val href = "text/chapter${index + 1}.xhtml"
            val body = StringBuilder()
            body.append("<section epub:type=\"chapter\"><h2 id=\"c${index + 1}\">${roman(index + 1)} · $title</h2>\n")
            if (index == 0) body.append("<figure><img src=\"../images/cover.png\" alt=\"A walled garden\"/><figcaption>The garden door</figcaption></figure>\n")
            repeat(paragraphsPerChapter) { paragraph ->
                val text = sampleParagraphs[(paragraph + index) % sampleParagraphs.size]
                val marker = if (paragraph % 6 == 2) {
                    val id = "page${page}"; pages += "$href#$id" to "${page++}"
                    "<span epub:type=\"pagebreak\" id=\"$id\" title=\"${page - 1}\"/>"
                } else ""
                val words = text.split(' ')
                val middle = words.size / 2
                body.append("<p>${words.take(middle).joinToString(" ")} $marker${words.drop(middle).joinToString(" ")}")
                if (index == 1 && paragraph == 1) body.append("<a epub:type=\"noteref\" href=\"#note1\">1</a>")
                if (index == 2 && paragraph == 1) body.append("<a id=\"FNanchor_1_1\" href=\"#Footnote_1_1\" class=\"fnanchor\">[1]</a>")
                body.append("</p>\n")
            }
            if (index == 1) body.append("<aside epub:type=\"footnote\" id=\"note1\"><p>An Ayah is a nursemaid or nanny.</p></aside>\n")
            if (index == 2) body.append("<div class=\"footnote\"><p><a id=\"Footnote_1_1\" href=\"#FNanchor_1_1\"><span class=\"label\">[1]</span></a> The moor is open, uncultivated upland covered with heather.</p></div>\n")
            body.append("</section>")
            href to page(title, body.toString())
        }
        val nav = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body>
<nav epub:type="toc" id="toc"><h1>Contents</h1><ol>${chapters.mapIndexed { index, (href, _) -> "<li><a href=\"$href#c${index + 1}\">${roman(index + 1)} · ${sampleChapters[index]}</a></li>" }.joinToString("")}</ol></nav>
<nav epub:type="page-list" id="pages"><ol>${pages.joinToString("") { (href, label) -> "<li><a href=\"$href\">$label</a></li>" }}</ol></nav>
</body></html>"""
        return epub("The Secret Garden", chapters, extra = mapOf("images/cover.png" to png(120, 160), "nav.xhtml" to nav.toByteArray()),
            manifestExtra = """<item id="cover" href="images/cover.png" media-type="image/png"/>""", nav = true, author = "Frances Hodgson Burnett")
    }

    private fun page(title: String, body: String) = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en" lang="en"><head><title>$title</title>
<style type="text/css">p { margin: 0; text-indent: 1.4em; } h2 { font-weight: normal; }</style></head>
<body>
$body
</body></html>"""

    private fun roman(value: Int) = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")[value - 1]

    fun epub(title: String, chapters: List<Pair<String, String>>, extra: Map<String, ByteArray> = emptyMap(), manifestExtra: String = "",
             nav: Boolean = false, author: String = "Narrio tests"): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mimetype.size.toLong(); compressedSize = size; crc = CRC32().apply { update(mimetype) }.value
            })
            zip.write(mimetype); zip.closeEntry()
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray())
            val items = chapters.mapIndexed { index, (href, _) -> """<item id="ch$index" href="$href" media-type="application/xhtml+xml"/>""" }.joinToString("")
            val navItem = if (nav) """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""" else ""
            put("OEBPS/content.opf", """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:narrio:fixture:${title.hashCode()}</dc:identifier><dc:title>$title</dc:title><dc:creator>$author</dc:creator><dc:language>en</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata>
<manifest>$navItem$items$manifestExtra</manifest><spine>${chapters.indices.joinToString("") { """<itemref idref="ch$it"/>""" }}</spine></package>""".toByteArray())
            chapters.forEach { (href, content) -> put("OEBPS/$href", content.toByteArray()) }
            extra.forEach { (name, bytes) -> put("OEBPS/$name", bytes) }
        }
        return output.toByteArray()
    }

    /** A small opaque PNG with a soft vertical gradient, encoded without platform image APIs. */
    fun png(width: Int, height: Int): ByteArray {
        val raw = ByteArrayOutputStream()
        for (y in 0 until height) {
            raw.write(0)
            for (x in 0 until width) {
                raw.write(70 + 60 * y / height); raw.write(100 + 50 * y / height); raw.write(80 + 20 * x / width)
            }
        }
        val compressed = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out, Deflater(9)).use { it.write(raw.toByteArray()) } }.toByteArray()
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            fun int(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
            output.write(int(data.size))
            val typed = type.toByteArray() + data
            output.write(typed)
            output.write(int(CRC32().apply { update(typed) }.value.toInt()))
        }
        val header = ByteArrayOutputStream().apply {
            write(byteArrayOf((width ushr 24).toByte(), (width ushr 16).toByte(), (width ushr 8).toByte(), width.toByte()))
            write(byteArrayOf((height ushr 24).toByte(), (height ushr 16).toByte(), (height ushr 8).toByte(), height.toByte()))
            write(byteArrayOf(8, 2, 0, 0, 0))
        }.toByteArray()
        chunk("IHDR", header); chunk("IDAT", compressed); chunk("IEND", ByteArray(0))
        return output.toByteArray()
    }
}
