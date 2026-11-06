package tv.lumen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import lumen.core.Channel
import lumen.core.ContentType
import lumen.core.Movie
import lumen.core.Series
import tv.lumen.app.MainViewModel
import tv.lumen.app.data.RepoState
import tv.lumen.app.data.WatchProgress
import tv.lumen.app.data.catKey

private sealed interface Tile {
    val id: String
    data class Live(val ch: Channel) : Tile { override val id = "c:${ch.key}" }
    data class Resume(val w: WatchProgress) : Tile { override val id = "w:${w.id}" }
    data class Film(val m: Movie) : Tile { override val id = "m:${m.id}" }
    data class Show(val s: Series) : Tile { override val id = "s:${s.id}" }
}

private data class Rail(val title: String, val tiles: List<Tile>)

@Composable
fun HomeScreen(vm: MainViewModel, repo: RepoState) {
    val catalog = repo.catalog ?: return
    val prefs by vm.store.prefs.collectAsStateWithLifecycle()
    val settings by vm.store.settings.collectAsStateWithLifecycle()
    val now by rememberNow()
    val guide = repo.guide

    val visible = remember(catalog, prefs.hiddenCats, prefs.hiddenChannels) { vm.repo.visibleChannels(catalog) }
    val byKey = remember(visible) { visible.associateBy { it.key } }
    val minute = now / 60_000

    val rails = remember(visible, prefs, guide, minute, catalog) {
        val out = ArrayList<Rail>()
        val resume = prefs.progress.values.filter { !it.finished }.sortedByDescending { it.updatedMs }.take(15)
        if (resume.isNotEmpty()) out += Rail("Continue watching", resume.map { Tile.Resume(it) })
        val favs = prefs.favorites.mapNotNull { byKey[it] }
        if (favs.isNotEmpty()) out += Rail("Favorite channels", favs.map { Tile.Live(it) })
        val recent = prefs.recent.mapNotNull { byKey[it] }.filter { it.key !in prefs.favorites }.take(15)
        if (recent.isNotEmpty()) out += Rail("Recently watched", recent.map { Tile.Live(it) })
        val t = System.currentTimeMillis()
        val onNow = visible.asSequence().filter { guide.at(it.key, t) != null }.take(30).toList()
            .ifEmpty { visible.take(30) }
        out += Rail(if (guide.hasAny(visible)) "On now" else "Channels", onNow.map { Tile.Live(it) })
        val hiddenMovieCats = prefs.hiddenCats
        val newest = catalog.movies.asSequence()
            .filter { catKey(ContentType.MOVIE, it.categoryId) !in hiddenMovieCats }
            .sortedByDescending { it.addedEpochSec ?: 0 }.take(20).toList()
        if (newest.isNotEmpty()) out += Rail("Recently added movies", newest.map { Tile.Film(it) })
        val shows = catalog.series.asSequence()
            .filter { catKey(ContentType.SERIES, it.categoryId) !in hiddenMovieCats }
            .sortedByDescending { it.rating ?: 0.0 }.take(20).toList()
        if (shows.isNotEmpty()) out += Rail("Top series", shows.map { Tile.Show(it) })
        out
    }

    var hero by remember {
        mutableStateOf(
            prefs.recent.firstNotNullOfOrNull { byKey[it] }
                ?: prefs.favorites.firstNotNullOfOrNull { byKey[it] }
                ?: visible.firstOrNull()
        )
    }
    var previewKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hero?.key, settings.livePreview) {
        previewKey = null
        if (settings.livePreview && hero != null) {
            delay(1_200) // only start a stream once focus settles
            previewKey = hero?.key
        }
    }

    val restoreId = vm.focusMemory["home"] ?: rails.firstOrNull()?.tiles?.firstOrNull()?.id
    var restored by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Hero(vm, hero, previewKey, now, guide.at(hero?.key ?: "", now), prefs.favorites)
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(rails, key = { it.title }) { rail ->
                Column {
                    Text(rail.title, style = T.h2, modifier = Modifier.padding(start = 40.dp, bottom = 6.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 40.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(rail.tiles, key = { rail.title + it.id }) { tile ->
                            val fr = remember { FocusRequester() }
                            val isRestore = !restored && tile.id == restoreId
                            val mod = if (isRestore) Modifier.focusRequester(fr) else Modifier
                            if (isRestore) LaunchedEffect(Unit) {
                                runCatching { fr.requestFocus() }
                                restored = true
                            }
                            TileView(vm, tile, guide, now, mod) { focused ->
                                if (focused) {
                                    vm.focusMemory["home"] = tile.id
                                    if (tile is Tile.Live) hero = tile.ch
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun lumen.core.EpgIndex.hasAny(channels: List<Channel>) = channels.any { hasGuide(it.key) }

@Composable
private fun Hero(
    vm: MainViewModel,
    ch: Channel?,
    previewKey: String?,
    now: Long,
    program: lumen.core.Program?,
    favorites: List<String>,
) {
    Box(Modifier.fillMaxWidth().height(250.dp).background(C.Surface)) {
        if (ch != null && previewKey == ch.key) {
            LivePreview(ch.streamUrl, Modifier.fillMaxSize())
        } else if (ch != null) {
            ChannelLogo(ch, Modifier.align(Alignment.CenterEnd).padding(end = 90.dp).size(220.dp, 130.dp), monogramSize = 90.dp)
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to C.Ink, 0.38f to C.Ink.copy(alpha = 0.86f), 0.72f to Color.Transparent)
            )
        )
        Box(
            Modifier.fillMaxWidth().height(70.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, C.Ink)))
        )
        if (ch != null) Column(Modifier.padding(start = 40.dp, top = 34.dp).width(400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveChip()
                OneLine("${ch.name} · Ch ${ch.number}", T.title.copy(color = C.Text2, fontWeight = FontWeight.SemiBold))
            }
            Text(program?.title ?: ch.name, style = T.hero, maxLines = 2)
            program?.description?.let { Text(it, style = T.body, maxLines = 2) }
            if (program != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(fmtRange(program), style = T.meta)
                    ProgressBar(program.progress(now), Modifier.width(110.dp))
                    Text(fmtLeft(program, now), style = T.meta)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                PillButton("Watch live", onClick = { vm.playChannel(ch) }, primary = true)
                PillButton(
                    if (ch.key in favorites) "Remove favorite" else "Add to favorites",
                    onClick = { vm.store.toggleFavorite(ch.key) },
                )
            }
        }
    }
}

@Composable
private fun TileView(
    vm: MainViewModel,
    tile: Tile,
    guide: lumen.core.EpgIndex,
    now: Long,
    modifier: Modifier,
    onFocus: (Boolean) -> Unit,
) {
    when (tile) {
        is Tile.Live -> {
            val p = guide.at(tile.ch.key, now)
            Column(Modifier.width(170.dp)) {
                Focusable(
                    onClick = { vm.playChannel(tile.ch) },
                    modifier = modifier.fillMaxWidth().height(95.dp),
                    color = C.Surface,
                    onFocus = onFocus,
                    onMenu = { vm.store.toggleFavorite(tile.ch.key) },
                ) {
                    ChannelLogo(tile.ch, Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 14.dp))
                    Text(
                        tile.ch.number.toString(), style = T.chip.copy(color = C.Text),
                        modifier = Modifier.padding(7.dp).clip(RoundedCornerShape(3.dp))
                            .background(C.Ink.copy(alpha = 0.75f)).padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                    if (p != null) ProgressBar(p.progress(now), Modifier.fillMaxWidth().align(Alignment.BottomCenter), height = 3.dp)
                }
                Spacer(Modifier.height(6.dp))
                OneLine(p?.title ?: tile.ch.name, T.title)
                OneLine(if (p != null) "${tile.ch.name} · ${fmtLeft(p, now)}" else "Ch ${tile.ch.number}", T.meta)
            }
        }
        is Tile.Resume -> Column(Modifier.width(170.dp)) {
            Focusable(onClick = { vm.resume(tile.w) }, modifier = modifier.fillMaxWidth().height(95.dp), onFocus = onFocus) {
                Poster(tile.w.image, tile.w.title, Modifier.fillMaxSize())
                ProgressBar(tile.w.fraction, Modifier.fillMaxWidth().align(Alignment.BottomCenter))
            }
            Spacer(Modifier.height(6.dp))
            OneLine(tile.w.title, T.title)
            OneLine(fmtDuration(tile.w.durationMs - tile.w.positionMs) + " left", T.meta)
        }
        is Tile.Film -> Column(Modifier.width(96.dp)) {
            Focusable(onClick = { vm.openMovie(tile.m) }, modifier = modifier.fillMaxWidth().height(142.dp), onFocus = onFocus) {
                Poster(tile.m.poster, tile.m.name, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(6.dp))
            OneLine(tile.m.name, T.title)
        }
        is Tile.Show -> Column(Modifier.width(96.dp)) {
            Focusable(onClick = { vm.openSeries(tile.s) }, modifier = modifier.fillMaxWidth().height(142.dp), onFocus = onFocus) {
                Poster(tile.s.cover, tile.s.name, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(6.dp))
            OneLine(tile.s.name, T.title)
        }
    }
}
