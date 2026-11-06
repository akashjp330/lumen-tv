package tv.lumen.app.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.lumen.app.MainViewModel
import tv.lumen.app.Route
import tv.lumen.app.data.RepoState

@Composable
fun AppRoot(vm: MainViewModel, onExit: () -> Unit) {
    val repo by vm.repo.state.collectAsStateWithLifecycle()
    val settings by vm.store.settings.collectAsStateWithLifecycle()

    BackHandler { if (!vm.back()) onExit() }

    LumenTheme {
        CompositionLocalProvider(LocalAccent provides Color(settings.accent)) {
            Box(Modifier.fillMaxSize().background(C.Ink)) {
                when {
                    repo.source == null -> LoginScreen(vm, firstRun = true)
                    repo.catalog == null && repo.error != null -> LoadErrorScreen(vm, repo)
                    repo.catalog == null -> Splash(repo.loading)
                    else -> MainArea(vm, repo)
                }
                vm.channelMenu?.let { ChannelMenu(vm, it) }
                vm.pinPrompt?.let { PinDialog(vm) }
                vm.banner?.let { BannerView(vm, it) }
            }
        }
    }
}

@Composable
private fun MainArea(vm: MainViewModel, repo: RepoState) {
    val route = vm.current
    key(route) {
        when (route) {
            is Route.Player -> PlayerScreen(vm, route.req)
            is Route.Multiview -> MultiviewScreen(vm, route.keys)
            else -> Row(Modifier.fillMaxSize()) {
                NavRail(vm, route)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (route) {
                        Route.Home -> HomeScreen(vm, repo)
                        Route.Guide -> GuideScreen(vm, repo)
                        Route.Movies -> MoviesScreen(vm, repo)
                        Route.Series -> SeriesScreen(vm, repo)
                        Route.Search -> SearchScreen(vm, repo)
                        Route.Settings -> SettingsScreen(vm, repo)
                        Route.Diagnostics -> DiagnosticsScreen()
                        Route.ManageCategories -> ManageCategoriesScreen(vm, repo)
                        Route.AddSource -> LoginScreen(vm, firstRun = false)
                        Route.ManageLists -> ManageListsScreen(vm, repo)
                        is Route.MovieDetail -> MovieDetailScreen(vm, route.movie)
                        is Route.SeriesDetail -> SeriesDetailScreen(vm, route.series)
                        else -> Unit
                    }
                }
            }
        }
    }
}

private data class RailItem(val route: Route, val icon: ImageVector, val label: String)

@Suppress("DEPRECATION")
private val railItems = listOf(
    RailItem(Route.Home, Icons.Filled.Home, "Home"),
    RailItem(Route.Guide, Icons.Filled.DateRange, "Guide"),
    RailItem(Route.Movies, Icons.Filled.PlayArrow, "Movies"),
    RailItem(Route.Series, Icons.Filled.List, "Series"),
    RailItem(Route.Search, Icons.Filled.Search, "Search"),
    RailItem(Route.Settings, Icons.Filled.Settings, "Settings"),
)

@Composable
private fun NavRail(vm: MainViewModel, current: Route) {
    val section = when (current) {
        is Route.MovieDetail -> Route.Movies
        is Route.SeriesDetail -> Route.Series
        Route.Diagnostics, Route.ManageCategories, Route.AddSource, Route.ManageLists -> Route.Settings
        else -> current
    }
    Column(
        Modifier.width(64.dp).fillMaxHeight().background(C.Rail).padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(LocalAccent.current),
            contentAlignment = Alignment.Center,
        ) { Text("L", style = T.h2.copy(color = C.Ink)) }
        Spacer(Modifier.height(16.dp))
        for (item in railItems) {
            if (item.route == Route.Settings) Spacer(Modifier.weight(1f))
            val selected = item.route == section
            Focusable(
                onClick = { if (!selected || current != item.route) vm.go(item.route) },
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(12.dp),
                color = if (selected) C.Raised else Color.Transparent,
                focusedColor = C.Text,
                ring = false,
            ) { focused ->
                Icon(
                    item.icon, contentDescription = item.label,
                    tint = when { focused -> C.Ink; selected -> C.Text; else -> C.Muted },
                    modifier = Modifier.align(Alignment.Center).size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun Splash(step: String?) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = LocalAccent.current, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(16.dp))
        Text(step ?: "Loading", style = T.body)
    }
}

@Composable
private fun LoadErrorScreen(vm: MainViewModel, repo: RepoState) {
    val fr = remember { FocusRequester() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Couldn't load your channels", style = T.h1)
        Spacer(Modifier.height(10.dp))
        Text(repo.error ?: "", style = T.body, modifier = Modifier.widthIn(max = 460.dp))
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Try again", onClick = { vm.repo.start() }, primary = true, modifier = Modifier.focusRequester(fr))
            PillButton("Change login", onClick = { vm.repo.signOut() })
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

@Composable
private fun BannerView(vm: MainViewModel, b: tv.lumen.app.Banner) {
    val fr = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomEnd) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).background(C.Raised).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(b.text, style = T.title)
            if (b.action != null) {
                PillButton(b.actionLabel ?: "OK", onClick = b.action, primary = true, modifier = Modifier.focusRequester(fr))
                PillButton("Dismiss", onClick = { vm.dismissBanner() })
                LaunchedEffect(b) { runCatching { fr.requestFocus() } }
            }
        }
    }
}

@Composable
private fun PinDialog(vm: MainViewModel) {
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    fun press(d: String) {
        if (entered.length >= 8) return
        entered += d
        wrong = false
        // Unlock as soon as the digits match; longer PINs keep collecting until OK.
        if (entered.length >= 4 && vm.store.checkPin(entered)) vm.submitPin(entered)
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000))
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                if (code in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9) { press((code - AndroidKeyEvent.KEYCODE_0).toString()); true } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(C.Surface).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Enter PIN", style = T.h2)
            Spacer(Modifier.height(8.dp))
            Text(if (entered.isEmpty()) "• • • •" else "●".repeat(entered.length), style = T.h1)
            if (wrong) Text("Wrong PIN", style = T.body.copy(color = C.Live))
            Spacer(Modifier.height(12.dp))
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK"))
            for ((ri, row) in rows.withIndex()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    for ((ci, k) in row.withIndex()) {
                        Focusable(
                            onClick = {
                                when (k) {
                                    "⌫" -> entered = entered.dropLast(1)
                                    "OK" -> if (!vm.submitPin(entered)) { wrong = true; entered = "" }
                                    else -> press(k)
                                }
                            },
                            modifier = Modifier.size(52.dp, 40.dp).then(if (ri == 0 && ci == 0) Modifier.focusRequester(first) else Modifier),
                            color = C.Surface2, focusedColor = C.Text, ring = false,
                        ) { f -> Text(k, style = T.h2.copy(color = if (f) C.Ink else C.Text), modifier = Modifier.align(Alignment.Center)) }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
