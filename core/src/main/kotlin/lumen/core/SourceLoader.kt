package lumen.core

import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

/**
 * One entry point for both login styles. The app calls [loadCatalog] at
 * startup/refresh and [loadGuide] in the background.
 */
class SourceLoader(
    val source: Source,
    private val http: HttpFetcher = UrlConnectionFetcher(),
) {
    val xtream: XtreamClient? = (source as? Source.Xtream)?.let {
        XtreamClient(it.server, it.username, it.password, http)
    }

    init {
        when (source) {
            is Source.Xtream -> LogScrubber.register(source.username, source.password)
            is Source.M3u -> Unit
        }
    }

    /** Verifies the login. For M3U this just checks the playlist downloads. */
    fun verify(): AccountInfo? = when (source) {
        is Source.Xtream -> xtream!!.account()
        is Source.M3u -> {
            http.open(source.playlistUrl).use { s ->
                val head = ByteArray(512)
                val n = s.read(head)
                val text = if (n > 0) String(head, 0, n, Charsets.UTF_8) else ""
                if (!text.trimStart('﻿', ' ', '\n', '\r').startsWith("#EXTM3U", ignoreCase = true) && !text.contains("#EXTINF")) {
                    throw AuthException("That link didn't return an M3U playlist.")
                }
            }
            null
        }
    }

    fun loadCatalog(includeVod: Boolean = true, onStep: (String) -> Unit = {}): Catalog = when (source) {
        is Source.Xtream -> {
            val x = xtream!!
            onStep("Checking account")
            val account = x.account()
            onStep("Loading channels")
            val liveCats = x.liveCategories()
            val channels = x.liveChannels()
            var movieCats = emptyList<Category>()
            var movies = emptyList<Movie>()
            var seriesCats = emptyList<Category>()
            var series = emptyList<Series>()
            if (includeVod) {
                onStep("Loading movies")
                movieCats = runCatching { x.movieCategories() }.getOrDefault(emptyList())
                movies = runCatching { x.movies() }.getOrDefault(emptyList())
                onStep("Loading series")
                seriesCats = runCatching { x.seriesCategories() }.getOrDefault(emptyList())
                series = runCatching { x.series() }.getOrDefault(emptyList())
            }
            Catalog(liveCats, channels, movieCats, movies, seriesCats, series, x.xmltvUrl(), account)
        }
        is Source.M3u -> {
            onStep("Loading playlist")
            val r = http.open(source.playlistUrl).use { M3uParser.parse(InputStreamReader(it, Charsets.UTF_8)) }
            Catalog(
                r.liveCategories, r.channels, r.movieCategories, r.movies,
                emptyList(), emptyList(), source.epgUrl?.takeIf { it.isNotBlank() } ?: r.epgUrl, null,
            )
        }
    }

    /**
     * Downloads and indexes the guide: [daysBack] for catch-up, [daysAhead] for the grid.
     * Falls back to per-channel "short EPG" calls for Xtream when the XMLTV file is
     * missing or empty.
     */
    fun loadGuide(
        catalog: Catalog,
        nowMs: Long,
        daysBack: Int = 2,
        daysAhead: Int = 3,
        overrides: Map<String, String> = emptyMap(),
        shortEpgFor: List<Channel> = emptyList(),
        /** Channels the user can actually see; guide data for everything else is skipped to save memory. */
        guideChannels: List<Channel> = catalog.channels,
    ): GuideResult {
        val from = nowMs - daysBack * 86_400_000L
        val to = nowMs + daysAhead * 86_400_000L
        var xmltv = XmltvData(emptyList(), emptyMap())
        var error: String? = null
        val ids = HashSet<String>().apply {
            guideChannels.forEach { c -> c.epgId?.let { add(it.lowercase()) } }
            overrides.values.forEach { add(it.lowercase()) }
        }
        val names = guideChannels.mapTo(HashSet()) { EpgMatcher.normalize(it.name) }.apply { remove("") }
        val accept: (EpgChannel) -> Boolean = { c ->
            c.id.lowercase() in ids || EpgMatcher.normalize(c.id) in names ||
                c.names.any { EpgMatcher.normalize(it) in names }
        }
        val replay = guideChannels.filter { it.catchupDays > 0 }
        val pastIds = HashSet<String>().apply {
            replay.forEach { c -> c.epgId?.let { add(it.lowercase()) } }
            overrides.filterKeys { k -> replay.any { it.key == k } }.values.forEach { add(it.lowercase()) }
        }
        val pastNames = replay.mapTo(HashSet()) { EpgMatcher.normalize(it.name) }.apply { remove("") }
        val acceptPast: (EpgChannel) -> Boolean = { c ->
            pastNames.isNotEmpty() && (EpgMatcher.normalize(c.id) in pastNames || c.names.any { EpgMatcher.normalize(it) in pastNames })
        }
        catalog.epgUrl?.let { url ->
            try {
                xmltv = http.open(url).use { XmltvParser.parse(it, from, to, ids, accept, nowMs, pastIds, acceptPast) }
            } catch (e: Exception) {
                error = LogScrubber.scrub(e.message ?: e.javaClass.simpleName)
            }
        }
        val extra = ConcurrentHashMap<String, List<Program>>()
        val x = xtream
        if (x != null && xmltv.programCount == 0) {
            for (ch in shortEpgFor) {
                runCatching { x.shortEpg(ch) }.getOrNull()?.let { if (it.isNotEmpty()) extra[ch.key] = it }
            }
        }
        val index = EpgIndex.build(guideChannels, xmltv, overrides, extra)
        return GuideResult(index, xmltv, error)
    }

    /**
     * Streams the guide into [sink] (a database writer) instead of memory, for
     * every channel in [guideChannels]. Returns the guide's channel list.
     */
    fun streamGuide(
        catalog: Catalog,
        nowMs: Long,
        daysBack: Int,
        daysAhead: Int,
        guideChannels: List<Channel>,
        overrides: Map<String, String> = emptyMap(),
        sink: (Program) -> Unit,
    ): StreamedGuide {
        val url = catalog.epgUrl ?: return StreamedGuide(emptyList(), "This provider didn't give a guide link.")
        val from = nowMs - daysBack * 86_400_000L
        val to = nowMs + daysAhead * 86_400_000L
        val ids = HashSet<String>().apply {
            guideChannels.forEach { c -> c.epgId?.let { add(it.lowercase()) } }
            overrides.values.forEach { add(it.lowercase()) }
        }
        val names = guideChannels.mapTo(HashSet()) { EpgMatcher.normalize(it.name) }.apply { remove("") }
        val accept: (EpgChannel) -> Boolean = { c ->
            c.id.lowercase() in ids || EpgMatcher.normalize(c.id) in names || c.names.any { EpgMatcher.normalize(it) in names }
        }
        val replay = guideChannels.filter { it.catchupDays > 0 }
        val pastIds = replay.mapNotNullTo(HashSet()) { it.epgId?.lowercase() }
        val pastNames = replay.mapTo(HashSet()) { EpgMatcher.normalize(it.name) }.apply { remove("") }
        val acceptPast: (EpgChannel) -> Boolean = { c ->
            pastNames.isNotEmpty() && (EpgMatcher.normalize(c.id) in pastNames || c.names.any { EpgMatcher.normalize(it) in pastNames })
        }
        return try {
            val d = http.open(url).use { XmltvParser.parse(it, from, to, ids, accept, nowMs, pastIds, acceptPast, sink) }
            StreamedGuide(d.channels, null)
        } catch (e: Exception) {
            StreamedGuide(emptyList(), LogScrubber.scrub(e.message ?: e.javaClass.simpleName))
        }
    }

    /** Now/next for channels outside the kept guide (Xtream only). */
    fun shortEpg(channels: List<Channel>): Map<String, List<Program>> {
        val x = xtream ?: return emptyMap()
        val out = HashMap<String, List<Program>>()
        for (ch in channels) runCatching { x.shortEpg(ch, limit = 6) }.getOrNull()?.let { if (it.isNotEmpty()) out[ch.key] = it }
        return out
    }

    fun catchupUrl(ch: Channel, p: Program, nowMs: Long): String? {
        if (!Catchup.isAvailable(ch, p, nowMs)) return null
        return when (source) {
            is Source.Xtream -> xtream!!.catchupUrl(ch, p.startMs, ((p.durationMs + 59_999) / 60_000).toInt())
            is Source.M3u -> Catchup.m3uUrl(ch, p, nowMs)
        }
    }
}

class GuideResult(val index: EpgIndex, val xmltv: XmltvData, val error: String?)

class StreamedGuide(val channels: List<EpgChannel>, val error: String?)
