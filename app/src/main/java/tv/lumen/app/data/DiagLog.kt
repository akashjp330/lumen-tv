package tv.lumen.app.data

import android.util.Log
import lumen.core.LogScrubber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory log for the "Diagnostics" screen. Everything is scrubbed of credentials. */
object DiagLog {
    private const val MAX = 300
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun log(msg: String, t: Throwable? = null) {
        val text = LogScrubber.scrub(
            msg + (t?.let { ": ${it.javaClass.simpleName} ${it.message ?: ""}" } ?: "")
        )
        Log.i("LumenTV", text)
        lines.addLast("${fmt.format(Date())}  $text")
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}
