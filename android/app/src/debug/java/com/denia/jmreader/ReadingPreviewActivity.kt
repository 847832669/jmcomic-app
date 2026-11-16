package com.denia.jmreader

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** Exercises the production detail, reader and history UI with generated pages and isolated prefs. */
class ReadingPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isolated = object : ContextWrapper(this) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("reading-fixture.$name", mode)
        }
        Settings.init(isolated)
        ReadingStats.init(isolated)
        BlockTags.init(isolated)
        ReadHistory.init(isolated)
        BrowseHistory.init(isolated)
        val source = FixtureComicReaderSource()
        setContent {
            val router = remember { Router() }
            val account = remember { AccountStore(isolated) }
            val library = remember { LibraryStore(isolated) }
            BackHandler(router.stack.isNotEmpty()) { router.pop() }
            JmTheme {
                val backdrop = rememberGlassBackdrop()
                CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().glassBackdrop(backdrop)) { GlassWallpaper() }
                        Box(Modifier.fillMaxSize().systemBarsPadding()) {
                            when (val dest = router.stack.lastOrNull()) {
                                is DetailDest -> DetailScreen(dest, router, account, library, source::album)
                                is ReaderDest -> ReaderScreen(dest, router, source)
                                BrowseHistoryDest -> BrowseHistoryScreen(router)
                                else -> Column(Modifier.fillMaxSize().padding(20.dp)) {
                                    Text("漫画续读离线验证（独立测试记录）")
                                    GlassButton(onClick = { router.push(DetailDest("fixture-a")) }) { Text("漫画 A 详情") }
                                    GlassButton(onClick = { router.push(DetailDest("fixture-b")) }) { Text("漫画 B 详情") }
                                    GlassButton(onClick = { router.push(BrowseHistoryDest) }) { Text("本地浏览历史") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private class FixtureComicReaderSource : ComicReaderSource {
    suspend fun album(id: String) = JSONObject().put("name", "续读验证漫画 ${id.takeLast(1).uppercase()}")
        .put("author", JSONArray().put("离线测试作者")).put("series", series(id))
    private fun series(album: String) = JSONArray().apply {
        (1..2).forEach { put(JSONObject().put("id", "$album-$it").put("name", "第 $it 话").put("sort", it)) }
    }
    override suspend fun chapter(id: String): JSONObject = JSONObject().put("name", "第 ${id.takeLast(1)} 话")
        .put("series", series(id.substringBeforeLast('-'))).put("images", JSONArray().apply {
            (1..8).forEach { put("$it.png") }
        })
    override suspend fun page(chapterId: String, file: String): Bitmap {
        val bitmap = Bitmap.createBitmap(600, 1500, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(AndroidColor.rgb(234, 241, 247))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.DKGRAY; textSize = 38f }
        canvas.drawText("Chapter ${chapterId.takeLast(1)} - Page ${file.substringBefore('.')}", 30f, 70f, paint)
        paint.textSize = 25f
        (1..14).forEach { row ->
            canvas.drawText("Reading position marker $row", 30f, row * 95f + 50f, paint)
        }
        return bitmap
    }
}
