package tv.lumen.app.data

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * A small web page the TV serves on your home Wi-Fi while the "Use your phone"
 * screen is open. From a phone or computer on the same network you can:
 *  - download a backup of profiles, favorites, lists and settings,
 *  - restore a backup,
 *  - type the provider login instead of using the remote.
 *
 * The address includes a random code shown on the TV, and the server stops as
 * soon as you leave that screen.
 */
class PhoneLink(
    private val store: Store,
    private val onRestored: () -> Unit,
    private val onLogin: (server: String, user: String, pass: String) -> String,
) {
    private var server: ServerSocket? = null
    val code: String = (1000..9999).random().toString()
    var address: String? = null; private set

    fun start(): String? {
        if (server != null) return address
        val ip = lanAddress() ?: return null
        val sock = (PORTS).firstNotNullOfOrNull { p -> runCatching { ServerSocket(p) }.getOrNull() } ?: return null
        server = sock
        address = "http://$ip:${sock.localPort}/$code"
        thread(isDaemon = true, name = "phone-link") {
            while (!sock.isClosed) {
                val c = runCatching { sock.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(c) }; runCatching { c.close() } }
            }
        }
        DiagLog.log("Phone link started")
        return address
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(c: Socket) {
        c.soTimeout = 15_000
        val input = BufferedInputStream(c.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val l = readLine(input) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore('?')
        val len = headers["content-length"]?.toIntOrNull()?.coerceAtMost(5_000_000) ?: 0
        val body = if (len > 0) readBytes(input, len).toString(Charsets.UTF_8) else ""
        val out = c.getOutputStream()

        fun send(status: String, type: String, content: ByteArray, extra: String = "") {
            out.write(("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${content.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n$extra\r\n").toByteArray())
            out.write(content)
            out.flush()
        }
        fun html(body: String) = send("200 OK", "text/html; charset=utf-8", page(body).toByteArray())

        if (!path.startsWith("/$code")) { send("404 Not Found", "text/plain", "Not found".toByteArray()); return }
        when (path.removePrefix("/$code").ifEmpty { "/" }) {
            "/" -> html(home())
            "/backup.json" -> {
                val withLogin = parts[1].contains("login=1")
                send("200 OK", "application/json", store.exportBackup(withLogin).toByteArray(),
                    "Content-Disposition: attachment; filename=\"kash-tv-backup.json\"\r\n")
            }
            "/restore" -> if (method == "POST") {
                val msg = try { store.importBackup(body).also { onRestored() } } catch (e: Exception) { "Couldn't restore: ${e.message}" }
                html("<h2>Restore</h2><p>${esc(msg)}</p><p><a href=\"/$code\">Back</a></p>")
            } else html(home())
            "/login" -> if (method == "POST") {
                val f = form(body)
                val msg = onLogin(f["server"].orEmpty(), f["user"].orEmpty(), f["pass"].orEmpty())
                html("<h2>Sign in</h2><p>${esc(msg)}</p><p>Look at your TV.</p><p><a href=\"/$code\">Back</a></p>")
            } else html(home())
            else -> send("404 Not Found", "text/plain", "Not found".toByteArray())
        }
    }

    private fun home() = """
        <h1>KASH TV</h1>
        <h2>Back up</h2>
        <p>Saves profiles, favorites, lists, watch history and settings to a file on this phone or computer.</p>
        <p><a class="btn" href="/$code/backup.json">Download backup</a></p>
        <p><a href="/$code/backup.json?login=1">Download backup including the provider login</a><br>
        <small>Only if you'll keep the file somewhere safe: it contains your password.</small></p>
        <h2>Restore</h2>
        <p>Choose a KASH TV backup file. It replaces the profiles and lists on the TV.</p>
        <input type="file" id="f" accept=".json,application/json"> <button onclick="go()">Restore</button>
        <p id="m"></p>
        <h2>Sign in to your provider</h2>
        <form method="post" action="/$code/login">
          <label>Server address<br><input name="server" placeholder="http://example.com:8080" autocapitalize="off" autocorrect="off"></label>
          <label>Username<br><input name="user" autocapitalize="off" autocorrect="off"></label>
          <label>Password<br><input name="pass" type="password"></label>
          <button type="submit">Sign in on the TV</button>
        </form>
        <script>
        function go(){var f=document.getElementById('f').files[0];if(!f){return}
          var r=new FileReader();r.onload=function(){fetch('/$code/restore',{method:'POST',body:r.result})
          .then(function(x){return x.text()}).then(function(t){document.open();document.write(t);document.close()})};r.readAsText(f)}
        </script>
    """.trimIndent()

    private fun page(body: String) = """<!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>KASH TV</title>
        <style>body{font-family:-apple-system,system-ui,sans-serif;background:#0B0D12;color:#F2F4F8;margin:0;padding:20px;max-width:560px}
        h1{font-size:26px}h2{font-size:18px;margin-top:28px}p,small{color:#B4BCCC;line-height:1.5}a{color:#9EC1FF}
        .btn,button{display:inline-block;background:#4C8DFF;color:#0B0D12;border:0;border-radius:10px;padding:12px 18px;font-weight:700;font-size:16px;text-decoration:none}
        input{width:100%;box-sizing:border-box;font-size:16px;padding:10px;border-radius:8px;border:1px solid #2A3142;background:#151A24;color:#F2F4F8;margin:4px 0 12px}
        input[type=file]{width:auto}label{display:block;color:#B4BCCC}</style></head><body>$body</body></html>"""

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun form(body: String): Map<String, String> = body.split('&').mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
    }.toMap()

    private fun readLine(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            b.write(c)
            if (b.size() > 8192) return null
        }
    }

    private fun readBytes(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(out, got, n - got)
            if (r < 0) break
            got += r
        }
        return if (got == n) out else out.copyOf(got)
    }

    companion object {
        private val PORTS = listOf(8765, 8766, 8767, 0)

        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }
}
