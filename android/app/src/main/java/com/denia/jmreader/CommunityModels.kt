package com.denia.jmreader

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

data class JmComment(
    val id: String,
    val username: String,
    val html: String,
    val time: String,
    val photo: String,
    val parentId: String = "",
) {
    val stableKey: String get() = id.ifEmpty { "$username\u0000$time\u0000$html" }
}

data class JmCommentPage(
    val comments: List<JmComment>,
    val total: Int?,
    val page: Int,
    val pageSize: Int,
    val hasMore: Boolean,
) {
    companion object {
        /** Learn the server's page size from page one; older clients assumed six. */
        fun parse(data: JSONObject, page: Int, pageSizeHint: Int = 10): JmCommentPage {
            require(page > 0 && pageSizeHint > 0)
            val rows = data.optJSONArray("list") ?: error("评论数据格式异常，请重试")
            val size = if (page == 1 && rows.length() > 0) rows.length() else pageSizeHint
            val total = data.optString("total").toIntOrNull()?.takeIf { it >= 0 }
            val comments = (0 until rows.length()).mapNotNull { i ->
                val item = rows.optJSONObject(i) ?: return@mapNotNull null
                fun field(name: String): String = if (item.isNull(name)) "" else item.optString(name)
                JmComment(
                    field("CID"), field("nickname").ifBlank { field("username").ifBlank { "匿名用户" } },
                    field("content"), field("addtime"), field("photo"),
                    field("parent_CID").takeUnless { it == "0" } ?: "",
                )
            }.distinctBy { it.stableKey }
            return JmCommentPage(comments, total, page, size,
                rows.length() > 0 && (total?.let { page.toLong() * size < it } ?: (rows.length() >= size)))
        }

        fun submissionMessage(data: JSONObject): String {
            val status = data.optString("status").lowercase()
            val message = data.optString("msg").ifBlank { data.optString("message") }
            check(status in setOf("ok", "success") || (status.isEmpty() && data.optInt("code") == 200)) {
                message.ifBlank { "服务器未确认发送结果，请刷新评论后确认" }
            }
            return message.ifBlank { "评论已发送" }
        }
    }
}

data class JmCommentThread(val comment: JmComment, val replies: List<JmCommentThread>)

fun threadComments(comments: List<JmComment>): List<JmCommentThread> {
    class MutableThread(val comment: JmComment) {
        val replies = mutableListOf<MutableThread>()
    }

    val nodes = comments.distinctBy { it.stableKey }.map { MutableThread(it) }
    val byId = nodes.filter { it.comment.id.isNotBlank() }.associateBy { it.comment.id }
    val roots = mutableListOf<MutableThread>()
    for (node in nodes) {
        val parent = byId[node.comment.parentId]
        if (node.comment.parentId.isNotBlank() && parent != null && parent !== node) parent.replies += node
        else roots += node
    }

    val visited = mutableSetOf<MutableThread>()
    fun freeze(node: MutableThread): JmCommentThread {
        visited += node
        return JmCommentThread(node.comment, node.replies.filter { it !in visited }.map(::freeze))
    }
    val result = roots.map(::freeze).toMutableList()
    nodes.forEach { if (it !in visited) result += freeze(it) }
    return result
}

fun filterCommentThreadsByKeywords(
    threads: List<JmCommentThread>,
    keywords: List<String>,
): List<JmCommentThread> {
    val terms = keywords.map(String::trim).filter(String::isNotEmpty)
    if (terms.isEmpty()) return threads

    fun matches(comment: JmComment): Boolean {
        val text = comment.html
            .replace(Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>"), " ")
            .replace(Regex("(?is)<(?:br\\b|/?(?:p|div|li)\\b)[^>]*>"), " ")
            .replace(Regex("(?s)<[^>]*>"), "")
            .replace(Regex("(?i)&(#x[0-9a-f]{1,6}|#\\d{1,7}|amp|lt|gt|quot|apos|nbsp);?")) { match ->
                val entity = match.groupValues[1]
                when {
                    entity.equals("amp", true) -> "&"
                    entity.equals("lt", true) -> "<"
                    entity.equals("gt", true) -> ">"
                    entity.equals("quot", true) -> "\""
                    entity.equals("apos", true) -> "'"
                    entity.equals("nbsp", true) -> " "
                    entity.startsWith("#x", true) -> entity.drop(2).toIntOrNull(16)
                        ?.takeIf(Character::isValidCodePoint)?.let(Character::toChars)?.concatToString() ?: " "
                    entity.startsWith("#") -> entity.drop(1).toIntOrNull()
                        ?.takeIf(Character::isValidCodePoint)?.let(Character::toChars)?.concatToString() ?: " "
                    else -> match.value
                }
            }
            .replace(Regex("\\s+"), " ")
        return terms.any { text.contains(it, ignoreCase = true) }
    }

    fun filter(thread: JmCommentThread): JmCommentThread? {
        if (matches(thread.comment)) return null
        val replies = thread.replies.mapNotNull(::filter)
        return thread.copy(replies = replies)
    }

    return threads.mapNotNull(::filter)
}

fun albumUpdateDate(value: String, zoneId: ZoneId = ZoneId.systemDefault()): String? {
    val raw = value.trim().takeUnless { it.isEmpty() || it == "null" } ?: return null
    val timestamp = raw.toLongOrNull()
    if (timestamp != null) {
        if (timestamp <= 0) return null
        val seconds = if (timestamp > 9_999_999_999L) timestamp / 1000 else timestamp
        return runCatching { Instant.ofEpochSecond(seconds).atZone(zoneId).toLocalDate().toString() }.getOrNull()
    }
    val date = raw.take(10)
    return date.takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) }
}

interface CommunitySource {
    suspend fun comments(albumId: String, page: Int, pageSize: Int): JmCommentPage
    suspend fun send(albumId: String, content: String): String
}

object JmCommunitySource : CommunitySource {
    override suspend fun comments(albumId: String, page: Int, pageSize: Int) =
        JmApi.comments(albumId, page, pageSize)
    override suspend fun send(albumId: String, content: String) = JmApi.sendComment(albumId, content)
}
