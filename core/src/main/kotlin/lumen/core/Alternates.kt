package lumen.core

/**
 * Other versions of the same channel: "USA ESPN HD", "USA ESPN SD", "USA ESPN 4K",
 * "USA AMC East", "USA AMC West"... Used to offer a backup when a stream keeps failing.
 */
object Alternates {
    private val feed = Regex("""\b(east|west|pacific|central|mountain|alt|backup|plus|lhd|\d{3,4}p|50fps|60fps|vip)\b""")

    /** Name with quality and feed words removed, so all versions share one key. */
    fun key(name: String): String {
        var s = EpgMatcher.normalizeSpaced(name)
        s = feed.replace(s, " ")
        return s.filter { it.isLetterOrDigit() }
    }

    /** Rough quality rank from the name: 4K > FHD > HD > (unknown) > SD. */
    fun rank(name: String): Int {
        val n = " " + name.uppercase().replace(Regex("[^A-Z0-9]+"), " ") + " "
        return when {
            " UHD " in n || " 4K " in n -> 4
            " FHD " in n || " 1080P " in n -> 3
            " HD " in n || " 720P " in n -> 2
            " SD " in n -> 0
            else -> 1
        }
    }

    /** Other versions of [ch] among [channels], best quality first. */
    fun of(ch: Channel, channels: List<Channel>): List<Channel> {
        val k = key(ch.name)
        if (k.length < 2) return emptyList()
        return channels.filter { it.key != ch.key && key(it.name) == k }
            .sortedWith(compareByDescending<Channel> { rank(it.name) }.thenBy { it.number })
    }

    /** Groups every channel by [key] once, for fast lookups over thousands of channels. */
    fun index(channels: List<Channel>): Map<String, List<Channel>> =
        channels.groupBy { key(it.name) }.filterValues { it.size > 1 }
}
