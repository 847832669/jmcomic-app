package com.denia.jmreader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Small in-memory cache of decoded reader pages. The next-chapter
 * preload warms it, and ReaderPage reads through it, so flipping to the
 * next chapter shows its first pages instantly. Capped; eldest evicted. */
object ReaderCache {
    private val map = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean = size > 28
    }
    @Synchronized fun get(chapterId: String, file: String): Bitmap? = map["$chapterId/$file"]
    @Synchronized fun put(chapterId: String, file: String, bmp: Bitmap) { map["$chapterId/$file"] = bmp }
}

@Composable
fun ReaderScreen(dest: ReaderDest, router: Router) {
    var chapter by remember(dest.chapterId) { mutableStateOf<JSONObject?>(null) }
    var error by remember(dest.chapterId) { mutableStateOf<String?>(null) }
    val preloadScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    var preloadJob by remember(dest.chapterId) { mutableStateOf<Job?>(null) }
    LaunchedEffect(dest.chapterId) {
        try {
            val ch = JmApi.chapter(dest.chapterId)
            chapter = ch
            ReadingStats.recordChapter(
                albumId = dest.albumId.ifEmpty { dest.chapterId },
                chapterId = dest.chapterId)
            // Next-chapter preload (bounded, sequential — the iOS build-40
            // lesson): chapter data + the first 6 pages only, warmed into
            // ReaderCache one at a time instead of a parallel storm.
            val series = parseSeries(ch, dest.chapterId)
            val idx = series.indexOfFirst { it.first == dest.chapterId }
            val nextId = if (idx >= 0 && idx < series.size - 1) series[idx + 1].first else null
            if (nextId != null) {
                preloadJob?.cancel()
                preloadJob = preloadScope.launch {
                    try {
                        val nch = JmApi.chapter(nextId)
                        val nimgs = nch.optJSONArray("images")
                        val nfiles = (0 until (nimgs?.length() ?: 0)).map { nimgs!!.optString(it) }
                        for (f in nfiles.take(6)) {
                            if (ReaderCache.get(nextId, f) != null) continue
                            val bytes = JmApi.fetchBytes(JmApi.photoUrl(nextId, f))
                            ReaderCache.put(nextId, f, Descramble.decode(bytes, nextId, f))
                        }
                    } catch (e: Exception) { /* preload is best-effort */ }
                }
            }
        } catch (e: Exception) { error = e.message }
    }
    LaunchedEffect(dest.chapterId) {
        while (true) { delay(10_000); ReadingStats.addSeconds(10) }
    }
    DisposableEffect(dest.chapterId) { onDispose { preloadJob?.cancel() } }
    Column(Modifier.fillMaxSize()) {
        val ch = chapter
        if (ch == null) {
            TopBar(title = dest.albumTitle) { router.pop() }
            if (error != null) ErrorText(error!!) else LoadingBox()
            return@Column
        }
        val imagesArr = ch.optJSONArray("images")
        val files = (0 until (imagesArr?.length() ?: 0)).map { imagesArr!!.optString(it) }
        val series = parseSeries(ch, dest.chapterId)
        val curIdx = series.indexOfFirst { it.first == dest.chapterId }
        val listState = rememberLazyListState()
        val scrollPage by remember { derivedStateOf { (listState.firstVisibleItemIndex + 1).coerceIn(1, maxOf(1, files.size)) } }
        var mode by remember { mutableStateOf(Settings.readerMode) }
        var rtl by remember { mutableStateOf(Settings.readerRtl) }
        var pageIdx by remember(dest.chapterId) { mutableIntStateOf(0) }
        val pageNow = if (mode == "page") (pageIdx + 1).coerceIn(1, maxOf(1, files.size)) else scrollPage
        // Reading stats: count pages as they are actually reached (both
        // modes funnel through pageNow), never the whole chapter at open.
        LaunchedEffect(pageNow, dest.chapterId) {
            ReadingStats.recordPage(dest.chapterId, pageNow - 1, files.size)
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { router.pop() }) { Text("‹ 返回", color = Pink, fontSize = 15.sp) }
            Column(Modifier.weight(1f)) {
                Text(ch.optString("name").ifEmpty { dest.albumTitle }, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(dest.albumTitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("$pageNow / ${files.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                mode = if (mode == "page") "scroll" else "page"
                Settings.updateReaderMode(mode)
                if (mode == "page") pageIdx = (scrollPage - 1).coerceIn(0, maxOf(0, files.size - 1))
            }) { Text(if (mode == "page") "翻页模式 · 切回滚动" else "滚动模式 · 切到翻页", color = Pink, fontSize = 12.sp) }
            Spacer(Modifier.weight(1f))
            if (mode == "page") {
                TextButton(onClick = { rtl = !rtl; Settings.updateReaderRtl(rtl) }) {
                    Text(if (rtl) "方向：从右往左" else "方向：从左往右", color = Pink, fontSize = 12.sp)
                }
            }
        }
        if (mode == "page" && files.isNotEmpty()) {
            val idx = pageIdx.coerceIn(0, files.size - 1)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ReaderPage(dest.chapterId, files[idx])
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(0.3f).fillMaxHeight().clickable {
                        pageIdx = (if (rtl) idx + 1 else idx - 1).coerceIn(0, files.size - 1)
                    })
                    Box(Modifier.weight(0.4f).fillMaxHeight())
                    Box(Modifier.weight(0.3f).fillMaxHeight().clickable {
                        pageIdx = (if (rtl) idx - 1 else idx + 1).coerceIn(0, files.size - 1)
                    })
                }
            }
            ChapterNavRow(curIdx, series, dest, router)
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(files, key = { i, f -> "$i-$f" }) { _, file ->
                    ReaderPage(dest.chapterId, file)
                }
                item { ChapterNavRow(curIdx, series, dest, router) }
            }
        }
    }
}

@Composable
private fun ChapterNavRow(curIdx: Int, series: List<Pair<String, String>>, dest: ReaderDest, router: Router) {
    Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        if (curIdx > 0) {
            OutlinedButton(onClick = {
                router.pop()
                router.push(ReaderDest(series[curIdx - 1].first, dest.albumTitle, dest.albumId))
            }) { Text("上一章", color = Pink) }
        } else Spacer(Modifier.width(1.dp))
        if (curIdx >= 0 && curIdx < series.size - 1) {
            Button(onClick = {
                router.pop()
                router.push(ReaderDest(series[curIdx + 1].first, dest.albumTitle, dest.albumId))
            }, colors = ButtonDefaults.buttonColors(containerColor = Pink)) {
                Text("下一章", color = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}

@Composable
private fun ReaderPage(chapterId: String, file: String) {
    var bmp by remember(chapterId, file) { mutableStateOf<Bitmap?>(ReaderCache.get(chapterId, file)) }
    var failed by remember(chapterId, file) { mutableStateOf(false) }
    LaunchedEffect(chapterId, file) {
        if (bmp != null) return@LaunchedEffect
        try {
            val bytes = JmApi.fetchBytes(JmApi.photoUrl(chapterId, file))
            val decoded = Descramble.decode(bytes, chapterId, file)
            ReaderCache.put(chapterId, file, decoded)
            bmp = decoded
        } catch (e: Exception) { failed = true }
    }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = null,
            contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
    } else {
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f), contentAlignment = Alignment.Center) {
            if (failed) Text("这一页加载失败", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            else CircularProgressIndicator(color = Pink)
        }
    }
}
