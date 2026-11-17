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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
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
fun ReaderScreen(dest: ReaderDest, router: Router, source: ComicReaderSource = OnlineComicReaderSource) {
    var chapter by remember(dest.chapterId) { mutableStateOf<JSONObject?>(null) }
    var error by remember(dest.chapterId) { mutableStateOf<String?>(null) }
    var retry by remember(dest.chapterId) { mutableIntStateOf(0) }
    var start by remember(dest.chapterId) { mutableStateOf(ComicPosition(0)) }
    LaunchedEffect(dest.chapterId, retry) {
        error = null
        try {
            val ch = source.chapter(dest.chapterId)
            val files = ch.optJSONArray("images")
            start = readingStart(ReadHistory.chapter(dest.albumId.ifEmpty { dest.chapterId }, dest.chapterId),
                dest.startPage, dest.startOffset, files?.length() ?: 0)
            // Decode the scroll anchor before layout, so a short loading placeholder cannot
            // clamp a pixel offset saved within a long comic image.
            if (Settings.readerMode != "page" && start.offset > 0 && files != null && files.length() > 0) {
                readerBitmap(source, dest.chapterId, files.optString(start.page))
            }
            chapter = ch
            BrowseHistory.visit(dest.albumId.ifEmpty { dest.chapterId }, dest.albumTitle, "")
            ReadingStats.recordChapter(
                albumId = dest.albumId.ifEmpty { dest.chapterId },
                chapterId = dest.chapterId)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "加载失败" }
    }
    LaunchedEffect(chapter, dest.chapterId) {
        val ch = chapter ?: return@LaunchedEffect
        launch {
            // Next-chapter preload (bounded, sequential — the iOS build-40
            // lesson): chapter data + the first 6 pages only, warmed into
            // ReaderCache one at a time instead of a parallel storm.
            val series = parseSeries(ch, dest.chapterId)
            val idx = series.indexOfFirst { it.first == dest.chapterId }
            val nextId = if (idx >= 0 && idx < series.size - 1) series[idx + 1].first else null
            if (nextId != null) {
                    try {
                        val nch = source.chapter(nextId)
                        val nimgs = nch.optJSONArray("images")
                        val nfiles = (0 until (nimgs?.length() ?: 0)).map { nimgs!!.optString(it) }
                        for (f in nfiles.take(6)) {
                            readerBitmap(source, nextId, f)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { /* preload is best-effort */ }
            }
        }
        while (true) { delay(10_000); ReadingStats.addSeconds(10) }
    }
    Column(Modifier.fillMaxSize()) {
        val ch = chapter
        if (ch == null) {
            TopBar(title = dest.albumTitle) { router.pop() }
            if (error != null) ErrorText(error!!) { retry++ } else LoadingBox()
            return@Column
        }
        key(dest.albumId, dest.chapterId) { ComicReaderContent(dest, router, ch, start, source) }
    }
}

interface ComicReaderSource {
    suspend fun chapter(id: String): JSONObject
    suspend fun page(chapterId: String, file: String): Bitmap
}

object OnlineComicReaderSource : ComicReaderSource {
    override suspend fun chapter(id: String) = JmApi.chapter(id)
    override suspend fun page(chapterId: String, file: String): Bitmap = Descramble.decode(
        JmApi.fetchBytes(JmApi.photoUrl(chapterId, file)), chapterId, file)
}

private suspend fun readerBitmap(source: ComicReaderSource, chapterId: String, file: String): Bitmap {
    return ReaderCache.get(chapterId, file) ?: source.page(chapterId, file).also {
        ReaderCache.put(chapterId, file, it)
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun ColumnScope.ComicReaderContent(dest: ReaderDest, router: Router, ch: JSONObject,
                                          start: ComicPosition, source: ComicReaderSource) {
        val imagesArr = ch.optJSONArray("images")
        val files = (0 until (imagesArr?.length() ?: 0)).map { imagesArr!!.optString(it) }
        val series = parseSeries(ch, dest.chapterId)
        val curIdx = series.indexOfFirst { it.first == dest.chapterId }
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = start.page,
            initialFirstVisibleItemScrollOffset = if (Settings.readerMode == "page") 0 else start.offset)
        val scrollPage by remember { derivedStateOf { (listState.firstVisibleItemIndex + 1).coerceIn(1, maxOf(1, files.size)) } }
        var mode by remember { mutableStateOf(Settings.readerMode) }
        var rtl by remember { mutableStateOf(Settings.readerRtl) }
        var pageIdx by remember(dest.chapterId) { mutableIntStateOf(start.page) }
        val pageNow = if (mode == "page") (pageIdx + 1).coerceIn(1, maxOf(1, files.size)) else scrollPage
        // Reading stats: count pages as they are actually reached (both
        // modes funnel through pageNow), never the whole chapter at open.
        LaunchedEffect(pageNow, dest.chapterId) {
            ReadingStats.recordPage(dest.chapterId, pageNow - 1, files.size)
        }
        val savePosition by rememberUpdatedState(newValue = {
            if (files.isNotEmpty()) {
                val position = if (mode == "page") ComicPosition(pageIdx) else
                    ComicPosition(listState.firstVisibleItemIndex.coerceAtMost(files.lastIndex), listState.firstVisibleItemScrollOffset)
                ReadHistory.record(dest.albumId.ifEmpty { dest.chapterId }, dest.albumTitle, dest.chapterId,
                    position.page, position.offset, series.firstOrNull { it.first == dest.chapterId }?.second.orEmpty(), files.size)
            }
        })
        LaunchedEffect(dest.chapterId) {
            snapshotFlow {
                if (mode == "page") ComicPosition(pageIdx) else
                    ComicPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            }.distinctUntilChanged().debounce(300).collect { savePosition() }
        }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle, dest.chapterId) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) savePosition()
            }
            lifecycle.addObserver(observer)
            onDispose { savePosition(); lifecycle.removeObserver(observer) }
        }

        GlassSurface(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Column {
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
                        savePosition()
                        if (mode == "page") {
                            listState.requestScrollToItem(pageIdx)
                            mode = "scroll"
                        } else {
                            pageIdx = (scrollPage - 1).coerceIn(0, maxOf(0, files.size - 1))
                            mode = "page"
                        }
                        Settings.updateReaderMode(mode)
                    }) { Text(if (mode == "page") "翻页模式 · 切回滚动" else "滚动模式 · 切到翻页", color = Pink, fontSize = 12.sp) }
                    Spacer(Modifier.weight(1f))
                    if (mode == "page") {
                        TextButton(onClick = { rtl = !rtl; Settings.updateReaderRtl(rtl) }) {
                            Text(if (rtl) "方向：从右往左" else "方向：从左往右", color = Pink, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        if (mode == "page" && files.isNotEmpty()) {
            val idx = pageIdx.coerceIn(0, files.size - 1)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ReaderPage(dest.chapterId, files[idx], source)
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
                    ReaderPage(dest.chapterId, file, source)
                }
                item { ChapterNavRow(curIdx, series, dest, router) }
            }
        }
}

