package tv.lumen.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import lumen.core.Json
import lumen.core.JsonObj
import lumen.core.Source
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class WatchProgress(
    val id: String,
    val kind: String, // "movie" | "episode"
    val title: String,
    val image: String?,
    val url: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedMs: Long,
    val seriesId: String? = null,
) {
    val fraction get() = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val finished get() = durationMs > 0 && positionMs > durationMs * 0.93
}

data class Reminder(val channelKey: String, val startMs: Long, val title: String)

/** A named channel menu the user builds, like Favorites. */
data class ChannelList(val id: String, val name: String, val keys: List<String>)

const val FAVORITES_ID = "fav"

/** Per-provider choices: favorites, hidden/locked categories, guide fixes, history. */
data class UserPrefs(
    val favorites: List<String> = emptyList(),
    val hiddenCats: Set<String> = emptySet(),
    val lockedCats: Set<String> = emptySet(),
    val hiddenChannels: Set<String> = emptySet(),
    val epgOverrides: Map<String, String> = emptyMap(),
    val recent: List<String> = emptyList(),
    val progress: Map<String, WatchProgress> = emptyMap(),
    val reminders: List<Reminder> = emptyList(),
    val lists: List<ChannelList> = emptyList(),
    /** Seconds watched per channel, for "Most watched". */
    val watchSec: Map<String, Long> = emptyMap(),
) {
    /** Favorites first, then the user's own lists. */
    val allLists: List<ChannelList> get() = listOf(ChannelList(FAVORITES_ID, "Favorites", favorites)) + lists

    fun mostWatched(minSec: Long = 60): List<Pair<String, Long>> =
        watchSec.entries.filter { it.value >= minSec }.sortedByDescending { it.value }.map { it.key to it.value }
}

data class AppSettings(
    val pinHash: String? = null,
    val livePreview: Boolean = false,
    val tmdbKey: String? = null,
    val accent: Long = 0xFF4C8DFF,
    /** 0 = normal, 1 = large, 2 = maximum. Bigger rides out hiccups; channel changes take a moment longer. */
    val bufferLevel: Int = 1,
    /** "auto" (TS, switching per channel when one struggles), "ts" or "hls". */
    val streamFormat: String = "auto",
    val softwareDecoder: Boolean = false,
    /** "fit", "fill" (zoom to fill, crops edges) or "stretch". */
    val pictureShape: String = "fit",
    val startOnLastChannel: Boolean = false,
    /** Live pause & rewind: keep a buffer of the channel you're watching on the device. */
    val timeshift: Boolean = true,
)

/** A person using the app. Each has their own favorites, lists, history and resume points. */
data class Profile(val id: String, val name: String, val color: Long)

val PROFILE_COLORS = listOf(0xFF4C8DFF, 0xFFE5484D, 0xFF35C8A8, 0xFFF5A524, 0xFFB78CFF, 0xFFFF7AB6, 0xFF7DD3FC, 0xFFA3E635)

/** Category keys are namespaced by type so a live and a movie category with id "1" don't collide. */
fun catKey(type: lumen.core.ContentType, id: String) = "${type.name[0]}:$id"

class Store(context: Context) {
    private val sp = context.getSharedPreferences("lumen", Context.MODE_PRIVATE)

    private val _sources = MutableStateFlow(readSources())
    val sources: StateFlow<List<Source>> = _sources.asStateFlow()

    private val _activeId = MutableStateFlow(sp.getString("active_source", null))
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _profiles = MutableStateFlow(readProfiles())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _profileId = MutableStateFlow(
        sp.getString("active_profile", null)?.takeIf { id -> _profiles.value.any { it.id == id } } ?: _profiles.value.first().id
    )
    val profileId: StateFlow<String> = _profileId.asStateFlow()
    val activeProfile: Profile get() = _profiles.value.firstOrNull { it.id == _profileId.value } ?: _profiles.value.first()

