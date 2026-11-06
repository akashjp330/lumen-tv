package tv.lumen.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import lumen.core.Channel
import lumen.core.Movie
import lumen.core.Program
import lumen.core.Series
import tv.lumen.app.MainViewModel
import tv.lumen.app.data.RepoState

private class Results(
    val channels: List<Channel> = emptyList(),
    val programs: List<Pair<Channel, Program>> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
) {
    val isEmpty get() = channels.isEmpty() && programs.isEmpty() && movies.isEmpty() && series.isEmpty()
}

@Composable
fun SearchScreen(vm: MainViewModel, repo: RepoState) {
    val catalog = repo.catalog ?: return
    var query by remember { mutableStateOf(vm.focusMemory["search"] ?: "") }
    val fieldFr = remember { FocusRequester() }
    val now = System.currentTimeMillis()

    val results by produceState(Results(), query, catalog, repo.guide) {
        delay(300)
        vm.focusMemory["search"] = query
        val q = query.trim().lowercase()
        if (q.length < 2) { value = Results(); return@produceState }
        value = withContext(Dispatchers.Default) {
            val visible = vm.repo.visibleChannels(catalog)
            val byKey = visible.associateBy { it.key }
            Results(
                channels = visible.filter { it.name.lowercase().contains(q) }.take(30),
                programs = repo.guide.search(q, now).mapNotNull { (k, p) -> byKey[k]?.let { it to p } }.take(30),
                movies = catalog.movies.filter { it.name.lowercase().contains(q) }.take(30),
                series = catalog.series.filter { it.name.lowercase().contains(q) }.take(30),
            )
        }
    }

    Column(Modifier.fillMaxSize().padding(start = 32.dp, top = 24.dp, end = 24.dp)) {
        Text("Search", style = T.h1)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            label = { Text("Channels, shows, movies, series") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = C.Text, unfocusedTextColor = C.Text,
                focusedBorderColor = LocalAccent.current, unfocusedBorderColor = C.Line,
            ),
            modifier = Modifier.width(520.dp).focusRequester(fieldFr),
        )
        Spacer(Modifier.height(14.dp))
        if (query.trim().length >= 2 && results.isEmpty) Text("Nothing found for \"$query\"", style = T.body)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (results.channels.isNotEmpty()) item {
                Section("Channels") {
                    items(results.channels, key = { it.key }) { ch ->
                        Focusable(onClick = { vm.playChannel(ch) }, modifier = Modifier.width(150.dp).height(84.dp)) {
                            ChannelLogo(ch, Modifier.fillMaxSize().padding(14.dp))
                        }
                    }
                }
            }
            if (results.programs.isNotEmpty()) item {
                Section("On TV") {
                    items(results.programs, key = { it.first.key + it.second.startMs }) { (ch, p) ->
                        Focusable(
                            onClick = { if (p.startMs <= now) vm.playChannel(ch) else vm.toggleReminder(ch, p) },
                            modifier = Modifier.width(220.dp).height(64.dp),
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                OneLine(p.title, T.title)
                                OneLine("${ch.name} · ${if (p.isAiring(now)) "on now" else fmtDay(p.startMs) + " " + fmtClock(p.startMs)}", T.meta)
                            }
                        }
                    }
                }
            }
            if (results.movies.isNotEmpty()) item {
                Section("Movies") {
                    items(results.movies, key = { it.id }) { m ->
                        Focusable(onClick = { vm.openMovie(m) }, modifier = Modifier.width(96.dp).height(142.dp)) {
                            Poster(m.poster, m.name, Modifier.fillMaxSize())
                        }
                    }
                }
            }
            if (results.series.isNotEmpty()) item {
                Section("Series") {
                    items(results.series, key = { it.id }) { s ->
                        Focusable(onClick = { vm.openSeries(s) }, modifier = Modifier.width(96.dp).height(142.dp)) {
                            Poster(s.cover, s.name, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { fieldFr.requestFocus() } }
}

@Composable
private fun Section(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = T.h2, modifier = Modifier.padding(bottom = 6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp), content = content)
    }
}
