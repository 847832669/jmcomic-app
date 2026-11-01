package com.denia.jmreader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AlbumCard(album: JmApi.Album, router: Router, modifier: Modifier = Modifier) {
    Column(modifier.clickable { router.push(DetailDest(album.id)) }) {
        CoverImage(album.id, Modifier.fillMaxWidth())
        Text(album.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, lineHeight = 15.sp,
            modifier = Modifier.padding(top = 4.dp))
        if (album.author.isNotEmpty()) {
            Text(album.author, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PagedGrid(loader: suspend (Int) -> List<JmApi.Album>, router: Router, key: Any) {
    var items by remember(key) { mutableStateOf<List<JmApi.Album>>(emptyList()) }
    var page by remember(key) { mutableIntStateOf(0) }
    var loading by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var hasMore by remember(key) { mutableStateOf(true) }
    val gridState = rememberLazyGridState()

    suspend fun load(next: Int) {
        loading = true
        try {
            val got = loader(next)
            items = if (next == 1) got else items + got
            page = next
            if (got.isEmpty()) hasMore = false
        } catch (e: Exception) { error = e.message }
        loading = false
    }
    LaunchedEffect(key) { load(1) }
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            hasMore && !loading && items.isNotEmpty() && last >= items.size - 6
        }
    }
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) load(page + 1) }

    when {
        loading && items.isEmpty() -> LoadingBox()
        error != null && items.isEmpty() -> ErrorText(error!!)
        else -> LazyVerticalGrid(GridCells.Fixed(3), state = gridState,
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id }) { AlbumCard(it, router) }
            if (loading) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Pink) }
            }
        }
    }
}

// ---------------- Home ----------------
private data class Channel(val name: String, val slug: String?) // slug null: 推荐 / latest / random

@Composable
fun HomeScreen(router: Router, goProfile: () -> Unit) {
    var categories by remember { mutableStateOf<List<JmApi.Category>>(emptyList()) }
    LaunchedEffect(Unit) { try { categories = JmApi.categories() } catch (e: Exception) {} }
    val channels = remember(categories) {
        listOf(Channel("推荐", null), Channel("最新", "latest"), Channel("随机", "random")) +
            categories.filter { it.slug.isNotEmpty() }.map { Channel(it.name, it.slug) }
    }
    var selected by remember { mutableIntStateOf(0) }
    val running = DownloadManager.tasks.any { it.state == DownloadManager.State.RUNNING }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.mipmap.ic_launcher), contentDescription = null,
                modifier = Modifier.size(38.dp).clip(CircleShape).clickable { goProfile() })
            Spacer(Modifier.width(10.dp))
            Text("JM Reader", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { router.push(DownloadsDest) }) {
                    Icon(Icons.Default.Download, contentDescription = "下载")
                }
                if (running) {
                    Box(Modifier.size(9.dp).clip(CircleShape)
                        .align(Alignment.TopEnd)
                        .background(Pink, CircleShape))
                }
            }
        }
        ScrollableChannelRow(channels.map { it.name }, selected) { selected = it }
        val ch = channels.getOrNull(selected) ?: channels.first()
        when {
            ch.slug == null -> RecommendContent(router)
            ch.slug == "latest" -> PagedGrid(loader = { p -> JmApi.latest(p) }, router = router, key = "latest")
            ch.slug == "random" -> PagedGrid(loader = { p -> if (p == 1) JmApi.randomRecommend() else emptyList() }, router = router, key = "random")
            else -> PagedGrid(loader = { p -> JmApi.categoryFilter(ch.slug, p) }, router = router, key = "cat-${ch.slug}")
        }
    }
}

@Composable
fun ScrollableChannelRow(names: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(names.indices.toList()) { i ->
            val sel = i == selected
            Surface(
                shape = RoundedCornerShape(50),
                color = if (sel) Pink else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable { onSelect(i) }
            ) {
                Text(names[i], fontSize = 13.sp,
                    color = if (sel) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
            }
        }
    }
}

@Composable
fun RecommendContent(router: Router) {
    var sections by remember { mutableStateOf<List<Pair<String, List<JmApi.Album>>>?>(null) }
    var randoms by remember { mutableStateOf<List<JmApi.Album>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            sections = JmApi.promote().filter { it.first != "連載更新" }
            randoms = JmApi.randomRecommend()
        } catch (e: Exception) { error = e.message }
    }
    when {
        sections == null && error == null -> LoadingBox()
        error != null -> ErrorText(error!!)
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 10.dp)) {
            sections?.forEach { (title, albums) ->
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(albums, key = { it.id }) { AlbumCard(it, router, Modifier.width(108.dp)) }
                }
            }
            if (randoms.isNotEmpty()) {
                Text("随机推荐", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                Column(Modifier.padding(horizontal = 12.dp)) {
                    randoms.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { AlbumCard(it, router, Modifier.weight(1f)) }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

// ---------------- Search ----------------
@Composable
fun SearchScreen(router: Router, history: HistoryStore) {
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf<String?>(null) }
    var queries by remember { mutableStateOf(history.queries()) }

    Column(Modifier.fillMaxSize().padding(top = 10.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("搜索标题 / 作者") },
            singleLine = true,
            shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            trailingIcon = {
                TextButton(onClick = {
                    val q = query.trim()
                    if (q.isNotEmpty()) { submitted = q; history.add(q); queries = history.queries() }
                }) { Text("搜索", color = Pink) }
            }
        )
        val s = submitted
        if (s == null) {
            if (queries.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("历史", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { history.clear(); queries = emptyList() }) { Text("清空", fontSize = 12.sp) }
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(queries) { q ->
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { query = q; submitted = q }) {
                            Text(q, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                }
            }
            Text("同人推荐", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            PagedGrid(loader = { p -> if (p == 1) JmApi.categoryFilter("doujin", 1) else emptyList() }, router = router, key = "doujin-rec")
        } else {
            PagedGrid(loader = { p -> JmApi.search(s, p).second }, router = router, key = "search-$s")
        }
    }
}

// ---------------- Categories ----------------
@Composable
fun CategoriesScreen(router: Router) {
    var cats by remember { mutableStateOf<List<JmApi.Category>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { try { cats = JmApi.categories() } catch (e: Exception) { error = e.message } }
    when {
        cats == null && error == null -> LoadingBox()
        error != null -> ErrorText(error!!)
        else -> androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
            items(cats!!.filter { it.slug.isNotEmpty() }, key = { it.slug }) { c ->
                Row(Modifier.fillMaxWidth().clickable { router.push(CategoryDest(c.slug, c.name)) }
                    .padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, fontSize = 15.sp)
                    Spacer(Modifier.weight(1f))
                    Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
            }
        }
    }
}

@Composable
fun CategoryPage(dest: CategoryDest, router: Router) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title = dest.name) { router.pop() }
        PagedGrid(loader = { p -> JmApi.categoryFilter(dest.slug, p) }, router = router, key = "catpage-${dest.slug}")
    }
}

@Composable
fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("‹ 返回", color = Pink, fontSize = 15.sp) }
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
