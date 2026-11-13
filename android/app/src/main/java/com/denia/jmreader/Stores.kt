package com.denia.jmreader

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

/** Albums unfavorited in-app, remembered for 7 days. The favorites hosts
 * disagree for hours, so a fresh fetch can briefly serve a just-removed
 * album again; while an id is in this map, cloud favorite lists skip it.
 * Mirrors the iOS removedFavTombstones (iOS build 44). */
object RemovedFavs {
    private var prefs: SharedPreferences? = null
    private val map = mutableMapOf<String, Long>()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("jm.removedfavs", Context.MODE_PRIVATE)
        prefs = p
        val obj = try { JSONObject(p.getString("map", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val ts = obj.optLong(k, 0)
            if (ts > cutoff) map[k] = ts
        }
    }

    private fun persist() {
        val p = prefs ?: return
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        p.edit().putString("map", obj.toString()).apply()
    }

    fun add(id: String) { map[id] = System.currentTimeMillis(); persist() }
    fun clear(id: String) { if (map.remove(id) != null) persist() }
    fun contains(id: String): Boolean = map.containsKey(id)
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
    var blockedAuthors by mutableStateOf<List<String>>(emptyList())
        private set
    var revision by mutableIntStateOf(0)
        private set
    private val tagCache = mutableMapOf<String, List<String>>()
    private val authorCache = mutableMapOf<String, List<String>>()
    private val inFlight = mutableSetOf<String>()
    private val sem = Semaphore(4)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("jm.blocktags", Context.MODE_PRIVATE)
        prefs = p
        val arr = try { JSONArray(p.getString("list", "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
        blockedTags = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
        val aarr = try { JSONArray(p.getString("authors", "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
        blockedAuthors = (0 until aarr.length()).map { aarr.optString(it) }.filter { it.isNotEmpty() }
        val obj = try { JSONObject(p.getString("cache", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val a = obj.optJSONArray(k)
            tagCache[k] = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
        }
        val aobj = try { JSONObject(p.getString("acache", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val akeys = aobj.keys()
        while (akeys.hasNext()) {
            val k = akeys.next()
            val a = aobj.optJSONArray(k)
            authorCache[k] = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
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

    fun addAuthor(raw: String) {
        val t = raw.trim()
        if (t.isEmpty() || blockedAuthors.contains(t)) return
        blockedAuthors = blockedAuthors + t
        revision++
        prefs?.edit()?.putString("authors", JSONArray(blockedAuthors).toString())?.apply()
    }

    fun removeAuthor(name: String) {
        blockedAuthors = blockedAuthors - name
        revision++
        prefs?.edit()?.putString("authors", JSONArray(blockedAuthors).toString())?.apply()
    }

    /** Record tags/authors learned from an album detail fetch (empty
     * recorded too, so tag-less albums are not refetched forever). The
     * whole-cache JSON encode runs on an IO scope: doing it on the caller
     * (main) thread for every learned album was the Android twin of the
     * iOS v2.18 build-40 list jank. */
    fun remember(albumId: String, tags: List<String>, authors: List<String>? = null) {
        var changed = false
        if (tagCache[albumId] != tags) { tagCache[albumId] = tags; changed = true }
        if (authors != null && authorCache[albumId] != authors) { authorCache[albumId] = authors; changed = true }
        if (!changed) return
        revision++
        val tagsSnap = HashMap(tagCache)
        val authorsSnap = HashMap(authorCache)
        ioScope.launch {
            val obj = JSONObject()
            for ((k, v) in tagsSnap) obj.put(k, JSONArray(v))
            val aobj = JSONObject()
            for ((k, v) in authorsSnap) aobj.put(k, JSONArray(v))
            prefs?.edit()?.putString("cache", obj.toString())?.putString("acache", aobj.toString())?.apply()
        }
    }

    fun isBlocked(album: JmApi.Album): Boolean {
        if (blockedTags.isEmpty() && blockedAuthors.isEmpty()) return false
        val hay = album.name + "\n" + album.author
        for (t in blockedTags) if (hay.contains(t, ignoreCase = true)) return true
        val tags = tagCache[album.id]
        if (tags != null) for (t in blockedTags) if (tags.contains(t)) return true
        for (a in blockedAuthors) if (album.author.contains(a, ignoreCase = true)) return true
        val authors = authorCache[album.id]
        if (authors != null) for (a in blockedAuthors) {
            if (authors.any { it.equals(a, ignoreCase = true) }) return true
        }
        return false
    }

    fun visible(items: List<JmApi.Album>): List<JmApi.Album> {
        if (blockedTags.isEmpty() && blockedAuthors.isEmpty()) return items
        return items.filter { !isBlocked(it) }
    }

    /** Background: learn tags/authors for listed albums whose data an
     * active filter still needs (4 at a time, at most 40 per call). */
    fun prefetch(scope: CoroutineScope, items: List<JmApi.Album>) {
        val needTags = blockedTags.isNotEmpty()
        val needAuthors = blockedAuthors.isNotEmpty()
        if (!needTags && !needAuthors) return
        val batch = items.map { it.id }.filter { id ->
            ((needTags && !tagCache.containsKey(id)) || (needAuthors && !authorCache.containsKey(id)))
                && !inFlight.contains(id)
        }.take(40)
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
                        val authors = mutableListOf<String>()
                        val a = d.opt("author")
                        if (a is JSONArray) for (i in 0 until a.length()) authors.add(a.optString(i))
                        else if (a is String && a.isNotEmpty()) authors.add(a)
                        remember(id, tags, authors)
                    } catch (e: Exception) { /* leave unknown; retried next time */ }
                }
                inFlight.remove(id)
            }
        }
    }
}

/** Reading statistics (mirror of iOS v2.19): albums/chapters counted once,
 * pages counted only as actually reached (per-episode furthest page),
 * plus reading time. The v1 counting added a chapter's full page count the
 * moment it was opened, so on first run of this version the page total
 * restarts from 0 (albums/chapters/seconds carry over). */
object ReadingStats {
    private var prefs: SharedPreferences? = null
    private var albums = mutableSetOf<String>()
    private var chapters = mutableSetOf<String>()
    private var episodePages = mutableMapOf<String, Int>()
    var albumCount by mutableIntStateOf(0)
        private set
    var chapterCount by mutableIntStateOf(0)
        private set
    var pagesRead by mutableIntStateOf(0)
        private set
    var secondsRead by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("jm.readstats", Context.MODE_PRIVATE)
        prefs = p
        fun loadSet(key: String): MutableSet<String> {
            val arr = try { JSONArray(p.getString(key, "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
            val out = mutableSetOf<String>()
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let { out.add(it) }
            return out
        }
        albums = loadSet("albums")
        chapters = loadSet("chapters")
        secondsRead = p.getInt("seconds", 0)
        if (p.getInt("ver", 1) >= 2) {
            pagesRead = p.getInt("pages", 0)
            val obj = try { JSONObject(p.getString("episodePages", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                episodePages[k] = obj.optInt(k)
            }
        } else {
            // v1 pages were whole-chapter counts; restart honestly.
            pagesRead = 0
            persist()
        }
        albumCount = albums.size
        chapterCount = chapters.size
    }

    private fun persist() {
        val p = prefs ?: return
        val ep = JSONObject()
        for ((k, v) in episodePages) ep.put(k, v)
        p.edit()
            .putString("albums", JSONArray(albums.toList()).toString())
            .putString("chapters", JSONArray(chapters.toList()).toString())
            .putInt("pages", pagesRead)
            .putInt("seconds", secondsRead)
            .putString("episodePages", ep.toString())
            .putInt("ver", 2)
            .apply()
    }

    /** Chapter opened: counts the album/chapter once; pages are counted
     * separately as they are reached (recordPage). */
    fun recordChapter(albumId: String, chapterId: String) {
        var changed = false
        if (albums.add(albumId)) { albumCount = albums.size; changed = true }
        if (chapters.add(chapterId)) { chapterCount = chapters.size; changed = true }
        if (changed) persist()
    }

    /** Only pages actually reached count, each at most once per episode:
     * the total grows by how far past the previous furthest page this
     * position is. */
    fun recordPage(chapterId: String, pageIndex: Int, chapterPages: Int) {
        if (chapterId.isEmpty() || chapterPages <= 0) return
        val reached = (pageIndex + 1).coerceIn(0, chapterPages)
        val prev = episodePages[chapterId] ?: 0
        if (reached <= prev) return
        episodePages[chapterId] = reached
        pagesRead += reached - prev
        persist()
    }

    fun addSeconds(sec: Int) {
        secondsRead += sec
        prefs?.edit()?.putInt("seconds", secondsRead)?.apply()
    }
}

/** Persistent app settings: appearance, auto check-in and reader mode. */
object Settings {
    private val themeModes = setOf("system", "light", "dark")
    private var prefs: SharedPreferences? = null
    var autoCheckIn by mutableStateOf(true)
        private set
    var themeMode by mutableStateOf("system")
        private set
    var readerMode by mutableStateOf("scroll") // "scroll" | "page"
        private set
    var readerRtl by mutableStateOf(false)
        private set
    var commentBlockedKeywords by mutableStateOf<List<String>>(emptyList())
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("jm.settings", Context.MODE_PRIVATE)
        prefs = p
        autoCheckIn = p.getBoolean("autoCheckIn", true)
        themeMode = p.getString("themeMode", "system")?.takeIf { it in themeModes } ?: "system"
        readerMode = p.getString("readerMode", "scroll") ?: "scroll"
        readerRtl = p.getBoolean("readerRtl", false)
        commentBlockedKeywords = p.getStringSet("commentBlockedKeywords", emptySet())
            .orEmpty().toList().distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
    }

    fun updateAutoCheckIn(v: Boolean) { autoCheckIn = v; prefs?.edit()?.putBoolean("autoCheckIn", v)?.apply() }
    fun updateThemeMode(v: String) {
        if (v !in themeModes) return
        themeMode = v
        prefs?.edit()?.putString("themeMode", v)?.apply()
    }
    fun updateReaderMode(v: String) { readerMode = v; prefs?.edit()?.putString("readerMode", v)?.apply() }
    fun updateReaderRtl(v: Boolean) { readerRtl = v; prefs?.edit()?.putBoolean("readerRtl", v)?.apply() }
    fun addCommentBlockedKeyword(raw: String): Boolean {
        val keyword = raw.trim()
        if (keyword.isEmpty() || commentBlockedKeywords.any { it.equals(keyword, ignoreCase = true) }) return false
        updateCommentBlockedKeywords(commentBlockedKeywords + keyword)
        return true
    }
    fun removeCommentBlockedKeyword(keyword: String) {
        updateCommentBlockedKeywords(commentBlockedKeywords.filterNot { it == keyword })
    }
    private fun updateCommentBlockedKeywords(values: List<String>) {
        val unique = values.map(String::trim).filter(String::isNotEmpty)
            .distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
        commentBlockedKeywords = unique
        prefs?.edit()?.putStringSet("commentBlockedKeywords", unique.toSet())?.apply()
    }

    var autoCheckinDay: String
        get() = prefs?.getString("autoCheckinDay", "") ?: ""
        set(v) { prefs?.edit()?.putString("autoCheckinDay", v)?.apply() }
}

/** Deleted favorite folders. JM's own folder delete is broken server-side
 * (envelope 200, payload {status: fail}) and its folder lists disagree
 * between hosts, so — like the iOS app — "deleted" is decided by a registry
 * on our own worker, keyed by sha256("username:uid"); local per-account
 * tombstones are the offline fallback and merge target. */
object FolderRegistry {
    private const val REGISTRY_URL = "https://anireader-sync.peimingliang8.workers.dev/jm/folders"
    private var prefs: SharedPreferences? = null
    @Volatile private var syncedAt = 0L

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences("jm.folders", Context.MODE_PRIVATE)
    }

    fun tombstones(username: String): Set<String> {
        val raw = prefs?.getString("deleted.$username", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }.toSet()
    }

    fun addTombstone(username: String, fid: String) {
        val set = tombstones(username) + fid
        prefs?.edit()?.putString("deleted.$username", JSONArray(set.toList()).toString())?.apply()
    }

    fun accountKey(username: String, uid: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$username:$uid".toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    suspend fun syncFromCloud(account: AccountStore, force: Boolean = false) {
        val username = account.username ?: return
        if (!force && System.currentTimeMillis() - syncedAt < 10 * 60 * 1000L) return
        syncedAt = System.currentTimeMillis()
        try {
            val key = accountKey(username, account.userId)
            val text = String(JmApi.fetchBytes("$REGISTRY_URL?acct=$key"), Charsets.UTF_8)
            val arr = JSONObject(text).optJSONArray("deleted") ?: return
            val have = tombstones(username).toMutableSet()
            var changed = false
            for (i in 0 until arr.length()) {
                val id = arr.optString(i)
                if (id.isNotEmpty() && have.add(id)) changed = true
            }
            if (changed) {
                prefs?.edit()?.putString("deleted.$username", JSONArray(have.toList()).toString())?.apply()
            }
        } catch (e: Exception) { /* local tombstones still apply */ }
    }

    suspend fun reportDeleted(account: AccountStore, fid: String) {
        val username = account.username ?: return
        try {
            val key = accountKey(username, account.userId)
            val body = JSONObject().put("acct", key).put("add", JSONArray().put(fid)).toString()
            JmApi.postJson(REGISTRY_URL, body)
        } catch (e: Exception) { /* best effort; local tombstone already saved */ }
    }

    /** Server folder list with deleted folders filtered out; a synthetic
     * default folder leads when the server list lacks FID 0. */
    fun visible(username: String, server: List<JmApi.Folder>): List<JmApi.Folder> {
        val gone = tombstones(username)
        val out = mutableListOf<JmApi.Folder>()
        if (server.none { it.fid == "0" }) out.add(JmApi.Folder("0", "默认收藏"))
        server.forEach { if (!gone.contains(it.fid)) out.add(it) }
        return out
    }
}
