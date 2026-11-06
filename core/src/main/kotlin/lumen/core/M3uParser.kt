package lumen.core

import java.io.BufferedReader
import java.io.Reader

data class M3uResult(
    val liveCategories: List<Category>,
    val channels: List<Channel>,
    val movieCategories: List<Category>,
    val movies: List<Movie>,
    val epgUrl: String?,
)

/** Parses extended M3U playlists (#EXTM3U / #EXTINF with tvg-* attributes). */
object M3uParser {
    private val attr = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""")

    fun parse(reader: Reader): M3uResult {
        val br = if (reader is BufferedReader) reader else BufferedReader(reader, 64 * 1024)
        var epgUrl: String? = null
        val channels = ArrayList<Channel>()
        val movies = ArrayList<Movie>()
        val liveGroups = LinkedHashSet<String>()
        val movieGroups = LinkedHashSet<String>()

        var pendingInfo: String? = null
        var pendingGroup: String? = null
        var number = 0

        br.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val a = attrs(line)
                    epgUrl = (a["url-tvg"] ?: a["x-tvg-url"] ?: a["tvg-url"])
                        ?.split(',')?.map { it.trim() }?.firstOrNull { it.startsWith("http") }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> { pendingInfo = line; pendingGroup = null }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> pendingGroup = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit // #EXTVLCOPT, #KODIPROP, comments
                else -> {
                    val info = pendingInfo
                    pendingInfo = null
                    if (info != null) {
                        // Attributes live before the title comma; split at the first comma outside quotes.
                        val a = attrs(headerPart(info))
                        val title = titlePart(info).ifBlank { a["tvg-name"] ?: "Untitled" }
                        val group = a["group-title"]?.takeIf { it.isNotBlank() } ?: pendingGroup ?: "Uncategorized"
                        val url = line
                        val lower = url.lowercase()
                        when {
                            "/series/" in lower -> Unit // series episodes are better browsed via the Xtream login
                            "/movie/" in lower -> {
                                movieGroups.add(group)
                                movies.add(
                                    Movie(
                                        id = stableKey(url), name = title,
                                        poster = a["tvg-logo"]?.takeIf { it.startsWith("http") },
                                        categoryId = group, rating = null, year = null,
                                        addedEpochSec = null, streamUrl = url,
                                    )
                                )
                            }
                            else -> {
                                number++
                                liveGroups.add(group)
                                channels.add(
                                    Channel(
                                        key = stableKey(url),
                                        number = a["tvg-chno"]?.toIntOrNull() ?: a["channel-number"]?.toIntOrNull() ?: number,
                                        name = title,
                                        logo = a["tvg-logo"]?.takeIf { it.startsWith("http") },
                                        categoryId = group,
                                        epgId = a["tvg-id"]?.takeIf { it.isNotBlank() },
                                        streamUrl = url,
                                        catchupDays = (a["catchup-days"] ?: a["timeshift"] ?: a["tvg-rec"])?.toIntOrNull()
                                            ?: if (a["catchup"] != null) 1 else 0,
                                        catchupTemplate = a["catchup-source"],
                                        catchupMode = a["catchup"] ?: a["catchup-type"],
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        return M3uResult(
            liveCategories = liveGroups.map { Category(it, it, ContentType.LIVE) },
            channels = channels,
            movieCategories = movieGroups.map { Category(it, it, ContentType.MOVIE) },
            movies = movies,
            epgUrl = epgUrl,
        )
    }

    private fun attrs(s: String): Map<String, String> =
        attr.findAll(s).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }

    /** Index of the first comma not inside double quotes. */
    private fun titleComma(info: String): Int {
        var inQuotes = false
        for (i in info.indices) {
            when (info[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return -1
    }

    private fun headerPart(info: String) = titleComma(info).let { if (it < 0) info else info.substring(0, it) }
    private fun titlePart(info: String) = titleComma(info).let { if (it < 0) "" else info.substring(it + 1).trim() }

    fun stableKey(url: String): String {
        // FNV-1a 64-bit: stable across runs and devices, unlike String.hashCode collisions on huge lists.
        var h = -0x340d631b7bdddcdbL
        for (c in url) { h = h xor c.code.toLong(); h *= 0x100000001b3L }
        return java.lang.Long.toHexString(h)
    }
}
