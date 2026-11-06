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
)

data class AppSettings(
    val pinHash: String? = null,
    val livePreview: Boolean = true,
    val tmdbKey: String? = null,
    val preferHls: Boolean = false,
    val accent: Long = 0xFF4C8DFF,
)

/** Category keys are namespaced by type so a live and a movie category with id "1" don't collide. */
fun catKey(type: lumen.core.ContentType, id: String) = "${type.name[0]}:$id"

class Store(context: Context) {
    private val sp = context.getSharedPreferences("lumen", Context.MODE_PRIVATE)

    private val _sources = MutableStateFlow(readSources())
    val sources: StateFlow<List<Source>> = _sources.asStateFlow()

    private val _activeId = MutableStateFlow(sp.getString("active_source", null))
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

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
        sp.edit().remove("prefs_$id").apply()
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

    // ---------- per-source prefs ----------

    fun updatePrefs(f: (UserPrefs) -> UserPrefs) {
        val id = _activeId.value ?: activeSource?.id ?: return
        val next = f(_prefs.value)
        _prefs.value = next
        sp.edit().putString("prefs_$id", prefsToJson(next)).apply()
    }

    fun toggleFavorite(channelKey: String) = updatePrefs { p ->
        p.copy(favorites = if (channelKey in p.favorites) p.favorites - channelKey else p.favorites + channelKey)
    }

    fun pushRecent(channelKey: String) = updatePrefs { p ->
        p.copy(recent = (listOf(channelKey) + p.recent.filterNot { it == channelKey }).take(20))
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
        val raw = sp.getString("prefs_$id", null) ?: return UserPrefs()
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
            )
        }.getOrDefault(UserPrefs())
    }

    private fun prefsToJson(p: UserPrefs): String = Json.write(
        mapOf(
            "fav" to p.favorites,
            "hc" to p.hiddenCats.toList(),
            "lc" to p.lockedCats.toList(),
            "hch" to p.hiddenChannels.toList(),
            "epg" to p.epgOverrides,
            "rec" to p.recent,
            "prog" to p.progress.values.map {
                mapOf("id" to it.id, "k" to it.kind, "t" to it.title, "img" to it.image, "url" to it.url,
                    "pos" to it.positionMs, "dur" to it.durationMs, "ts" to it.updatedMs, "sid" to it.seriesId)
            },
            "rem" to p.reminders.map { mapOf("c" to it.channelKey, "s" to it.startMs, "t" to it.title) },
        )
    )

    // ---------- app settings ----------

    fun updateSettings(f: (AppSettings) -> AppSettings) {
        val next = f(_settings.value)
        _settings.value = next
        sp.edit().putString("settings", Json.write(
            mapOf("pin" to next.pinHash, "lp" to next.livePreview, "tmdb" to next.tmdbKey, "hls" to next.preferHls, "acc" to next.accent)
        )).apply()
    }

    private fun readSettings(): AppSettings {
        val raw = sp.getString("settings", null) ?: return AppSettings()
        return runCatching {
            val o: JsonObj = Json.parseObject(raw)
            AppSettings(
                pinHash = o.text("pin"),
                livePreview = o.raw("lp")?.let { o.bool("lp") } ?: true,
                tmdbKey = o.text("tmdb"),
                preferHls = o.bool("hls"),
                accent = o.long("acc") ?: 0xFF4C8DFF,
            )
        }.getOrDefault(AppSettings())
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
