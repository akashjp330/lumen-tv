package lumen.core

/**
 * Removes credentials from anything that might end up in a diagnostic log.
 * The app registers the active username/password/server on login.
 */
object LogScrubber {
    private val secrets = linkedSetOf<String>()

    @Synchronized
    fun register(vararg values: String?) {
        values.filterNotNull().map { it.trim() }.filter { it.length >= 3 }.forEach { secrets.add(it) }
    }

    @Synchronized
    fun clear() = secrets.clear()

    private val queryCreds = Regex("""(?i)\b(username|password|user|pass|token)=([^&\s"']+)""")
    private val pathCreds = Regex("""(?i)/(live|movie|series|timeshift)/([^/\s]+)/([^/\s]+)/""")
    private val userInfo = Regex("""(?i)(https?://)[^/@\s]+@""")

    fun scrubUrl(url: String): String = scrub(url)

    @Synchronized
    fun scrub(text: String): String {
        var out = text
        // Longest first so a password containing the username is fully removed.
        for (s in secrets.sortedByDescending { it.length }) out = out.replace(s, "•••", ignoreCase = true)
        out = queryCreds.replace(out) { "${it.groupValues[1]}=•••" }
        out = pathCreds.replace(out) { "/${it.groupValues[1]}/•••/•••/" }
        out = userInfo.replace(out) { "${it.groupValues[1]}•••@" }
        return out
    }
}
