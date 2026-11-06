package tv.lumen.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import lumen.core.AccountInfo
import lumen.core.AuthException
import lumen.core.CachingFetcher
import lumen.core.Catalog
import lumen.core.Channel
import lumen.core.ContentType
import lumen.core.EpgChannel
import lumen.core.EpgIndex
import lumen.core.Guide
import lumen.core.LogScrubber
import lumen.core.Program
import lumen.core.ProgramStore
import lumen.core.Source
import lumen.core.SourceLoader
import lumen.core.StoreGuide
import lumen.core.UrlConnectionFetcher
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class RepoState(
    val source: Source? = null,
    val catalog: Catalog? = null,
    val guide: Guide = EpgIndex.EMPTY,
    /** Every channel listed in the guide file, for the "Fix guide match" picker. */
    val epgChannels: List<EpgChannel> = emptyList(),
    /** Non-null while the first load is running; shown on the splash. */
    val loading: String? = null,
    val refreshing: Boolean = false,
    val guideLoading: Boolean = false,
    /** Listings saved so far while the guide downloads. */
    val guideProgress: Int = 0,
    val error: String? = null,
    val guideError: String? = null,
    /** When channels and guide were last downloaded from the provider. */
    val lastRefreshMs: Long = 0,
)

/**
 * Loads and caches everything for the active provider. Opens from what's saved
 * on the device instantly, then refreshes in the background when it's stale.
 *
 * Channels, movies and series are kept in memory (they're small even for huge
 * providers). The guide lives in a database on the device ([GuideDb]) and only
 * the rows on screen are read.
 */
