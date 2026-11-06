package lumen.core

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

data class EpgChannel(val id: String, val names: List<String>, val icon: String?)

class XmltvData(val channels: List<EpgChannel>, val programs: Map<String, List<Program>>) {
    val programCount get() = programs.values.sumOf { it.size }
}

/**
 * Streaming XMLTV parser. Provider guide files are often 50–200 MB, so it never
 * builds a DOM and drops programmes outside [fromMs, toMs].
 */
object XmltvParser {

    fun parse(
        input: InputStream,
        fromMs: Long = Long.MIN_VALUE,
        toMs: Long = Long.MAX_VALUE,
        /** If non-null, only programmes for these XMLTV channel ids (lower-cased) are kept... */
        keepChannelIds: Set<String>? = null,
        /** ...plus channels declared in the file that this accepts (decided as <channel> elements stream past). */
        acceptChannel: ((EpgChannel) -> Boolean)? = null,
        /**
         * Programmes that ended before [nowMs] are only worth keeping on channels with
         * catch-up. When set, past programmes are kept only for ids in [pastIds] or
         * channels [acceptPast] accepts.
         */
        nowMs: Long? = null,
        pastIds: Set<String>? = null,
        acceptPast: ((EpgChannel) -> Boolean)? = null,
        /** When set, programmes are handed here (e.g. written to a database) instead of kept in memory. */
        onProgram: ((Program) -> Unit)? = null,
    ): XmltvData {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        trySet(factory, "http://xml.org/sax/features/external-general-entities", false)
        trySet(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        trySet(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val handler = Handler(fromMs, toMs, keepChannelIds, acceptChannel, nowMs, pastIds, acceptPast, onProgram)
        val parser = factory.newSAXParser()
        val source = InputSource(input)
        try {
            parser.parse(source, handler)
        } catch (e: org.xml.sax.SAXParseException) {
            // Truncated downloads are common; keep what was parsed so far if it's useful.
            if (handler.programs.isEmpty() && handler.streamed == 0) throw e
        }
        val sorted = handler.programs.mapValues { (_, l) -> l.sortedBy { it.startMs } }
        return XmltvData(handler.channels, sorted)
    }

    private fun trySet(f: SAXParserFactory, feature: String, v: Boolean) {
        try { f.setFeature(feature, v) } catch (_: Exception) { }
    }

    private class Handler(
        val fromMs: Long,
        val toMs: Long,
        val keep: Set<String>?,
        val accept: ((EpgChannel) -> Boolean)?,
        val nowMs: Long?,
        val pastIds: Set<String>?,
        val acceptPast: ((EpgChannel) -> Boolean)?,
        val onProgram: ((Program) -> Unit)?,
    ) : DefaultHandler() {
        var streamed = 0
        private val pastAccepted = HashSet<String>()
        private fun pastWanted(id: String) = nowMs == null || pastIds?.contains(id) == true || id in pastAccepted
        val channels = ArrayList<EpgChannel>()
        private val accepted = HashSet<String>()
        private val filtering = keep != null || accept != null

        private fun wanted(id: String) = !filtering || (keep?.contains(id) == true) || id in accepted
        val programs = HashMap<String, MutableList<Program>>()

        private val text = StringBuilder()
        private var capture = false

        // current <channel>
        private var chId: String? = null
        private var chNames = ArrayList<String>()
        private var chIcon: String? = null

        // current <programme>
        private var inProg = false
        private var pChannel: String? = null
        private var pStart = 0L
        private var pStop = 0L
        private var pTitle: String? = null
        private var pDesc: String? = null
        private var pCat: String? = null

        override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
            when (qName) {
                "channel" -> if (!inProg) {
                    chId = a.getValue("id")?.trim()
                    chNames = ArrayList(); chIcon = null
                }
                "programme" -> {
                    val ch = a.getValue("channel")?.trim()?.lowercase()
                    val start = parseTime(a.getValue("start"))
                    val stop = parseTime(a.getValue("stop")) ?: start?.plus(30 * 60_000L)
                    val wanted = ch != null && start != null && stop != null &&
                        stop > fromMs && start < toMs && wanted(ch) &&
                        (nowMs == null || stop > nowMs || pastWanted(ch))
                    inProg = true
                    pChannel = if (wanted) ch else null
                    pStart = start ?: 0; pStop = stop ?: 0
                    pTitle = null; pDesc = null; pCat = null
                }
                "display-name", "title", "desc", "category" -> {
                    capture = (qName == "display-name" && chId != null && !inProg) || (inProg && pChannel != null)
                    text.setLength(0)
                }
                "icon" -> if (chId != null && !inProg && chIcon == null) chIcon = a.getValue("src")
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (capture) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "display-name" -> if (capture && !inProg) text.toString().trim().takeIf { it.isNotEmpty() }?.let { chNames.add(it) }
                "title" -> if (capture && pTitle == null) pTitle = text.toString().trim()
                "desc" -> if (capture && pDesc == null) pDesc = text.toString().trim().let { if (it.length > 400) it.take(397) + "…" else it }
                "category" -> if (capture && pCat == null) pCat = text.toString().trim()
                "channel" -> if (!inProg) {
                    chId?.let {
                        val c = EpgChannel(it, chNames, chIcon)
                        channels.add(c)
                        if (accept?.invoke(c) == true) accepted.add(it.lowercase())
                        if (acceptPast?.invoke(c) == true) pastAccepted.add(it.lowercase())
                    }
                    chId = null
                }
                "programme" -> {
                    val c = pChannel
                    if (c != null) {
                        val p = Program(c, pStart, pStop, pTitle ?: "", pDesc?.takeIf { it.isNotEmpty() }, pCat)
                        val sink = onProgram
                        if (sink != null) { sink(p); streamed++ } else programs.getOrPut(c) { ArrayList() }.add(p)
                    }
                    inProg = false; pChannel = null
                }
            }
            if (qName == "display-name" || qName == "title" || qName == "desc" || qName == "category") capture = false
        }
    }

    /**
     * XMLTV timestamps: "YYYYMMDDhhmmss +hhmm" (seconds and offset optional).
     * Hand-rolled because this runs hundreds of thousands of times per refresh.
     */
    fun parseTime(raw: String?): Long? {
        if (raw == null) return null
        val s = raw.trim()
        if (s.length < 12) return null
        fun n(from: Int, len: Int): Int? {
            if (from + len > s.length) return null
            var v = 0
            for (i in from until from + len) {
                val d = s[i] - '0'
                if (d !in 0..9) return null
                v = v * 10 + d
            }
            return v
        }
        val year = n(0, 4) ?: return null
        val month = n(4, 2) ?: return null
        val day = n(6, 2) ?: return null
        val hour = n(8, 2) ?: return null
        val minute = n(10, 2) ?: return null
        val second = if (s.length >= 14 && s[12].isDigit()) n(12, 2) ?: 0 else 0
        val epochDay = daysFromCivil(year, month, day)
        var ms = ((epochDay * 24 + hour) * 60 + minute) * 60_000L + second * 1000L

        val rest = s.substring(minOf(s.length, if (s.length >= 14 && s[12].isDigit()) 14 else 12)).trim()
        if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
            val oh = rest.substring(1, 3).toIntOrNull() ?: 0
            val om = rest.substring(3, 5).toIntOrNull() ?: 0
            val off = (oh * 60 + om) * 60_000L
            ms -= if (rest[0] == '+') off else -off
        }
        return ms
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm). */
    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }
}
