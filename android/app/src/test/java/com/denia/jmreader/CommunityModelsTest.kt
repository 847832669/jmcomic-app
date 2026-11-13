package com.denia.jmreader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

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
