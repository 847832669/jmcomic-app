package com.denia.jmreader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BrowseHistoryScreen(router: Router) {
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val all = BrowseHistory.entries
    val shown = all.filter { query.isBlank() || it.title.contains(query.trim(), true) || it.author.contains(query.trim(), true) }
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "本地浏览历史") { router.pop() }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${all.size} 本 · 仅保存在本机", fontSize = 12.sp, modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { confirmClear = true }, enabled = all.isNotEmpty()) { Text("清空历史") }
        }
        GlassTextField(query, { query = it }, label = { Text("搜索标题或作者") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        if (shown.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (all.isEmpty()) "还没有浏览记录，打开漫画详情后会自动记录" else "没有匹配的浏览记录",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(shown, key = { it.albumId }) { entry ->
                    val progress = ReadHistory.entries.firstOrNull { it.albumId == entry.albumId }
                    GlassSurface(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth().clickable { router.push(DetailDest(entry.albumId)) }) {
                                CoverImage(entry.albumId, Modifier.width(64.dp))
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(entry.title, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (entry.author.isNotBlank()) Text(entry.author, fontSize = 11.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("浏览于 ${dateFormat.format(Date(entry.ts))}", fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(if (progress == null) "尚未开始阅读" else
                                        "${progress.chapterTitle.ifBlank { "上次阅读" }} · 第 ${progress.page + 1} 页",
                                        fontSize = 12.sp, color = Pink)
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { BrowseHistory.remove(entry.albumId) }) { Text("移除记录") }
                                if (progress != null) GlassOutlinedButton(onClick = {
                                    router.push(ReaderDest(progress.chapterId, entry.title, entry.albumId))
                                }) { Text("继续阅读") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) GlassAlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("清空本地浏览历史？") },
        text = { Text("浏览记录将被移除，已保存的阅读进度和收藏会保留。") },
        confirmButton = { TextButton(onClick = { BrowseHistory.clear(); confirmClear = false }) { Text("确认清空") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
    )
}
