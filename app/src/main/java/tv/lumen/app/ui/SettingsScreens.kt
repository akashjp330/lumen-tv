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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import lumen.core.AccountInfo
import lumen.core.Category
import lumen.core.ContentType
import lumen.core.Source
import tv.lumen.app.BuildInfo
import tv.lumen.app.MainViewModel
import tv.lumen.app.Route
import tv.lumen.app.data.DiagLog
import tv.lumen.app.data.RepoState
import tv.lumen.app.data.catKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(vm: MainViewModel, repo: RepoState) {
    val settings by vm.store.settings.collectAsStateWithLifecycle()
    val sources by vm.store.sources.collectAsStateWithLifecycle()
    val source = repo.source
    val account by produceState<Result<AccountInfo?>?>(null, source?.id) {
        value = runCatching { vm.repo.accountNow() }
    }
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val profiles by vm.store.profiles.collectAsStateWithLifecycle()
    val currentProfile by vm.store.profileId.collectAsStateWithLifecycle()
    var pinField by remember { mutableStateOf("") }
    var tmdbField by remember { mutableStateOf(settings.tmdbKey ?: "") }
    val first = remember { FocusRequester() }
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 32.dp, top = 24.dp, end = 32.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Settings", style = T.h1) }

        item { Heading("Account") }
        item {
            val a = account
            val lines = when {
                source is Source.M3u -> listOf("M3U playlists don't report account details.")
                a == null -> listOf("Checking account…")
                a.isFailure -> listOf("Couldn't reach the server: ${a.exceptionOrNull()?.message ?: "unknown error"}")
                else -> a.getOrNull()?.let { info ->
                    listOfNotNull(
                        "Status: ${info.status}" + if (info.isTrial) " (trial)" else "",
                        info.expiresEpochSec?.let { "Expires: " + SimpleDateFormat("MMMM d, yyyy", Locale.US).format(Date(it * 1000)) },
                        if (info.maxConnections != null) "Streams in use: ${info.activeConnections ?: 0} of ${info.maxConnections}" else null,
                    )
                } ?: emptyList()
            }
            Column { lines.forEach { Text(it, style = T.body) } }
        }

        item { Heading("Provider") }
        item {
            val host = (source as? Source.Xtream)?.server?.substringAfter("://")?.substringBefore('/') ?: (source as? Source.M3u)?.let { "M3U playlist" }
            val guideCount = repo.catalog?.let { c -> vm.repo.visibleChannels(c).count { repo.guide.hasGuide(it.key) } } ?: 0
            Column {
                Text("${source?.label ?: ""} · $host", style = T.body)
                Text("${repo.catalog?.channels?.size ?: 0} channels · guide found for $guideCount · " +
                    "${repo.catalog?.movies?.size ?: 0} movies · ${repo.catalog?.series?.size ?: 0} series", style = T.meta)
                if (repo.lastRefreshMs > 0) Text("Last refreshed ${fmtDay(repo.lastRefreshMs)} ${fmtClock(repo.lastRefreshMs)}", style = T.meta)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(if (repo.refreshing || repo.guideLoading) "Refreshing…" else "Refresh channels & guide",
                    onClick = { vm.repo.refresh(); vm.showBanner("Refreshing in the background") },
                    modifier = Modifier.focusRequester(first))
                PillButton("Add another provider", onClick = { vm.go(Route.AddSource) })
                PillButton(if (confirmSignOut) "Press again to sign out" else "Sign out",
                    onClick = { if (confirmSignOut) vm.repo.signOut() else confirmSignOut = true })
            }
        }
        val others = sources.filter { it.id != source?.id }
        if (others.isNotEmpty()) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Switch to:", style = T.meta)
                others.forEach { s ->
                    PillButton(s.label, onClick = { vm.store.setActive(s.id); vm.repo.start(); vm.go(Route.Home) })
                }
            }
        }

        item { Heading("Home screen") }
        item {
            Toggle("Play a muted video preview on the Home banner (uses one of your streams)", settings.livePreview) {
                vm.store.updateSettings { s -> s.copy(livePreview = !s.livePreview) }
            }
        }

        item { Heading("Parental controls") }
        item {
            Text(if (settings.pinHash == null) "No PIN set. Set one, then lock categories below." else "A PIN is set. Locked categories ask for it once per session.", style = T.body)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pinField, onValueChange = { v -> pinField = v.filter { it.isDigit() }.take(8) },
                    label = { Text("New PIN (4–8 digits)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    colors = fieldColors(), modifier = Modifier.width(220.dp),
                )
                PillButton("Save PIN", onClick = {
                    if (pinField.length >= 4) { vm.store.setPin(pinField); pinField = ""; vm.showBanner("PIN saved") }
                    else vm.showBanner("Use at least 4 digits")
                })
                if (settings.pinHash != null) PillButton("Remove PIN", onClick = { vm.store.setPin(null); vm.showBanner("PIN removed") })
            }
        }
        item { PillButton("Hide or lock categories", onClick = { vm.go(Route.ManageCategories) }) }

        item { Heading("Profiles") }
        item {
            Text("Each person gets their own favorites, lists, recently watched, most watched and resume points. " +
                "The login, guide and hidden categories are shared.", style = T.body)
        }
        items(profiles, key = { "prof_" + it.id }) { p ->
            var editing by remember(p.id) { mutableStateOf(false) }
            var newName by remember(p.id) { mutableStateOf(p.name) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Avatar(p, 30.dp)
                if (editing) {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it.take(20) }, singleLine = true, label = { Text("Name") },
                        colors = fieldColors(), modifier = Modifier.width(220.dp),
                    )
                    PillButton("Save", primary = true, onClick = { vm.store.renameProfile(p.id, newName); editing = false })
                } else {
                    Text(p.name + if (p.id == currentProfile) "  (watching now)" else "", style = T.title, modifier = Modifier.width(220.dp))
                    PillButton("Rename", onClick = { editing = true })
                }
                PillButton("Change color", onClick = { vm.store.recolorProfile(p.id) })
                if (p.id != currentProfile) PillButton("Switch to", onClick = { vm.switchProfile(p.id) })
                if (profiles.size > 1) PillButton(if (confirmDelete == p.id) "Press again to delete" else "Delete", onClick = {
                    if (confirmDelete == p.id) { vm.store.deleteProfile(p.id); confirmDelete = null } else confirmDelete = p.id
                })
            }
        }
        item { PillButton("Add a profile", onClick = { vm.profileMenu = true }) }

        item { Heading("My channels") }
        item { Text("Favorites and your own lists. Press ≡ on any channel to add it to one.", style = T.body) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Edit my lists", onClick = { vm.go(Route.ManageLists) })
                PillButton(if (confirmClear) "Press again to clear" else "Clear watch history", onClick = {
                    if (confirmClear) { vm.store.clearWatchHistory(); confirmClear = false; vm.showBanner("Watch history cleared") }
                    else confirmClear = true
                })
            }
        }

        item { Heading("Artwork") }
        item {
            Text("Optional: a free TMDB API key (themoviedb.org → Settings → API) fills in missing movie and series artwork.", style = T.body)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = tmdbField, onValueChange = { tmdbField = it.trim() }, label = { Text("TMDB API key") },
                    singleLine = true, colors = fieldColors(), modifier = Modifier.width(360.dp),
                )
                PillButton("Save key", onClick = {
                    vm.store.updateSettings { it.copy(tmdbKey = tmdbField.ifBlank { null }) }
                    vm.showBanner("Saved")
                })
            }
        }

        item { Heading("Help") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Diagnostics log", onClick = { vm.go(Route.Diagnostics) })
            }
        }
        item { Text("KASH TV ${BuildInfo.version(app)}", style = T.meta) }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = C.Text, unfocusedTextColor = C.Text,
    focusedBorderColor = LocalAccent.current, unfocusedBorderColor = C.Line,
    focusedLabelColor = C.Text2, unfocusedLabelColor = C.Muted,
)

