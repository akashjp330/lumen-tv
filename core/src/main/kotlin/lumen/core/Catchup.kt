package lumen.core

import java.util.Calendar
import java.util.TimeZone

/** Catch-up ("watch from start" / past programmes) URL building. */
object Catchup {

    fun isAvailable(ch: Channel, p: Program, nowMs: Long): Boolean {
        if (ch.catchupDays <= 0) return false
        if (p.startMs > nowMs) return false
        return nowMs - p.startMs <= ch.catchupDays * 86_400_000L
    }

    /**
     * M3U catch-up following the conventions used by Kodi/TiviMate playlists:
     * "default" replaces the stream URL with catchup-source, "append" appends it,
     * "shift"/"timeshift" adds utc/lutc query parameters.
     */
    fun m3uUrl(ch: Channel, p: Program, nowMs: Long): String? {
        val mode = ch.catchupMode?.lowercase()?.trim()
        val tpl = ch.catchupTemplate
        return when (mode) {
            "shift", "timeshift" -> {
                val sep = if ('?' in ch.streamUrl) '&' else '?'
                "${ch.streamUrl}${sep}utc=${p.startMs / 1000}&lutc=${nowMs / 1000}"
            }
            "append" -> tpl?.let { ch.streamUrl + fill(it, p, nowMs) }
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> flussonic(ch.streamUrl, p)
            else -> tpl?.let { t ->
                val filled = fill(t, p, nowMs)
                if (filled.startsWith("http")) filled
                else ch.streamUrl + filled // relative templates behave like append
            }
        }
    }

    private fun flussonic(url: String, p: Program): String? {
        val start = p.startMs / 1000
        val dur = p.durationMs / 1000
        return when {
            url.contains("/index.m3u8") -> url.replace("/index.m3u8", "/index-$start-$dur.m3u8")
            url.contains("/video.m3u8") -> url.replace("/video.m3u8", "/video-$start-$dur.m3u8")
            url.contains("/mpegts") -> url.replace("/mpegts", "/timeshift_abs-$start.ts")
            else -> null
        }
    }

    fun fill(template: String, p: Program, nowMs: Long): String {
        val start = p.startMs / 1000
        val end = p.stopMs / 1000
        val now = nowMs / 1000
        val durSec = (p.stopMs - p.startMs) / 1000
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = p.startMs }
        fun two(v: Int) = v.toString().padStart(2, '0')
        var s = template
        // {duration:60} style divisors (duration in minutes, etc.)
        s = Regex("""\$?\{duration:(\d+)\}""").replace(s) { (durSec / (it.groupValues[1].toLongOrNull()?.coerceAtLeast(1) ?: 1)).toString() }
        // {utc:Y-m-d} style formats
        s = Regex("""\$?\{(utc|start|utcend|end|lutc|now|timestamp):([^}]+)\}""").replace(s) { m ->
            val t = when (m.groupValues[1]) { "utcend", "end" -> end; "lutc", "now", "timestamp" -> now; else -> start }
            formatTime(m.groupValues[2], t * 1000)
        }
        val simple = mapOf(
            "utc" to start, "start" to start, "timestamp" to now, "lutc" to now, "now" to now,
            "utcend" to end, "end" to end, "duration" to durSec, "offset" to (now - start),
        )
        for ((k, v) in simple) s = s.replace("\${$k}", v.toString()).replace("{$k}", v.toString())
        s = s.replace("{Y}", cal.get(Calendar.YEAR).toString())
            .replace("{m}", two(cal.get(Calendar.MONTH) + 1))
            .replace("{d}", two(cal.get(Calendar.DAY_OF_MONTH)))
            .replace("{H}", two(cal.get(Calendar.HOUR_OF_DAY)))
            .replace("{M}", two(cal.get(Calendar.MINUTE)))
            .replace("{S}", two(cal.get(Calendar.SECOND)))
        return s
    }

    private fun formatTime(fmt: String, ms: Long): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
        fun two(v: Int) = v.toString().padStart(2, '0')
        val sb = StringBuilder()
        for (c in fmt) sb.append(
            when (c) {
                'Y' -> cal.get(Calendar.YEAR).toString()
                'm' -> two(cal.get(Calendar.MONTH) + 1)
                'd' -> two(cal.get(Calendar.DAY_OF_MONTH))
                'H' -> two(cal.get(Calendar.HOUR_OF_DAY))
                'M' -> two(cal.get(Calendar.MINUTE))
                'S' -> two(cal.get(Calendar.SECOND))
                else -> c.toString()
            }
        )
        return sb.toString()
    }
}