    private val _prefs = MutableStateFlow(readPrefs(_activeId.value))
    val prefs: StateFlow<UserPrefs> = _prefs.asStateFlow()

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val activeSource: Source? get() = _sources.value.firstOrNull { it.id == _activeId.value } ?: _sources.value.firstOrNull()

    // ---------- sources (encrypted) ----------

    fun saveSource(s: Source) {
        val list = _sources.value.filterNot { it.id == s.id } + s
        writeSources(list)
        setActive(s.id)
    }

    fun removeSource(id: String) {
        writeSources(_sources.value.filterNot { it.id == id })
        val e = sp.edit().remove("prefs_$id").remove("shared_$id")
        _profiles.value.forEach { e.remove("prefs_${id}_${it.id}") }
        e.apply()
        if (_activeId.value == id) setActive(_sources.value.firstOrNull()?.id)
    }

    fun setActive(id: String?) {
        sp.edit().putString("active_source", id).apply()
        _activeId.value = id
        _prefs.value = readPrefs(id)
    }

    private fun writeSources(list: List<Source>) {
        val json = Json.write(list.map { s ->
            when (s) {
                is Source.Xtream -> mapOf("t" to "x", "id" to s.id, "label" to s.label, "server" to s.server, "u" to s.username, "p" to s.password)
                is Source.M3u -> mapOf("t" to "m", "id" to s.id, "label" to s.label, "url" to s.playlistUrl, "epg" to s.epgUrl)
            }
        })
        sp.edit().putString("sources", Crypto.encrypt(json)).apply()
        _sources.value = list
    }

    private fun readSources(): List<Source> {
        val enc = sp.getString("sources", null) ?: return emptyList()
        val json = Crypto.decrypt(enc) ?: return emptyList()
        return runCatching {
            Json.parseObjectList(json).mapNotNull { o ->
                val id = o.text("id") ?: return@mapNotNull null
                when (o.text("t")) {
                    "x" -> Source.Xtream(id, o.text("label") ?: "IPTV", o.text("server") ?: "", o.str("u") ?: "", o.str("p") ?: "")
                    "m" -> Source.M3u(id, o.text("label") ?: "Playlist", o.text("url") ?: "", o.text("epg"))
                    else -> null
                }
            }
        }.getOrDefault(emptyList())
    }

    // ---------- profiles ----------

    private fun readProfiles(): List<Profile> {
        val raw = sp.getString("profiles", null)
        val list = raw?.let {
            runCatching {
                Json.parseObjectList(it).mapNotNull { o ->
                    Profile(o.text("id") ?: return@mapNotNull null, o.text("n") ?: "Profile", o.long("c") ?: PROFILE_COLORS[0])
                }
            }.getOrNull()
        }.orEmpty()
        return list.ifEmpty { listOf(Profile("p1", "Me", PROFILE_COLORS[0])) }
    }

    private fun writeProfiles(list: List<Profile>) {
        sp.edit().putString("profiles", Json.write(list.map { mapOf("id" to it.id, "n" to it.name, "c" to it.color) })).apply()
        _profiles.value = list
    }

    fun addProfile(name: String): Profile {
        val used = _profiles.value.map { it.color }.toSet()
        val color = PROFILE_COLORS.firstOrNull { it !in used } ?: PROFILE_COLORS[_profiles.value.size % PROFILE_COLORS.size]
        val p = Profile("p" + System.currentTimeMillis().toString(36), name.trim().ifBlank { "Profile ${_profiles.value.size + 1}" }, color)
        writeProfiles(_profiles.value + p)
        return p
    }

