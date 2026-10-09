package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSourceProviderSettingsTest {
    private fun addon(id: String, enabled: Boolean = true, type: String = "audiobook", capability: String = "source") = InstalledAddon(
        buildJsonObject { put("id", id); put("name", id); put("contentType", type); put("provides", buildJsonArray { add(capability) }) }, "https://example.com/$id", enabled)

    @Test fun migrationPreservesExistingAddonEnabledStatesAndOnlyListsAudioSources() = runTest {
        val addons = AddonManager(OkHttpClient(), listOf(addon("on"), addon("off", false), addon("ebook", type = "ebook"), addon("info", capability = "catalog")))
        val settings = DeviceSourceProviderSettings(addons, backgroundScope); runCurrent()
        assertEquals(listOf("archive", "torbox-library", "addon:on", "addon:off"), settings.providers.value.map { it.id })
        assertFalse(settings.providers.value.last().enabled)
        assertTrue(settings.providers.value.take(2).all { !it.removable && it.kind == SourceProviderKind.BUILT_IN })
        settings.setEnabled("addon:off", true); assertTrue(addons.installed.value.first { it.id == "off" }.enabled)
        addons.enable("on", false); runCurrent(); assertFalse(settings.providers.value.first { it.id == "addon:on" }.enabled)
    }

    @Test fun annasArchiveIsSearchedAsAnEbookSourceWithoutTorBoxWhileOtherWebsitesStayLinks() = runTest {
        val addons = AddonManager(OkHttpClient(), listOf(addon("knaben", type = "ebook"), addon(AnnasArchive.ADDON_ID, type = "ebook", capability = "ebook-search"),
            addon("other-website", type = "ebook", capability = "ebook-search")))
        val settings = DeviceSourceProviderSettings(addons, backgroundScope, catalog = SourceCatalog.EBOOK); runCurrent()
        assertEquals(listOf("recording-files", "torbox-ebooks", "addon:knaben", "addon:${AnnasArchive.ADDON_ID}", "gutenberg"), settings.providers.value.map { it.id })
        assertFalse(settings.providers.value.first { it.id == "addon:${AnnasArchive.ADDON_ID}" }.requiresTorBox)
        assertTrue(settings.providers.value.first { it.id == "addon:knaben" }.requiresTorBox)
    }

    @Test fun builtinSwitchAndPrioritySurviveRestoreAndAddonRemovalStaysRemoved() = runTest {
        var saved = SourceProviderPreferences()
        var installed = listOf(addon("audio", false))
        val addons = AddonManager(OkHttpClient(), installed, persist = { installed = it })
        val settings = DeviceSourceProviderSettings(addons, backgroundScope, saved) { saved = it }; runCurrent()
        settings.setEnabled("archive", false); settings.move("addon:audio", 0)
        settings.move("torbox-library", 500)
        val restored = DeviceSourceProviderSettings(AddonManager(OkHttpClient(), installed), backgroundScope, saved); runCurrent()
        assertEquals(settings.providers.value, restored.providers.value)
        assertFalse(restored.providers.value.first { it.id == "archive" }.enabled)
        addons.remove("audio"); runCurrent()
        assertEquals(2, settings.providers.value.size)
        assertTrue(settings.providers.value.none { it.id == "addon:audio" })
        assertEquals(listOf(0, 1), settings.providers.value.map { it.order })
    }

    @Test fun lookupStatusDoesNotAlterSelectionAndFailedPersistenceDoesNotPublish() = runTest {
        val addons = AddonManager(OkHttpClient(), emptyList())
        val settings = DeviceSourceProviderSettings(addons, backgroundScope, persist = { error("disk full") }); runCurrent()
        val before = settings.providers.value
        settings.recordStatus("archive", "Source lookup timed out. Retry.")
        assertEquals("Source lookup timed out. Retry.", settings.providers.value.first().lastStatus)
        assertEquals(before.map { it.copy(lastStatus = null) }, settings.providers.value.map { it.copy(lastStatus = null) })
        assertThrows(IllegalStateException::class.java) { settings.setEnabled("archive", false) }
        assertTrue(settings.providers.value.first().enabled)
        settings.move("missing", 0); settings.setEnabled("missing", false)
    }
}
