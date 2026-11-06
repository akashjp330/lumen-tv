package tv.lumen.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import lumen.core.Source
import tv.lumen.app.MainViewModel
import tv.lumen.app.Route
import tv.lumen.app.data.Repository
import java.util.UUID

@Composable
fun LoginScreen(vm: MainViewModel, firstRun: Boolean) {
    var xtream by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var m3u by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var phone by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }

    fun connect() {
        error = null
        val id = UUID.randomUUID().toString().take(8)
        val source = if (xtream) {
            if (server.isBlank() || user.isBlank() || pass.isBlank()) { error = "Fill in the server, username and password."; return }
            Source.Xtream(id, name.ifBlank { "My IPTV" }, server.trim(), user.trim(), pass)
        } else {
            if (!m3u.trim().startsWith("http")) { error = "Enter the full playlist link, starting with http."; return }
            Source.M3u(id, name.ifBlank { "My playlist" }, m3u.trim(), epg.trim().ifBlank { null })
        }
        busy = true
        scope.launch {
            try {
                val acct = vm.repo.verify(source)
                if (acct != null && !acct.isActive) {
                    error = "The login worked, but the account status is \"${acct.status}\"."
                } else {
                    vm.repo.addSource(source)
                    if (!firstRun) vm.go(Route.Home)
                }
            } catch (e: Exception) {
                error = Repository.friendly(e)
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(520.dp).verticalScroll(rememberScrollState()).padding(vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (firstRun) "Welcome" else "Add a provider", style = T.h1)
            Text(
                "Enter the details your IPTV provider gave you, or sign in from your phone, which is much easier than typing with the remote.",
                style = T.body,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Xtream Codes login", onClick = { xtream = true }, selected = xtream, modifier = Modifier.focusRequester(first))
                PillButton("M3U playlist link", onClick = { xtream = false }, selected = !xtream)
            }
            Field("Name (optional)", name, { name = it })
            if (xtream) {
                Field("Server address, e.g. http://example.com:8080", server, { server = it }, KeyboardType.Uri)
                Field("Username", user, { user = it })
                Field("Password", pass, { pass = it }, KeyboardType.Password, hidden = !showPass)
                PillButton(if (showPass) "Hide password" else "Show password", onClick = { showPass = !showPass })
            } else {
                Field("Playlist link (M3U)", m3u, { m3u = it }, KeyboardType.Uri)
                Field("Guide (EPG/XMLTV) link, optional", epg, { epg = it }, KeyboardType.Uri)
            }
            error?.let { Text(it, style = T.body.copy(color = C.Live)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(if (busy) "Connecting…" else "Connect", onClick = { if (!busy) connect() }, primary = true)
                if (!firstRun) PillButton("Cancel", onClick = { vm.back() })
                if (busy) CircularProgressIndicator(color = LocalAccent.current, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                PillButton(if (phone) "Hide phone sign-in" else "Sign in from my phone", onClick = { phone = !phone })
            }
            if (phone) PhoneLinkPanel(vm, Modifier.padding(top = 8.dp))
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    type: KeyboardType = KeyboardType.Text,
    hidden: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Next),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = LocalAccent.current,
            unfocusedBorderColor = C.Line,
            focusedTextColor = C.Text,
            unfocusedTextColor = C.Text,
            focusedLabelColor = C.Text2,
            unfocusedLabelColor = C.Muted,
            cursorColor = LocalAccent.current,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
