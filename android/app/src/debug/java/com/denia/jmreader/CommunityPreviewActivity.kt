package com.denia.jmreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** Offline integration fixture. Sending goes to memory, never to a remote service. */
class CommunityPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        val mode = intent.getStringExtra("mode") ?: "normal"
        val source = FixtureCommunitySource(mode)
        val live = mode == "live"
        val albumId = if (live) "591677" else "fixture"
        setContent {
            JmTheme {
                val backdrop = rememberGlassBackdrop()
                var open by remember { mutableStateOf(live || mode == "error") }
                CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().glassBackdrop(backdrop)) { GlassWallpaper() }
                        Box(Modifier.fillMaxSize().statusBarsPadding()) {
                            if (open) CommentsScreen(CommentsDest(albumId, "评论模块验证"), { open = false },
                                isLoggedIn = !live && mode != "guest", onLogin = { open = false },
                                source = if (live) JmCommunitySource else source)
                            else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                                Text("作品详情模块验证")
                                AlbumDescription(if (mode == "empty") "" else
                                    "<p><b>作品描述</b>：中文 &amp; HTML 实体、换行与可选择文本。</p>" +
                                    (1..12).joinToString("") { "<p>描述第 $it 段：验证长内容展开与收起。</p>" })
                                AlbumCommentsPreview(albumId, "12", source) { open = true }
                            }
                        }
                    }
                }
            }
        }
    }
}

private class FixtureCommunitySource(private val mode: String) : CommunitySource {
    private var failLoad = mode == "error"
    private var failSend = mode == "send-error"
    private val posted = mutableListOf<String>()
    override suspend fun comments(albumId: String, page: Int, pageSize: Int): JmCommentPage {
        delay(200)
        if (failLoad) { failLoad = false; error("离线网络故障，请重试") }
        val all = if (mode == "empty") posted else posted + (1..12).map { "测试评论 $it：<b>清晰文字</b> &amp; 中文换行<br>第二行" }
        return JmCommentPage.parse(JSONObject().put("total", all.size).put("list", JSONArray().apply {
            all.drop((page - 1) * 6).take(6).forEachIndexed { i, html ->
                put(JSONObject().put("CID", "${(page - 1) * 6 + i}").put("username", "测试读者")
                    .put("addtime", "2026-11-13").put("content", "<div>$html</div>"))
            }
        }), page, if (page == 1) 6 else pageSize)
    }
    override suspend fun send(albumId: String, content: String): String {
        delay(200)
        if (failSend) { failSend = false; error("离线发送失败，草稿已保留") }
        posted.add(0, content)
        return "评论已发送（离线验证）"
    }
}
