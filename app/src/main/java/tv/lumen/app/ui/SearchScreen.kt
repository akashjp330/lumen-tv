package tv.lumen.app.ui

import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
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
    val barFr = remember { FocusRequester() }
    val now = System.currentTimeMillis()
    // The keyboard opens only while editing: on a fresh search, or when you click the search box.
    var editing by remember { mutableStateOf(query.isBlank()) }
    val restoreId = vm.focusMemory["searchItem"]
    var restored by remember { mutableStateOf(query.isBlank()) }

    /** Remembers the clicked result so Back from the player lands on it, not the keyboard. */
    @Composable
    fun Modifier.restorable(id: String): Modifier {
        val fr = remember { FocusRequester() }
        if (!restored && id == restoreId) LaunchedEffect(Unit) { delay(60); runCatching { fr.requestFocus() }; restored = true }
        return this.focusRequester(fr)
    }
    fun pick(id: String, action: () -> Unit) { vm.focusMemory["searchItem"] = id; action() }

    // Coming back with results: land on the one you picked (or the search box if it's gone).
    LaunchedEffect(Unit) {
        if (editing) return@LaunchedEffect
        if (restoreId == null) { delay(80); runCatching { barFr.requestFocus() } }
        else { delay(900); if (!restored) { restored = true; runCatching { barFr.requestFocus() } } }
    }

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
        if (editing) {
            // Only leave typing mode after the box really had focus and then lost it
            // (a new text box reports "not focused" the moment it appears).
            var hadFocus by remember { mutableStateOf(false) }
            val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text("Channels, shows, movies, series") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboard?.hide(); editing = false }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = C.Text, unfocusedTextColor = C.Text,
                    focusedBorderColor = LocalAccent.current, unfocusedBorderColor = C.Line,
                ),
                modifier = Modifier.width(520.dp).focusRequester(fieldFr)
                    .onFocusChanged {
                        if (it.isFocused) hadFocus = true
                        else if (hadFocus && query.isNotBlank()) editing = false
                    },
            )
            LaunchedEffect(Unit) {
                delay(80)
                runCatching { fieldFr.requestFocus() }
                delay(50)
                keyboard?.show() // the Fire Stick doesn't always open it on its own
            }
        } else {
            // Looks like the search box; OK opens the keyboard.
            Focusable(
                onClick = { editing = true },
                modifier = Modifier.width(520.dp).height(56.dp).focusRequester(barFr),
                shape = RoundedCornerShape(6.dp), color = C.Ink, focusedColor = C.Surface, scale = 1f,
            ) { f ->
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (query.isBlank()) "Channels, shows, movies, series" else query,
                        style = T.body.copy(color = if (query.isBlank()) C.Muted else C.Text), modifier = Modifier.weight(1f))
                    Text(if (f) "OK to type" else "", style = T.meta)
                }
                Box(Modifier.matchParentSize().border(1.dp, if (f) LocalAccent.current else C.Line, RoundedCornerShape(6.dp)))
            }
        }
        Spacer(Modifier.height(14.dp))
        if (query.trim().length >= 2 && results.isEmpty) Text("Nothing found for \"$query\"", style = T.body)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (results.channels.isNotEmpty()) item {
                Section("Channels") {
                    items(results.channels, key = { it.key }) { ch ->
                        val p = repo.guide.at(ch.key, now)
                        Column(Modifier.width(170.dp)) {
                            Focusable(
                                onClick = { pick("c:" + ch.key) { vm.playChannel(ch, zapKeys = results.channels.map { it.key }) } },
                                modifier = Modifier.fillMaxWidth().height(95.dp).restorable("c:" + ch.key),
                                onMenu = { vm.openChannelMenu(ch) },
                            ) {
                                ChannelLogo(ch, Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 14.dp))
                                Text(
                                    ch.number.toString(), style = T.chip.copy(color = C.Text),
                                    modifier = Modifier.padding(7.dp).clip(RoundedCornerShape(3.dp))
                                        .background(C.Ink.copy(alpha = 0.75f)).padding(horizontal = 5.dp, vertical = 2.dp),
                                )
                                QualityBadge(ch.name, Modifier.align(Alignment.TopEnd).padding(7.dp))
                            }
                            Spacer(Modifier.height(6.dp))
                            OneLine(ch.name, T.title)
                            OneLine(p?.let { "${it.title} · ${fmtLeft(it, now)}" } ?: "Ch ${ch.number}", T.meta)
                        }
                    }
                }
            }
            if (results.programs.isNotEmpty()) item {
                Section("On TV") {
                    items(results.programs, key = { it.first.key + it.second.startMs }) { (ch, p) ->
                        Focusable(
                            onClick = { pick("p:" + ch.key + p.startMs) { if (p.startMs <= now) vm.playChannel(ch) else vm.toggleReminder(ch, p) } },
                            modifier = Modifier.width(220.dp).height(64.dp).restorable("p:" + ch.key + p.startMs),
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
                        Focusable(onClick = { pick("m:" + m.id) { vm.openMovie(m) } }, modifier = Modifier.width(96.dp).height(142.dp).restorable("m:" + m.id)) {
                            Poster(m.poster, m.name, Modifier.fillMaxSize())
                        }
                    }
                }
            }
            if (results.series.isNotEmpty()) item {
                Section("Series") {
                    items(results.series, key = { it.id }) { s ->
                        Focusable(onClick = { pick("s:" + s.id) { vm.openSeries(s) } }, modifier = Modifier.width(96.dp).height(142.dp).restorable("s:" + s.id)) {
                            Poster(s.cover, s.name, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = T.h2, modifier = Modifier.padding(bottom = 6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp), content = content)
    }
}
