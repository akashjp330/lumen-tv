package lumen.core

/**
 * Small, dependency-free JSON reader.
 *
 * IPTV panels are loose with types: the same field can arrive as "12", 12, 12.0,
 * "" or null depending on the provider. [JsonObj] accessors therefore coerce
 * rather than fail.
 */
object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.atEnd()) throw JsonException("Trailing characters at ${p.pos}")
        return v
    }

    fun parseObject(text: String): JsonObj =
        when (val v = parse(text)) {
            is Map<*, *> -> JsonObj(v)
            else -> throw JsonException("Expected a JSON object")
        }

    /** Arrays of objects; a provider that returns an object keyed by id is tolerated too. */
    fun parseObjectList(text: String): List<JsonObj> = toObjList(parse(text))

    /** Serializes maps, lists, strings, numbers, booleans and null. */
    fun write(v: Any?): String = StringBuilder().also { writeTo(it, v) }.toString()

    private fun writeTo(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> {
                sb.append('"')
                for (c in v) when (c) {
                    '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
                }
                sb.append('"')
            }
            is Boolean, is Int, is Long -> sb.append(v.toString())
            is Number -> v.toDouble().let { d -> if (d.isFinite()) sb.append(d.toString()) else sb.append("null") }
            is JsonObj -> writeTo(sb, v.map)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(','); first = false
                    writeTo(sb, k.toString()); sb.append(':'); writeTo(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) { if (!first) sb.append(','); first = false; writeTo(sb, item) }
                sb.append(']')
            }
            else -> writeTo(sb, v.toString())
        }
    }

    fun toObjList(v: Any?): List<JsonObj> = when (v) {
        is List<*> -> v.filterIsInstance<Map<*, *>>().map { JsonObj(it) }
        is Map<*, *> -> v.values.filterIsInstance<Map<*, *>>().map { JsonObj(it) }
        else -> emptyList()
    }

    private class Parser(val s: String) {
        var pos = 0
        fun atEnd() = pos >= s.length
        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

        fun value(): Any? {
            skipWs()
            if (atEnd()) throw JsonException("Unexpected end of input")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw JsonException("Unexpected '$c' at $pos")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw JsonException("Bad literal at $pos")
            pos += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            pos++ // {
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (s.getOrNull(pos) == '}') { pos++; return m }
            while (true) {
                skipWs()
                if (s.getOrNull(pos) != '"') throw JsonException("Expected key at $pos")
                val k = str()
                skipWs()
                if (s.getOrNull(pos) != ':') throw JsonException("Expected ':' at $pos")
                pos++
                m[k] = value()
                skipWs()
                when (s.getOrNull(pos)) {
                    ',' -> pos++
                    '}' -> { pos++; return m }
                    else -> throw JsonException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun arr(): List<Any?> {
            pos++ // [
            val l = ArrayList<Any?>()
            skipWs()
            if (s.getOrNull(pos) == ']') { pos++; return l }
            while (true) {
                l.add(value())
                skipWs()
                when (s.getOrNull(pos)) {
                    ',' -> pos++
                    ']' -> { pos++; return l }
                    else -> throw JsonException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun str(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("Unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonException("Bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonException("Bad unicode escape")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val t = s.substring(start, pos)
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble()
            else t.toLongOrNull() ?: t.toDouble()
        }
    }
}

class JsonException(msg: String) : Exception(msg)

/** Lenient view over a parsed JSON object. */
class JsonObj(val map: Map<*, *>) {
    fun raw(key: String): Any? = map[key]

    fun str(key: String): String? = when (val v = map[key]) {
        null -> null
        is String -> v
        is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
        is Map<*, *>, is List<*> -> null
        else -> v.toString()
    }

    /** Non-blank string or null. */
    fun text(key: String): String? = str(key)?.trim()?.takeIf { it.isNotEmpty() }

    fun long(key: String): Long? = when (val v = map[key]) {
        is Long -> v
        is Double -> v.toLong()
        is Int -> v.toLong()
        is String -> v.trim().toLongOrNull() ?: v.trim().toDoubleOrNull()?.toLong()
        is Boolean -> if (v) 1 else 0
        else -> null
    }

    fun int(key: String): Int? = long(key)?.toInt()

    fun double(key: String): Double? = when (val v = map[key]) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    fun bool(key: String): Boolean = when (val v = map[key]) {
        is Boolean -> v
        is Number -> v.toLong() != 0L
        is String -> v.trim().let { it == "1" || it.equals("true", true) }
        else -> false
    }

    fun obj(key: String): JsonObj? = (map[key] as? Map<*, *>)?.let { JsonObj(it) }
    fun objList(key: String): List<JsonObj> = Json.toObjList(map[key])
    fun strList(key: String): List<String> = when (val v = map[key]) {
        is List<*> -> v.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
        is String -> listOf(v).filter { it.isNotBlank() }
        else -> emptyList()
    }
}
