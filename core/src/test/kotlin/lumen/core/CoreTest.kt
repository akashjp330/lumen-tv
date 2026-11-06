package lumen.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.StringReader

class CoreTest {
    private val nowSec = 1_793_418_000L // 2026-10-31 ~03:00 UTC
    private val nowMs = nowSec * 1000

    // ---------- JSON ----------

    @Test fun jsonHandlesLooseTypes() {
        val o = Json.parseObject("""{"a":"12","b":12,"c":12.0,"d":"","e":null,"f":"1","g":[1,"x"],"h":"café \"q\""}""")
        assertEquals(12L, o.long("a")); assertEquals(12L, o.long("b")); assertEquals(12L, o.long("c"))
        assertEquals("12", o.str("c"))
        assertNull(o.text("d")); assertNull(o.long("e"))
        assertTrue(o.bool("f")); assertFalse(o.bool("missing"))
        assertEquals(listOf("1", "x"), o.strList("g"))
        assertEquals("café \"q\"", o.str("h"))
    }

    @Test fun jsonRoundTrip() {
        val v = mapOf("s" to "a\"b\\c\nd é", "n" to 5L, "d" to 1.5, "b" to true, "z" to null, "l" to listOf(1L, "x"))
        assertEquals(v, Json.parse(Json.write(v)))
    }

    @Test fun jsonObjectKeyedListIsTolerated() {
        assertEquals(2, Json.parseObjectList("""{"1":{"x":1},"2":{"x":2}}""").size)
        assertEquals(0, Json.parseObjectList("""[]""").size)
    }

    @Test fun jsonRejectsGarbage() {
        try { Json.parse("<html>nope</html>"); fail() } catch (e: JsonException) { }
    }

    // ---------- Xtream ----------

    @Test fun serverAddressIsNormalized() {
        assertEquals("http://host.com:8080", XtreamClient.normalizeServer(" host.com:8080/ "))
        assertEquals("https://h.tv", XtreamClient.normalizeServer("https://h.tv/player_api.php?username=a"))
        assertEquals("http://h.tv/sub", XtreamClient.normalizeServer("http://h.tv/sub/get.php"))
    }

    @Test fun xtreamFullFlowAgainstFakeServer() = FakeXtreamServer(nowSec).use { srv ->
        val loader = SourceLoader(Source.Xtream("s", "Test", srv.base, srv.user, srv.pass))
        val acct = loader.verify()!!
        assertTrue(acct.isActive)
        assertEquals(5, acct.maxConnections); assertEquals(1, acct.activeConnections)
        assertEquals(1924992000L, acct.expiresEpochSec)

        val cat = loader.loadCatalog()
        assertEquals(listOf("News", "Sports"), cat.liveCategories.map { it.name })
        assertEquals(3, cat.channels.size)
        val metro = cat.channels.first { it.key == "101" }
        assertEquals(7, metro.catchupDays)
        assertEquals("metro.us", metro.epgId)
        // password contains '/', which must be escaped inside the URL path
        assertEquals("${srv.base}/live/akash_test/s3cr3t%2Fp@ss/101.ts", metro.streamUrl)
        val sports = cat.channels.first { it.key == "204" }
        assertNull(sports.logo); assertEquals(2, sports.number); assertEquals(0, sports.catchupDays)
        assertEquals(3, cat.channels.first { it.key == "999" }.number)

        assertEquals(1, cat.movies.size)
        assertEquals("${srv.base}/movie/akash_test/s3cr3t%2Fp@ss/5001.mkv", cat.movies[0].streamUrl)
        assertEquals(7.4, cat.movies[0].rating!!, 0.001)
        assertEquals("2021", cat.series[0].year)

        val md = loader.xtream!!.movieDetail(cat.movies[0])
        assertEquals("A plot.", md.plot); assertEquals(6000L, md.durationSec); assertEquals("http://img/b.jpg", md.backdrop)

        val sd = loader.xtream!!.seriesDetail(cat.series[0])
        assertEquals(listOf(1, 2), sd.seasons.keys.toList())
        assertEquals(listOf("S1E1", "S1E2"), sd.seasons[1]!!.map { it.title })
        assertTrue(sd.seasons[2]!![0].streamUrl.endsWith("/series/akash_test/s3cr3t%2Fp@ss/702.mp4"))

        // Guide: gzip XMLTV, window filter, id match + name match
        val guide = loader.loadGuide(cat, nowMs, daysBack = 2, daysAhead = 1)
        assertNull(guide.error)
        val now = guide.index.at("101", nowMs)
        assertEquals("Evening Report & Weather", now?.title)
        assertEquals("Headlines.", now?.description)
        assertEquals("Late Edition", guide.index.next("101", nowMs)?.title)
        assertFalse(guide.index.programs("101").any { it.title == "Too Old" })
        // "US: Sports One FHD" has no epg id; matched by name to SportsOne.us
        assertEquals("Sunday Night Football", guide.index.at("204", nowMs)?.title)
        assertFalse(guide.index.hasGuide("999"))
        assertEquals(1, guide.index.search("football", nowMs).size)

        // Catch-up: Earlier News on Metro, server timezone America/Chicago
        val earlier = guide.index.programs("101").first { it.title == "Earlier News" }
        val url = loader.catchupUrl(metro, earlier, nowMs)!!
        val expectedStamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")
            .withZone(java.time.ZoneId.of("America/Chicago")).format(java.time.Instant.ofEpochMilli(earlier.startMs))
        assertEquals("${srv.base}/timeshift/akash_test/s3cr3t%2Fp@ss/30/$expectedStamp/101.ts", url)
        assertNull("no archive on this channel", loader.catchupUrl(sports, guide.index.at("204", nowMs)!!, nowMs))
    }

