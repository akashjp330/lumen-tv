package tv.lumen.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lumen.core.Json
import lumen.core.UrlConnectionFetcher
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Optional artwork lookup on TMDB (free key from themoviedb.org) for movies and
 * series whose provider artwork is missing.
 */
object Tmdb {
    private val http = UrlConnectionFetcher()
    private val cache = ConcurrentHashMap<String, String>()
    private const val NONE = "-"
    private val yearInName = Regex("""\((\d{4})\)""")
    private val prefix = Regex("""^\s*[A-Z]{2,3}\s*[:|\-]\s*""")

    data class Art(val poster: String?, val backdrop: String?)

    suspend fun art(apiKey: String?, name: String, isSeries: Boolean): Art? {
        if (apiKey.isNullOrBlank()) return null
        val key = (if (isSeries) "tv:" else "mv:") + name
        cache[key]?.let { return if (it == NONE) null else decode(it) }
        return withContext(Dispatchers.IO) {
            runCatching {
                val year = yearInName.find(name)?.groupValues?.get(1)
                val clean = prefix.replace(yearInName.replace(name, ""), "").trim()
                val kind = if (isSeries) "tv" else "movie"
                var url = "https://api.themoviedb.org/3/search/$kind?api_key=${enc(apiKey)}&query=${enc(clean)}"
                if (year != null) url += if (isSeries) "&first_air_date_year=$year" else "&year=$year"
                val first = Json.parseObject(http.getText(url)).objList("results").firstOrNull()
                val art = Art(
                    first?.text("poster_path")?.let { "https://image.tmdb.org/t/p/w342$it" },
                    first?.text("backdrop_path")?.let { "https://image.tmdb.org/t/p/w1280$it" },
                )
                cache[key] = if (art.poster == null && art.backdrop == null) NONE else "${art.poster ?: ""}|${art.backdrop ?: ""}"
                art.takeIf { it.poster != null || it.backdrop != null }
            }.getOrNull()
        }
    }

    private fun decode(v: String): Art {
        val (p, b) = v.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return Art(p.ifBlank { null }, b.ifBlank { null })
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
