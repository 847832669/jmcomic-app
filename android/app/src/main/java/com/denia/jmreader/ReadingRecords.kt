package com.denia.jmreader

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

data class ReadEntry(
    val albumId: String, val title: String, val chapterId: String, val page: Int, val ts: Long,
    val scrollOffset: Int = 0, val chapterTitle: String = "", val pageCount: Int = 0,
)

data class ComicPosition(val page: Int, val offset: Int = 0)

/** Explicit page 0 means restart; null means resume this chapter's own position. */
fun readingStart(saved: ReadEntry?, page: Int?, offset: Int?, pageCount: Int): ComicPosition {
    val requested = page ?: saved?.page ?: 0
    val safePage = requested.coerceIn(0, maxOf(0, pageCount - 1))
    val safeOffset = if (safePage != requested || pageCount <= 0) 0
        else (offset ?: if (page == null) saved?.scrollOffset ?: 0 else 0).coerceAtLeast(0)
    return ComicPosition(safePage, safeOffset)
}

/** Keep chapter positions independently: reading an earlier chapter must not erase a later one. */
data class ReadingRecords(val positions: List<ReadEntry> = emptyList()) {
    val recent: List<ReadEntry> get() = positions.sortedByDescending { it.ts }.distinctBy { it.albumId }
    fun chapter(albumId: String, chapterId: String) = positions.firstOrNull {
        it.albumId == albumId && it.chapterId == chapterId
    }
    fun resume(albumId: String, available: List<String>) = positions
        .filter { it.albumId == albumId && it.chapterId in available }.maxByOrNull { it.ts }
    fun record(entry: ReadEntry): ReadingRecords {
        if (entry.albumId.isBlank() || entry.chapterId.isBlank()) return this
        val safe = entry.copy(page = entry.page.coerceAtLeast(0), scrollOffset = entry.scrollOffset.coerceAtLeast(0),
            pageCount = entry.pageCount.coerceAtLeast(0))
        return ReadingRecords(listOf(safe) + positions.filterNot {
            it.albumId == safe.albumId && it.chapterId == safe.chapterId
        })
    }
    fun encode(): String = JSONArray().apply {
        positions.forEach { e -> put(JSONObject().put("albumId", e.albumId).put("title", e.title)
            .put("chapterId", e.chapterId).put("page", e.page).put("ts", e.ts)
            .put("scrollOffset", e.scrollOffset).put("chapterTitle", e.chapterTitle).put("pageCount", e.pageCount)) }
    }.toString()

    companion object {
        // The old entries array has the same required fields; new fields default safely on upgrade.
        fun decode(raw: String): ReadingRecords {
            val array = runCatching { JSONArray(raw) }.getOrElse { return ReadingRecords() }
            var records = ReadingRecords()
            val entries = (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                ReadEntry(o.optString("albumId", ""), o.optString("title", ""), o.optString("chapterId", ""),
                    o.optInt("page"), o.optLong("ts"), o.optInt("scrollOffset"),
                    o.optString("chapterTitle", ""), o.optInt("pageCount"))
            }
            for (entry in entries.sortedBy { it.ts }) records = records.record(entry)
            return records
        }
    }
}

object ReadHistory {
    private var prefs: SharedPreferences? = null
    private var records by mutableStateOf(ReadingRecords())
    val entries: List<ReadEntry> get() = records.recent
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("jm.readhistory", Context.MODE_PRIVATE)
        records = ReadingRecords.decode(p.getString("positions", null) ?: p.getString("entries", "[]") ?: "[]")
        prefs = p
    }
    fun chapter(albumId: String, chapterId: String) = records.chapter(albumId, chapterId)
    fun resume(albumId: String, available: List<String>) = records.resume(albumId, available)
    fun record(albumId: String, title: String, chapterId: String, page: Int,
               scrollOffset: Int = 0, chapterTitle: String = "", pageCount: Int = 0) {
        records = records.record(ReadEntry(albumId, title, chapterId, page, System.currentTimeMillis(),
            scrollOffset, chapterTitle, pageCount))
        prefs?.edit()?.putString("positions", records.encode())?.apply()
    }
}

data class BrowseEntry(val albumId: String, val title: String, val author: String, val ts: Long)

data class BrowsingRecords(val entries: List<BrowseEntry> = emptyList()) {
    fun visit(entry: BrowseEntry): BrowsingRecords = if (entry.albumId.isBlank()) this else
        BrowsingRecords((listOf(entry) + entries.filterNot { it.albumId == entry.albumId }).take(500))
    fun remove(albumId: String) = BrowsingRecords(entries.filterNot { it.albumId == albumId })
    fun encode(): String = JSONArray().apply {
        entries.forEach { put(JSONObject().put("albumId", it.albumId).put("title", it.title)
            .put("author", it.author).put("ts", it.ts)) }
    }.toString()
    companion object {
        fun decode(raw: String): BrowsingRecords {
            val arr = runCatching { JSONArray(raw) }.getOrElse { return BrowsingRecords() }
            val rows = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                BrowseEntry(o.optString("albumId", ""), o.optString("title", ""),
                    o.optString("author", ""), o.optLong("ts"))
            }
            return rows.sortedBy { it.ts }.fold(BrowsingRecords()) { records, entry -> records.visit(entry) }
        }
    }
}

/** Local detail visits, independent of login, search queries and saved reading positions. */
object BrowseHistory {
    private var prefs: SharedPreferences? = null
    private var records by mutableStateOf(BrowsingRecords())
    val entries get() = records.entries
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("jm.browsehistory", Context.MODE_PRIVATE)
        records = BrowsingRecords.decode(p.getString("entries", "[]") ?: "[]")
        // Seed previous readers' history once, without losing their saved progress.
        if (!p.contains("entries")) {
            records = ReadHistory.entries.sortedBy { it.ts }.fold(records) { data, entry ->
                data.visit(BrowseEntry(entry.albumId, entry.title, "", entry.ts))
            }
        }
        prefs = p
        persist()
    }
    fun visit(albumId: String, title: String, author: String) {
        val previous = records.entries.firstOrNull { it.albumId == albumId }
        records = records.visit(BrowseEntry(albumId, title.ifBlank { previous?.title.orEmpty() },
            author.ifBlank { previous?.author.orEmpty() }, System.currentTimeMillis()))
        persist()
    }
    fun remove(albumId: String) { records = records.remove(albumId); persist() }
    fun clear() { records = BrowsingRecords(); persist() }
    private fun persist() { prefs?.edit()?.putString("entries", records.encode())?.apply() }
}
