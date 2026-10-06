package app.narrio

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.data.AddonManager
import app.narrio.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import app.narrio.ui.NarrioViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AddonSettingsExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
    private fun reveal(matcher: SemanticsMatcher) = compose.onNodeWithTag("addon-options").performScrollToNode(matcher)

    @Test fun preAnnaSettingsKeepAudioProvidersAndDisabledAndRemovedChoicesOnUpgrade() = runBlocking {
        val name = "addons-lookup-upgrade-fixture"
        val context = object : ContextWrapper(compose.activity) {
            override fun getSharedPreferences(ignored: String, mode: Int) = super.getSharedPreferences(name, mode)
        }
        val preferences = context.getSharedPreferences("addons.v1", Context.MODE_PRIVATE)
        val legacy = AddonManager.bundledUrls.filterKeys { it != "annas-archive-ebooks" }.map { (id, url) ->
            AddonManifest.parse(context.assets.open("addons/$id.json").bufferedReader().use { it.readText() }, url)
        }.filterNot { it.id == "audiobookbay" }.map { it.copy(enabled = it.id != "tpb-audiobooks") }
        val hash = "a".repeat(40)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            assertEquals("https://api.knaben.org/v1", chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"hits":[{"title":"Book - Writer","hash":"$hash"}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            // Older installs have no known-bundled tracking preference.
            check(preferences.edit().clear().putString("installed", NarrioJson.encodeToString(legacy)).commit())
            val upgraded = AddonManager.create(context, client)
            assertEquals(legacy, upgraded.installed.value.filterNot { it.id == "annas-archive-ebooks" })
            assertTrue(upgraded.installed.value.single { it.id == "annas-archive-ebooks" }.enabled)
            assertEquals(hash, upgraded.search("Book").single().torrentHash)
            assertEquals(1, requests.get())
            assertEquals(upgraded.installed.value, AddonManager.create(context, client).installed.value)
            upgraded.remove("annas-archive-ebooks")
            assertEquals(legacy, AddonManager.create(context, client).installed.value)
        } finally { check(preferences.edit().clear().commit()) }
    }

    @Test fun managerShowsProvidersPersistsDisabledStateAndReturnsToSettings() {
        val original = vm.graph.addons.installed.value.first { it.id == "audiobookbay" }.enabled
        try {
            compose.runOnIdle { vm.navigate(2) }
            compose.onNodeWithText("Add-ons · Book metadata, audio & ebooks").performScrollTo().performClick()
            compose.onNodeWithText("Add-on manifest URL").assertIsDisplayed()
            compose.onNodeWithText("Import add-on").assertIsNotEnabled()
            reveal(hasText("Audible Audiobooks")); compose.onNodeWithText("Audible Audiobooks").assertIsDisplayed()
            reveal(hasText("Open Library")); compose.onNodeWithText("Open Library").assertIsDisplayed()
            reveal(hasContentDescription("Enable AudiobookBay")); compose.onNodeWithContentDescription("Enable AudiobookBay").performClick()
            compose.waitUntil { vm.graph.addons.installed.value.first { it.id == "audiobookbay" }.enabled != original }
            compose.runOnIdle {
                val restored = AddonManager.create(compose.activity, vm.graph.http)
                assertEquals(!original, restored.installed.value.first { it.id == "audiobookbay" }.enabled)
            }
            reveal(hasText("Knaben AudioBooks")); compose.onNodeWithText("Knaben AudioBooks").assertIsDisplayed()
            reveal(hasText("The Pirate Bay")); compose.onNodeWithText("The Pirate Bay").assertIsDisplayed()
            reveal(hasText("Knaben Ebooks")); compose.onNodeWithText("Knaben Ebooks").assertIsDisplayed()
            reveal(hasText("Anna's Archive")); compose.onNodeWithText("Anna's Archive").assertIsDisplayed()
            compose.onNodeWithText("Enabled · Opens inside Narrio").assertExists()
            compose.onNodeWithContentDescription("Back to settings").performClick()
            compose.onNodeWithText("Add-ons · Book metadata, audio & ebooks").assertIsDisplayed()
            compose.onNodeWithText("Add-ons · Book metadata, audio & ebooks").performScrollTo().performClick()
            reveal(hasText("Add-on manifest URL"))
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(compose.activity.filesDir, "addon-settings.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } finally { compose.runOnIdle { vm.graph.addons.enable("audiobookbay", original) } }
    }
}