class Repository(private val context: Context, val store: Store) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val net = UrlConnectionFetcher(userAgent = USER_AGENT)

    private val _state = MutableStateFlow(RepoState())
    val state: StateFlow<RepoState> = _state.asStateFlow()

    /** Network loader for one-off calls (account status, movie/series details). */
    @Volatile var loader: SourceLoader? = null
        private set

    private var loadJob: Job? = null
    private var periodicJob: Job? = null
    private val refreshLock = Mutex()
    @Volatile private var guideDb: GuideDb? = null
    @Volatile private var programStore: ProgramStore? = null

    private fun cacheDir(source: Source) = File(context.cacheDir, "src_${source.id}")
    private val sp = context.getSharedPreferences("lumen_refresh", Context.MODE_PRIVATE)
    private fun savedRefresh(source: Source) = sp.getLong("last_${source.id}", 0)

    /** Called at app start and whenever the active provider changes. */
    fun start() {
        val source = store.activeSource ?: run { _state.value = RepoState(); return }
        if (store.activeId.value != source.id) store.setActive(source.id)
        loadJob?.cancel()
        loader = SourceLoader(source, net)
        onDemand.clear(); asked.clear()
        val db = GuideDb(context, source.id).also { guideDb = it }
        programStore = null
        _state.value = RepoState(source = source, loading = "Starting", lastRefreshMs = savedRefresh(source))
        loadJob = scope.launch {
            val cachedLoader = SourceLoader(source, CachingFetcher(cacheDir(source), net, cacheFirst = true))
            try {
                val catalog = cachedLoader.loadCatalog { step -> _state.update { it.copy(loading = step) } }
                _state.update { it.copy(catalog = catalog, loading = null, error = null) }
                DiagLog.log("Loaded ${catalog.channels.size} channels, ${catalog.movies.size} movies, ${catalog.series.size} series")
                // The saved guide opens in a moment; no download needed.
                db.open()?.let { ps ->
                    programStore = ps
                    applyGuide(catalog, ps)
                    DiagLog.log("Opened saved guide (${ps.idsWithData().size} guide channels)")
                }
            } catch (e: Exception) {
                DiagLog.log("Initial load failed", e)
                _state.update { it.copy(loading = null, error = friendly(e)) }
            }
            val last = savedRefresh(source)
            val stale = System.currentTimeMillis() - last > STALE_MS
            if (stale || !db.exists || _state.value.catalog == null) refreshNow()
        }
        periodicJob?.cancel()
        periodicJob = scope.launch {
            while (isActive) {
                delay(AUTO_REFRESH_MS)
                refreshNow()
            }
        }
    }

    /** The Refresh button: re-download channels, movies, series and the guide. */
    fun refresh() { scope.launch { refreshNow() } }

    private suspend fun refreshNow() {
        if (refreshLock.isLocked) return
        refreshLock.withLock {
            val source = _state.value.source ?: return
            _state.update { it.copy(refreshing = true) }
            try {
                val fresh = SourceLoader(source, CachingFetcher(cacheDir(source), net, cacheFirst = false))
                val catalog = fresh.loadCatalog()
                onDemand.clear(); asked.clear()
                val now = System.currentTimeMillis()
                sp.edit().putLong("last_${source.id}", now).apply()
                _state.update { it.copy(catalog = catalog, error = null, lastRefreshMs = now) }
                DiagLog.log("Refreshed: ${catalog.channels.size} channels, ${catalog.movies.size} movies, ${catalog.series.size} series")
                buildGuide(source, catalog)
            } catch (e: Exception) {
                DiagLog.log("Refresh failed", e)
                if (_state.value.catalog == null) _state.update { it.copy(error = friendly(e)) }
                else _state.update { it.copy(guideError = friendly(e)) }
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    /** Downloads the guide straight into a new database, then swaps it in. */
    private fun buildGuide(source: Source, catalog: Catalog) {
        val db = guideDb ?: return
        val channels = visibleChannels(catalog)
        _state.update { it.copy(guideLoading = true, guideProgress = 0, guideError = null) }
        val builder = try { db.builder() } catch (e: Exception) {
            DiagLog.log("Couldn't create guide database", e)
            _state.update { it.copy(guideLoading = false, guideError = "Not enough storage on this device to save the guide.") }
            return
        }
        try {
            val maxCatchup = channels.maxOfOrNull { it.catchupDays } ?: 0
            val t0 = System.currentTimeMillis()
            val result = SourceLoader(source, net).streamGuide(
                catalog = catalog,
                nowMs = System.currentTimeMillis(),
                daysBack = maxCatchup.coerceIn(0, 3),
                daysAhead = GUIDE_DAYS_AHEAD,
                guideChannels = channels,
                overrides = store.prefs.value.epgOverrides,
            ) { p ->
                builder.add(p)
                if (builder.count % 20_000 == 0) _state.update { it.copy(guideProgress = builder.count) }
            }
            if (builder.count == 0) {
                builder.abort()
                DiagLog.log("Guide download gave no listings" + (result.error?.let { ": $it" } ?: ""))
                _state.update { it.copy(guideError = result.error ?: "Your provider's guide had no listings for these channels.") }
                return
            }
            val ps = builder.finish(result.channels) ?: run {
                _state.update { it.copy(guideError = "Couldn't save the guide.") }; return
            }
            programStore = ps
            applyGuide(catalog, ps)
            DiagLog.log("Guide saved: ${builder.count} listings, ${ps.idsWithData().size} guide channels, " +
                "${(System.currentTimeMillis() - t0) / 1000}s" + (result.error?.let { " (stopped early: $it)" } ?: ""))
            if (result.error != null) _state.update { it.copy(guideError = "The guide download was cut short; some listings may be missing.") }
        } catch (e: Exception) {
            builder.abort()
            DiagLog.log("Guide failed", e)
            _state.update { it.copy(guideError = friendly(e)) }
        } finally {
            _state.update { it.copy(guideLoading = false) }
        }
    }

    private fun applyGuide(catalog: Catalog, ps: ProgramStore) {
        val g = StoreGuide.build(visibleChannels(catalog), ps, store.prefs.value.epgOverrides)
        _state.update { it.copy(guide = g.withExtra(onDemand), epgChannels = ps.epgChannels()) }
    }

    /** Re-matches the guide after the user fixes a channel's guide match or shows a hidden category (no download). */
    fun rebuildGuideMatches() {
        val catalog = _state.value.catalog ?: return
        val ps = programStore ?: return
        scope.launch { applyGuide(catalog, ps) }
    }

    private val onDemand = ConcurrentHashMap<String, List<Program>>()
    private val asked: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    private var askWindowStart = 0L
    private var askedInWindow = 0

    /** Fetches now/next for channels on screen that the guide file has nothing for (at most 30 a minute). */
    fun ensureGuide(channels: List<Channel>) {
        val l = loader ?: return
        if (l.xtream == null) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - askWindowStart > 60_000) { askWindowStart = now; askedInWindow = 0 }
            if (askedInWindow >= 30) return
        }
        val g = _state.value.guide
        val need = channels.filter { !g.hasGuide(it.key) && it.key !in asked }.take(minOf(10, 30 - askedInWindow))
        need.forEach { asked.add(it.key) }
        synchronized(this) { askedInWindow += need.size }
        if (need.isEmpty()) return
        scope.launch {
            val got = l.shortEpg(need)
            if (got.isEmpty()) return@launch
            onDemand.putAll(got)
            _state.update { it.copy(guide = it.guide.withExtra(got)) }
        }
    }

    fun visibleChannels(catalog: Catalog): List<Channel> {
        val p = store.prefs.value
        return catalog.channels.filter { ch ->
            ch.key !in p.hiddenChannels && catKey(ContentType.LIVE, ch.categoryId) !in p.hiddenCats
        }
    }

    // ---------- login ----------

    suspend fun verify(source: Source): AccountInfo? = withContext(Dispatchers.IO) {
        SourceLoader(source, net).verify()
    }

    fun addSource(source: Source) {
        store.saveSource(source)
        start()
    }

    fun signOut() {
        val s = _state.value.source ?: return
        loadJob?.cancel(); periodicJob?.cancel()
        CachingFetcher(cacheDir(s), net, true).clear()
        guideDb?.delete()
        sp.edit().remove("last_${s.id}").apply()
        store.removeSource(s.id)
        LogScrubber.clear()
        loader = null
        start()
    }

    suspend fun accountNow(): AccountInfo? = withContext(Dispatchers.IO) { loader?.xtream?.account() }

    fun catchupUrl(ch: Channel, p: Program): String? = loader?.catchupUrl(ch, p, System.currentTimeMillis())

    companion object {
        const val USER_AGENT = "LumenTV/1.0 (Linux; Android) ExoPlayer"
        private const val STALE_MS = 12 * 3_600_000L
        private const val AUTO_REFRESH_MS = 6 * 3_600_000L
        private const val GUIDE_DAYS_AHEAD = 3

        fun friendly(e: Throwable): String = when (e) {
            is AuthException -> e.message ?: "Login failed."
            is java.net.UnknownHostException -> "Can't find that server. Check the address and your internet connection."
            is java.net.SocketTimeoutException -> "The server didn't answer. Double-check the server address and port number. " +
                "If they're right and your provider's own app can't connect either, the provider may be down or briefly blocking this device; try again later."
            is java.net.ConnectException -> "Couldn't connect to the server. Check the address and port."
            is lumen.core.HttpException -> "The server answered with an error (${e.code})."
            is IOException -> "Network problem: ${LogScrubber.scrub(e.message ?: "unknown")}"
            else -> LogScrubber.scrub(e.message ?: e.javaClass.simpleName)
        }
    }
}
