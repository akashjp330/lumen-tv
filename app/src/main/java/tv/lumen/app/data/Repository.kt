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
import kotlinx.coroutines.withContext
import lumen.core.AccountInfo
import lumen.core.AuthException
import lumen.core.CachingFetcher
import lumen.core.Catalog
import lumen.core.Channel
import lumen.core.ContentType
import lumen.core.EpgIndex
import lumen.core.LogScrubber
import lumen.core.Program
import lumen.core.Source
import lumen.core.SourceLoader
import lumen.core.UrlConnectionFetcher
import lumen.core.XmltvData
import java.io.File
import java.io.IOException

data class RepoState(
    val source: Source? = null,
    val catalog: Catalog? = null,
    val guide: EpgIndex = EpgIndex.EMPTY,
    val xmltv: XmltvData? = null,
    /** Non-null while the first load is running; shown on the splash. */
    val loading: String? = null,
    val refreshing: Boolean = false,
    val guideLoading: Boolean = false,
    val error: String? = null,
    val guideError: String? = null,
    val lastRefreshMs: Long = 0,
)

/**
 * Loads and caches everything for the active provider. Opens from the disk cache
 * instantly, then refreshes in the background when the cache is stale.
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

    private fun cacheDir(source: Source) = File(context.cacheDir, "src_${source.id}")

    /** Called at app start and whenever the active provider changes. */
    fun start() {
        val source = store.activeSource ?: run { _state.value = RepoState(); return }
        if (store.activeId.value != source.id) store.setActive(source.id)
        loadJob?.cancel()
        loader = SourceLoader(source, net)
        _state.value = RepoState(source = source, loading = "Starting")
        loadJob = scope.launch {
            val dir = cacheDir(source)
            val cachedLoader = SourceLoader(source, CachingFetcher(dir, net, cacheFirst = true))
            try {
                val catalog = cachedLoader.loadCatalog { step -> _state.update { it.copy(loading = step) } }
                _state.update { it.copy(catalog = catalog, loading = null, error = null) }
                DiagLog.log("Loaded ${catalog.channels.size} channels, ${catalog.movies.size} movies, ${catalog.series.size} series")
                loadGuide(cachedLoader, catalog)
            } catch (e: Exception) {
                DiagLog.log("Initial load failed", e)
                _state.update { it.copy(loading = null, error = friendly(e)) }
            }
            val age = CachingFetcher(dir, net, true).cachedAgeMs(cachedLoader.xtream?.apiUrl("get_live_streams")
                ?: (source as Source.M3u).playlistUrl, System.currentTimeMillis())
            if (age == null || age > STALE_MS || _state.value.catalog == null) refreshNow()
        }
        periodicJob?.cancel()
        periodicJob = scope.launch {
            while (isActive) {
                delay(GUIDE_REFRESH_MS)
                refresh()
            }
        }
    }

    fun refresh() { scope.launch { refreshNow() } }

    private suspend fun refreshNow() {
        val source = _state.value.source ?: return
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        try {
            val fresh = SourceLoader(source, CachingFetcher(cacheDir(source), net, cacheFirst = false))
            val catalog = fresh.loadCatalog()
            _state.update { it.copy(catalog = catalog, error = null, lastRefreshMs = System.currentTimeMillis()) }
            DiagLog.log("Refreshed: ${catalog.channels.size} channels")
            loadGuide(fresh, catalog)
        } catch (e: Exception) {
            DiagLog.log("Refresh failed", e)
            if (_state.value.catalog == null) _state.update { it.copy(error = friendly(e)) }
        } finally {
            _state.update { it.copy(refreshing = false) }
        }
    }

    /** Re-matches the guide after the user fixes a channel's guide match (no download). */
    fun rebuildGuideMatches() {
        val s = _state.value
        val catalog = s.catalog ?: return
        val xmltv = s.xmltv ?: return
        scope.launch {
            val index = EpgIndex.build(visibleChannels(catalog), xmltv, store.prefs.value.epgOverrides)
            _state.update { it.copy(guide = index) }
        }
    }

    private suspend fun loadGuide(l: SourceLoader, catalog: Catalog) {
        _state.update { it.copy(guideLoading = true) }
        try {
            val prefs = store.prefs.value
            val visible = visibleChannels(catalog)
            val maxCatchup = visible.maxOfOrNull { it.catchupDays } ?: 0
            val favs = prefs.favorites.mapNotNull { k -> visible.firstOrNull { it.key == k } }
            val result = l.loadGuide(
                catalog = catalog,
                nowMs = System.currentTimeMillis(),
                daysBack = maxCatchup.coerceIn(0, 3),
                daysAhead = 2,
                overrides = prefs.epgOverrides,
                shortEpgFor = (favs + visible).distinct().take(60),
                guideChannels = visible,
            )
            _state.update { it.copy(guide = result.index, xmltv = result.xmltv, guideError = result.error) }
            DiagLog.log("Guide: ${result.xmltv.programCount} programmes, ${result.xmltv.channels.size} guide channels" +
                (result.error?.let { " (error: $it)" } ?: ""))
        } catch (e: OutOfMemoryError) {
            DiagLog.log("Guide too large for this device's memory")
            _state.update { it.copy(guideError = "The guide file is too large for this device. Hide categories you don't watch, then refresh.") }
        } catch (e: Exception) {
            DiagLog.log("Guide failed", e)
            _state.update { it.copy(guideError = friendly(e)) }
        } finally {
            _state.update { it.copy(guideLoading = false) }
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
        private const val GUIDE_REFRESH_MS = 6 * 3_600_000L

        fun friendly(e: Throwable): String = when (e) {
            is AuthException -> e.message ?: "Login failed."
            is java.net.UnknownHostException -> "Can't find that server. Check the address and your internet connection."
            is java.net.SocketTimeoutException -> "The server took too long to answer. Try again in a minute."
            is java.net.ConnectException -> "Couldn't connect to the server. Check the address and port."
            is lumen.core.HttpException -> "The server answered with an error (${e.code})."
            is IOException -> "Network problem: ${LogScrubber.scrub(e.message ?: "unknown")}"
            else -> LogScrubber.scrub(e.message ?: e.javaClass.simpleName)
        }
    }
}
