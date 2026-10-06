package app.narrio.data

/** Per-book successful snapshots only. Selection changes invalidate the entire ten-minute cache. */
class RecentSourceResults<T>(private val now: () -> Long = System::currentTimeMillis) {
    private val entries = mutableMapOf<String, Pair<Long, T>>()
    fun get(key: String, force: Boolean = false): T? {
        if (force) return null
        val entry = entries[key] ?: return null
        if (now() - entry.first !in 0 until 10 * 60_000L) { entries.remove(key); return null }
        return entry.second
    }
    fun put(key: String, value: T) { entries[key] = now() to value }
    fun clear() = entries.clear()
}
