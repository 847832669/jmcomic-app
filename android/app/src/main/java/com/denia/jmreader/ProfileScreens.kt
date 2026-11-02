package com.denia.jmreader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

// ---------------- Profile / 个人 ----------------
@Composable
fun ProfileScreen(router: Router, account: AccountStore, library: LibraryStore) {
    val scope = rememberCoroutineScope()
    var loggedIn by remember { mutableStateOf(account.isLoggedIn) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (!loggedIn) {
            Text("登录 JM 账号", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(user, { user = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(pass, { pass = it }, label = { Text("密码") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                busy = true; msg = null
                scope.launch {
                    try {
                        val d = JmApi.login(user.trim(), pass)
                        account.saveLogin(d, user.trim())
                        loggedIn = true
                    } catch (e: Exception) { msg = e.message }
                    busy = false
                }
            }, enabled = !busy && user.isNotBlank() && pass.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Pink), modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "登录中…" else "登录", color = Color.White)
            }
            msg?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(20.dp))
            Text("未登录时收藏保存在本机，登录后可同步云端收藏。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(account.username ?: "", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { account.logout(); loggedIn = false }) { Text("退出", color = Pink) }
            }
            account.stats?.let { st ->
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row {
                            Text("LV.${st.level}", fontWeight = FontWeight.Bold, color = Pink)
                            Spacer(Modifier.width(12.dp))
                            Text("经验 ${st.exp} / ${st.nextLevelExp}", fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            Text("J Coins ${st.coin}", fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { if (st.nextLevelExp > 0) (st.exp.toFloat() / st.nextLevelExp).coerceIn(0f, 1f) else 0f },
                            color = Pink, modifier = Modifier.fillMaxWidth())
                        Text("可收藏 ${st.favCount} / ${st.favMax}", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            CheckinCard(account)
        }

        Spacer(Modifier.height(14.dp))
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().clickable { router.push(BlockedTagsDest) }) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("屏蔽标签", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (BlockTags.blockedTags.isNotEmpty()) {
                    Text("${BlockTags.blockedTags.size} 个", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(" ›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(18.dp))
        FavoritesSection(router, account, library, loggedIn)
    }
}

@Composable
fun CheckinCard(account: AccountStore) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun reload() {
        try { data = JmApi.daily(account.userId) } catch (e: Exception) { note = e.message }
    }
    LaunchedEffect(Unit) { reload() }
    val d = data ?: return
    val dailyId = d.optString("daily_id")
    val record = d.optJSONArray("record")
    val todayStr = remember { java.text.SimpleDateFormat("dd", java.util.Locale.US).format(java.util.Date()) }
    var todaySigned = false
    var todayIndex = -1
    if (record != null) {
        for (w in 0 until record.length()) {
            val week = record.optJSONArray(w) ?: continue
            for (i in 0 until week.length()) {
                val day = week.optJSONObject(i) ?: continue
                if (day.optString("date") == todayStr) {
                    todayIndex = w * 7 + i
                    todaySigned = day.optBoolean("signed", false)
                }
            }
        }
    }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("每日签到", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(d.optString("event_name"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    if (todaySigned || busy) return@Button
                    busy = true
                    scope.launch {
                        try {
                            val r = JmApi.dailyCheck(account.userId, dailyId)
                            note = r.optString("msg").ifEmpty { "签到成功" }
                            reload()
                        } catch (e: Exception) { note = e.message }
                        busy = false
                    }
                }, enabled = !todaySigned && !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Pink),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    Text(if (todaySigned) "已签到" else "签到", color = Color.White, fontSize = 13.sp)
                }
            }
            Text("连签 3 天 +${d.optString("three_days_coin")} 金币 · 7 天 +${d.optString("seven_days_coin")} 金币 · 补签 ${d.optString("makeup_used")}/${d.optString("makeup_limit")}",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
            if (record != null) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (w in 0 until record.length()) {
                        val week = record.optJSONArray(w) ?: continue
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            for (i in 0 until week.length()) {
                                val day = week.optJSONObject(i)
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    if (day == null) { Text("") } else {
                                        val signed = if (day.isNull("signed")) null else day.optBoolean("signed")
                                        val isToday = day.optString("date") == todayStr
                                        Surface(shape = RoundedCornerShape(8.dp),
                                            color = when {
                                                signed == true -> Pink
                                                isToday -> MaterialTheme.colorScheme.surface
                                                else -> Color.Transparent
                                            },
                                            border = if (isToday && signed != true) androidx.compose.foundation.BorderStroke(1.5.dp, Pink) else null,
                                            modifier = Modifier.size(30.dp)) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    when {
                                                        day.optBoolean("bonus") -> "★"
                                                        signed == true -> "✓"
                                                        else -> day.optString("date").trimStart('0')
                                                    },
                                                    fontSize = 11.sp,
                                                    color = if (signed == true) Color.White else MaterialTheme.colorScheme.onSurface)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            note?.let { Text(it, fontSize = 12.sp, color = Pink, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

@Composable
fun FavoritesSection(router: Router, account: AccountStore, library: LibraryStore, loggedIn: Boolean) {
    var tab by remember { mutableIntStateOf(if (loggedIn) 0 else 1) } // 0 cloud, 1 local
    var query by remember { mutableStateOf("") }
    var cloud by remember { mutableStateOf<List<JmApi.Album>>(emptyList()) }
    var cloudTotal by remember { mutableIntStateOf(0) }
    var local by remember { mutableStateOf(library.localFavorites()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(loggedIn) {
        local = library.localFavorites()
        if (loggedIn) {
            scope.launch {
                try {
                    val (total, first) = JmApi.favorites(1)
                    cloudTotal = total; cloud = first
                } catch (e: Exception) {}
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("我的收藏", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.width(12.dp))
        if (loggedIn) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("云端 $cloudTotal") })
            Spacer(Modifier.width(6.dp))
        }
        FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("本地 ${local.size}") })
    }
    OutlinedTextField(query, { query = it }, placeholder = { Text("搜收藏（标题/作者）") }, singleLine = true,
        shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
    val source = if (tab == 0 && loggedIn) cloud else local
    val filtered = if (query.isBlank()) source else source.filter {
        it.name.contains(query, true) || it.author.contains(query, true)
    }
    if (filtered.isEmpty()) {
        Text("没有匹配的收藏", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
    } else {
        LazyVerticalGrid(GridCells.Fixed(3), modifier = Modifier.heightIn(max = 2000.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            userScrollEnabled = false) {
            items(filtered, key = { it.id }) { AlbumCard(it, router) }
        }
    }
}

// ---------------- Detail ----------------
@Composable
fun DetailScreen(dest: DetailDest, router: Router, account: AccountStore, library: LibraryStore) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var fav by remember { mutableStateOf(library.isFavorite(dest.id)) }
    var tagsExpanded by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(dest.id) {
        try {
            val dd = JmApi.album(dest.id)
            detail = dd
            val t = dd.optJSONArray("tags")
            val tl = mutableListOf<String>()
            if (t != null) for (i in 0 until t.length()) tl.add(t.optString(i))
            BlockTags.remember(dest.id, tl)
        } catch (e: Exception) { error = e.message }
        fav = library.isFavorite(dest.id)
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "详情") { router.pop() }
        val d = detail
        when {
            d == null && error == null -> LoadingBox()
            error != null -> ErrorText(error!!)
            d != null -> {
                val name = d.optString("name")
                val authors = mutableListOf<String>()
                val a = d.opt("author")
                if (a is org.json.JSONArray) for (i in 0 until a.length()) authors.add(a.optString(i))
                else if (a is String && a.isNotEmpty()) authors.add(a)
                val tags = mutableListOf<String>()
                val t = d.optJSONArray("tags"); if (t != null) for (i in 0 until t.length()) tags.add(t.optString(i))
                val chapters = parseSeries(d, dest.id)
                val albumModel = JmApi.Album(dest.id, name, authors.joinToString(" / "), tags, d.optString("total_views"), d.optString("likes"))
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Row {
                        CoverImage(dest.id, Modifier.width(120.dp))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(name, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text("浏览 ${d.optString("total_views")} · 点赞 ${d.optString("likes")}", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("章节 ${chapters.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (authors.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("作者", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            authors.forEach { au ->
                                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.clickable { router.push(AuthorDest(au)) }) {
                                    Text(au, fontSize = 13.sp, color = Pink, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                                }
                            }
                        }
                    }
                    if (tags.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("标签", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val shown = if (tagsExpanded) tags else tags.take(8)
                        FlowRowTags(shown) { tag -> BlockTags.add(tag); toast = "已屏蔽标签「$tag」，列表里将不再显示" }
                        if (tags.size > 8) {
                            TextButton(onClick = { tagsExpanded = !tagsExpanded }) {
                                Text(if (tagsExpanded) "收起" else "展开全部 ${tags.size} 个", color = Pink, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { if (chapters.isNotEmpty()) router.push(ReaderDest(chapters.first().first, name)) },
                            colors = ButtonDefaults.buttonColors(containerColor = Pink)) { Text("开始阅读", color = Color.White) }
                        OutlinedButton(onClick = {
                            if (fav) { library.removeFavorite(dest.id); fav = false; toast = "已取消本地收藏" }
                            else {
                                library.addFavorite(albumModel); fav = true
                                if (account.isLoggedIn) scope.launch { try { JmApi.addFavorite(dest.id) } catch (e: Exception) {} }
                                toast = "已收藏"
                            }
                        }) { Text(if (fav) "已收藏" else "收藏", color = Pink) }
                        OutlinedButton(onClick = { DownloadManager.enqueue(context, albumModel); toast = "已加入下载，去下载页看进度" }) {
                            Text("下载", color = Pink)
                        }
                    }
                    toast?.let { Text(it, color = Pink, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
                    Spacer(Modifier.height(18.dp))
                    Text("章节", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    chapters.forEach { (cid, cname) ->
                        Row(Modifier.fillMaxWidth().clickable { router.push(ReaderDest(cid, name)) }
                            .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(cname.ifEmpty { "第 1 话" }, fontSize = 14.sp)
                            Spacer(Modifier.weight(1f))
                            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FlowRowTags(tags: List<String>, onLongPress: (String) -> Unit = {}) {
    var x = 0f
    Column {
        var row = mutableListOf<String>()
        val rows = mutableListOf<List<String>>()
        // simple chunking: 4 per row is fine for display purposes
        tags.chunked(4).forEach { rows.add(it) }
        rows.forEach { r ->
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.forEach { tag ->
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { onLongPress(tag) })) {
                        Text(tag, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                }
            }
        }
    }
}

// ---------------- Author works ----------------
@Composable
fun AuthorScreen(dest: AuthorDest, router: Router) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title = dest.name) { router.pop() }
        PagedGrid(loader = { p ->
            val (_, all) = JmApi.search(dest.name, p)
            all.filter { it.author.contains(dest.name) }
        }, router = router, key = "author-${dest.name}")
    }
}

// ---------------- Downloads page ----------------
@Composable
fun DownloadsScreen(router: Router) {
    val context = LocalContext.current
    var viewing by remember { mutableStateOf<File?>(null) }
    if (viewing != null) {
        PdfViewerScreen(viewing!!) { viewing = null }
        return
    }
    var files by remember { mutableStateOf(DownloadManager.files(context)) }
    LaunchedEffect(DownloadManager.tasks.map { it.state }) { files = DownloadManager.files(context) }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "下载") { router.pop() }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (DownloadManager.tasks.isNotEmpty()) {
                Text("任务", fontWeight = FontWeight.Bold)
                DownloadManager.tasks.forEach { t ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(t.title, fontSize = 14.sp, maxLines = 1)
                        Text(when (t.state) {
                            DownloadManager.State.RUNNING -> "下载中 ${t.done}/${t.total} 页"
                            DownloadManager.State.DONE -> "已完成（也在系统下载/JMReader 里）"
                            DownloadManager.State.FAILED -> "失败" + (t.error?.let { "：$it" } ?: "")
                        }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (t.state == DownloadManager.State.FAILED) {
                            TextButton(onClick = { DownloadManager.enqueue(context, t.album) }) { Text("继续", color = Pink) }
                        }
                        if (t.state == DownloadManager.State.RUNNING && t.total > 0) {
                            LinearProgressIndicator(progress = { t.done.toFloat() / t.total }, color = Pink,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }
                Spacer(Modifier.height(14.dp))
            }
            Text("已下载", fontWeight = FontWeight.Bold)
            if (files.isEmpty()) Text("暂无", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
            files.forEach { f ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = f.extension == "pdf") { viewing = f }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, fontSize = 13.sp, maxLines = 1)
                        Text("%.1f MB".format(f.length() / 1048576.0), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { DownloadManager.share(context, f) }) { Text("分享", color = Pink) }
                    TextButton(onClick = { f.delete(); files = DownloadManager.files(context) }) { Text("删除") }
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

// ---------------- Blocked tags ----------------
@Composable
fun BlockedTagsScreen(router: Router) {
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "屏蔽标签") { router.pop() }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, placeholder = { Text("输入要屏蔽的标签") },
                    singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = { BlockTags.add(input); input = "" }, enabled = input.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Pink)) {
                    Text("添加", color = Color.White)
                }
            }
            Spacer(Modifier.height(14.dp))
            if (BlockTags.blockedTags.isEmpty()) {
                Text("还没有屏蔽任何标签。在漫画详情页长按标签也可以快速屏蔽。",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                BlockTags.blockedTags.forEach { tag ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tag, fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { BlockTags.remove(tag) }) { Text("取消屏蔽", color = Pink, fontSize = 12.sp) }
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("在首页、分类、搜索等列表中，标题或作者含屏蔽词的作品会直接隐藏；作品的真实标签会随着浏览自动记录，之后按标签过滤。收藏与历史不受影响。",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
