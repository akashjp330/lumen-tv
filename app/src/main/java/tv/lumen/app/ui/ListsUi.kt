package tv.lumen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import lumen.core.Channel
import tv.lumen.app.MainViewModel
import tv.lumen.app.data.FAVORITES_ID
import tv.lumen.app.data.RepoState

fun fmtWatched(sec: Long): String {
    val m = sec / 60
    return when {
        m < 60 -> "${m}m watched"
        else -> "${m / 60}h ${m % 60}m watched"
    }
}

/**
 * The ≡ menu for a channel: favorites, every custom list, make a new list, hide.
 * Opened from any channel tile, guide row or the player.
 */
@Composable
fun ChannelMenu(vm: MainViewModel, ch: Channel) {
    val prefs by vm.store.prefs.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    val nameFr = remember { FocusRequester() }
    val watched = prefs.watchSec[ch.key]

    Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.width(360.dp).fillMaxHeight().background(C.Surface).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(ch, Modifier.size(64.dp, 40.dp).clip(RoundedCornerShape(8.dp)).background(C.Surface2), monogramSize = 28.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    OneLine(ch.name, T.h2)
                    Text("Ch ${ch.number}" + (watched?.let { " · " + fmtWatched(it) } ?: ""), style = T.meta)
                }
            }
            Spacer(Modifier.height(14.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    MenuRow("Watch now", Modifier.focusRequester(first)) { vm.closeChannelMenu(); vm.playChannel(ch) }
                }
                item { Text("Add to", style = T.meta, modifier = Modifier.padding(top = 6.dp)) }
                items(prefs.allLists, key = { it.id }) { list ->
                    val inIt = ch.key in list.keys
                    MenuRow((if (inIt) "✓  " else "+  ") + list.name, Modifier) {
                        vm.store.toggleInList(list.id, ch.key)
                    }
                }
                item {
                    if (!naming) {
                        MenuRow("+  New list…", Modifier) { naming = true }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = newName, onValueChange = { newName = it.take(30) }, singleLine = true,
                                label = { Text("List name, e.g. Sports") },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = C.Text, unfocusedTextColor = C.Text,
                                    focusedBorderColor = LocalAccent.current, unfocusedBorderColor = C.Line,
                                ),
                                modifier = Modifier.fillMaxWidth().focusRequester(nameFr),
                            )
                            PillButton("Create and add ${ch.name.take(18)}", primary = true, onClick = {
                                if (newName.isNotBlank()) {
                                    vm.store.createList(newName, ch.key)
                                    naming = false; newName = ""
                                }
                            })
                            LaunchedEffect(Unit) { delay(50); runCatching { nameFr.requestFocus() } }
                        }
                    }
                }
                item { Spacer(Modifier.height(6.dp)) }
                item {
                    MenuRow("Hide this channel", Modifier) {
                        vm.store.updatePrefs { it.copy(hiddenChannels = it.hiddenChannels + ch.key) }
                        vm.closeChannelMenu()
                        vm.showBanner("${ch.name} hidden. Show it again in Settings → Categories.")
                    }
                }
                item { MenuRow("Done", Modifier) { vm.closeChannelMenu() } }
            }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}

@Composable
fun MenuRow(label: String, modifier: Modifier, onClick: () -> Unit) {
    Focusable(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(36.dp),
        color = C.Surface2, focusedColor = C.Text, scale = 1.02f, ring = false,
    ) { f ->
        OneLine(label, T.title.copy(color = if (f) C.Ink else C.Text), Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp))
    }
}

