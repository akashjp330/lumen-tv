package lumen.core

/** Finds games in the guide and groups the many channels showing the same one. */
object Sports {
    /** Words that, in a programme's category, mean sport. */
    val CATEGORY_WORDS = listOf("sport", "football", "basketball", "baseball", "hockey", "soccer", "golf", "tennis",
        "boxing", "mma", "wrestling", "racing", "motor", "cricket", "rugby", "volleyball", "olympic")

    /** Words that, in a title, mean sport even without a category. */
    val TITLE_WORDS = listOf(" vs", " v. ", "NFL", "NBA", "WNBA", "MLB", "NHL", "MLS", "NCAA", "College Football",
        "College Basketball", "Premier League", "Champions League", "La Liga", "Serie A", "Bundesliga", "UFC", "Formula 1",
        "F1 ", "NASCAR", "IndyCar", "PGA", "LPGA", "ATP", "WTA", "Boxing", "Wrestling", "Cricket", "IPL", "Rugby", "Golf",
        "Football", "Basketball", "Baseball", "Hockey", "Soccer", "Grand Prix", "Super Bowl", "Playoffs", "SportsCenter")

    private val versus = Regex("""\s(vs\.?|v\.?|@|at)\s""", RegexOption.IGNORE_CASE)
    private val strongVersus = Regex("""\s(vs\.?|v\.?|@)\s""", RegexOption.IGNORE_CASE)
    private val notSport = Regex("""(?i)\b(news|talk|tonight show|live at five)\b""")
    private val trailing = Regex("""\s*(\(.*?\)|\[.*?\]|-\s*live|live)\s*$""", RegexOption.IGNORE_CASE)

    fun isSport(p: Program): Boolean {
        val cat = p.category?.lowercase().orEmpty()
        if (CATEGORY_WORDS.any { it in cat }) return true
        val t = " " + p.title + " "
        if (notSport.containsMatchIn(p.title)) return false
        return TITLE_WORDS.any { t.contains(it, ignoreCase = true) } || strongVersus.containsMatchIn(t)
    }

    /** A head-to-head game ("Texans at Colts", "Duke vs. UNC"), as opposed to a studio show. */
    fun isGame(p: Program): Boolean = teams(p.title).size == 2 &&
        (strongVersus.containsMatchIn(" ${p.title} ") || isSport(p))

    /** "NFL Football: Houston Texans at Indianapolis Colts (Live)" → ["Houston Texans", "Indianapolis Colts"]. */
    fun teams(title: String): List<String> {
        var t = title.substringAfterLast(": ", title)
        t = trailing.replace(t, "").trim()
        val m = versus.find(" $t ") ?: return emptyList()
        val a = (" $t ").substring(0, m.range.first).trim().trim('-', ':', ' ')
        val b = (" $t ").substring(m.range.last + 1).trim().trim('-', ':', ' ')
        if (a.length < 2 || b.length < 2 || a.length > 40 || b.length > 40) return emptyList()
        return listOf(a, b)
    }

    class Item(val program: Program, val channels: List<Channel>) {
        val title get() = program.title
        val best get() = channels.first()
    }

    /**
     * One entry per game: the same game on HD/SD/East/West or several networks is
     * grouped, and the channel list is ordered favourites first, then [rank] (e.g.
     * how smoothly a channel has played), then quality, then channel number.
     */
    fun group(
        hits: List<Pair<String, Program>>,
        byKey: Map<String, Channel>,
        favorites: Set<String> = emptySet(),
        rank: (Channel) -> Int = { 0 },
    ): List<Item> {
        val groups = LinkedHashMap<String, MutableList<Pair<Channel, Program>>>()
        for ((k, p) in hits) {
            val ch = byKey[k] ?: continue
            val key = EpgMatcher.normalize(p.title) + "@" + (p.startMs / (15 * 60_000))
            groups.getOrPut(key) { ArrayList() }.add(ch to p)
        }
        return groups.values.map { list ->
            val chans = list.map { it.first }.distinctBy { it.key }.sortedWith(
                compareByDescending<Channel> { it.key in favorites }
                    .thenByDescending { rank(it) }
                    .thenByDescending { Alternates.rank(it.name) }
                    .thenBy { it.number }
            )
            Item(list.first().second, chans)
        }.sortedWith(compareBy<Item> { it.program.startMs }.thenBy { it.title })
    }
}

/** Teams and shows a person follows: plain words matched against programme titles. */
object Follows {
    fun matches(title: String, follows: Collection<String>): String? {
        val t = " " + title.lowercase().replace(Regex("[^a-z0-9]+"), " ") + " "
        return follows.firstOrNull { f ->
            val w = f.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
            w.length >= 2 && t.contains(" $w ")
        }
    }
}
