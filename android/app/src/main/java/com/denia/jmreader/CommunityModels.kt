package com.denia.jmreader

import org.json.JSONObject

data class JmComment(
    val id: String,
    val username: String,
    val html: String,
    val time: String,
    val photo: String,
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

interface CommunitySource {
    suspend fun comments(albumId: String, page: Int, pageSize: Int): JmCommentPage
    suspend fun send(albumId: String, content: String): String
}

object JmCommunitySource : CommunitySource {
    override suspend fun comments(albumId: String, page: Int, pageSize: Int) =
        JmApi.comments(albumId, page, pageSize)
    override suspend fun send(albumId: String, content: String) = JmApi.sendComment(albumId, content)
}
