package app.narrio.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubSanitizerTest {
    private val files = unzip(ReaderFixtures.hostileEpub())
    private val chapter = ReaderDocuments.prepareChapter(files.getValue("OEBPS/chapter.xhtml"), "OEBPS/chapter.xhtml", xhtml = true, EditionKind.EPUB)
    private val served = chapter.bytes.toString(Charsets.UTF_8)

    @Test fun noPublicationScriptSurvives() {
        assertFalse(served.contains("<script", ignoreCase = true))
        assertFalse(served.contains("narrioHostile"))
        assertFalse(Regex("\\son[a-z]+\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(served))
        assertFalse(served.filter { it > ' ' }.contains("javascript:", ignoreCase = true))
        assertFalse(served.contains("<set ", ignoreCase = true))
    }

    @Test fun nothingRemoteRemainsInMarkupOrStyles() {
        assertFalse(served, served.contains(ReaderFixtures.HOSTILE_HOST))
        listOf("<iframe", "<embed", "<base", "http-equiv", "<object", "prefetch", "expression(").forEach {
            assertFalse("$it survived", served.contains(it, ignoreCase = true))
        }
        assertFalse(served.contains("autoplay"))
        assertTrue(served.contains("preload=\"none\""))
    }

    @Test fun readableContentLocalResourcesAndOutsideLinksRemain() {
        assertTrue(served.contains("Readable text stays readable while everything active is removed."))
        assertTrue("plugin fallback is shown instead of the plugin", served.contains("Plugin fallback text."))
        assertTrue(served.contains("href=\"style.css\""))
        assertTrue(served.contains("src=\"images/cover.png\""))
        assertTrue(served.contains("srcset=\"images/cover.png 2x\"") || served.contains("srcset=\" images/cover.png 2x\""))
        assertTrue(served.contains("href=\"https://example.org/outside\""))
        assertFalse("Readium adds its own html style attribute", served.contains("color:red"))
    }

    @Test fun servedChapterIsWellFormedXhtml() {
        assertWellFormedXml(chapter.bytes)
        assertTrue(served.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
    }

    @Test fun stylesheetsAndSvgFilesAreFilteredToo() {
        val css = ReaderDocuments.prepareCss(files.getValue("OEBPS/style.css")).toString(Charsets.UTF_8)
        assertFalse(css, css.contains(ReaderFixtures.HOSTILE_HOST))
        assertTrue("namespace declarations are not loads", css.contains("@namespace epub url(http://www.idpf.org/2007/ops)"))
        assertTrue(css.contains("url(images/cover.png)"))
        val svg = ReaderDocuments.prepareSvg(files.getValue("OEBPS/images/hostile.svg")).toString(Charsets.UTF_8)
        assertFalse(svg, svg.contains(ReaderFixtures.HOSTILE_HOST) || svg.contains("narrioHostile") || svg.contains("<script"))
        assertWellFormedXml(svg.toByteArray())
    }

    @Test fun cssEscapesAndSchemesCannotHideRemoteReferences() {
        listOf(
            "a{background:u\\72l(https://x.test/a.png)}", "a{background:url( \"//x.test/a.png\" )}", "a{background:url(\\68ttps://x.test)}",
            "@import 'https://x.test/a.css';", "@import url(ftp://x.test/a.css) screen;", "a{src:src(\"https://x.test/f\")}",
            "a{background:-webkit-image-set(url(https://x.test/a.png) 1x)}", "a{background:url(HTTPS://x.test/a.png)}",
        ).forEach { css -> assertFalse(css, EpubSanitizer.sanitizeCss(css).contains("x.test")) }
        assertEquals(".md\\:flex{display:flex}", EpubSanitizer.sanitizeCss(".md\\:flex{display:flex}"))
        assertEquals("p{content:\"\\201C\"}", EpubSanitizer.sanitizeCss("p{content:\"\\201C\"}"))
    }

    @Test fun localAndInlineReferencesAreNotRemote() {
        listOf("images/a.png", "../a.png", "#note", "data:image/png;base64,AAAA", "a%20b.png", "").forEach { assertFalse(it, EpubSanitizer.isRemote(it)) }
        listOf("https://a", "http://a", "//a", "\\\\a", "ftp://a", "file:///etc/hosts", "content://a", "blob:a").forEach { assertTrue(it, EpubSanitizer.isRemote(it)) }
    }

    @Test fun sanitizingTwiceChangesNothing() {
        val again = ReaderDocuments.prepareChapter(chapter.bytes, "OEBPS/chapter.xhtml", xhtml = true, EditionKind.EPUB).bytes.toString(Charsets.UTF_8)
        assertEquals(served, again)
    }
}
