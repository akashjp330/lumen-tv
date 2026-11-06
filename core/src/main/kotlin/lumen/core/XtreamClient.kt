package lumen.core

import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AuthException(msg: String) : Exception(msg)

/**
 * Client for the Xtream Codes "player API" that Smarters, TiviMate and most
 * IPTV panels speak.
 */
class XtreamClient(
    server: String,
    private val username: String,
    private val password: String,
    private val http: HttpFetcher = UrlConnectionFetcher(),
) {
    val base: String = normalizeServer(server)

    private val u = enc(username)
    private val p = enc(password)

    /** Path segments in stream URLs are not form-encoded; only escape what would break the path. */
    private val up = pathSeg(username)
    private val pp = pathSeg(password)

    @Volatile var serverTimezone: String? = null
        private set

    fun apiUrl(action: String? = null, extra: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder("$base/player_api.php?username=$u&password=$p")
        if (action != null) sb.append("&action=").append(action)
        extra.forEach { (k, v) -> sb.append('&').append(k).append('=').append(enc(v)) }
        return sb.toString()
    }

    // ---------- account ----------

    fun account(): AccountInfo {
        val root = try {
            Json.parseObject(http.getText(apiUrl()))
        } catch (e: HttpException) {
            if (e.code == 401 || e.code == 403) throw AuthException("The server rejected this username or password.")
            throw e
        } catch (e: JsonException) {
            throw AuthException("The server didn't answer like an Xtream Codes server. Check the server address.")
        }
        val ui = root.obj("user_info") ?: throw AuthException("The server didn't return account information.")
        if (ui.raw("auth") != null && !ui.bool("auth")) {
            throw AuthException("The server rejected this username or password.")
        }
        val tz = root.obj("server_info")?.text("timezone")
        serverTimezone = tz
        return AccountInfo(
            status = ui.text("status") ?: "Unknown",
            expiresEpochSec = ui.long("exp_date")?.takeIf { it > 0 },
            maxConnections = ui.int("max_connections"),
            activeConnections = ui.int("active_cons"),
            isTrial = ui.bool("is_trial"),
            serverTimezone = tz,
        )
    }

    // ---------- categories ----------

    fun liveCategories() = categories("get_live_categories", ContentType.LIVE)
    fun movieCategories() = categories("get_vod_categories", ContentType.MOVIE)
    fun seriesCategories() = categories("get_series_categories", ContentType.SERIES)

    private fun categories(action: String, type: ContentType): List<Category> =
        Json.parseObjectList(http.getText(apiUrl(action))).mapNotNull { o ->
            val id = o.text("category_id") ?: return@mapNotNull null
            Category(id, o.text("category_name") ?: "Other", type)
        }

    // ---------- live ----------

    fun liveChannels(preferHls: Boolean = false): List<Channel> {
        val list = Json.parseObjectList(http.getText(apiUrl("get_live_streams")))
        var fallbackNumber = 0
        return list.mapNotNull { o ->
            val id = o.long("stream_id") ?: return@mapNotNull null
            fallbackNumber++
            Channel(
                key = id.toString(),
                number = o.int("num")?.takeIf { it > 0 } ?: fallbackNumber,
                name = o.text("name") ?: "Channel $id",
                logo = o.text("stream_icon")?.takeIf { it.startsWith("http") },
                categoryId = o.text("category_id") ?: o.strList("category_ids").firstOrNull() ?: "",
                epgId = o.text("epg_channel_id"),
                streamUrl = liveUrl(id, if (preferHls) "m3u8" else "ts"),
                catchupDays = if (o.bool("tv_archive")) (o.int("tv_archive_duration") ?: 0) else 0,
                xtreamStreamId = id,
            )
        }
    }

    fun liveUrl(streamId: Long, ext: String = "ts") = "$base/live/$up/$pp/$streamId.$ext"

    // ---------- movies ----------

    fun movies(): List<Movie> =
        Json.parseObjectList(http.getText(apiUrl("get_vod_streams"))).mapNotNull { o ->
            val id = o.long("stream_id") ?: return@mapNotNull null
            val ext = o.text("container_extension") ?: "mp4"
            Movie(
                id = id.toString(),
                name = o.text("name") ?: "Movie $id",
                poster = o.text("stream_icon")?.takeIf { it.startsWith("http") },
                categoryId = o.text("category_id") ?: "",
                rating = o.double("rating")?.takeIf { it > 0 },
                year = o.text("year") ?: o.text("releasedate")?.take(4),
                addedEpochSec = o.long("added"),
                streamUrl = movieUrl(id, ext),
            )
        }

    fun movieUrl(id: Long, ext: String) = "$base/movie/$up/$pp/$id.$ext"

    fun movieDetail(movie: Movie): MovieDetail {
        val root = Json.parseObject(http.getText(apiUrl("get_vod_info", mapOf("vod_id" to movie.id))))
        val info = root.obj("info")
        return MovieDetail(
            movie = movie,
            plot = info?.text("plot") ?: info?.text("description"),
            cast = info?.text("cast") ?: info?.text("actors"),
            director = info?.text("director"),
            genre = info?.text("genre"),
            durationSec = info?.long("duration_secs"),
            backdrop = info?.strList("backdrop_path")?.firstOrNull { it.startsWith("http") }
                ?: info?.text("movie_image")?.takeIf { it.startsWith("http") },
        )
    }

    // ---------- series ----------

    fun series(): List<Series> =
        Json.parseObjectList(http.getText(apiUrl("get_series"))).mapNotNull { o ->
            val id = o.long("series_id") ?: return@mapNotNull null
            Series(
                id = id.toString(),
                name = o.text("name") ?: "Series $id",
                cover = o.text("cover")?.takeIf { it.startsWith("http") },
                categoryId = o.text("category_id") ?: "",
                plot = o.text("plot"),
                rating = o.double("rating")?.takeIf { it > 0 },
                year = (o.text("releaseDate") ?: o.text("release_date") ?: o.text("year"))?.take(4),
                backdrop = o.strList("backdrop_path").firstOrNull { it.startsWith("http") },
            )
        }

    fun seriesDetail(series: Series): SeriesDetail {
        val root = Json.parseObject(http.getText(apiUrl("get_series_info", mapOf("series_id" to series.id))))
        val rawEpisodes: List<JsonObj> = when (val e = root.raw("episodes")) {
            is Map<*, *> -> e.values.flatMap { Json.toObjList(it) }
            is List<*> -> e.flatMap { if (it is List<*>) Json.toObjList(it) else Json.toObjList(listOf(it)) }
            else -> emptyList()
        }
        val episodes = rawEpisodes.mapNotNull { o ->
            val id = o.long("id") ?: return@mapNotNull null
            val info = o.obj("info")
            Episode(
                id = id.toString(),
                season = o.int("season") ?: info?.int("season") ?: 1,
                number = o.int("episode_num") ?: 0,
                title = o.text("title") ?: "Episode ${o.int("episode_num") ?: ""}".trim(),
                plot = info?.text("plot"),
                image = info?.text("movie_image")?.takeIf { it.startsWith("http") },
                durationSec = info?.long("duration_secs"),
                streamUrl = "$base/series/$up/$pp/$id.${o.text("container_extension") ?: "mp4"}",
            )
        }
        val seasons = episodes.groupBy { it.season }
            .mapValues { (_, eps) -> eps.sortedBy { it.number } }
            .toSortedMap()
        return SeriesDetail(series, seasons)
    }

    // ---------- guide ----------

    fun xmltvUrl() = "$base/xmltv.php?username=$u&password=$p"

    /** Now/next for one channel. Faster than the full XMLTV file; used as a fallback. */
    fun shortEpg(channel: Channel, limit: Int = 4): List<Program> {
        val id = channel.xtreamStreamId ?: return emptyList()
        val root = Json.parseObject(
            http.getText(apiUrl("get_short_epg", mapOf("stream_id" to id.toString(), "limit" to limit.toString())))
        )
        return root.objList("epg_listings").mapNotNull { o ->
            val start = o.long("start_timestamp") ?: return@mapNotNull null
            val stop = o.long("stop_timestamp") ?: return@mapNotNull null
            Program(
                channelId = channel.epgId ?: channel.key,
                startMs = start * 1000,
                stopMs = stop * 1000,
                title = decodeB64(o.str("title")) ?: "",
                description = decodeB64(o.str("description")),
            )
        }
    }

    // ---------- catch-up ----------

    /**
     * Xtream "timeshift" URL. The start time is written in the *server's* timezone,
     * which [account] reads from server_info.
     */
    fun catchupUrl(channel: Channel, startMs: Long, durationMinutes: Int): String? {
        val id = channel.xtreamStreamId ?: return null
        if (channel.catchupDays <= 0) return null
        val zone = try {
            serverTimezone?.let { ZoneId.of(it) } ?: ZoneId.of("UTC")
        } catch (e: Exception) {
            ZoneId.of("UTC")
        }
        val stamp = TIMESHIFT_FMT.withZone(zone).format(Instant.ofEpochMilli(startMs))
        return "$base/timeshift/$up/$pp/${durationMinutes.coerceAtLeast(1)}/$stamp/$id.ts"
    }

    companion object {
        private val TIMESHIFT_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")

        fun normalizeServer(input: String): String {
            var s = input.trim()
            if (s.isEmpty()) throw IllegalArgumentException("Enter the server address.")
            if (!s.contains("://")) s = "http://$s"
            s = s.substringBefore('?').substringBefore('#')
            for (suffix in listOf("/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php")) {
                if (s.endsWith(suffix, ignoreCase = true)) s = s.dropLast(suffix.length)
            }
            return s.trimEnd('/')
        }

        private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

        private fun pathSeg(v: String): String =
            v.replace("%", "%25").replace("/", "%2F").replace("?", "%3F").replace("#", "%23").replace(" ", "%20")

        fun decodeB64(v: String?): String? {
            if (v.isNullOrBlank()) return null
            return Base64Lite.decode(v.trim())?.toString(Charsets.UTF_8) ?: v // some panels send plain text
        }
    }
}
