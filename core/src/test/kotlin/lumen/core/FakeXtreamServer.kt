package lumen.core

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.zip.GZIPOutputStream

/**
 * A stand-in for a real IPTV panel. Answers player_api.php, xmltv.php and an M3U
 * playlist with deliberately messy data (numbers as strings, empty fields, base64
 * titles, gzip guide) the way real providers do.
 */
class FakeXtreamServer(private val nowSec: Long) : AutoCloseable {
    val user = "akash_test"
    val pass = "s3cr3t/p@ss"
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val base get() = "http://127.0.0.1:${server.address.port}"
    var requests = mutableListOf<String>()

    init {
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            val q = (ex.requestURI.rawQuery ?: "").split('&').filter { it.contains('=') }
                .associate { val (k, v) = it.split('=', limit = 2); k to URLDecoder.decode(v, "UTF-8") }
            requests.add(ex.requestURI.toString())
            val authed = q["username"] == user && q["password"] == pass
            val (code, body, gzip) = when {
                path == "/redirect/player_api.php" -> {
                    ex.responseHeaders.add("Location", "$base/player_api.php?${ex.requestURI.rawQuery}")
                    Triple(302, "", false)
                }
                path == "/player_api.php" && !authed -> Triple(200, """{"user_info":{"auth":0}}""", false)
                path == "/player_api.php" -> Triple(200, api(q["action"], q), false)
                path == "/xmltv.php" && authed -> Triple(200, xmltv(), true)
                path == "/playlist.m3u" -> Triple(200, m3u(), false)
                else -> Triple(404, "not found", false)
            }
            val bytes = if (gzip) gz(body.toByteArray()) else body.toByteArray()
            ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        server.start()
    }

    private fun gz(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private fun b64(s: String) = java.util.Base64.getEncoder().encodeToString(s.toByteArray())

    private fun api(action: String?, q: Map<String, String>): String = when (action) {
        null -> """{"user_info":{"username":"$user","auth":1,"status":"Active","exp_date":"1924992000",
            "is_trial":"0","active_cons":"1","max_connections":"5"},
            "server_info":{"timezone":"America/Chicago","url":"127.0.0.1"}}"""
        "get_live_categories" -> """[{"category_id":"1","category_name":"News"},{"category_id":"2","category_name":"Sports"},{"category_id":null,"category_name":"Broken"}]"""
        "get_live_streams" -> """[
            {"num":1,"name":"Metro News HD","stream_id":"101","stream_icon":"http://img/metro.png","epg_channel_id":"metro.us","category_id":"1","tv_archive":1,"tv_archive_duration":"7"},
            {"num":"2","name":"US: Sports One FHD","stream_id":204,"stream_icon":"","epg_channel_id":null,"category_id":"2","tv_archive":0},
            {"name":"No Guide Channel","stream_id":"999","category_id":"2"}
        ]"""
        "get_vod_categories" -> """[{"category_id":"10","category_name":"Action"}]"""
        "get_vod_streams" -> """[{"stream_id":"5001","name":"Big Movie","stream_icon":"http://img/p.jpg","category_id":"10","rating":"7.4","container_extension":"mkv","added":"1700000000"}]"""
        "get_vod_info" -> """{"info":{"plot":"A plot.","cast":"A, B","director":"C","genre":"Action","duration_secs":"6000","backdrop_path":["http://img/b.jpg"]},"movie_data":{"stream_id":5001}}"""
        "get_series_categories" -> """[{"category_id":"20","category_name":"Drama"}]"""
        "get_series" -> """[{"series_id":7,"name":"Good Show","cover":"http://img/c.jpg","category_id":"20","plot":"Plot","rating":"8","releaseDate":"2021-03-01","backdrop_path":[]}]"""
        "get_series_info" -> """{"info":{"name":"Good Show"},"episodes":{
            "2":[{"id":"702","episode_num":"1","title":"S2E1","container_extension":"mp4","season":2,"info":{"plot":"x","duration_secs":"1800"}}],
            "1":[{"id":"602","episode_num":2,"title":"S1E2","container_extension":"mkv","season":1,"info":[]},
                 {"id":"601","episode_num":1,"title":"S1E1","container_extension":"mkv","season":1,"info":{}}]}}"""
        "get_short_epg" -> """{"epg_listings":[
            {"title":"${b64("Live Game")}","description":"${b64("Teams play.")}","start_timestamp":"${nowSec - 600}","stop_timestamp":"${nowSec + 3000}"},
            {"title":"${b64("Post Game")}","description":"","start_timestamp":"${nowSec + 3000}","stop_timestamp":"${nowSec + 4800}"}]}"""
        else -> "[]"
    }

    private fun stamp(sec: Long): String {
        val f = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(java.time.ZoneOffset.UTC)
        return f.format(java.time.Instant.ofEpochSecond(sec)) + " +0000"
    }

    private fun xmltv(): String = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE tv SYSTEM "xmltv.dtd">
<tv generator-info-name="fake">
  <channel id="metro.us"><display-name>Metro News</display-name><icon src="http://img/m.png"/></channel>
  <channel id="SportsOne.us"><display-name>Sports One</display-name><display-name>SPORTS1</display-name></channel>
  <programme start="${stamp(nowSec - 3 * 86400)}" stop="${stamp(nowSec - 3 * 86400 + 1800)}" channel="metro.us"><title>Too Old</title></programme>
  <programme start="${stamp(nowSec - 3600)}" stop="${stamp(nowSec - 1800)}" channel="metro.us"><title lang="en">Earlier News</title></programme>
  <programme start="${stamp(nowSec - 1800)}" stop="${stamp(nowSec + 1800)}" channel="metro.us"><title>Evening Report &amp; Weather</title><desc>Headlines.</desc><category>News</category></programme>
  <programme start="${stamp(nowSec + 1800)}" stop="${stamp(nowSec + 5400)}" channel="metro.us"><title>Late Edition</title></programme>
  <programme start="${stamp(nowSec - 600)}" stop="${stamp(nowSec + 9000)}" channel="SportsOne.us"><title>Sunday Night Football</title></programme>
</tv>"""

    private fun m3u(): String = """#EXTM3U url-tvg="$base/xmltv.php?username=$user&password=$pass"
#EXTINF:-1 tvg-id="metro.us" tvg-name="Metro News" tvg-logo="http://img/metro.png" group-title="News" catchup="default" catchup-days="3" catchup-source="http://cdn/metro?start={utc}&dur={duration:60}",Metro News, Evening
#EXTVLCOPT:http-user-agent=Foo
http://cdn/live/metro.ts
#EXTINF:-1 tvg-chno="42" group-title="Sports",Sports One
http://cdn/live/sports1.m3u8
#EXTINF:-1,Orphan Channel
#EXTGRP:Misc
http://cdn/live/orphan.ts
#EXTINF:-1 group-title="Movies: Action",Big Movie (2020)
http://cdn/movie/u/p/5001.mkv
#EXTINF:-1 group-title="Series",Good Show S01E01
http://cdn/series/u/p/601.mkv
"""

    override fun close() = server.stop(0)
}
