package lumen.core

/**
 * Guide data keyed by app channel key, after matching provider channels to
 * XMLTV channel ids.
 */
class EpgIndex(private val byChannelKey: Map<String, List<Program>>) {

    fun programs(channelKey: String): List<Program> = byChannelKey[channelKey].orEmpty()

    fun hasGuide(channelKey: String) = byChannelKey[channelKey]?.isNotEmpty() == true

    fun at(channelKey: String, timeMs: Long): Program? {
        val list = byChannelKey[channelKey] ?: return null
        val i = indexAtOrBefore(list, timeMs)
        return list.getOrNull(i)?.takeIf { it.isAiring(timeMs) }
    }

    fun next(channelKey: String, timeMs: Long): Program? {
        val list = byChannelKey[channelKey] ?: return null
        var i = indexAtOrBefore(list, timeMs) + 1
        if (i < 0) i = 0
        while (i < list.size && list[i].startMs < timeMs) i++
        return list.getOrNull(i)
    }

    /** Programmes overlapping [fromMs, toMs). */
    fun window(channelKey: String, fromMs: Long, toMs: Long): List<Program> {
        val list = byChannelKey[channelKey] ?: return emptyList()
        val start = indexAtOrBefore(list, fromMs).coerceAtLeast(0)
        val out = ArrayList<Program>()
        for (i in start until list.size) {
            val p = list[i]
            if (p.startMs >= toMs) break
            if (p.stopMs > fromMs) out.add(p)
        }
        return out
    }

    /** Case-insensitive substring search over titles, soonest first. */
    fun search(query: String, nowMs: Long, limit: Int = 60): List<Pair<String, Program>> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        val hits = ArrayList<Pair<String, Program>>()
        for ((key, list) in byChannelKey) for (p in list) {
            if (p.stopMs > nowMs && p.title.lowercase().contains(q)) hits.add(key to p)
        }
        return hits.sortedBy { it.second.startMs }.take(limit)
    }

    private fun indexAtOrBefore(list: List<Program>, t: Long): Int {
        var lo = 0; var hi = list.size - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].startMs <= t) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    companion object {
        val EMPTY = EpgIndex(emptyMap())

        /**
         * Builds the index by matching each channel to an XMLTV channel:
         * 1. a manual override the user picked,
         * 2. the provider's epg id (tvg-id / epg_channel_id),
         * 3. a normalized name match against the XMLTV display names.
         */
        fun build(
            channels: List<Channel>,
            xmltv: XmltvData,
            overrides: Map<String, String> = emptyMap(),
            extra: Map<String, List<Program>> = emptyMap(),
        ): EpgIndex {
            val matcher = EpgMatcher(xmltv)
            val map = HashMap<String, List<Program>>()
            for (ch in channels) {
                val epgId = overrides[ch.key]?.lowercase() ?: matcher.match(ch)
                val progs = epgId?.let { xmltv.programs[it] }
                if (!progs.isNullOrEmpty()) map[ch.key] = progs
            }
            for ((k, v) in extra) if (map[k].isNullOrEmpty() && v.isNotEmpty()) map[k] = v.sortedBy { it.startMs }
            return EpgIndex(map)
        }
    }
}

class EpgMatcher(private val xmltv: XmltvData) {
    private val ids: Set<String> = buildSet {
        xmltv.channels.forEach { add(it.id.lowercase()) }
        addAll(xmltv.programs.keys)
    }

    private val byName: Map<String, String> = buildMap {
        for (c in xmltv.channels) for (n in c.names + c.id) {
            val key = normalize(n)
            if (key.isNotEmpty() && key !in this) put(key, c.id.lowercase())
        }
    }

    /** Returns the lower-cased XMLTV channel id for this channel, or null. */
    fun match(ch: Channel): String? {
        ch.epgId?.lowercase()?.let { if (it in ids) return it }
        val n = normalize(ch.name)
        if (n.isNotEmpty()) byName[n]?.let { return it }
        ch.epgId?.let { id -> byName[normalize(id)]?.let { return it } }
        return null
    }

    /** Candidate XMLTV channels for the manual "fix guide match" picker. */
    fun candidates(query: String, limit: Int = 30): List<EpgChannel> {
        val q = normalize(query)
        if (q.isEmpty()) return xmltv.channels.take(limit)
        return xmltv.channels
            .filter { c -> (c.names + c.id).any { normalize(it).contains(q) } }
            .take(limit)
    }

    companion object {
        private val prefix = Regex("""^\s*(\|[^|]*\||[A-Z]{2,3}\s*[:|\-]\s*)""")
        private val noise = Regex("""\b(fhd|uhd|hd|sd|4k|hevc|h265|raw|backup)\b""")
        private val bracketed = Regex("""[\[(][^\])]*[\])]""")

        /** "US: ESPN HD (East)" -> "espn". Lossy on purpose. */
        fun normalize(name: String): String {
            var s = prefix.replace(name, "")
            s = bracketed.replace(s, " ")
            s = s.lowercase().replace('&', ' ').replace("+", "plus")
            s = noise.replace(s, " ")
            return s.filter { it.isLetterOrDigit() }
        }
    }
}