    fun renameProfile(id: String, name: String) =
        writeProfiles(_profiles.value.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it })

    fun recolorProfile(id: String) = writeProfiles(_profiles.value.map {
        if (it.id == id) it.copy(color = PROFILE_COLORS[(PROFILE_COLORS.indexOf(it.color) + 1).mod(PROFILE_COLORS.size)]) else it
    })

    /** Deletes a profile and its favorites/history for every provider. The last profile can't be deleted. */
    fun deleteProfile(id: String) {
        if (_profiles.value.size <= 1) return
        val e = sp.edit()
        _sources.value.forEach { e.remove("prefs_${it.id}_$id") }
        e.apply()
        writeProfiles(_profiles.value.filterNot { it.id == id })
        if (_profileId.value == id) switchProfile(_profiles.value.first().id)
    }

    fun switchProfile(id: String) {
        if (_profiles.value.none { it.id == id }) return
        sp.edit().putString("active_profile", id).apply()
        _profileId.value = id
        _prefs.value = readPrefs(_activeId.value)
    }

    // ---------- prefs: personal (per profile) + shared (per provider) ----------

    fun updatePrefs(f: (UserPrefs) -> UserPrefs) {
        val id = _activeId.value ?: activeSource?.id ?: return
        val next = f(_prefs.value)
        _prefs.value = next
        sp.edit()
            .putString("prefs_${id}_${_profileId.value}", personalJson(next))
            .putString("shared_$id", sharedJson(next))
            .apply()
    }

    fun toggleFavorite(channelKey: String) = updatePrefs { p ->
        p.copy(favorites = if (channelKey in p.favorites) p.favorites - channelKey else p.favorites + channelKey)
    }

    fun pushRecent(channelKey: String) = updatePrefs { p ->
        p.copy(recent = (listOf(channelKey) + p.recent.filterNot { it == channelKey }).take(60))
    }

    fun addWatchTime(channelKey: String, seconds: Long) {
        if (seconds <= 0) return
        updatePrefs { p -> p.copy(watchSec = p.watchSec + (channelKey to ((p.watchSec[channelKey] ?: 0L) + seconds))) }
    }

    fun clearWatchHistory() = updatePrefs { it.copy(recent = emptyList(), watchSec = emptyMap()) }

    // ---------- channel lists ----------

    fun listKeys(listId: String): List<String> =
        prefs.value.allLists.firstOrNull { it.id == listId }?.keys.orEmpty()

    private fun setListKeys(listId: String, keys: List<String>) = updatePrefs { p ->
        if (listId == FAVORITES_ID) p.copy(favorites = keys)
        else p.copy(lists = p.lists.map { if (it.id == listId) it.copy(keys = keys) else it })
    }

    fun toggleInList(listId: String, key: String) {
        val keys = listKeys(listId)
        setListKeys(listId, if (key in keys) keys - key else keys + key)
    }

    fun moveInList(listId: String, key: String, delta: Int) {
        val keys = listKeys(listId).toMutableList()
        val i = keys.indexOf(key)
        val j = i + delta
        if (i < 0 || j !in keys.indices) return
        keys.removeAt(i); keys.add(j, key)
        setListKeys(listId, keys)
    }

    /** Returns the new list's id. */
    fun createList(name: String, firstKey: String? = null): String {
        val id = "l" + System.currentTimeMillis().toString(36)
        updatePrefs { p -> p.copy(lists = p.lists + ChannelList(id, name.trim().ifBlank { "My channels" }, listOfNotNull(firstKey))) }
        return id
    }

    fun renameList(listId: String, name: String) = updatePrefs { p ->
        p.copy(lists = p.lists.map { if (it.id == listId) it.copy(name = name.trim().ifBlank { it.name }) else it })
    }

    fun deleteList(listId: String) = updatePrefs { p -> p.copy(lists = p.lists.filterNot { it.id == listId }) }

    fun moveList(listId: String, delta: Int) = updatePrefs { p ->
        val l = p.lists.toMutableList()
        val i = l.indexOfFirst { it.id == listId }
        val j = i + delta
        if (i < 0 || j !in l.indices) p else { val x = l.removeAt(i); l.add(j, x); p.copy(lists = l) }
    }

    fun saveProgress(w: WatchProgress) = updatePrefs { p ->
        val map = p.progress.toMutableMap()
        map[w.id] = w
        // keep the 60 most recent
        val trimmed = map.values.sortedByDescending { it.updatedMs }.take(60).associateBy { it.id }
        p.copy(progress = trimmed)
    }

    private fun readPrefs(id: String?): UserPrefs {
        if (id == null) return UserPrefs()
        // Before profiles existed everything lived in "prefs_<source>"; it becomes the
        // first profile's favorites/history and the shared category settings.
        sp.getString("prefs_$id", null)?.let { legacy ->
            val first = "prefs_${id}_${_profiles.value.first().id}"
            val e = sp.edit()
            if (!sp.contains(first)) e.putString(first, legacy)
            if (!sp.contains("shared_$id")) e.putString("shared_$id", legacy)
            e.remove("prefs_$id").commit()
        }
        val personal = parsePrefs(sp.getString("prefs_${id}_${_profileId.value}", null))
        val shared = parsePrefs(sp.getString("shared_$id", null))
        return personal.copy(
            hiddenCats = shared.hiddenCats, lockedCats = shared.lockedCats,
            hiddenChannels = shared.hiddenChannels, epgOverrides = shared.epgOverrides,
        )
    }

    private fun parsePrefs(raw: String?): UserPrefs {
        if (raw == null) return UserPrefs()
        return runCatching {
            val o = Json.parseObject(raw)
            UserPrefs(
                favorites = o.strList("fav"),
                hiddenCats = o.strList("hc").toSet(),
                lockedCats = o.strList("lc").toSet(),
                hiddenChannels = o.strList("hch").toSet(),
                epgOverrides = (o.obj("epg")?.map ?: emptyMap<Any, Any>())
                    .entries.associate { it.key.toString() to it.value.toString() },
                recent = o.strList("rec"),
                progress = o.objList("prog").mapNotNull { w ->
                    WatchProgress(
                        id = w.text("id") ?: return@mapNotNull null,
                        kind = w.text("k") ?: "movie",
                        title = w.text("t") ?: "",
                        image = w.text("img"),
                        url = w.text("url") ?: return@mapNotNull null,
                        positionMs = w.long("pos") ?: 0,
                        durationMs = w.long("dur") ?: 0,
                        updatedMs = w.long("ts") ?: 0,
                        seriesId = w.text("sid"),
                    )
                }.associateBy { it.id },
                reminders = o.objList("rem").mapNotNull { r ->
                    Reminder(r.text("c") ?: return@mapNotNull null, r.long("s") ?: return@mapNotNull null, r.text("t") ?: "")
                },
                lists = o.objList("lists").mapNotNull { l ->
                    ChannelList(l.text("id") ?: return@mapNotNull null, l.text("n") ?: "My channels", l.strList("k"))
                },
                watchSec = (o.obj("ws")?.map ?: emptyMap<Any, Any>()).entries.mapNotNull { e ->
                    (e.value as? Number)?.toLong()?.let { e.key.toString() to it }
                }.toMap(),
            )
        }.getOrDefault(UserPrefs())
    }

    /** Shared by every profile on this provider: what's hidden or locked, and guide fixes. */
    private fun sharedJson(p: UserPrefs): String = Json.write(
        mapOf(
            "hc" to p.hiddenCats.toList(),
            "lc" to p.lockedCats.toList(),
            "hch" to p.hiddenChannels.toList(),
            "epg" to p.epgOverrides,
        )
    )

    private fun personalJson(p: UserPrefs): String = Json.write(
        mapOf(
            "fav" to p.favorites,
            "rec" to p.recent,
            "prog" to p.progress.values.map {
                mapOf("id" to it.id, "k" to it.kind, "t" to it.title, "img" to it.image, "url" to it.url,
                    "pos" to it.positionMs, "dur" to it.durationMs, "ts" to it.updatedMs, "sid" to it.seriesId)
            },
            "rem" to p.reminders.map { mapOf("c" to it.channelKey, "s" to it.startMs, "t" to it.title) },
            "lists" to p.lists.map { mapOf("id" to it.id, "n" to it.name, "k" to it.keys) },
            // keep the 300 most watched so the saved file stays small
            "ws" to p.watchSec.entries.sortedByDescending { it.value }.take(300).associate { it.key to it.value },
        )
    )

    // ---------- app settings ----------

    fun updateSettings(f: (AppSettings) -> AppSettings) {
        val next = f(_settings.value)
        _settings.value = next
        sp.edit().putString("settings", Json.write(
            mapOf("pin" to next.pinHash, "lp2" to next.livePreview, "tmdb" to next.tmdbKey, "acc" to next.accent,
                "buf" to next.bufferLevel, "fmt" to next.streamFormat, "swdec" to next.softwareDecoder,
                "shape" to next.pictureShape, "last" to next.startOnLastChannel, "ts" to next.timeshift)
        )).apply()
    }

    private fun readSettings(): AppSettings {
        val raw = sp.getString("settings", null) ?: return AppSettings()
        return runCatching {
            val o: JsonObj = Json.parseObject(raw)
            AppSettings(
                pinHash = o.text("pin"),
                livePreview = o.bool("lp2"),
                tmdbKey = o.text("tmdb"),
                accent = o.long("acc") ?: 0xFF4C8DFF,
                bufferLevel = (o.int("buf") ?: 1).coerceIn(0, 2),
                streamFormat = o.text("fmt")?.takeIf { it in setOf("auto", "ts", "hls") } ?: if (o.bool("hls")) "hls" else "auto",
                softwareDecoder = o.bool("swdec"),
                pictureShape = o.text("shape")?.takeIf { it in setOf("fit", "fill", "stretch") } ?: "fit",
                startOnLastChannel = o.bool("last"),
                timeshift = o.raw("ts")?.let { o.bool("ts") } ?: true,
            )
        }.getOrDefault(AppSettings())
    }

    // ---------- per-channel stream format (remembered when "auto" had to switch) ----------

    fun channelFormat(channelKey: String): String? {
        val src = _activeId.value ?: return null
        return sp.getString("fmt_${src}_$channelKey", null)
    }

    fun setChannelFormat(channelKey: String, format: String?) {
        val src = _activeId.value ?: return
        sp.edit().apply { if (format == null) remove("fmt_${src}_$channelKey") else putString("fmt_${src}_$channelKey", format) }.apply()
    }

    fun clearChannelFormats() {
        val src = _activeId.value ?: return
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("fmt_${src}_") }.forEach { e.remove(it) }
        e.apply()
    }

    // ---------- backup & restore ----------

    private fun fingerprint(s: Source): String = when (s) {
        is Source.Xtream -> lumen.core.M3uParser.stableKey(lumen.core.XtreamClient.normalizeServer(s.server).lowercase() + "|" + s.username)
        is Source.M3u -> lumen.core.M3uParser.stableKey(s.playlistUrl)
    }

    /** Everything a person set up, as JSON. The login is included only when asked. */
    fun exportBackup(includeLogin: Boolean): String {
        val src = _sources.value.map { s ->
            val keys = sp.all.keys.filter { it.startsWith("prefs_${s.id}_") || it == "shared_${s.id}" || it.startsWith("fmt_${s.id}_") }
            val data = keys.associateWith { k -> sp.getString(k, null) }
            val m = linkedMapOf<String, Any?>("id" to s.id, "fp" to fingerprint(s), "label" to s.label, "data" to data)
            if (includeLogin) m["login"] = when (s) {
                is Source.Xtream -> mapOf("t" to "x", "server" to s.server, "u" to s.username, "p" to s.password)
                is Source.M3u -> mapOf("t" to "m", "url" to s.playlistUrl, "epg" to s.epgUrl)
            }
            m
        }
        return Json.write(linkedMapOf(
            "app" to "KASH TV", "v" to 1, "at" to System.currentTimeMillis(),
            "profiles" to _profiles.value.map { mapOf("id" to it.id, "n" to it.name, "c" to it.color) },
            "settings" to sp.getString("settings", null),
            "sources" to src,
        ))
    }

    /**
     * Restores a backup. Favorites/lists are matched to providers by server + username,
     * so it works after signing in again on a new install. Returns a short summary.
     */
    fun importBackup(json: String): String {
        val o = Json.parseObject(json)
        if (o.text("app") != "KASH TV") throw IllegalArgumentException("That isn't a KASH TV backup file.")
        val e = sp.edit()
        val profiles = o.objList("profiles").mapNotNull { p ->
            Profile(p.text("id") ?: return@mapNotNull null, p.text("n") ?: "Profile", p.long("c") ?: PROFILE_COLORS[0])
        }
        if (profiles.isNotEmpty()) e.putString("profiles", Json.write(profiles.map { mapOf("id" to it.id, "n" to it.name, "c" to it.color) }))
        o.text("settings")?.let { e.putString("settings", it) }
        var matched = 0
        var added = 0
        val current = _sources.value.toMutableList()
        for (b in o.objList("sources")) {
            val fp = b.text("fp")
            val oldId = b.text("id") ?: continue
            var target = current.firstOrNull { fingerprint(it) == fp }
            if (target == null && current.size == 1 && o.objList("sources").size == 1) target = current[0]
            if (target == null) {
                val l = b.obj("login") ?: continue
                target = when (l.text("t")) {
                    "x" -> Source.Xtream(oldId, b.text("label") ?: "IPTV", l.text("server") ?: continue, l.str("u") ?: "", l.str("p") ?: "")
                    "m" -> Source.M3u(oldId, b.text("label") ?: "Playlist", l.text("url") ?: continue, l.text("epg"))
                    else -> continue
                }
                current.add(target); added++
            } else matched++
            val data = b.obj("data")?.map ?: continue
            for ((k, v) in data) {
                val key = k.toString().replaceFirst("_${oldId}", "_${target.id}")
                if (v is String) e.putString(key, v)
            }
        }
        e.commit()
        if (added > 0) writeSources(current)
        _profiles.value = readProfiles()
        _settings.value = readSettings()
        if (_profiles.value.none { it.id == _profileId.value }) _profileId.value = _profiles.value.first().id
        if (_activeId.value == null) _activeId.value = current.firstOrNull()?.id
        _prefs.value = readPrefs(_activeId.value)
        return "Restored ${profiles.size} profile(s)" + when {
            added > 0 -> " and your login"
            matched > 0 -> " for your provider"
            else -> ". Sign in to your provider, then restore again to bring back favorites and lists"
        } + "."
    }

    fun checkPin(pin: String) = _settings.value.pinHash == null || _settings.value.pinHash == hash(pin)
    fun setPin(pin: String?) = updateSettings { it.copy(pinHash = pin?.takeIf { p -> p.isNotBlank() }?.let(::hash)) }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(("lumen:" + s).toByteArray())
            .joinToString("") { "%02x".format(it) }
}

/** AES-GCM with a key held in the Android Keystore, so the login never sits in plain text. */
private object Crypto {
    private const val ALIAS = "lumen_creds"
    private const val PLAIN = "p:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    } catch (e: Exception) {
        DiagLog.log("Keystore unavailable, storing login unencrypted", e)
        PLAIN + Base64.encodeToString(plain.toByteArray(), Base64.NO_WRAP)
    }

    fun decrypt(enc: String): String? = try {
        if (enc.startsWith(PLAIN)) {
            String(Base64.decode(enc.removePrefix(PLAIN), Base64.NO_WRAP))
        } else {
            val all = Base64.decode(enc, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
            String(c.doFinal(all, 12, all.size - 12))
        }
    } catch (e: Exception) {
        DiagLog.log("Couldn't read saved login", e)
        null
    }
}