    @Test fun shortEpgFallbackDecodesBase64() = FakeXtreamServer(nowSec).use { srv ->
        val x = XtreamClient(srv.base, srv.user, srv.pass)
        val ch = x.liveChannels().first { it.key == "999" }
        val progs = x.shortEpg(ch)
        assertEquals(listOf("Live Game", "Post Game"), progs.map { it.title })
        assertEquals("Teams play.", progs[0].description)
        assertNull(progs[1].description)
    }

    @Test fun wrongPasswordIsAnAuthError() = FakeXtreamServer(nowSec).use { srv ->
        try {
            XtreamClient(srv.base, srv.user, "wrong").account(); fail()
        } catch (e: AuthException) {
            assertTrue(e.message!!.contains("rejected"))
        }
    }

    @Test fun redirectsAreFollowed() = FakeXtreamServer(nowSec).use { srv ->
        val acct = XtreamClient("${srv.base}/redirect", srv.user, srv.pass).account()
        assertTrue(acct.isActive)
    }

    // ---------- M3U ----------

    @Test fun m3uParsesAttributesGroupsAndKinds() = FakeXtreamServer(nowSec).use { srv ->
        val loader = SourceLoader(Source.M3u("m", "Playlist", "${srv.base}/playlist.m3u"))
        loader.verify()
        val cat = loader.loadCatalog()
        assertEquals(3, cat.channels.size)
        val metro = cat.channels[0]
        assertEquals("Metro News, Evening", metro.name) // comma inside the title
        assertEquals("News", metro.categoryId)
        assertEquals("metro.us", metro.epgId)
        assertEquals(3, metro.catchupDays)
        assertEquals(42, cat.channels[1].number)
        assertEquals("Misc", cat.channels[2].categoryId)
        assertEquals(1, cat.movies.size)
        assertEquals("Movies: Action", cat.movieCategories[0].name)
        assertTrue(cat.epgUrl!!.contains("/xmltv.php"))

        val guide = loader.loadGuide(cat, nowMs)
        assertEquals("Evening Report & Weather", guide.index.at(metro.key, nowMs)?.title)
        val earlier = guide.index.programs(metro.key).first { it.title == "Earlier News" }
        assertEquals("http://cdn/metro?start=${earlier.startMs / 1000}&dur=30", loader.catchupUrl(metro, earlier, nowMs))
    }

    @Test fun m3uRejectsNonPlaylist() {
        val r = M3uParser.parse(StringReader("<html></html>"))
        assertEquals(0, r.channels.size)
    }

