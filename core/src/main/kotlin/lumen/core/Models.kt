package lumen.core

/** Where the channels come from. */
sealed class Source {
    abstract val id: String
    abstract val label: String

    data class Xtream(
        override val id: String,
        override val label: String,
        val server: String,
        val username: String,
        val password: String,
    ) : Source()

    data class M3u(
        override val id: String,
        override val label: String,
        val playlistUrl: String,
        /** Optional; falls back to the playlist's url-tvg header. */
        val epgUrl: String? = null,
    ) : Source()
}

enum class ContentType { LIVE, MOVIE, SERIES }

data class Category(val id: String, val name: String, val type: ContentType)

data class Channel(
    /** Stable key within one source (stream id for Xtream, url hash for M3U). */
    val key: String,
    val number: Int,
    val name: String,
    val logo: String?,
    val categoryId: String,
    /** XMLTV channel id, when the provider supplies one. */
    val epgId: String?,
    val streamUrl: String,
    /** Days of catch-up the provider keeps; 0 = none. */
    val catchupDays: Int = 0,
    /** M3U only: catch-up URL template (catchup-source). */
    val catchupTemplate: String? = null,
    /** M3U only: catchup mode attribute (default, append, shift, flussonic...). */
    val catchupMode: String? = null,
    val xtreamStreamId: Long? = null,
)

data class Program(
    val channelId: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String? = null,
    val category: String? = null,
) {
    val durationMs get() = stopMs - startMs
    fun isAiring(now: Long) = now in startMs until stopMs
    fun progress(now: Long): Float =
        if (durationMs <= 0) 0f else ((now - startMs).toFloat() / durationMs).coerceIn(0f, 1f)
}

data class Movie(
    val id: String,
    val name: String,
    val poster: String?,
    val categoryId: String,
    val rating: Double?,
    val year: String?,
    val addedEpochSec: Long?,
    val streamUrl: String,
)

data class Series(
    val id: String,
    val name: String,
    val cover: String?,
    val categoryId: String,
    val plot: String?,
    val rating: Double?,
    val year: String?,
    val backdrop: String?,
)

data class Episode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val plot: String?,
    val image: String?,
    val durationSec: Long?,
    val streamUrl: String,
)

data class SeriesDetail(val series: Series, val seasons: Map<Int, List<Episode>>)

data class MovieDetail(
    val movie: Movie,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val durationSec: Long?,
    val backdrop: String?,
)

data class AccountInfo(
    val status: String,
    val expiresEpochSec: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val isTrial: Boolean,
    val serverTimezone: String?,
) {
    val isActive get() = status.equals("Active", ignoreCase = true)
}

/** Everything loaded from a source, ready for the UI. */
data class Catalog(
    val liveCategories: List<Category>,
    val channels: List<Channel>,
    val movieCategories: List<Category>,
    val movies: List<Movie>,
    val seriesCategories: List<Category>,
    val series: List<Series>,
    val epgUrl: String?,
    val account: AccountInfo?,
)