@Composable
private fun Heading(text: String) {
    Text(text, style = T.h2, modifier = Modifier.padding(top = 14.dp))
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    Focusable(onClick = onClick, modifier = Modifier.width(520.dp).height(40.dp), color = C.Surface, focusedColor = C.Raised, scale = 1.01f) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = T.title, modifier = Modifier.weight(1f))
            Text(if (on) "On" else "Off", style = T.title.copy(color = if (on) LocalAccent.current else C.Muted))
        }
    }
}

@Composable
fun ManageCategoriesScreen(vm: MainViewModel, repo: RepoState) {
    val catalog = repo.catalog ?: return
    val prefs by vm.store.prefs.collectAsStateWithLifecycle()
    val settings by vm.store.settings.collectAsStateWithLifecycle()
    var type by remember { mutableStateOf(ContentType.LIVE) }
    val cats: List<Category> = when (type) {
        ContentType.LIVE -> catalog.liveCategories
        ContentType.MOVIE -> catalog.movieCategories
        ContentType.SERIES -> catalog.seriesCategories
    }
    val counts = remember(catalog, type) {
        when (type) {
            ContentType.LIVE -> catalog.channels.groupingBy { it.categoryId }.eachCount()
            ContentType.MOVIE -> catalog.movies.groupingBy { it.categoryId }.eachCount()
            ContentType.SERIES -> catalog.series.groupingBy { it.categoryId }.eachCount()
        }
    }
    val first = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(start = 32.dp, top = 24.dp, end = 32.dp)) {
        Text("Categories", style = T.h1)
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.repo.rebuildGuideMatches() } }
        Text("Hidden categories disappear everywhere and skip guide downloads, which keeps big providers fast. " +
            "Categories you show again get their guide on the next refresh. " +
            "Locked categories need the PIN" + if (settings.pinHash == null) " (set one in Settings first)." else ".", style = T.body)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Live TV", onClick = { type = ContentType.LIVE }, selected = type == ContentType.LIVE, modifier = Modifier.focusRequester(first))
            PillButton("Movies", onClick = { type = ContentType.MOVIE }, selected = type == ContentType.MOVIE)
            PillButton("Series", onClick = { type = ContentType.SERIES }, selected = type == ContentType.SERIES)
            PillButton("Hide all", onClick = { vm.store.updatePrefs { p -> p.copy(hiddenCats = p.hiddenCats + cats.map { catKey(type, it.id) }) } })
            PillButton("Show all", onClick = { vm.store.updatePrefs { p -> p.copy(hiddenCats = p.hiddenCats - cats.map { catKey(type, it.id) }.toSet()) } })
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (type == ContentType.LIVE && prefs.hiddenChannels.isNotEmpty()) {
                item { Text("Hidden channels", style = T.meta) }
                items(prefs.hiddenChannels.toList(), key = { "h_$it" }) { k ->
                    val ch = catalog.channels.firstOrNull { it.key == k }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(ch?.name ?: k, style = T.title.copy(color = C.Faint), modifier = Modifier.width(360.dp))
                        PillButton("Show again", onClick = { vm.store.updatePrefs { p -> p.copy(hiddenChannels = p.hiddenChannels - k) } })
                    }
                }
                item { Spacer(Modifier.height(8.dp)); Text("Categories", style = T.meta) }
            }
            items(cats, key = { it.id }) { c ->
                val k = catKey(type, c.id)
                val hidden = k in prefs.hiddenCats
                val locked = k in prefs.lockedCats
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(displayCategory(c.name) + "  ·  ${counts[c.id] ?: 0}", style = T.title.copy(color = if (hidden) C.Faint else C.Text), modifier = Modifier.width(360.dp))
                    PillButton(if (hidden) "Hidden" else "Shown", selected = !hidden, onClick = {
                        vm.store.updatePrefs { p -> p.copy(hiddenCats = if (hidden) p.hiddenCats - k else p.hiddenCats + k) }
                    })
                    PillButton(if (locked) "Locked" else "Unlocked", selected = locked, onClick = {
                        vm.store.updatePrefs { p -> p.copy(lockedCats = if (locked) p.lockedCats - k else p.lockedCats + k) }
                    })
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
}

@Composable
fun DiagnosticsScreen() {
    val lines = remember { DiagLog.snapshot().reversed() }
    val first = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(start = 32.dp, top = 24.dp, end = 32.dp)) {
        Text("Diagnostics", style = T.h1)
        Text("Usernames and passwords are removed from this log. Take a photo of it if you need help with a problem.", style = T.body)
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(lines.size) { i ->
                // Each line is focusable so the remote can scroll the list.
                Focusable(
                    onClick = {}, modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth(),
                    color = C.Ink, focusedColor = C.Surface, scale = 1f, ring = false,
                ) { Text(lines[i], style = T.meta.copy(color = C.Text2), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) }
            }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
}
