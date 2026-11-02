package com.denia.jmreader

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

/** Local favorites + favorite-time stamps (mirrors the iOS LibraryStore model). */
class LibraryStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("jm.library", Context.MODE_PRIVATE)

    fun localFavorites(): List<JmApi.Album> {
        val raw = prefs.getString("favorites", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        val albums = JmApi.parseAlbums(arr)
        val dates = favDates()
        return albums.sortedByDescending { dates[it.id] ?: 0L }
    }

    fun isFavorite(id: String): Boolean = localFavorites().any { it.id == id }

    fun addFavorite(album: JmApi.Album) {
        val list = localFavorites().toMutableList()
        if (list.any { it.id == album.id }) return
        list.add(0, album)
        save(list)
        val dates = favDates().toMutableMap()
        dates[album.id] = System.currentTimeMillis()
        prefs.edit().putString("favdates", JSONObject(dates as Map<*, *>).toString()).apply()
    }

    fun removeFavorite(id: String) {
        save(localFavorites().filter { it.id != id })
    }

    private fun save(list: List<JmApi.Album>) {
        val arr = JSONArray()
        for (a in list) {
            arr.put(JSONObject().apply {
                put("id", a.id); put("name", a.name); put("author", a.author)
            })
        }
        prefs.edit().putString("favorites", arr.toString()).apply()
    }

    private fun favDates(): Map<String, Long> {
        val raw = prefs.getString("favdates", "{}") ?: "{}"
        val obj = try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
        val out = mutableMapOf<String, Long>()
        val keys = obj.keys()
        while (keys.hasNext()) { val k = keys.next(); out[k] = obj.optLong(k) }
        return out
    }
}

/** Login session + cached member stats (mirrors the iOS AccountStore). */
class AccountStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("jm.account", Context.MODE_PRIVATE)

    var jwt: String?
        get() = prefs.getString("jwt", null)
        set(v) { prefs.edit().putString("jwt", v).apply(); syncApi() }
    var avs: String?
        get() = prefs.getString("avs", null)
        set(v) { prefs.edit().putString("avs", v).apply(); syncApi() }
    var username: String?
        get() = prefs.getString("username", null)
        set(v) { prefs.edit().putString("username", v).apply() }

    val isLoggedIn: Boolean get() = !jwt.isNullOrEmpty()
    val userId: String get() = jwt?.let { JmApi.uidFromJwt(it) } ?: ""

    data class Stats(val coin: Int, val exp: Int, val nextLevelExp: Int, val level: Int, val favCount: Int, val favMax: Int)
    var stats: Stats?
        get() {
            val raw = prefs.getString("stats", null) ?: return null
            return try {
                val o = JSONObject(raw)
                Stats(o.optInt("coin"), o.optInt("exp"), o.optInt("nextLevelExp"), o.optInt("level"), o.optInt("album_favorites"), o.optInt("album_favorites_max"))
            } catch (e: Exception) { null }
        }
        set(v) {
            if (v == null) { prefs.edit().remove("stats").apply(); return }
            val o = JSONObject().apply {
                put("coin", v.coin); put("exp", v.exp); put("nextLevelExp", v.nextLevelExp)
                put("level", v.level); put("album_favorites", v.favCount); put("album_favorites_max", v.favMax)
            }
            prefs.edit().putString("stats", o.toString()).apply()
        }

    fun saveLogin(data: JSONObject, name: String) {
        jwt = data.optString("jwttoken")
        avs = data.optString("s")
        username = data.optString("username", name)
        stats = Stats(
            coin = data.optInt("coin"),
            exp = data.optInt("exp"),
            nextLevelExp = data.optInt("nextLevelExp"),
            level = data.optInt("level"),
            favCount = data.optInt("album_favorites"),
            favMax = data.optInt("album_favorites_max"),
        )
        syncApi()
    }

    /** The favorites endpoint reports the live total on every load; fold it
     *  into the cached stats so the profile strip's count never goes stale
     *  (exp / level / coin only exist in the login response). */
    fun syncFavCount(total: Int) {
        val st = stats ?: return
        if (total > 0 && st.favCount != total) stats = st.copy(favCount = total)
    }

    fun logout() {
        prefs.edit().clear().apply()
        JmApi.authToken = null; JmApi.authCookie = null
    }

    fun syncApi() {
        JmApi.authToken = jwt
        JmApi.authCookie = avs
    }
}

class HistoryStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("jm.history", Context.MODE_PRIVATE)
    fun queries(): List<String> {
        val raw = prefs.getString("queries", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
    }
    fun add(q: String) {
        val list = (listOf(q) + queries().filter { it != q }).take(20)
        val arr = JSONArray(); list.forEach { arr.put(it) }
        prefs.edit().putString("queries", arr.toString()).apply()
    }
    fun clear() { prefs.edit().remove("queries").apply() }
}

/** Custom tag blocklist (mirrors the iOS BlockTagStore). Browse surfaces hide
 * albums whose title/author contains a blocked word, or — once their real
 * tags are known — whose detail tags contain a blocked tag. List endpoints
 * return no tags, so tags are learned from detail fetches (opening a detail
 * page, plus background prefetch from lists) and cached in SharedPreferences.
 * Favorites and history are never filtered. */
object BlockTags {
    private var prefs: SharedPreferences? = null
    var blockedTags by mutableStateOf<List<String>>(emptyList())
        private set
    var revision by mutableIntStateOf(0)
        private set
    private val tagCache = mutableMapOf<String, List<String>>()
    private val inFlight = mutableSetOf<String>()
    private val sem = Semaphore(4)

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("jm.blocktags", Context.MODE_PRIVATE)
        prefs = p
        val arr = try { JSONArray(p.getString("list", "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
        blockedTags = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
        val obj = try { JSONObject(p.getString("cache", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val a = obj.optJSONArray(k)
            tagCache[k] = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
        }
    }

    fun add(raw: String) {
        val t = raw.trim()
        if (t.isEmpty() || blockedTags.contains(t)) return
        blockedTags = blockedTags + t
        revision++
        prefs?.edit()?.putString("list", JSONArray(blockedTags).toString())?.apply()
    }

    fun remove(tag: String) {
        blockedTags = blockedTags - tag
        revision++
        prefs?.edit()?.putString("list", JSONArray(blockedTags).toString())?.apply()
    }

    /** Record tags learned from an album detail fetch (empty recorded too,
     * so tag-less albums are not refetched forever). */
    fun remember(albumId: String, tags: List<String>) {
        if (tagCache[albumId] == tags) return
        tagCache[albumId] = tags
        revision++
        val obj = JSONObject()
        for ((k, v) in tagCache) obj.put(k, JSONArray(v))
        prefs?.edit()?.putString("cache", obj.toString())?.apply()
    }

    fun isBlocked(album: JmApi.Album): Boolean {
        if (blockedTags.isEmpty()) return false
        val hay = album.name + "\n" + album.author
        for (t in blockedTags) if (hay.contains(t, ignoreCase = true)) return true
        val tags = tagCache[album.id]
        if (tags != null) for (t in blockedTags) if (tags.contains(t)) return true
        return false
    }

    fun visible(items: List<JmApi.Album>): List<JmApi.Album> {
        if (blockedTags.isEmpty()) return items
        return items.filter { !isBlocked(it) }
    }

    /** Background: learn tags for listed albums whose tags are still
     * unknown (4 at a time, at most 40 per call). */
    fun prefetch(scope: CoroutineScope, items: List<JmApi.Album>) {
        if (blockedTags.isEmpty()) return
        val batch = items.map { it.id }.filter { !tagCache.containsKey(it) && !inFlight.contains(it) }.take(40)
        if (batch.isEmpty()) return
        inFlight.addAll(batch)
        scope.launch {
            for (id in batch) {
                sem.withPermit {
                    try {
                        val d = JmApi.album(id)
                        val t = d.optJSONArray("tags")
                        val tags = mutableListOf<String>()
                        if (t != null) for (i in 0 until t.length()) tags.add(t.optString(i))
                        if (tags.isNotEmpty()) remember(id, tags)
                    } catch (e: Exception) { /* leave unknown; retried next time */ }
                }
                inFlight.remove(id)
            }
        }
    }
}
