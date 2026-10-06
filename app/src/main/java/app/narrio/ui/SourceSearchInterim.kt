package app.narrio.ui

import app.narrio.data.AddonManager
import app.narrio.data.SourceQuality
import app.narrio.data.text
import app.narrio.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

/*
 * TEMPORARY (workstream U): adapters that let the per-source UI run on today's one-shot search until the streaming
 * engine and its provider settings land (workstream S, docs/SOURCE_SEARCH.md). Replace them with
 * graph.streamingSourceSearch / graph.sourceProviderSettings and delete this file; nothing else depends on it.
 */

object InterimSourceIds {
    const val ARCHIVE = "archive"
    const val LIBRARY = "torbox-library"
    const val TORBOX_SEARCH = "torbox-search"
    fun addon(id: String) = "addon:$id"
}

/**
 * TEMPORARY: built-in sources and audiobook add-ons in one ordered list. Add-on switches use the add-on manager's
 * saved state; built-in switches and the order live only in memory until S's persisted settings replace this.
 */
class InterimSourceProviderSettings(private val addons: AddonManager, scope: CoroutineScope) : SourceProviderSettings {
    private val builtIns = listOf(
        SourceProvider(InterimSourceIds.ARCHIVE, "Internet Archive / LibriVox", SourceProviderKind.BUILT_IN, true, 0, requiresTorBox = false, removable = false),
        SourceProvider(InterimSourceIds.LIBRARY, "My TorBox library", SourceProviderKind.BUILT_IN, true, 1, requiresTorBox = true, removable = false),
        SourceProvider(InterimSourceIds.TORBOX_SEARCH, "TorBox search", SourceProviderKind.BUILT_IN, true, 2, requiresTorBox = true, removable = false),
    )
    private val disabledBuiltIns = MutableStateFlow(emptySet<String>())
    private val order = MutableStateFlow(emptyList<String>())
    override val providers: StateFlow<List<SourceProvider>> = combine(addons.installed, addons.status, disabledBuiltIns, order) { installed, status, off, saved ->
        val all = builtIns.map { it.copy(enabled = it.id !in off) } + installed.filter { it.purpose == "Audiobook sources" }.map { addon ->
            SourceProvider(InterimSourceIds.addon(addon.id), addon.name, SourceProviderKind.ADDON, addon.enabled, 0, requiresTorBox = true, removable = true,
                lastStatus = status[addon.id])
        }
        all.sortedBy { saved.indexOf(it.id).takeIf { index -> index >= 0 } ?: (saved.size + all.indexOf(it)) }.mapIndexed { index, provider -> provider.copy(order = index) }
    }.stateIn(scope, SharingStarted.Eagerly, builtIns)

    override fun setEnabled(id: String, enabled: Boolean) {
        if (id.startsWith("addon:")) addons.enable(id.removePrefix("addon:"), enabled)
        else disabledBuiltIns.update { if (enabled) it - id else it + id }
    }

    override fun move(id: String, index: Int) {
        val ids = providers.value.map { it.id }.toMutableList()
        if (!ids.remove(id)) return
        ids.add(index.coerceIn(0, ids.size), id)
        order.value = ids
    }
}

/** The section a one-shot result belongs to, by the provider that reported it. */
internal fun interimProviderId(recording: Audiobook, providers: List<SourceProvider>): String = when (recording.provider) {
    "archive" -> InterimSourceIds.ARCHIVE
    "torbox" -> InterimSourceIds.LIBRARY
    else -> providers.firstOrNull { it.kind == SourceProviderKind.ADDON && recording.sourceAddonName.isNotBlank() && it.name == recording.sourceAddonName }?.id
        ?: InterimSourceIds.TORBOX_SEARCH
}

/** Reasons for a one-shot result, from what today's ranking already knows. */
internal fun interimReasons(recording: Audiobook): List<BestMatchReason> = buildList {
    when {
        recording.cacheState == "cached" -> add(BestMatchReason.READY_TO_STREAM)
        recording.provider == "archive" -> add(BestMatchReason.FREE_PUBLIC_RECORDING)
        else -> add(BestMatchReason.NEEDS_PREPARING)
    }
    add(BestMatchReason.STRONG_MATCH)
    if (recording.sources.any { it.format == "M4B" }) add(BestMatchReason.PREFERRED_FORMAT)
    if (SourceQuality.edition(recording).narrator.isNotBlank()) add(BestMatchReason.NARRATOR_KNOWN)
    if (!SourceQuality.ready(recording) && recording.seeders > 0) add(BestMatchReason.WELL_SEEDED)
}

/**
 * TEMPORARY: today's one-shot result shown as sections. Every source reports together, so sections appear as
 * searching and then all finish at once; a lookup failure can't be attributed to one provider and fails every
 * section that found nothing.
 */
fun SourceSearchState.interimStreamed(providers: List<SourceProvider>, connected: Boolean): StreamedSourceSearch? {
    val book = book ?: return null
    val searching = loading || !searched
    val byProvider = recordings.groupBy { interimProviderId(it, providers) }
    val possibleBy = possible.groupBy { interimProviderId(it, providers) }
    val groups = providers.sortedBy { it.order }.map { provider ->
        val found = byProvider[provider.id].orEmpty(); val maybe = possibleBy[provider.id].orEmpty()
        val status = when {
            !provider.enabled || provider.requiresTorBox && !connected -> SourceGroupStatus.SKIPPED
            searching -> SourceGroupStatus.SEARCHING
            error != null && found.isEmpty() && maybe.isEmpty() && results.isEmpty() -> SourceGroupStatus.FAILED
            else -> SourceGroupStatus.DONE
        }
        SourceGroup(provider.id, provider.name, status, if (searching) emptyList() else found, if (searching) emptyList() else maybe,
            message = error.takeIf { status == SourceGroupStatus.FAILED })
    }
    val best = recordings.firstOrNull()?.takeIf { !searching }?.let { BestMatch(it, interimReasons(it), interimProviderId(it, providers)) }
    return StreamedSourceSearch(book, groups, best, complete = !searching)
}

/** The add-on manifest version, for settings rows. */
internal fun AddonManager.version(id: String): String = installed.value.firstOrNull { it.id == id }?.manifest?.text("version").orEmpty()
