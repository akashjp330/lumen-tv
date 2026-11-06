package tv.lumen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import lumen.core.Category
import lumen.core.ContentType
import tv.lumen.app.MainViewModel
import tv.lumen.app.data.RepoState
import tv.lumen.app.data.Tmdb
import tv.lumen.app.data.catKey

private data class BrowseItem(val id: String, val name: String, val image: String?, val categoryId: String, val sortKey: Long, val open: () -> Unit)

@Composable
fun MoviesScreen(vm: MainViewModel, repo: RepoState) {
    val catalog = repo.catalog ?: return
    val items = remember(catalog) {
        catalog.movies.map { m -> BrowseItem(m.id, m.name, m.poster, m.categoryId, m.addedEpochSec ?: 0) { vm.openMovie(m) } }
    }
    Browse(vm, "Movies", ContentType.MOVIE, catalog.movieCategories, items, isSeries = false,
        empty = "Your provider didn't list any movies, or this login type doesn't include them.")
}

@Composable
fun SeriesScreen(vm: MainViewModel, repo: RepoState) {
    val catalog = repo.catalog ?: return
    val items = remember(catalog) {
        catalog.series.map { s -> BrowseItem(s.id, s.name, s.cover, s.categoryId, ((s.rating ?: 0.0) * 1000).toLong()) { vm.openSeries(s) } }
    }
    Browse(vm, "Series", ContentType.SERIES, catalog.seriesCategories, items, isSeries = true,
        empty = "Series come with an Xtream Codes login. M3U playlists usually don't include them.")
}

private const val ALL = "__all"

@Composable
private fun Browse(
    vm: MainViewModel,
    title: String,
    type: ContentType,
    categories: List<Category>,
    items: List<BrowseItem>,
    isSeries: Boolean,
    empty: String,
) {
    val prefs by vm.store.prefs.collectAsStateWithLifecycle()
    val settings by vm.store.settings.collectAsStateWithLifecycle()
    val counts = remember(items) { items.groupingBy { it.categoryId }.eachCount() }
    val cats = remember(categories, prefs.hiddenCats, counts) {
        categories.filter { catKey(type, it.id) !in prefs.hiddenCats && (counts[it.id] ?: 0) > 0 }
    }
    val memKey = "browse_${type.name}"
    var selected by remember { mutableStateOf(vm.focusMemory[memKey] ?: ALL) }
    var pending by remember { mutableStateOf(selected) }
    LaunchedEffect(pending) { delay(250); selected = pending; vm.focusMemory[memKey] = pending }

    val shown = remember(items, selected, prefs.hiddenCats) {
        if (selected == ALL) items.filter { catKey(type, it.categoryId) !in prefs.hiddenCats }.sortedByDescending { it.sortKey }.take(300)
        else items.filter { it.categoryId == selected }
    }
    val first = remember { FocusRequester() }

    if (items.isEmpty()) { EmptyState("No ${title.lowercase()} yet", empty); return }

    Row(Modifier.fillMaxSize().padding(top = 20.dp)) {
        Column(Modifier.width(190.dp).fillMaxHeight().padding(start = 16.dp)) {
            Text(title, style = T.h1, modifier = Modifier.padding(start = 6.dp, bottom = 10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                item {
                    CategoryRow(if (isSeries) "Top rated" else "Recently added", null, selected == ALL,
                        Modifier.focusRequester(first)) { pending = ALL }
                }
                items(cats, key = { it.id }) { c ->
                    val locked = catKey(type, c.id) in prefs.lockedCats && settings.pinHash != null
                    CategoryRow(displayCategory(c.name) + if (locked) "  (locked)" else "", counts[c.id], selected == c.id, Modifier) { pending = c.id }
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(start = 12.dp, end = 24.dp, top = 40.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(shown, key = { it.id }) { item ->
                val art by produceState<String?>(item.image, item.id, settings.tmdbKey) {
                    if (item.image == null) value = Tmdb.art(settings.tmdbKey, item.name, isSeries)?.poster
                }
                Column {
                    Focusable(onClick = item.open, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
                        Poster(art, item.name, Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.height(5.dp))
                    OneLine(item.name, T.meta.copy(color = C.Text2))
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
}

@Composable
private fun CategoryRow(name: String, count: Int?, selected: Boolean, modifier: Modifier, onFocus: () -> Unit) {
    Focusable(
        onClick = onFocus,
        modifier = modifier.fillMaxWidth().height(30.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) C.Raised else C.Ink,
        focusedColor = C.Text, scale = 1f, ring = false,
        onFocus = { if (it) onFocus() },
    ) { f ->
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OneLine(name, T.title.copy(color = if (f) C.Ink else if (selected) C.Text else C.Muted), Modifier.weight(1f))
            if (count != null) Text("$count", style = T.meta.copy(color = if (f) C.Ink else C.Faint))
        }
    }
}
