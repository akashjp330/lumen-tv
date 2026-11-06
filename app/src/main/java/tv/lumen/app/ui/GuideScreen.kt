package tv.lumen.app.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lumen.core.Catchup
import lumen.core.Channel
import lumen.core.ContentType
import lumen.core.Program
import tv.lumen.app.MainViewModel
import tv.lumen.app.data.RepoState
import tv.lumen.app.data.catKey

private const val HALF_HOUR = 30 * 60_000L
private const val WINDOW = 4 * HALF_HOUR

private data class Focused(val ch: Channel, val p: Program?)

@Composable
fun GuideScreen(vm: MainViewModel, repo: RepoState) {
    val catalog = repo.catalog ?: return
    val prefs by vm.store.prefs.collectAsStateWithLifecycle()
    val now by rememberNow(30_000)
    val guide = repo.guide

    val visible = remember(catalog, prefs.hiddenCats, prefs.hiddenChannels) { vm.repo.visibleChannels(catalog) }
    var filter by remember { mutableStateOf(if (prefs.favorites.isNotEmpty()) "fav" else "all") }
    val categories = remember(catalog, prefs.hiddenCats) {
        catalog.liveCategories.filter { catKey(ContentType.LIVE, it.id) !in prefs.hiddenCats }
    }
    val rows = remember(visible, filter, prefs.favorites) {
        when (filter) {
            "all" -> visible
            "fav" -> prefs.favorites.mapNotNull { k -> visible.firstOrNull { it.key == k } }.ifEmpty { visible }
            else -> visible.filter { it.categoryId == filter }
        }
    }

    val maxBack = (rows.maxOfOrNull { it.catchupDays } ?: 0).coerceAtMost(3) * 86_400_000L
    var windowStart by remember { mutableLongStateOf(now - now % HALF_HOUR) }
    val minStart = (now - maxBack) - (now - maxBack) % HALF_HOUR
    val maxStart = now + 2 * 86_400_000L
    var focused by remember { mutableStateOf<Focused?>(null) }
    val reminderStarts = remember(prefs.reminders) {
        prefs.reminders.groupBy { it.channelKey }.mapValues { (_, l) -> l.map { it.startMs }.toSet() }
    }

    val restoreKey = vm.focusMemory["guide"] ?: rows.firstOrNull()?.key
    var restored by remember { mutableStateOf(false) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = rows.indexOfFirst { it.key == restoreKey }.coerceAtLeast(0).let { (it - 2).coerceAtLeast(0) }
    )

    Column(Modifier.fillMaxSize().padding(start = 24.dp, top = 20.dp, end = 20.dp)) {
        // Header + details of the focused program
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("Live guide", style = T.h1)
                Text("${fmtDay(now)} · ${fmtClock(now)}", style = T.meta)
            }
            if (repo.guideLoading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CircularProgressIndicator(color = LocalAccent.current, modifier = Modifier.width(14.dp).height(14.dp), strokeWidth = 2.dp)
                    Text("Updating guide", style = T.meta)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { PillButton("Favorites", onClick = { filter = "fav" }, selected = filter == "fav") }
            item { PillButton("All", onClick = { filter = "all" }, selected = filter == "all") }
            items(categories, key = { it.id }) { c ->
                PillButton(c.name, onClick = { filter = c.id }, selected = filter == c.id)
            }
        }
        Spacer(Modifier.height(10.dp))
        DetailStrip(vm, focused, now, Modifier.fillMaxWidth().height(58.dp))
        val guideError = repo.guideError
        if (guideError != null && !guide.hasAnyGuide(rows)) Text(guideError, style = T.meta.copy(color = C.Live))
        Spacer(Modifier.height(6.dp))

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val channelCol = 150.dp
            val gridWidth: Dp = maxWidth - channelCol
            val dpPerMs = gridWidth.value / WINDOW

            Column(Modifier.fillMaxSize()) {
                // time header
                Row(Modifier.height(22.dp)) {
                    Spacer(Modifier.width(channelCol))
                    for (i in 0 until 4) {
                        Text(fmtClock(windowStart + i * HALF_HOUR), style = T.meta, modifier = Modifier.width(gridWidth / 4))
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val f = focused?.p ?: return@onPreviewKeyEvent false
                        when (e.nativeKeyEvent.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_RIGHT ->
                                if (f.stopMs >= windowStart + WINDOW && windowStart < maxStart) {
                                    windowStart += HALF_HOUR; true
                                } else false
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT ->
                                if (f.startMs <= windowStart && windowStart > minStart) {
                                    windowStart -= HALF_HOUR; true
                                } else false
                            else -> false
                        }
                    },
                ) {
                    items(rows, key = { it.key }) { ch ->
                        GuideRow(
                            vm = vm, ch = ch, programs = guide.window(ch.key, windowStart, windowStart + WINDOW),
                            windowStart = windowStart, dpPerMs = dpPerMs, channelCol = channelCol, now = now,
                            favorite = ch.key in prefs.favorites,
                            reminders = reminderStarts[ch.key].orEmpty(),
                            requestFocus = !restored && ch.key == restoreKey,
                            onRestored = { restored = true },
                            onFocus = { p ->
                                focused = Focused(ch, p)
                                vm.focusMemory["guide"] = ch.key
                            },
                        )
                    }
                }
            }
            // red "now" line
            if (now in windowStart until windowStart + WINDOW) {
                val x = channelCol + ((now - windowStart) * dpPerMs).dp
                Box(Modifier.offset(x = x, y = 18.dp).width(2.dp).fillMaxHeight().background(C.Live))
            }
        }
    }
}

