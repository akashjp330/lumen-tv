package lumen.core

import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader

/**
 * Streaming reader for the huge list responses big providers send (57,000 movies
 * is a 40–80 MB JSON array). Each element is parsed, handed to [onObject], and
 * dropped, so memory holds only the app's small model objects and never the whole
 * response text.
 */
object JsonStream {

    /** Calls [onObject] for each object in a top-level array (or each value of a top-level object). */
    fun forEachObject(input: InputStream, onObject: (JsonObj) -> Unit) {
        val r = CharSource(InputStreamReader(input, Charsets.UTF_8))
        r.skipWs()
        when (val c = r.peek()) {
            '[' -> {
                r.next()
                r.skipWs()
                if (r.peek() == ']') return
                while (true) {
                    val v = r.value()
                    if (v is Map<*, *>) onObject(JsonObj(v))
                    r.skipWs()
                    when (r.next()) {
                        ',' -> continue
                        ']' -> return
                        else -> throw JsonException("Expected ',' or ']'")
                    }
                }
            }
            '{' -> {
                r.next()
                r.skipWs()
                if (r.peek() == '}') return
                while (true) {
                    r.skipWs()
                    r.string() // key
                    r.skipWs()
                    if (r.next() != ':') throw JsonException("Expected ':'")
                    val v = r.value()
                    if (v is Map<*, *>) onObject(JsonObj(v))
                    r.skipWs()
                    when (r.next()) {
                        ',' -> continue
                        '}' -> return
                        else -> throw JsonException("Expected ',' or '}'")
                    }
                }
            }
            END -> return // empty body: some panels answer nothing for empty lists
            else -> throw JsonException("The server didn't send a list (got '$c').")
        }
    }

    fun <T> mapObjects(input: InputStream, f: (JsonObj) -> T?): List<T> {
        val out = ArrayList<T>()
        forEachObject(input) { o -> f(o)?.let { out.add(it) } }
        return out
    }

    private const val END = '￿'

    /** Buffered char reader with one-char lookahead. */
    private class CharSource(private val reader: Reader) {
        private val buf = CharArray(64 * 1024)
        private var len = 0
        private var pos = 0

        fun peek(): Char {
            if (pos >= len) {
                len = reader.read(buf, 0, buf.size)
                pos = 0
                if (len <= 0) { len = 0; return END }
            }
            return buf[pos]
        }

        fun next(): Char {
            val c = peek()
            if (c != END) pos++
            return c
        }

        fun skipWs() {
            while (true) {
                val c = peek()
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '﻿') pos++ else return
            }
        }

        fun value(): Any? {
            skipWs()
            return when (val c = peek()) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                END -> throw JsonException("Unexpected end of data (download cut off?)")
                else -> if (c == '-' || c.isDigit()) num() else throw JsonException("Unexpected '$c'")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            for (ch in word) if (next() != ch) throw JsonException("Bad literal")
            return v
        }

        private fun obj(): Map<String, Any?> {
            next()
            val m = LinkedHashMap<String, Any?>(16)
            skipWs()
            if (peek() == '}') { next(); return m }
            while (true) {
                skipWs()
                if (peek() != '"') throw JsonException("Expected key")
                val k = string()
                skipWs()
                if (next() != ':') throw JsonException("Expected ':'")
                m[k] = value()
                skipWs()
                when (next()) {
                    ',' -> {}
                    '}' -> return m
                    else -> throw JsonException("Expected ',' or '}'")
                }
            }
        }

        private fun arr(): List<Any?> {
            next()
            val l = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { next(); return l }
            while (true) {
                l.add(value())
                skipWs()
                when (next()) {
                    ',' -> {}
                    ']' -> return l
                    else -> throw JsonException("Expected ',' or ']'")
                }
            }
        }

        private val sb = StringBuilder(128)

        fun string(): String {
            if (next() != '"') throw JsonException("Expected string")
            sb.setLength(0)
            while (true) {
                val c = next()
                when (c) {
                    END -> throw JsonException("Unterminated string")
                    '"' -> return sb.toString()
                    '\\' -> when (val e = next()) {
                        'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'u' -> {
                            var code = 0
                            repeat(4) { code = code * 16 + Character.digit(next(), 16).also { d -> if (d < 0) throw JsonException("Bad unicode escape") } }
                            sb.append(code.toChar())
                        }
                        END -> throw JsonException("Bad escape")
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            sb.setLength(0)
            while (true) {
                val c = peek()
                if (c.isDigit() || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') { sb.append(c); pos++ } else break
            }
            val t = sb.toString()
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
        }
    }
}
