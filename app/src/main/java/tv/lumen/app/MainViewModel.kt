package tv.lumen.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lumen.core.Channel
import lumen.core.ContentType
import lumen.core.Episode
import lumen.core.Movie
import lumen.core.Program
import lumen.core.Series
import tv.lumen.app.data.Reminder
import tv.lumen.app.data.Repository
import tv.lumen.app.data.WatchProgress
import tv.lumen.app.data.catKey

sealed interface Route {
    data object Home : Route
    data object Guide : Route
    data object Movies : Route
    data object Series : Route
    data object Search : Route
    data object Settings : Route
    data class Player(val req: PlayRequest) : Route
    data class MovieDetail(val movie: Movie) : Route
    data class SeriesDetail(val series: lumen.core.Series) : Route
    data class Multiview(val keys: List<String>) : Route
    data object Diagnostics : Route
    data object ManageCategories : Route
    data object AddSource : Route
}

/** What the player should play. Live requests carry the channel so up/down can zap. */
data class PlayRequest(
    val kind: Kind,
    val url: String,
    val title: String,
    val channelKey: String? = null,
    val subtitle: String? = null,
    val startPositionMs: Long = 0,
    val progressId: String? = null,
    val image: String? = null,
    val seriesId: String? = null,
) {
    enum class Kind { LIVE, CATCHUP, MOVIE, EPISODE }
    val isLive get() = kind == Kind.LIVE
}