private fun lumen.core.EpgIndex.hasAnyGuide(rows: List<Channel>) = rows.any { hasGuide(it.key) }

@Composable
private fun GuideRow(
    vm: MainViewModel,
    ch: Channel,
    programs: List<Program>,
    windowStart: Long,
    dpPerMs: Float,
    channelCol: Dp,
    now: Long,
    favorite: Boolean,
    reminders: Set<Long>,
    requestFocus: Boolean,
    onRestored: () -> Unit,
    onFocus: (Program?) -> Unit,
) {
    val windowEnd = windowStart + WINDOW
    val fr = remember { FocusRequester() }
    Row(Modifier.fillMaxWidth().height(46.dp).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Focusable(
            onClick = { vm.playChannel(ch) },
            modifier = Modifier.width(channelCol - 6.dp).fillMaxHeight(),
            color = C.Ink, focusedColor = C.Raised, scale = 1f,
            onFocus = { if (it) onFocus(null) },
            onMenu = { vm.store.toggleFavorite(ch.key) },
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ch.number.toString(), style = T.meta, modifier = Modifier.width(30.dp))
                ChannelLogo(ch, Modifier.width(40.dp).height(28.dp).clip(RoundedCornerShape(5.dp)).background(C.Surface2), monogramSize = 22.dp)
                Spacer(Modifier.width(6.dp))
                OneLine((if (favorite) "★ " else "") + ch.name, T.title.copy(color = C.Text2))
            }
        }
        Spacer(Modifier.width(6.dp))

        if (programs.isEmpty()) {
            val cellMod = Modifier.width(((windowEnd - windowStart) * dpPerMs).dp - 6.dp).fillMaxHeight()
            Focusable(
                onClick = { vm.playChannel(ch) },
                modifier = if (requestFocus) cellMod.focusRequester(fr) else cellMod,
                color = C.Surface, focusedColor = C.Text, scale = 1f, ring = false,
                onFocus = { if (it) onFocus(null) },
            ) { f ->
                Text("No guide information", style = T.meta.copy(color = if (f) C.Ink else C.Faint),
                    modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 10.dp))
            }
            if (requestFocus) LaunchedEffect(Unit) { runCatching { fr.requestFocus() }; onRestored() }
        } else {
        var cursor = windowStart
        val airingIndex = programs.indexOfFirst { it.isAiring(now) }.let { if (it < 0) 0 else it }
        programs.forEachIndexed { i, p ->
            val s = maxOf(p.startMs, windowStart)
            val e = minOf(p.stopMs, windowEnd)
            if (s > cursor) Spacer(Modifier.width(((s - cursor) * dpPerMs).dp))
            cursor = e
            val w = ((e - s) * dpPerMs).dp - 4.dp
            if (w > 0.dp) key(p.startMs) {
                val airing = p.isAiring(now)
                val past = p.stopMs <= now
                val replay = past && Catchup.isAvailable(ch, p, now)
                val reminder = !past && !airing && p.startMs in reminders
                val cellMod = Modifier.width(w).fillMaxHeight()
                Focusable(
                    onClick = {
                        when {
                            airing -> vm.playChannel(ch)
                            replay -> vm.playCatchup(ch, p)
                            past -> vm.playChannel(ch)
                            else -> vm.toggleReminder(ch, p)
                        }
                    },
                    modifier = if (requestFocus && i == airingIndex) cellMod.focusRequester(fr) else cellMod,
                    shape = RoundedCornerShape(8.dp),
                    color = when { airing -> C.Raised; past -> C.Ink; else -> C.Surface },
                    focusedColor = C.Text,
                    scale = 1f, ring = false,
                    onFocus = { if (it) onFocus(p) },
                    onMenu = { vm.store.toggleFavorite(ch.key) },
                ) { f ->
                    Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 8.dp)) {
                        OneLine(p.title.ifBlank { "Untitled" }, T.title.copy(color = if (f) C.Ink else if (past) C.Faint else C.Text))
                        val tag = when { replay -> "  ·  REPLAY"; reminder -> "  ·  REMINDER SET"; else -> "" }
                        OneLine(fmtRange(p) + tag, T.meta.copy(color = if (f) C.Ink.copy(alpha = 0.7f) else C.Faint))
                    }
                }
                Spacer(Modifier.width(4.dp))
            }
        }
        if (requestFocus) LaunchedEffect(Unit) { runCatching { fr.requestFocus() }; onRestored() }
        }
    }
}

@Composable
private fun DetailStrip(vm: MainViewModel, f: Focused?, now: Long, modifier: Modifier) {
    Column(modifier) {
        if (f == null) {
            Text("Press OK on a program to watch it. Past programs marked Replay can be watched from the start. " +
                "Pick a future program to get a reminder. Press ≡ to favorite a channel.", style = T.meta)
        } else {
        val p = f.p
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p != null && p.isAiring(now)) LiveChip()
            OneLine(p?.title ?: f.ch.name, T.h2)
        }
        val line = if (p == null) "${f.ch.name} · Ch ${f.ch.number}"
        else "${f.ch.name} · ${fmtRange(p)}" + when {
            p.isAiring(now) -> " · ${fmtLeft(p, now)}"
            p.stopMs <= now && Catchup.isAvailable(f.ch, p, now) -> " · Press OK to watch from the start"
            p.startMs > now -> if (vm.hasReminder(f.ch, p)) " · Reminder set (OK to remove)" else " · Press OK for a reminder"
            else -> ""
        }
        Text(line, style = T.meta)
        p?.description?.let { OneLine(it, T.meta.copy(color = C.Text2)) }
        }
    }
}
