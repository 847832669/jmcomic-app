package com.denia.jmreader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset

class CommunityModelsTest {
    private fun response(count: Int, total: Int? = null): JSONObject = JSONObject().apply {
        put("list", JSONArray().apply {
            repeat(count) { put(JSONObject().put("CID", "$it").put("username", "reader")
                .put("content", "<div>第 $it 条 &amp; 内容</div>").put("addtime", "2026-11-13")) }
        })
        total?.let { put("total", it.toString()) }
    }

    @Test fun detectsCurrentTenItemPageSize() {
        val page = JmCommentPage.parse(response(10, 259), 1)
        assertEquals(10, page.pageSize)
        assertTrue(page.hasMore)
        assertEquals("<div>第 0 条 &amp; 内容</div>", page.comments[0].html)
    }

    @Test fun learnsLegacyPageSizeWithoutExtraExactMultiplePage() {
        val first = JmCommentPage.parse(response(6, 12), 1)
        assertEquals(6, first.pageSize)
        assertTrue(first.hasMore)
        assertFalse(JmCommentPage.parse(response(6, 12), 2, first.pageSize).hasMore)
    }

    @Test fun lastPartialPageRetainsOriginalSize() {
        val page = JmCommentPage.parse(response(9, 259), 26, 10)
        assertEquals(10, page.pageSize)
        assertFalse(page.hasMore)
    }

    @Test fun emptyPageStopsEvenWithStaleTotal() {
        assertFalse(JmCommentPage.parse(response(0, 100), 2).hasMore)
    }

    @Test fun absentTotalUsesFullPageHint() {
        assertTrue(JmCommentPage.parse(response(10), 1).hasMore)
        assertFalse(JmCommentPage.parse(response(3), 2, 10).hasMore)
    }

    @Test fun handlesNullNamesAndDuplicateRows() {
        val data = response(1, 2)
        val row = data.getJSONArray("list").getJSONObject(0)
        row.put("nickname", JSONObject.NULL).put("photo", JSONObject.NULL)
        data.getJSONArray("list").put(row)
        val page = JmCommentPage.parse(data, 1)
        assertEquals(1, page.comments.size)
        assertEquals("reader", page.comments.single().username)
        assertEquals("", page.comments.single().photo)
    }

    @Test fun groupsNestedRepliesAndKeepsOrphansVisible() {
        val root = JmComment("1", "reader", "root", "", "")
        val reply = JmComment("2", "reader", "reply", "", "", parentId = "1")
        val nestedReply = JmComment("3", "reader", "nested", "", "", parentId = "2")
        val orphan = JmComment("4", "reader", "orphan", "", "", parentId = "missing")
        val threads = threadComments(listOf(root, reply, nestedReply, orphan))
        assertEquals(listOf("1", "4"), threads.map { it.comment.id })
        assertEquals("2", threads.first().replies.single().comment.id)
        assertEquals("3", threads.first().replies.single().replies.single().comment.id)
    }

    @Test fun parsesParentCidWhenPresent() {
        val row = response(1, 1).getJSONArray("list").getJSONObject(0).put("parent_CID", "42")
        val page = JmCommentPage.parse(JSONObject().put("list", JSONArray().put(row)).put("total", 1), 1)
        assertEquals("42", page.comments.single().parentId)
    }

    @Test fun cyclicParentReferencesDoNotDuplicateComments() {
        val first = JmComment("1", "reader", "first", "", "", parentId = "2")
        val second = JmComment("2", "reader", "second", "", "", parentId = "1")
        val threads = threadComments(listOf(first, second))
        assertEquals(1, threads.size)
        assertEquals("2", threads.single().replies.single().comment.id)
        assertTrue(threads.single().replies.single().replies.isEmpty())
    }

    @Test fun keywordFilterHidesMatchingReplyBranchesAndKeepsOtherComments() {
        val root = JmComment("1", "reader", "<b>normal root</b>", "", "")
        val spam = JmComment("2", "reader", "<p>BUY&#32;NOW</p>", "", "", parentId = "1")
        val nested = JmComment("3", "reader", "reply to spam", "", "", parentId = "2")
        val cleanReply = JmComment("4", "reader", "normal reply", "", "", parentId = "1")
        val threads = filterCommentThreadsByKeywords(
            threadComments(listOf(root, spam, nested, cleanReply)), listOf(" buy now "))

        assertEquals(listOf("1"), threads.map { it.comment.id })
        assertEquals(listOf("4"), threads.single().replies.map { it.comment.id })
        assertEquals(listOf(root), filterCommentThreadsByKeywords(threadComments(listOf(root)), emptyList())
            .map { it.comment })
    }

    @Test fun formatsAlbumUpdateTimestampsAndDates() {
        assertEquals("2026-11-13", albumUpdateDate("1794534613", ZoneOffset.UTC))
        assertEquals("2026-11-13", albumUpdateDate("1794534613000", ZoneOffset.UTC))
        assertEquals("2026-11-13", albumUpdateDate("2026-11-13 09:50:13"))
        assertNull(albumUpdateDate("null"))
    }

    @Test fun keywordFilterRemovesDeepMatchesAndMatchesRenderedInlineText() {
        val root = JmComment("1", "reader", "root", "", "")
        val reply = JmComment("2", "reader", "clean reply", "", "", parentId = "1")
        val ad = JmComment("3", "reader", "点<b>击</b>&#x5E7F;告", "", "", parentId = "2")
        val threads = filterCommentThreadsByKeywords(threadComments(listOf(root, reply, ad)), listOf("点击广告"))
        assertTrue(threads.single().replies.single().replies.isEmpty())
        assertTrue(filterCommentThreadsByKeywords(threadComments(listOf(ad)), listOf("点击广告")).isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun invalidResponseDoesNotBecomeEmptySuccess() {
        JmCommentPage.parse(JSONObject().put("_raw", "invalid"), 1)
    }

    @Test fun onlyAcknowledgedPostsSucceed() {
        assertEquals("成功", JmCommentPage.submissionMessage(JSONObject("""{"status":"ok","msg":"成功"}""")))
        for (json in listOf("""{"status":"fail","msg":"请登录"}""", """{"status":"error","code":200}""", "{}", """{"_raw":"bad"}""")) {
            try { JmCommentPage.submissionMessage(JSONObject(json)); fail("Should reject: $json") }
            catch (_: IllegalStateException) { }
        }
    }
}
