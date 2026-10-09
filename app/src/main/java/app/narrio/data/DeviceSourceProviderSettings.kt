package app.narrio.data

import android.content.Context
import app.narrio.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/** Add-on enabled state stays in AddonManager; only built-in switches and ordering live here, one file per content type. */
@Serializable
data class SourceProviderPreferences(val disabledBuiltIns: Set<String> = emptySet(), val order: List<String> = emptyList())

/**
 * Which add-ons a registry lists, and the built-in sources before and after them in default order. Ebook sources keep
 * Project Gutenberg last, as the old fixed lookup order did.
 */
class SourceCatalog(val contentType: String, val leading: List<SourceProvider>, val trailing: List<SourceProvider> = emptyList()) {
    companion object {
        private fun builtIn(id: String, name: String, torBox: Boolean) = SourceProvider(id, name, SourceProviderKind.BUILT_IN, true, 0, torBox, false)
        val AUDIOBOOK = SourceCatalog("audiobook", listOf(
            builtIn(DeviceSourceProviderSettings.ARCHIVE, "Internet Archive / LibriVox", false),
            builtIn(DeviceSourceProviderSettings.LIBRARY, "My TorBox library", true)))
        val EBOOK = SourceCatalog("ebook", listOf(
            builtIn(DeviceSourceProviderSettings.RECORDING_FILES, "This recording's files", false),
            builtIn(DeviceSourceProviderSettings.TORBOX_EBOOKS, "My TorBox ebooks", true)),
            listOf(builtIn(DeviceSourceProviderSettings.GUTENBERG, "Project Gutenberg", false)))
    }
}

class DeviceSourceProviderSettings(
    private val addons: AddonManager,
    scope: CoroutineScope,
    initial: SourceProviderPreferences = SourceProviderPreferences(),
    private val catalog: SourceCatalog = SourceCatalog.AUDIOBOOK,
    private val persist: (SourceProviderPreferences) -> Unit = {},
) : SourceProviderSettings {
    private var saved = initial
    private val outcomes = mutableMapOf<String, String>()
    private val state = MutableStateFlow<List<SourceProvider>>(emptyList())
    override val providers = state.asStateFlow()

    init {
        publish()
        scope.launch { combine(addons.installed, addons.status) { _, _ -> Unit }.collect { publish() } }
    }

    @Synchronized private fun publish() {
        fun builtIns(list: List<SourceProvider>) = list.map { it.copy(enabled = it.id !in saved.disabledBuiltIns) }
        val installed = addons.installed.value.filter { (it.source || it.searchedInApp) && it.contentType == catalog.contentType }.map {
            SourceProvider("addon:${it.id}", it.name, SourceProviderKind.ADDON, it.enabled, 0, it.source, true,
                addons.status.value[it.id])
        }
        state.value = (builtIns(catalog.leading) + installed + builtIns(catalog.trailing)).sortedWith(compareBy<SourceProvider> {
            saved.order.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
        }).mapIndexed { index, provider -> provider.copy(order = index, lastStatus = outcomes[provider.id] ?: provider.lastStatus) }
    }

    @Synchronized override fun setEnabled(id: String, enabled: Boolean) {
        val provider = providers.value.firstOrNull { it.id == id } ?: return
        if (provider.kind == SourceProviderKind.ADDON) addons.enable(id.removePrefix("addon:"), enabled)
        else {
            val updated = saved.copy(disabledBuiltIns = if (enabled) saved.disabledBuiltIns - id else saved.disabledBuiltIns + id)
            persist(updated); saved = updated
        }
        publish()
    }

    @Synchronized override fun move(id: String, index: Int) {
        val ids = providers.value.map { it.id }.toMutableList()
        if (!ids.remove(id)) return
        ids.add(index.coerceIn(0, ids.size), id)
        val updated = saved.copy(order = ids)
        persist(updated); saved = updated; publish()
    }

    @Synchronized fun recordStatus(id: String, status: String) { outcomes[id] = status; publish() }

    companion object {
        const val ARCHIVE = "archive"
        const val LIBRARY = "torbox-library"
        const val RECORDING_FILES = "recording-files"
        const val TORBOX_EBOOKS = "torbox-ebooks"
        const val GUTENBERG = "gutenberg"
        fun create(context: Context, addons: AddonManager, scope: CoroutineScope, catalog: SourceCatalog = SourceCatalog.AUDIOBOOK): DeviceSourceProviderSettings {
            val name = if (catalog.contentType == "audiobook") "source-providers.v1" else "${catalog.contentType}-providers.v1"
            val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val initial = runCatching { NarrioJson.decodeFromString<SourceProviderPreferences>(preferences.getString("settings", "{}")!!) }
                .getOrDefault(SourceProviderPreferences())
            return DeviceSourceProviderSettings(addons, scope, initial, catalog) {
                check(preferences.edit().putString("settings", NarrioJson.encodeToString(it)).commit()) { "Could not save source settings." }
            }
        }
    }
}