@Composable
private fun ChapterNavRow(curIdx: Int, series: List<Pair<String, String>>, dest: ReaderDest, router: Router) {
    GlassSurface(Modifier.fillMaxWidth().padding(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            if (curIdx > 0) {
                GlassOutlinedButton(onClick = {
                    router.pop()
                    router.push(ReaderDest(series[curIdx - 1].first, dest.albumTitle, dest.albumId))
                }) { Text("上一章", color = Pink) }
            } else Spacer(Modifier.width(1.dp))
            if (curIdx >= 0 && curIdx < series.size - 1) {
                GlassButton(onClick = {
                    router.pop()
                    router.push(ReaderDest(series[curIdx + 1].first, dest.albumTitle, dest.albumId))
                }, colors = ButtonDefaults.buttonColors(containerColor = Pink)) {
                    Text("下一章", color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    }
}

@Composable
private fun ReaderPage(chapterId: String, file: String, source: ComicReaderSource) {
    var bmp by remember(chapterId, file) { mutableStateOf<Bitmap?>(ReaderCache.get(chapterId, file)) }
    var failed by remember(chapterId, file) { mutableStateOf(false) }
    LaunchedEffect(chapterId, file) {
        if (bmp != null) return@LaunchedEffect
        try {
            bmp = readerBitmap(source, chapterId, file)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failed = true }
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