data class Banner(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val lumen = app as LumenApp
    val repo: Repository = lumen.repo
    val store = lumen.store

    val stack = mutableStateListOf<Route>(Route.Home)
    val current: Route get() = stack.last()

    /** Set when something locked needs the parental PIN; the dialog calls [submitPin]. */
    var pinPrompt by mutableStateOf<(() -> Unit)?>(null)
        private set
    private var unlockedThisSession = false

    var banner by mutableStateOf<Banner?>(null)
        private set

    /** Last focused item per screen, so coming back from the player lands where you were. */
    val focusMemory = mutableMapOf<String, String>()

    init {
        // In-app reminders: check every 20 s while the app is open.
        viewModelScope.launch {
            while (isActive) {
                delay(20_000)
                checkReminders()
            }
        }
    }

    // ---------- navigation ----------

    fun go(r: Route) {
        val topLevel = r is Route.Home || r is Route.Guide || r is Route.Movies || r is Route.Series ||
            r is Route.Search || r is Route.Settings
        if (topLevel) { stack.clear(); stack.add(Route.Home); if (r !is Route.Home) stack.add(r) }
        else stack.add(r)
    }

    /** Returns false when there's nothing to pop (the activity should exit). */
    fun back(): Boolean {
        if (pinPrompt != null) { pinPrompt = null; return true }
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun replaceTop(r: Route) {
        stack[stack.lastIndex] = r
    }

    // ---------- parental lock ----------

    fun isLocked(type: ContentType, categoryId: String): Boolean =
        !unlockedThisSession && store.settings.value.pinHash != null &&
            catKey(type, categoryId) in store.prefs.value.lockedCats

    private fun guarded(type: ContentType, categoryId: String, action: () -> Unit) {
        if (isLocked(type, categoryId)) pinPrompt = action else action()
    }

    fun submitPin(pin: String): Boolean {
        if (!store.checkPin(pin)) return false
        unlockedThisSession = true
        val next = pinPrompt
        pinPrompt = null
        next?.invoke()
        return true
    }

    // ---------- playback ----------

    fun channelRequest(ch: Channel, program: Program? = null) = PlayRequest(
        kind = PlayRequest.Kind.LIVE, url = ch.streamUrl, title = program?.title ?: ch.name,
        channelKey = ch.key, subtitle = ch.name, image = ch.logo,
    )

    fun playChannel(ch: Channel, replace: Boolean = false) = guarded(ContentType.LIVE, ch.categoryId) {
        store.pushRecent(ch.key)
        val route = Route.Player(channelRequest(ch))
        if (replace && current is Route.Player) replaceTop(route) else go(route)
    }

    fun playCatchup(ch: Channel, p: Program) = guarded(ContentType.LIVE, ch.categoryId) {
        val url = repo.catchupUrl(ch, p)
        if (url == null) {
            showBanner("This program isn't available to replay.")
        } else {
            go(Route.Player(PlayRequest(PlayRequest.Kind.CATCHUP, url, p.title, ch.key, "${ch.name} · replay", image = ch.logo)))
        }
    }

    fun playMovie(m: Movie, resume: Boolean = true) = guarded(ContentType.MOVIE, m.categoryId) {
        val pos = if (resume) store.prefs.value.progress["m:${m.id}"]?.takeIf { !it.finished }?.positionMs ?: 0L else 0L
        go(Route.Player(PlayRequest(PlayRequest.Kind.MOVIE, m.streamUrl, m.name, startPositionMs = pos, progressId = "m:${m.id}", image = m.poster)))
    }

    fun playEpisode(s: Series, e: Episode, resume: Boolean = true) = guarded(ContentType.SERIES, s.categoryId) {
        val id = "e:${e.id}"
        val pos = if (resume) store.prefs.value.progress[id]?.takeIf { !it.finished }?.positionMs ?: 0L else 0L
        go(Route.Player(PlayRequest(
            PlayRequest.Kind.EPISODE, e.streamUrl, e.title, subtitle = "${s.name} · S${e.season} E${e.number}",
            startPositionMs = pos, progressId = id, image = e.image ?: s.cover, seriesId = s.id,
        )))
    }

    fun resume(w: WatchProgress) {
        go(Route.Player(PlayRequest(
            if (w.kind == "episode") PlayRequest.Kind.EPISODE else PlayRequest.Kind.MOVIE,
            w.url, w.title, startPositionMs = w.positionMs, progressId = w.id, image = w.image, seriesId = w.seriesId,
        )))
    }

    fun saveProgress(req: PlayRequest, positionMs: Long, durationMs: Long) {
        val id = req.progressId ?: return
        if (durationMs <= 0 || positionMs < 15_000) return
        store.saveProgress(WatchProgress(
            id = id, kind = if (req.kind == PlayRequest.Kind.EPISODE) "episode" else "movie",
            title = listOfNotNull(req.subtitle, req.title).joinToString(" · ").ifBlank { req.title },
            image = req.image, url = req.url, positionMs = positionMs, durationMs = durationMs,
            updatedMs = System.currentTimeMillis(), seriesId = req.seriesId,
        ))
    }

    fun openMovie(m: Movie) = guarded(ContentType.MOVIE, m.categoryId) { go(Route.MovieDetail(m)) }
    fun openSeries(s: Series) = guarded(ContentType.SERIES, s.categoryId) { go(Route.SeriesDetail(s)) }

    // ---------- reminders ----------

    fun toggleReminder(ch: Channel, p: Program) {
        val exists = store.prefs.value.reminders.any { it.channelKey == ch.key && it.startMs == p.startMs }
        store.updatePrefs { prefs ->
            prefs.copy(reminders = if (exists) prefs.reminders.filterNot { it.channelKey == ch.key && it.startMs == p.startMs }
            else prefs.reminders + Reminder(ch.key, p.startMs, p.title))
        }
        showBanner(if (exists) "Reminder removed" else "We'll remind you when ${p.title} starts")
    }

    fun hasReminder(ch: Channel, p: Program) =
        store.prefs.value.reminders.any { it.channelKey == ch.key && it.startMs == p.startMs }

    private fun checkReminders() {
        val now = System.currentTimeMillis()
        val due = store.prefs.value.reminders.filter { it.startMs - 60_000 <= now }
        if (due.isEmpty()) return
        store.updatePrefs { it.copy(reminders = it.reminders - due.toSet()) }
        val r = due.filter { now - it.startMs < 30 * 60_000 }.maxByOrNull { it.startMs } ?: return
        val ch = repo.state.value.catalog?.channels?.firstOrNull { it.key == r.channelKey } ?: return
        banner = Banner("${r.title} is starting on ${ch.name}", "Watch") { banner = null; playChannel(ch) }
    }

    fun showBanner(text: String) {
        banner = Banner(text)
        viewModelScope.launch {
            delay(4_000)
            if (banner?.text == text) banner = null
        }
    }

    fun dismissBanner() { banner = null }
}