/** Settings → My lists: rename, reorder, delete lists; reorder or remove channels in each. */
@Composable
fun ManageListsScreen(vm: MainViewModel, repo: RepoState) {
    val catalog = repo.catalog ?: return
    val prefs by vm.store.prefs.collectAsStateWithLifecycle()
    val byKey = remember(catalog) { catalog.channels.associateBy { it.key } }
    var selected by remember { mutableStateOf(FAVORITES_ID) }
    val list = prefs.allLists.firstOrNull { it.id == selected } ?: prefs.allLists.first()
    var rename by remember(list.id) { mutableStateOf(list.name) }
    val first = remember { FocusRequester() }

    Row(Modifier.fillMaxSize().padding(start = 24.dp, top = 24.dp, end = 24.dp)) {
        Column(Modifier.width(220.dp).fillMaxHeight()) {
            Text("My lists", style = T.h1)
            Text("Press ≡ on any channel to add it to a list.", style = T.meta)
            Spacer(Modifier.height(10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(prefs.allLists, key = { it.id }) { l ->
                    Focusable(
                        onClick = { selected = l.id },
                        modifier = (if (l.id == FAVORITES_ID) Modifier.focusRequester(first) else Modifier).fillMaxWidth().height(34.dp),
                        color = if (l.id == list.id) C.Raised else C.Ink, focusedColor = C.Text, scale = 1f, ring = false,
                        onFocus = { if (it) selected = l.id },
                    ) { f ->
                        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            OneLine(l.name, T.title.copy(color = if (f) C.Ink else C.Text), Modifier.weight(1f))
                            Text("${l.keys.size}", style = T.meta.copy(color = if (f) C.Ink else C.Muted))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (list.id != FAVORITES_ID) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = rename, onValueChange = { rename = it.take(30) }, singleLine = true, label = { Text("List name") },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = C.Text, unfocusedTextColor = C.Text,
                            focusedBorderColor = LocalAccent.current, unfocusedBorderColor = C.Line),
                        modifier = Modifier.width(240.dp),
                    )
                    PillButton("Rename", onClick = { vm.store.renameList(list.id, rename); vm.showBanner("Renamed") })
                    PillButton("Move up", onClick = { vm.store.moveList(list.id, -1) })
                    PillButton("Move down", onClick = { vm.store.moveList(list.id, +1) })
                    PillButton("Delete list", onClick = { vm.store.deleteList(list.id); selected = FAVORITES_ID })
                }
            } else {
                Text("Favorites", style = T.h2)
            }
            Spacer(Modifier.height(10.dp))
            if (list.keys.isEmpty()) {
                Text("No channels yet. Press ≡ on a channel anywhere in the app and choose this list.", style = T.body)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                items(list.keys, key = { it }) { k ->
                    val ch = byKey[k]
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(ch?.number?.toString() ?: "", style = T.meta, modifier = Modifier.width(40.dp))
                        OneLine(ch?.name ?: "Channel no longer offered", T.title, Modifier.width(280.dp))
                        PillButton("▲", onClick = { vm.store.moveInList(list.id, k, -1) })
                        PillButton("▼", onClick = { vm.store.moveInList(list.id, k, +1) })
                        PillButton("Remove", onClick = { vm.store.toggleInList(list.id, k) })
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
}

/** Side panel listing categories with channel counts, like the provider's own app. */
@Composable
fun CategoryPicker(
    title: String,
    items: List<Triple<String, String, Int>>,
    selected: String?,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    androidx.activity.compose.BackHandler { onClose() }
    val first = remember { FocusRequester() }
    val startIndex = items.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (startIndex - 3).coerceAtLeast(0))
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.width(380.dp).fillMaxHeight().background(C.Surface).padding(20.dp)) {
            Text(title, style = T.h2)
            Spacer(Modifier.height(10.dp))
            LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(items.size) { i ->
                    val (id, name, count) = items[i]
                    Focusable(
                        onClick = { onPick(id) },
                        modifier = (if (i == startIndex) Modifier.focusRequester(first) else Modifier).fillMaxWidth().height(36.dp),
                        color = if (id == selected) C.Raised else C.Surface2, focusedColor = C.Text, scale = 1.02f, ring = false,
                    ) { f ->
                        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            OneLine(name, T.title.copy(color = if (f) C.Ink else C.Text), Modifier.weight(1f))
                            Text("$count", style = T.meta.copy(color = if (f) C.Ink else C.Muted))
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}

fun fmtAgo(ms: Long, now: Long): String {
    if (ms <= 0) return "Not updated yet"
    val min = (now - ms) / 60_000
    return when {
        min < 2 -> "Updated just now"
        min < 60 -> "Updated $min min ago"
        min < 24 * 60 -> "Updated ${min / 60}h ago"
        else -> "Updated ${min / (24 * 60)}d ago"
    }
}

/**
 * One-click refresh: re-downloads channels, movies, series and the guide, picking
 * up new channels and changed stream links. Shows progress while it runs.
 */
@Composable
fun RefreshButton(vm: MainViewModel, repo: RepoState, modifier: Modifier = Modifier) {
    val now by rememberNow(30_000)
    val busy = repo.refreshing || repo.guideLoading
    val label = when {
        repo.refreshing && !repo.guideLoading -> "Refreshing channels…"
        repo.guideLoading -> if (repo.guideProgress > 0) "Updating guide · ${"%,d".format(repo.guideProgress)}" else "Updating guide…"
        else -> "⟳  Refresh"
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (busy) androidx.compose.material3.CircularProgressIndicator(
            color = LocalAccent.current, modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
        )
        Column(horizontalAlignment = Alignment.End) {
            PillButton(label, onClick = {
                if (busy) vm.showBanner("Already refreshing — the old guide stays usable until the new one is ready")
                else { vm.repo.refresh(); vm.showBanner("Refreshing channels, movies, series and guide") }
            })
            Text(fmtAgo(repo.lastRefreshMs, now), style = T.meta.copy(color = C.Faint), modifier = Modifier.padding(top = 2.dp, end = 6.dp))
        }
    }
}

@Composable
fun Avatar(p: tv.lumen.app.data.Profile, size: androidx.compose.ui.unit.Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape).background(Color(p.color)),
        contentAlignment = Alignment.Center,
    ) { Text(p.name.take(1).uppercase(), style = T.h2.copy(color = C.Ink, fontSize = (size.value * 0.42f).sp)) }
}

/** "Who's watching?": switch profiles, or add one. */
@Composable
fun ProfileMenu(vm: MainViewModel) {
    val profiles by vm.store.profiles.collectAsStateWithLifecycle()
    val current by vm.store.profileId.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    val nameFr = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color(0xE60B0D12)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Who's watching?", style = T.h1)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                profiles.forEachIndexed { i, p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Focusable(
                            onClick = { vm.switchProfile(p.id) },
                            modifier = (if (p.id == current || (i == 0 && profiles.none { it.id == current })) Modifier.focusRequester(first) else Modifier).size(84.dp),
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = Color(p.color), scale = 1.1f,
                        ) { Text(p.name.take(1).uppercase(), style = T.hero.copy(color = C.Ink), modifier = Modifier.align(Alignment.Center)) }
                        Spacer(Modifier.height(8.dp))
                        Text(p.name + if (p.id == current) "  ✓" else "", style = T.title)
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Focusable(
                        onClick = { adding = true },
                        modifier = Modifier.size(84.dp),
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = C.Surface2, focusedColor = C.Raised, scale = 1.1f,
                    ) { Text("+", style = T.hero.copy(color = C.Muted), modifier = Modifier.align(Alignment.Center)) }
                    Spacer(Modifier.height(8.dp))
                    Text("Add profile", style = T.title.copy(color = C.Muted))
                }
            }
            if (adding) {
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(20) }, singleLine = true, label = { Text("Name") },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = C.Text, unfocusedTextColor = C.Text,
                            focusedBorderColor = LocalAccent.current, unfocusedBorderColor = C.Line),
                        modifier = Modifier.width(260.dp).focusRequester(nameFr),
                    )
                    PillButton("Create", primary = true, onClick = {
                        if (name.isNotBlank()) { val p = vm.store.addProfile(name); adding = false; name = ""; vm.switchProfile(p.id) }
                    })
                }
                LaunchedEffect(Unit) { delay(50); runCatching { nameFr.requestFocus() } }
            }
            Spacer(Modifier.height(20.dp))
            Text("Rename, recolor or delete profiles in Settings.", style = T.meta)
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}

