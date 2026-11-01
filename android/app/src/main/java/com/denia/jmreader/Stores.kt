package com.denia.jmreader

import android.content.Context
import android.content.SharedPreferences
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
