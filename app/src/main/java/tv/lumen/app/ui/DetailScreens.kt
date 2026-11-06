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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import lumen.core.Movie
import lumen.core.MovieDetail
import lumen.core.Series
import lumen.core.SeriesDetail
import tv.lumen.app.MainViewModel
import tv.lumen.app.data.DiagLog
import tv.lumen.app.data.Tmdb

@Composable
private fun Backdrop(url: String?) {
    Box(Modifier.fillMaxSize()) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to C.Ink, 0.5f to C.Ink.copy(alpha = 0.9f), 1f to C.Ink.copy(alpha = 0.35f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to C.Ink)))
    }
}

@Composable
fun MovieDetailScreen(vm: MainViewModel, movie: Movie) {
    val settings by vm.store.settings.collectAsStateWithLifecycle()
    val prefs by vm.store.prefs.collectAsStateWithLifecycle()
    val detail by produceState<MovieDetail?>(null, movie.id) {
        value = withContext(Dispatchers.IO) {
            runCatching { vm.repo.loader?.xtream?.movieDetail(movie) }
                .onFailure { DiagLog.log("Movie details failed", it) }.getOrNull()
        }
    }
    val tmdb by produceState<Tmdb.Art?>(null, movie.id, settings.tmdbKey) {
        value = Tmdb.art(settings.tmdbKey, movie.name, isSeries = false)
    }
    val progress = prefs.progress["m:${movie.id}"]?.takeIf { !it.finished }
    val playFr = remember { FocusRequester() }

    Box(Modifier.fillMaxSize()) {
        Backdrop(detail?.backdrop ?: tmdb?.backdrop)
        Row(Modifier.padding(start = 40.dp, top = 40.dp, end = 40.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Poster(movie.poster ?: tmdb?.poster, movie.name, Modifier.size(160.dp, 240.dp).clip(RoundedCornerShape(10.dp)))
            Column(Modifier.width(460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(movie.name, style = T.hero, maxLines = 2)
                val meta = listOfNotNull(
                    movie.year, detail?.genre, detail?.durationSec?.let { fmtDuration(it * 1000) },
                    movie.rating?.let { "★ %.1f".format(it) },
                ).joinToString("  ·  ")
                if (meta.isNotBlank()) Text(meta, style = T.meta)
                Text(detail?.plot ?: if (detail == null && vm.repo.loader?.xtream != null) "Loading details…" else "", style = T.body, maxLines = 6)
                detail?.cast?.let { Text("Cast: $it", style = T.meta, maxLines = 2) }
                detail?.director?.let { Text("Director: $it", style = T.meta) }
                Spacer(Modifier.height(6.dp))
                if (progress != null) ProgressBar(progress.fraction, Modifier.width(220.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(
                        if (progress != null) "Resume (${fmtDuration(progress.durationMs - progress.positionMs)} left)" else "Play",
                        onClick = { vm.playMovie(movie, resume = true) }, primary = true, modifier = Modifier.focusRequester(playFr),
                    )
                    if (progress != null) PillButton("Start from the beginning", onClick = { vm.playMovie(movie, resume = false) })
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { playFr.requestFocus() } }
}

@Composable
fun SeriesDetailScreen(vm: MainViewModel, series: Series) {
    val settings by vm.store.settings.collectAsStateWithLifecycle()
    val prefs by vm.store.prefs.collectAsStateWithLifecycle()
    val detail by produceState<SeriesDetail?>(null, series.id) {
        value = withContext(Dispatchers.IO) {
            runCatching { vm.repo.loader?.xtream?.seriesDetail(series) }
                .onFailure { DiagLog.log("Series details failed", it) }.getOrNull()
        }
    }
    val tmdb by produceState<Tmdb.Art?>(null, series.id, settings.tmdbKey) {
        value = Tmdb.art(settings.tmdbKey, series.name, isSeries = true)
    }
    val seasons = detail?.seasons?.keys?.toList().orEmpty()
    // Default to the season of the most recently watched episode, else the first.
    val lastWatched = prefs.progress.values.filter { it.seriesId == series.id }.maxByOrNull { it.updatedMs }
    var season by remember(detail) {
        val watchedSeason = lastWatched?.let { w ->
            detail?.seasons?.entries?.firstOrNull { entry -> entry.value.any { "e:${it.id}" == w.id } }?.key
        }
        mutableIntStateOf(watchedSeason ?: seasons.firstOrNull() ?: 1)
    }
    val firstFr = remember { FocusRequester() }

    Box(Modifier.fillMaxSize()) {
        Backdrop(series.backdrop ?: tmdb?.backdrop)
        Column(Modifier.padding(start = 40.dp, top = 32.dp, end = 32.dp)) {
            Text(series.name, style = T.hero, maxLines = 1)
            val meta = listOfNotNull(series.year, series.rating?.let { "★ %.1f".format(it) }, if (seasons.isNotEmpty()) "${seasons.size} seasons" else null)
                .joinToString("  ·  ")
            if (meta.isNotBlank()) Text(meta, style = T.meta)
            series.plot?.let { Text(it, style = T.body, maxLines = 3, modifier = Modifier.width(560.dp).padding(top = 6.dp)) }
            Spacer(Modifier.height(12.dp))
            if (detail == null) {
                if (vm.repo.loader?.xtream == null) Text("Episodes need an Xtream Codes login.", style = T.body)
                else CircularProgressIndicator(color = LocalAccent.current, modifier = Modifier.size(24.dp))
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(seasons) { s -> PillButton("Season $s", onClick = { season = s }, selected = s == season, onFocus = { if (it) season = s }) }
                }
                Spacer(Modifier.height(10.dp))
                val episodes = detail?.seasons?.get(season).orEmpty()
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(episodes, key = { it.id }) { e ->
                        val w = prefs.progress["e:${e.id}"]
                        Focusable(
                            onClick = { vm.playEpisode(series, e) },
                            modifier = (if (e == episodes.first()) Modifier.focusRequester(firstFr) else Modifier).fillMaxWidth().height(64.dp),
                            color = C.Surface.copy(alpha = 0.85f), focusedColor = C.Raised, scale = 1.01f,
                        ) {
                            Row(Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Poster(e.image ?: series.cover, e.title, Modifier.size(84.dp, 48.dp).clip(RoundedCornerShape(6.dp)))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    OneLine("${e.number}. ${e.title}", T.title)
                                    e.plot?.let { OneLine(it, T.meta) }
                                    if (w != null) ProgressBar(w.fraction, Modifier.width(140.dp).padding(top = 4.dp))
                                }
                                e.durationSec?.let { Text(fmtDuration(it * 1000), style = T.meta) }
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(detail) { if (detail != null) { delay(80); runCatching { firstFr.requestFocus() } } }
}