/** Shows the address to open on a phone, while the TV serves the backup / sign-in page. */
@Composable
fun PhoneLinkPanel(vm: MainViewModel, modifier: Modifier = Modifier) {
    val link = remember {
        tv.lumen.app.data.PhoneLink(vm.store, onRestored = { vm.afterRestore() }, onLogin = { s, u, p -> vm.loginFromPhone(s, u, p) })
    }
    var address by remember { mutableStateOf<String?>(null) }
    var tried by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        address = link.start(); tried = true
        onDispose { link.stop() }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            address != null -> {
                Text("On a phone or computer connected to the same Wi-Fi, open:", style = T.body)
                Text(address!!.removePrefix("http://"), style = T.hero.copy(color = LocalAccent.current))
                Text("You'll be able to download or restore a backup, and type your provider login. " +
                    "This page only works while this screen is open.", style = T.meta)
            }
            tried -> Text("This TV doesn't seem to be on a home network right now, so the phone page can't start.", style = T.body.copy(color = C.Live))
        }
    }
}

@Composable
fun PhoneLinkScreen(vm: MainViewModel) {
    val first = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(start = 32.dp, top = 24.dp, end = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Use your phone", style = T.h1)
        PhoneLinkPanel(vm)
        PillButton("Done", onClick = { vm.back() }, modifier = Modifier.focusRequester(first))
    }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
}
