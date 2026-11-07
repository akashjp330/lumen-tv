package tv.lumen.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import lumen.core.EpgChannel
import lumen.core.Program
import lumen.core.ProgramStore
import java.io.File

/**
 * The guide, stored on the Fire Stick in a SQLite database, one per provider.
 * The full schedule for every channel lives on disk; the app reads only the rows
 * it's drawing. A new guide is built into a separate file and swapped in when
 * complete, so the old guide keeps working during a refresh.
 */
class GuideDb(context: Context, sourceId: String) {
    private val file: File = context.getDatabasePath("guide_$sourceId.db")
    private val tmp: File = context.getDatabasePath("guide_$sourceId.building.db")

    @Volatile private var db: SQLiteDatabase? = null

    init { file.parentFile?.mkdirs() }

    val exists: Boolean get() = file.exists() && file.length() > 0

    /** When the stored guide was built, or 0. */
    val builtAtMs: Long get() = if (exists) file.lastModified() else 0

    /** Opens the stored guide, or returns null if there isn't one (or it's unreadable). */
    @Synchronized
    fun open(): ProgramStore? {
        if (!exists) return null
        return try {
            val d = db ?: SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
            SqliteStore(d)
        } catch (e: Exception) {
            DiagLog.log("Stored guide unreadable; will rebuild", e)
            closeQuietly(); file.delete()
            null
        }
    }

    /** Writer for a fresh guide. Call [Builder.finish] to swap it in, or [Builder.abort]. */
    fun builder(): Builder = Builder()

    inner class Builder {
        private val w: SQLiteDatabase
        private val insert: android.database.sqlite.SQLiteStatement
        private var pending = 0
        var count = 0
            private set

        init {
            deleteDb(tmp)
            w = SQLiteDatabase.openOrCreateDatabase(tmp, null)
            // Building a throwaway file: skip crash-safety work for speed.
            runCatching { w.rawQuery("PRAGMA journal_mode=OFF", null).use { it.moveToFirst() } }
            w.execSQL("PRAGMA synchronous=OFF")
            w.execSQL("CREATE TABLE programs (ch TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT, descr TEXT, cat TEXT)")
            w.execSQL("CREATE TABLE channels (id TEXT PRIMARY KEY, names TEXT, icon TEXT)")
            insert = w.compileStatement("INSERT INTO programs (ch, start, stop, title, descr, cat) VALUES (?, ?, ?, ?, ?, ?)")
            w.beginTransaction()
        }

        fun add(p: Program) {
            insert.clearBindings()
            insert.bindString(1, p.channelId)
            insert.bindLong(2, p.startMs)
            insert.bindLong(3, p.stopMs)
            insert.bindString(4, p.title)
            p.description?.let { insert.bindString(5, it) } ?: insert.bindNull(5)
            p.category?.let { insert.bindString(6, it) } ?: insert.bindNull(6)
            insert.executeInsert()
            count++
            if (++pending >= 10_000) {
                w.setTransactionSuccessful(); w.endTransaction(); w.beginTransaction()
                pending = 0
            }
        }

        fun finish(channels: List<EpgChannel>): ProgramStore? {
            val ch = w.compileStatement("INSERT OR IGNORE INTO channels (id, names, icon) VALUES (?, ?, ?)")
            for (c in channels) {
                ch.clearBindings()
                ch.bindString(1, c.id.lowercase())
                ch.bindString(2, c.names.joinToString(SEP))
                c.icon?.let { ch.bindString(3, it) } ?: ch.bindNull(3)
                ch.executeInsert()
            }
            w.setTransactionSuccessful(); w.endTransaction()
            // Index after the bulk insert: much faster than maintaining it row by row.
            w.execSQL("CREATE INDEX idx_ch_start ON programs (ch, start)")
            w.execSQL("CREATE INDEX idx_start ON programs (start)") // for "what's on now" across all channels
            w.close()
            synchronized(this@GuideDb) {
                closeQuietly()
                deleteDb(file)
                if (!tmp.renameTo(file)) { DiagLog.log("Couldn't replace the stored guide"); return null }
            }
            return open()
        }

        fun abort() {
            runCatching { if (w.inTransaction()) w.endTransaction() }
            runCatching { w.close() }
            deleteDb(tmp)
        }
    }

    @Synchronized
    fun delete() { closeQuietly(); deleteDb(file); deleteDb(tmp) }

    private fun closeQuietly() { runCatching { db?.close() }; db = null }

    private fun deleteDb(f: File) {
        f.delete()
        File(f.path + "-journal").delete(); File(f.path + "-wal").delete(); File(f.path + "-shm").delete()
    }

    private class SqliteStore(private val d: SQLiteDatabase) : ProgramStore {
        private val ids: Set<String> by lazy {
            val out = HashSet<String>()
            d.rawQuery("SELECT DISTINCT ch FROM programs", null).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
            out
        }
        private val chans: List<EpgChannel> by lazy {
            val out = ArrayList<EpgChannel>()
            d.rawQuery("SELECT id, names, icon FROM channels", null).use { c ->
                while (c.moveToNext()) {
                    out.add(EpgChannel(c.getString(0), c.getString(1)?.split(SEP)?.filter { it.isNotBlank() }.orEmpty(), c.getString(2)))
                }
            }
            out
        }

        override fun idsWithData(): Set<String> = ids
        override fun epgChannels(): List<EpgChannel> = chans

        override fun window(epgId: String, fromMs: Long, toMs: Long): List<Program> = safe {
            read("SELECT ch, start, stop, title, descr, cat FROM programs WHERE ch = ? AND start < ? AND stop > ? ORDER BY start",
                arrayOf(epgId, toMs.toString(), fromMs.toString()))
        }

        override fun search(query: String, nowMs: Long, limit: Int): List<Program> = safe {
            val like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            read("SELECT ch, start, stop, title, descr, cat FROM programs WHERE stop > ? AND title LIKE ? ESCAPE '\\' ORDER BY start LIMIT $limit",
                arrayOf(nowMs.toString(), like))
        }

        override fun matching(fromMs: Long, toMs: Long, titleWords: List<String>, categoryWords: List<String>, limit: Int): List<Program> {
            if (titleWords.isEmpty() && categoryWords.isEmpty()) return emptyList()
            fun like(w: String) = "%" + w.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            val conds = titleWords.map { "title LIKE ? ESCAPE '\\'" } + categoryWords.map { "cat LIKE ? ESCAPE '\\'" }
            // Programmes airing in the window started at most 12 h before it: lets SQLite use the start index.
            val sql = "SELECT ch, start, stop, title, descr, cat FROM programs WHERE start > ? AND start < ? AND stop > ? AND (" +
                conds.joinToString(" OR ") + ") ORDER BY start LIMIT $limit"
            val args = arrayOf((fromMs - 12 * 3_600_000L).toString(), toMs.toString(), fromMs.toString()) +
                titleWords.map { like(it) } + categoryWords.map { like(it) }
            return safe { read(sql, args) }
        }

        private fun read(sql: String, args: Array<String>): List<Program> {
            val out = ArrayList<Program>()
            d.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    out.add(Program(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3) ?: "",
                        if (c.isNull(4)) null else c.getString(4), if (c.isNull(5)) null else c.getString(5)))
                }
            }
            return out
        }

        /** A guide swap closes the old database; a read racing it just returns nothing once. */
        private inline fun safe(f: () -> List<Program>): List<Program> =
            try { f() } catch (e: IllegalStateException) { emptyList() } catch (e: android.database.SQLException) { emptyList() }
    }

    companion object {
        private const val SEP = "\u0001"
    }
}
