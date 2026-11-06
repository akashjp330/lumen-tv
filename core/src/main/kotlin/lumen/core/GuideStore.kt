package lumen.core

/**
 * Guide listings stored by (lower-cased) XMLTV channel id, e.g. in a database on
 * the device. Only what's on screen is ever read.
 */
interface ProgramStore {
    /** XMLTV channel ids that have at least one programme. */
    fun idsWithData(): Set<String>
    fun epgChannels(): List<EpgChannel>
    /** Programmes for one XMLTV id overlapping [fromMs, toMs), sorted by start. */
    fun window(epgId: String, fromMs: Long, toMs: Long): List<Program>
    /** Programmes whose title contains [query] (case-insensitive) and end after [nowMs], soonest first. */
    fun search(query: String, nowMs: Long, limit: Int): List<Program>
}

/** In-memory [ProgramStore], used in tests and as the shape a database must match. */
class MemoryProgramStore(private val data: XmltvData) : ProgramStore {
    private val index = EpgIndex(data.programs)
    override fun idsWithData() = data.programs.filterValues { it.isNotEmpty() }.keys
    override fun epgChannels() = data.channels
    override fun window(epgId: String, fromMs: Long, toMs: Long) = index.window(epgId, fromMs, toMs)
    override fun search(query: String, nowMs: Long, limit: Int) = index.search(query, nowMs, limit).map { it.second }
}

/**
 * [Guide] over a [ProgramStore]. Channels are matched to XMLTV ids once; listings
 * are read per channel in day-sized slices and kept in a small cache, so drawing
 * the guide grid costs a handful of indexed lookups.
 */
class StoreGuide(
    private val store: ProgramStore,
    private val keyToEpg: Map<String, String>,
) : Guide {
    private val epgToKeys: Map<String, List<String>> = keyToEpg.entries.groupBy({ it.value }, { it.key })

    private class Slice(val from: Long, val to: Long, val list: List<Program>)

    private val cache = object : LinkedHashMap<String, Slice>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Slice>?) = size > 600
    }

    override fun hasGuide(channelKey: String) = keyToEpg.containsKey(channelKey)

    @Synchronized
    private fun slice(channelKey: String, fromMs: Long, toMs: Long): Slice? {
        val epg = keyToEpg[channelKey] ?: return null
        cache[channelKey]?.let { if (it.from <= fromMs && it.to >= toMs) return it }
        val from = minOf(fromMs, floorHour(fromMs) - 6 * HOUR)
        val to = maxOf(toMs, floorHour(fromMs) + 18 * HOUR)
        return Slice(from, to, store.window(epg, from, to)).also { cache[channelKey] = it }
    }

    override fun at(channelKey: String, timeMs: Long): Program? =
        slice(channelKey, timeMs, timeMs + 1)?.list?.lastOrNull { it.startMs <= timeMs && it.stopMs > timeMs }

    override fun next(channelKey: String, timeMs: Long): Program? {
        val s = slice(channelKey, timeMs, timeMs + 1) ?: return null
        s.list.firstOrNull { it.startMs >= timeMs }?.let { return it }
        val epg = keyToEpg[channelKey] ?: return null
        return store.window(epg, s.to, s.to + DAY).firstOrNull { it.startMs >= timeMs }
    }

    override fun window(channelKey: String, fromMs: Long, toMs: Long): List<Program> {
        val s = slice(channelKey, fromMs, toMs) ?: return emptyList()
        return s.list.filter { it.stopMs > fromMs && it.startMs < toMs }
    }

    override fun search(query: String, nowMs: Long, limit: Int): List<Pair<String, Program>> {
        if (query.trim().length < 2) return emptyList()
        return store.search(query.trim(), nowMs, limit * 2)
            .flatMap { p -> epgToKeys[p.channelId].orEmpty().take(1).map { it to p } }
            .take(limit)
    }

    @Synchronized
    fun clearCache() = cache.clear()

    companion object {
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
        private fun floorHour(t: Long) = t - Math.floorMod(t, HOUR)

        /** Matches channels to XMLTV ids (manual override → provider id → name) and keeps those with data. */
        fun build(channels: List<Channel>, store: ProgramStore, overrides: Map<String, String> = emptyMap()): StoreGuide {
            val withData = store.idsWithData()
            val matcher = EpgMatcher(store.epgChannels(), withData)
            val map = HashMap<String, String>()
            for (ch in channels) {
                val id = overrides[ch.key]?.lowercase() ?: matcher.match(ch)
                if (id != null && id in withData) map[ch.key] = id
            }
            return StoreGuide(store, map)
        }
    }
}
