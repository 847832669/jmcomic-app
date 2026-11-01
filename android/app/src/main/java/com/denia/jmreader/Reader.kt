package com.denia.jmreader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import org.json.JSONObject

@Composable
fun ReaderScreen(dest: ReaderDest, router: Router) {
    var chapter by remember(dest.chapterId) { mutableStateOf<JSONObject?>(null) }
    var error by remember(dest.chapterId) { mutableStateOf<String?>(null) }
    LaunchedEffect(dest.chapterId) {
        try { chapter = JmApi.chapter(dest.chapterId) } catch (e: Exception) { error = e.message }
    }
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
        val pageNow by remember { derivedStateOf { (listState.firstVisibleItemIndex + 1).coerceIn(1, maxOf(1, files.size)) } }

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
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(files, key = { i, f -> "$i-$f" }) { _, file ->
                ReaderPage(dest.chapterId, file)
            }
            item {
                Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (curIdx > 0) {
                        OutlinedButton(onClick = {
                            router.pop()
                            router.push(ReaderDest(series[curIdx - 1].first, dest.albumTitle))
                        }) { Text("上一章", color = Pink) }
                    } else Spacer(Modifier.width(1.dp))
                    if (curIdx >= 0 && curIdx < series.size - 1) {
                        Button(onClick = {
                            router.pop()
                            router.push(ReaderDest(series[curIdx + 1].first, dest.albumTitle))
                        }, colors = ButtonDefaults.buttonColors(containerColor = Pink)) {
                            Text("下一章", color = androidx.compose.ui.graphics.Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderPage(chapterId: String, file: String) {
    var bmp by remember(chapterId, file) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(chapterId, file) { mutableStateOf(false) }
    LaunchedEffect(chapterId, file) {
        try {
            val bytes = JmApi.fetchBytes(JmApi.photoUrl(chapterId, file))
            bmp = Descramble.decode(bytes, chapterId, file)
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