    @Test fun catchupTemplates() {
        val ch = Channel("k", 1, "C", null, "g", null, "http://h/live/1.m3u8", 3, "?utc={utc}&end={utcend}&d={Y}-{m}-{d}", "append")
        val p = Program("c", 1_700_000_000_000L, 1_700_003_600_000L, "T")
        assertEquals("http://h/live/1.m3u8?utc=1700000000&end=1700003600&d=2023-11-14", Catchup.m3uUrl(ch, p, 1_700_010_000_000L))
        val shift = ch.copy(catchupMode = "shift", catchupTemplate = null)
        assertEquals("http://h/live/1.m3u8?utc=1700000000&lutc=1700010000", Catchup.m3uUrl(shift, p, 1_700_010_000_000L))
        val fl = ch.copy(catchupMode = "flussonic", streamUrl = "http://h/ch/index.m3u8")
        assertEquals("http://h/ch/index-1700000000-3600.m3u8", Catchup.m3uUrl(fl, p, 1_700_010_000_000L))
        assertEquals("2023-11-14T22", Catchup.fill("{utc:Y-m-dTH}", p, 0))
        assertFalse("too old", Catchup.isAvailable(ch, p, p.startMs + 4 * 86_400_000L))
        assertFalse("future", Catchup.isAvailable(ch, p, p.startMs - 1))
    }

    // ---------- XMLTV ----------

    @Test fun xmltvTimeFormats() {
        assertEquals(1_700_000_000_000L, XmltvParser.parseTime("20231114221320 +0000"))
        assertEquals(1_700_000_000_000L, XmltvParser.parseTime("20231114231320 +0100"))
        assertEquals(1_700_000_000_000L, XmltvParser.parseTime("20231114171320 -0500"))
        assertEquals(1_699_999_980_000L, XmltvParser.parseTime("202311142213"))
        assertNull(XmltvParser.parseTime("garbage"))
    }

    @Test fun xmltvToleratesTruncatedFile() {
        val xml = """<tv><programme start="20231114220000 +0000" stop="20231114230000 +0000" channel="A"><title>One</title></programme><programme start="2023"""
        val d = XmltvParser.parse(ByteArrayInputStream(xml.toByteArray()))
        assertEquals("One", d.programs["a"]!![0].title)
    }

    @Test fun guideSkipsChannelsTheUserCantSee() = FakeXtreamServer(nowSec).use { srv ->
        val loader = SourceLoader(Source.Xtream("s", "Test", srv.base, srv.user, srv.pass))
        val cat = loader.loadCatalog(includeVod = false)
        val onlyMetro = cat.channels.filter { it.key == "101" }
        val g = loader.loadGuide(cat, nowMs, guideChannels = onlyMetro)
        assertTrue(g.index.hasGuide("101"))
        assertFalse(g.index.hasGuide("204"))
        assertNull("sports programmes never kept in memory", g.xmltv.programs["sportsone.us"])
        // but the XMLTV channel list is still complete for the manual match picker
        assertEquals(2, g.xmltv.channels.size)
    }

    @Test fun nameNormalization() {
        assertEquals("espn", EpgMatcher.normalize("US: ESPN HD (East)"))
        assertEquals("sportsone", EpgMatcher.normalize("|US| Sports One FHD"))
        assertEquals(EpgMatcher.normalize("A & E"), EpgMatcher.normalize("A&E"))
    }

    @Test fun cacheServesOfflineAfterFullRead() = FakeXtreamServer(nowSec).use { srv ->
        val dir = java.nio.file.Files.createTempDirectory("lumencache").toFile()
        val net = UrlConnectionFetcher()
        val url = XtreamClient(srv.base, srv.user, srv.pass).apiUrl("get_live_streams")
        // A partial read must not be committed
        CachingFetcher(dir, net, cacheFirst = false).open(url).use { it.read(ByteArray(10)) }
        assertFalse(CachingFetcher(dir, net, true).cacheFile(url).exists())
        val fresh = CachingFetcher(dir, net, cacheFirst = false).getText(url)
        val before = srv.requests.size
        val cached = CachingFetcher(dir, net, cacheFirst = true).getText(url)
        assertEquals(fresh, cached)
        assertEquals("served from disk", before, srv.requests.size)
        dir.deleteRecursively()
    }

    // ---------- misc ----------

    @Test fun logScrubberRemovesCredentials() {
        LogScrubber.register("akash_test", "s3cr3t/p@ss")
        val s = LogScrubber.scrub("GET http://h:80/live/akash_test/s3cr3t%2Fp@ss/101.ts and player_api.php?username=akash_test&password=s3cr3t/p@ss")
        assertFalse(s.contains("akash_test")); assertFalse(s.contains("s3cr3t"))
        LogScrubber.clear()
    }

    @Test fun base64Lite() {
        assertEquals("Live Game", Base64Lite.decode("TGl2ZSBHYW1l")!!.toString(Charsets.UTF_8))
        assertNull(Base64Lite.decode("not base64!"))
    }
}
