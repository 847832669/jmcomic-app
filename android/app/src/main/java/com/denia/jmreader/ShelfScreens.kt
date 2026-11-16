package com.denia.jmreader

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil

// ---------------- Bookshelf (v2.30: replaces the Categories tab) ----------------

private sealed interface ShelfItem {
    data class Dl(val file: File) : ShelfItem
    data class Local(val book: LocalBook) : ShelfItem
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookshelfScreen(router: Router) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var segment by remember { mutableIntStateOf(0) }       // 0 全部 / 1 下载 / 2 导入
    var typeFilter by remember { mutableIntStateOf(0) }    // 0 全部 / 1 漫画 / 2 小说
    var categoryFilter by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var deleteDl by remember { mutableStateOf<File?>(null) }
    var deleteLocal by remember { mutableStateOf<LocalBook?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBatchDelete by remember { mutableStateOf(false) }
    LaunchedEffect(segment, typeFilter, categoryFilter) { selection = emptySet() }

    fun itemKey(item: ShelfItem): String = when (item) {
        is ShelfItem.Dl -> "dl:" + item.file.name
        is ShelfItem.Local -> "local:" + item.book.id
    }
    fun toggleKey(k: String) { selection = if (k in selection) selection - k else selection + k }

    var dlFiles by remember { mutableStateOf(DownloadManager.files(context)) }
    LaunchedEffect(DownloadManager.tasks.map { it.state }, segment) {
        dlFiles = DownloadManager.files(context)
    }
    val tick = LocalBooks.progressTick
    val locals = LocalBooks.books

    val categories = remember(dlFiles) {
        dlFiles.map { DownloadManager.categoryOf(context, it) }.filter { it.isNotEmpty() }.distinct().sorted()
    }
    val hasUncategorized = dlFiles.any { DownloadManager.categoryOf(context, it).isEmpty() }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            importing = true
            scope.launch {
                val (added, failed) = LocalBooks.importUris(context, uris)
                importing = false
                note = buildString {
                    if (added.isNotEmpty()) append("已导入 ${added.size} 本：${added.joinToString("、")}")
                    if (failed.isNotEmpty()) {
                        if (isNotEmpty()) append("\n")
                        append("失败 ${failed.size} 个：${failed.joinToString("、")}")
                    }
                    if (added.isEmpty() && failed.isEmpty()) append("没有选到文件")
                }
            }
        }
    }

    val showDownloads = segment != 2
    val showLocals = segment != 1
    val gridItems = mutableListOf<ShelfItem>()
    if (showDownloads) {
        val filtered = if (segment == 1) {
            when {
                categoryFilter == null -> dlFiles
                categoryFilter == "" -> dlFiles.filter { DownloadManager.categoryOf(context, it).isEmpty() }
                else -> dlFiles.filter { DownloadManager.categoryOf(context, it) == categoryFilter }
            }
        } else dlFiles
        filtered.forEach { gridItems.add(ShelfItem.Dl(it)) }
    }
    if (showLocals) {
        val filtered = when (typeFilter) {
            1 -> locals.filter { it.kind != LocalKind.NOVEL }
            2 -> locals.filter { it.kind == LocalKind.NOVEL }
            else -> locals
        }
        filtered.forEach { gridItems.add(ShelfItem.Local(it)) }
    }
    val railEntries = ReadHistory.entries.take(10)
    val showRail = segment == 0 && railEntries.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("我的书架", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (selecting) {
                TextButton(onClick = { selecting = false; selection = emptySet() }) {
                    Text("完成", color = Pink, fontWeight = FontWeight.SemiBold)
                }
            } else {
                TextButton(enabled = gridItems.isNotEmpty(), onClick = { selecting = true }) {
                    Text("多选", color = if (gridItems.isEmpty())
                        MaterialTheme.colorScheme.onSurfaceVariant else Pink)
                }
            }
            GlassSurface(shape = CircleShape, color = Pink,
                modifier = Modifier.size(36.dp).clickable { launcher.launch(arrayOf("*/*")) }) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Add, contentDescription = "导入", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
        // Segmented control: 全部 / 下载 / 导入
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "下载", "导入").forEachIndexed { i, label ->
                val sel = segment == i
                GlassSurface(shape = RoundedCornerShape(50),
                    color = if (sel) Pink else MaterialTheme.colorScheme.surface,
                    border = if (sel) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.weight(1f).clickable {
                        segment = i
                        if (i == 1) typeFilter = 0
                    }) {
                    Text(label, fontSize = 13.sp,
                        color = if (sel) Color.White else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
        // Filter chips
        if (segment == 1) {
            LazyRow(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip("全部", categoryFilter == null) { categoryFilter = null } }
                items(categories) { c -> FilterChip(c, categoryFilter == c) { categoryFilter = c } }
                if (hasUncategorized) item { FilterChip("未分类", categoryFilter == "") { categoryFilter = "" } }
            }
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip("全部", typeFilter == 0) { typeFilter = 0 } }
                item { FilterChip("漫画", typeFilter == 1) { typeFilter = 1 } }
                item { FilterChip("小说", typeFilter == 2) { typeFilter = 2 } }
            }
        }

        Box(Modifier.fillMaxSize()) {
        if (gridItems.isEmpty() && !showRail) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (segment == 0) {
                    IosCard(Modifier.fillMaxWidth(), onClick = { launcher.launch(arrayOf("*/*")) }) {
                        Column(Modifier.padding(18.dp)) {
                            Text("书架还是空的", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text("下载的漫画会出现在这里，也可以导入自己的漫画和小说（CBZ / ZIP / PDF / EPUB / TXT）。",
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(10.dp))
                            Text("导入文件", fontSize = 14.sp, color = Pink, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Text(if (segment == 1) "这个分类下还没有下载" else "还没有导入的书，点右上角 + 导入",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyVerticalGrid(columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()) {
                if (showRail) {
                    item(span = { GridItemSpan(3) }) {
                        Column {
                            Text("最近阅读", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(railEntries, key = { it.albumId }) { entry ->
                                    CoverImage(entry.albumId, Modifier.width(96.dp)
                                        .clickable { router.push(ReaderDest(entry.chapterId, entry.title, entry.albumId)) })
                                }
                            }
                        }
                    }
                }
                items(gridItems, key = { item ->
                    when (item) {
                        is ShelfItem.Dl -> "dl:" + item.file.name
                        is ShelfItem.Local -> "local:" + item.book.id
                    }
                }) { item ->
                    when (item) {
                        is ShelfItem.Dl -> DownloadCell(item.file, context,
                            onOpen = { if (item.file.extension == "pdf") router.push(PdfDest(item.file.absolutePath)) else DownloadManager.share(context, item.file) },
                            onLongPress = { deleteDl = item.file },
                            selecting = selecting,
                            selected = selection.contains(itemKey(item)),
                            onToggle = { toggleKey(itemKey(item)) })
                        is ShelfItem.Local -> LocalCell(item.book,
                            onOpen = {
                                when (item.book.kind) {
                                    LocalKind.PDF -> router.push(PdfDest(File(LocalBooks.dirOf(item.book), item.book.sourceName).absolutePath))
                                    LocalKind.COMIC -> router.push(LocalComicDest(item.book.id))
                                    LocalKind.NOVEL -> router.push(LocalNovelDest(item.book.id))
                                }
                            },
                            onLongPress = { deleteLocal = item.book },
                            selecting = selecting,
                            selected = selection.contains(itemKey(item)),
                            onToggle = { toggleKey(itemKey(item)) })
                    }
                }
            }
        }
        if (selecting) {
            GlassSurface(shape = RoundedCornerShape(50), prominent = true,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selection = gridItems.map { itemKey(it) }.toSet() }) {
                        Text("全选", color = Pink)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("已选 ${selection.size} 本", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = selection.isNotEmpty(), onClick = { showBatchDelete = true }) {
                        Text("删除", fontWeight = FontWeight.SemiBold,
                            color = if (selection.isEmpty())
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        }
    }

    if (importing) {
        GlassAlertDialog(onDismissRequest = {}, title = { Text("导入中") },
            text = { Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Pink, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp)); Text("正在处理文件…")
            } }, confirmButton = {})
    }
    note?.let { msg ->
        GlassAlertDialog(onDismissRequest = { note = null }, title = { Text("导入结果") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { note = null }) { Text("好", color = Pink) } })
    }
    deleteDl?.let { f ->
        GlassAlertDialog(onDismissRequest = { deleteDl = null },
            title = { Text("删除这本下载？") },
            text = { Text(DownloadManager.titleOf(context, f)) },
            confirmButton = { TextButton(onClick = {
                DownloadManager.forgetMeta(context, f.name); f.delete()
                dlFiles = DownloadManager.files(context); deleteDl = null
            }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteDl = null }) { Text("取消") } })
    }
    if (showBatchDelete) {
        val selItems = gridItems.filter { selection.contains(itemKey(it)) }
        val localCount = selItems.count { it is ShelfItem.Local }
        val dlCount = selItems.size - localCount
        GlassAlertDialog(onDismissRequest = { showBatchDelete = false },
            title = { Text("删除所选书籍？") },
            text = { Text(when {
                localCount > 0 && dlCount > 0 ->
                    "包含 $localCount 本导入书（文件删除后无法恢复）和 $dlCount 本下载（可重新下载）。"
                localCount > 0 -> "$localCount 本导入书的文件将被删除，无法恢复。"
                else -> "$dlCount 本下载将被删除，可重新下载。"
            }) },
            confirmButton = { TextButton(onClick = {
                selItems.forEach { item ->
                    when (item) {
                        is ShelfItem.Dl -> {
                            DownloadManager.forgetMeta(context, item.file.name); item.file.delete()
                        }
                        is ShelfItem.Local -> LocalBooks.delete(item.book)
                    }
                }
                dlFiles = DownloadManager.files(context)
                selection = emptySet(); selecting = false; showBatchDelete = false
            }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showBatchDelete = false }) { Text("取消") } })
    }
    deleteLocal?.let { b ->
        GlassAlertDialog(onDismissRequest = { deleteLocal = null },
            title = { Text("删除这本导入的书？") },
            text = { Text("《${b.title}》的文件会从设备上删除，删了要重新导入。") },
            confirmButton = { TextButton(onClick = { LocalBooks.delete(b); deleteLocal = null }) {
                Text("删除文件", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteLocal = null }) { Text("取消") } })
    }
}

@Composable
private fun SelectBadge(selected: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(22.dp).clip(CircleShape).background(Color.White),
        contentAlignment = Alignment.Center) {
        Icon(if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) Pink else Color.Gray, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun PageBadge(text: String, modifier: Modifier = Modifier) {
    GlassSurface(shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        modifier = modifier) {
        Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    GlassSurface(shape = RoundedCornerShape(50),
        color = if (selected) Pink else MaterialTheme.colorScheme.surface,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.clickable { onClick() }) {
        Text(label, fontSize = 12.sp,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadCell(file: File, context: android.content.Context, onOpen: () -> Unit,
    onLongPress: () -> Unit, selecting: Boolean = false, selected: Boolean = false,
    onToggle: () -> Unit = {}) {
    val albumId = DownloadManager.albumIdOf(context, file)
    val category = DownloadManager.categoryOf(context, file)
    Column(Modifier.combinedClickable(
        onClick = { if (selecting) onToggle() else onOpen() },
        onLongClick = if (selecting) null else onLongPress)) {
        Box {
            if (albumId.isNotEmpty()) {
                CoverImage(albumId, Modifier.fillMaxWidth())
            } else {
                PlaceholderCover("PDF")
            }
            if (selecting) SelectBadge(selected, Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Spacer(Modifier.height(5.dp))
        Text(DownloadManager.titleOf(context, file), fontSize = 12.sp, maxLines = 2,
            lineHeight = 15.sp, modifier = Modifier.fillMaxWidth())
        Text(if (category.isNotEmpty()) category else "JM 下载", fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalCell(book: LocalBook, onOpen: () -> Unit, onLongPress: () -> Unit,
    selecting: Boolean = false, selected: Boolean = false, onToggle: () -> Unit = {}) {
    val cover = LocalBooks.coverFile(book)
    Column(Modifier.combinedClickable(
        onClick = { if (selecting) onToggle() else onOpen() },
        onLongClick = if (selecting) null else onLongPress)) {
        Box {
            if (cover != null) {
                AsyncImage(model = cover, contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(10.dp)))
            } else {
                PlaceholderCover(when (book.kind) {
                    LocalKind.PDF -> "PDF"
                    LocalKind.NOVEL -> book.sourceName.substringAfterLast('.', "").uppercase()
                    LocalKind.COMIC -> "CBZ"
                })
            }
            val badge = when (book.kind) {
                LocalKind.PDF -> "PDF"
                LocalKind.NOVEL -> book.sourceName.substringAfterLast('.', "").uppercase()
                LocalKind.COMIC -> book.sourceName.substringAfterLast('.', "").uppercase()
            }
            GlassSurface(shape = RoundedCornerShape(6.dp), color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
                Text(badge, fontSize = 9.sp, color = Color.White,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
            }
            if (selecting) SelectBadge(selected, Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Spacer(Modifier.height(5.dp))
        Text(book.title, fontSize = 12.sp, maxLines = 2, lineHeight = 15.sp, modifier = Modifier.fillMaxWidth())
        Text(when (book.kind) {
            LocalKind.COMIC -> "${book.fileCount} 页"
            LocalKind.PDF -> "${book.fileCount} 页"
            LocalKind.NOVEL -> "${book.fileCount} 章"
        }, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun PlaceholderCover(label: String) {
    Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (label == "PDF") Icons.Default.PictureAsPdf else Icons.Default.MenuBook,
                contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------- local readers ----------------

@Composable
fun LocalReaderHost(dest: Dest, router: Router) {
    val bookId = when (dest) {
        is LocalComicDest -> dest.bookId
        is LocalNovelDest -> dest.bookId
        else -> return
    }
    val book = LocalBooks.books.firstOrNull { it.id == bookId }
    if (book == null) {
        LaunchedEffect(Unit) { router.pop() }
        return
    }
    when (book.kind) {
        LocalKind.COMIC -> LocalComicReaderScreen(book, router)
        LocalKind.NOVEL -> LocalNovelReaderScreen(book, router)
        LocalKind.PDF -> LaunchedEffect(Unit) { router.pop() }
    }
}

@Composable
private fun LocalComicReaderScreen(book: LocalBook, router: Router) {
    val start = LocalBooks.progressOf(book.id).coerceIn(0, maxOf(0, book.pageFiles.size - 1))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = start)
    LaunchedEffect(book.id) {
        snapshotFlow { listState.firstVisibleItemIndex }.collectLatest {
            LocalBooks.recordProgress(book.id, it)
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = book.title) { router.pop() }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(book.pageFiles, key = { it }) { name ->
                AsyncImage(model = File(LocalBooks.dirOf(book), name), contentDescription = null,
                    contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun LocalNovelReaderScreen(book: LocalBook, router: Router) {
    var allBlocks by remember(book.id) { mutableStateOf<List<List<NovelBlock>>?>(null) }
    LaunchedEffect(book.id) {
        allBlocks = withContext(Dispatchers.IO) {
            book.chapters.indices.map { LocalBooks.chapterBlocks(book, it) }
        }
    }
    val startChapter = remember(book.id) {
        LocalBooks.progressOf(book.id).coerceIn(0, maxOf(0, book.chapters.size - 1))
    }
    var curChapter by remember(book.id) { mutableIntStateOf(startChapter) }
    var chrome by remember { mutableStateOf(true) }
    val activity = LocalContext.current as? android.app.Activity
    androidx.compose.runtime.DisposableEffect(activity, chrome) {
        val controller = activity?.let {
            androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView)
        }
        controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chrome) controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        else controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }
    var showToc by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var jumpChapter by remember { mutableStateOf<Int?>(null) }
    val mode = Settings.novelMode
    val fontSize = Settings.novelFontSize
    val blocks = allBlocks

    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(chrome) {
            Column {
                TopBar(title = book.title) { router.pop() }
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(book.chapters.getOrNull(curChapter)?.title ?: "", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { showToc = true }) { Text("目录", color = Pink) }
                    TextButton(onClick = { showMenu = true }) { Text("AA", color = Pink) }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                blocks == null -> CircularProgressIndicator(color = Pink,
                    modifier = Modifier.align(Alignment.Center))
                mode == "paged" -> NovelPagedContent(book, blocks, fontSize, startChapter,
                    jumpChapter, { jumpChapter = null },
                    { c -> curChapter = c; LocalBooks.recordProgress(book.id, c) },
                    { chrome = !chrome })
                else -> NovelScrollContent(book, blocks, fontSize, startChapter,
                    jumpChapter, { jumpChapter = null },
                    { c -> curChapter = c; LocalBooks.recordProgress(book.id, c) },
                    { chrome = !chrome })
            }
        }
    }
    if (showToc) {
        GlassAlertDialog(onDismissRequest = { showToc = false },
            title = { Text("目录") },
            text = {
                LazyColumn(Modifier.height(400.dp)) {
                    items(book.chapters.indices.toList()) { i ->
                        Text(book.chapters[i].title, fontSize = 14.sp,
                            color = if (i == curChapter) Pink else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { jumpChapter = i; showToc = false }
                                .padding(vertical = 10.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showToc = false }) { Text("关闭", color = Pink) } })
    }
    if (showMenu) {
        GlassAlertDialog(onDismissRequest = { showMenu = false },
            title = { Text("阅读设置") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("阅读模式", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { Settings.updateNovelMode("scroll") }) {
                            Text("上下滚动", fontWeight = if (mode == "scroll") FontWeight.Bold else FontWeight.Normal,
                                color = if (mode == "scroll") Pink else MaterialTheme.colorScheme.onSurface)
                        }
                        TextButton(onClick = { Settings.updateNovelMode("paged") }) {
                            Text("左右翻页", fontWeight = if (mode == "paged") FontWeight.Bold else FontWeight.Normal,
                                color = if (mode == "paged") Pink else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("字号", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { if (fontSize > 13) Settings.updateNovelFontSize(fontSize - 1) }) {
                            Text("A-", color = Pink)
                        }
                        Text("$fontSize", fontSize = 14.sp)
                        TextButton(onClick = { if (fontSize < 26) Settings.updateNovelFontSize(fontSize + 1) }) {
                            Text("A+", color = Pink)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMenu = false }) { Text("完成", color = Pink) } })
    }
}

@Composable
private fun NovelBlockView(book: LocalBook, block: NovelBlock, fontSize: Int) {
    when (block.kind) {
        "image" -> {
            val f = block.file?.let { File(LocalBooks.dirOf(book), it) }
            if (f != null && f.exists()) {
                AsyncImage(model = f, contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            }
        }
        else -> {
            val paras = (block.text ?: "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            Column {
                for (para in paras) {
                    Text("　　" + para, fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.75f).sp,
                        modifier = Modifier.padding(bottom = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun NovelScrollContent(book: LocalBook, blocks: List<List<NovelBlock>>, fontSize: Int,
    startChapter: Int, jumpChapter: Int?, onJumpConsumed: () -> Unit,
    onChapter: (Int) -> Unit, onToggleChrome: () -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startChapter)
    LaunchedEffect(jumpChapter) {
        val j = jumpChapter ?: return@LaunchedEffect
        listState.animateScrollToItem(j)
        onJumpConsumed()
    }
    LaunchedEffect(book.id) {
        snapshotFlow { listState.firstVisibleItemIndex }.collectLatest { onChapter(it) }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState,
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onToggleChrome() } }) {
            items(blocks.indices.toList(), key = { it }) { ci ->
                Column(Modifier.padding(horizontal = 18.dp)) {
                    Spacer(Modifier.height(14.dp))
                    Text(book.chapters[ci].title, fontSize = (fontSize + 5).sp,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    for (block in blocks[ci]) NovelBlockView(book, block, fontSize)
                    Spacer(Modifier.height(26.dp))
                }
            }
        }
        // Live page estimate: realized item sizes stand in for the
        // whole book, the same live-estimate semantics as iOS.
        val info = listState.layoutInfo
        val viewport = info.viewportSize.height
        val visible = info.visibleItemsInfo
        if (viewport > 0 && visible.isNotEmpty() && info.totalItemsCount > 0) {
            val avg = visible.sumOf { it.size }.toFloat() / visible.size
            val totalEst = avg * info.totalItemsCount
            val first = visible.first()
            val offsetEst = first.index * avg - first.offset
            val total = ceil(totalEst / viewport).toInt().coerceAtLeast(1)
            val page = (offsetEst / viewport).toInt().coerceIn(0, total - 1) + 1
            PageBadge("$page / $total",
                Modifier.align(Alignment.BottomEnd).padding(14.dp))
        }
    }
}

private sealed interface NovelPage {
    val chapter: Int
    val chapterStart: Boolean
}

private data class NovelTextPage(override val chapter: Int,
    override val chapterStart: Boolean, val text: String) : NovelPage

private data class NovelImagePage(override val chapter: Int,
    override val chapterStart: Boolean, val file: File) : NovelPage

private fun paginateNovel(book: LocalBook, blocks: List<List<NovelBlock>>, fontSize: Int,
    pageWidthPx: Int, pageHeightPx: Int, measurer: TextMeasurer): List<NovelPage> {
    val style = TextStyle(fontSize = fontSize.sp, lineHeight = (fontSize * 1.75f).sp)
    val probe = measurer.measure("字", style, constraints = Constraints(maxWidth = pageWidthPx))
    val lineHeightPx = probe.size.height.toFloat().coerceAtLeast(1f)
    val linesPerPage = (pageHeightPx / lineHeightPx).toInt().coerceAtLeast(1)
    val out = mutableListOf<NovelPage>()
    blocks.forEachIndexed { ci, chapterBlocks ->
        var firstOfChapter = true
        for (block in chapterBlocks) {
            if (block.kind == "image") {
                val f = block.file?.let { File(LocalBooks.dirOf(book), it) }
                if (f != null && f.exists()) {
                    out.add(NovelImagePage(ci, firstOfChapter, f)); firstOfChapter = false
                }
            } else {
                val raw = block.text ?: continue
                val text = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                    .joinToString("\n") { "　　" + it }
                if (text.isEmpty()) continue
                val layout = measurer.measure(text, style,
                    constraints = Constraints(maxWidth = pageWidthPx))
                var startLine = 0
                while (startLine < layout.lineCount) {
                    val budget = (linesPerPage - if (firstOfChapter) 3 else 0).coerceAtLeast(1)
                    val endLine = minOf(layout.lineCount, startLine + budget)
                    val so = layout.getLineStart(startLine)
                    val eo = layout.getLineEnd(endLine - 1)
                    out.add(NovelTextPage(ci, firstOfChapter, text.substring(so, eo)))
                    firstOfChapter = false
                    startLine = endLine
                }
            }
        }
        if (firstOfChapter) out.add(NovelTextPage(ci, true, ""))
    }
    return out
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalTextApi::class)
@Composable
private fun NovelPagedContent(book: LocalBook, blocks: List<List<NovelBlock>>, fontSize: Int,
    startChapter: Int, jumpChapter: Int?, onJumpConsumed: () -> Unit,
    onChapter: (Int) -> Unit, onToggleChrome: () -> Unit) {
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val pageWidthPx = with(density) { (maxWidth - 40.dp).toPx() }.toInt().coerceAtLeast(80)
        val pageHeightPx = with(density) { (maxHeight - 24.dp).toPx() }.toInt().coerceAtLeast(80)
        val measurer = rememberTextMeasurer()
        val pages = remember(blocks, fontSize, pageWidthPx, pageHeightPx) {
            paginateNovel(book, blocks, fontSize, pageWidthPx, pageHeightPx, measurer)
        }
        val chapterStarts = remember(pages) {
            pages.mapIndexedNotNull { i, pg -> if (pg.chapterStart) pg.chapter to i else null }.toMap()
        }
        val pagerState = rememberPagerState(
            initialPage = chapterStarts[startChapter] ?: 0, pageCount = { pages.size })
        LaunchedEffect(jumpChapter) {
            val j = jumpChapter ?: return@LaunchedEffect
            chapterStarts[j]?.let { pagerState.animateScrollToPage(it) }
            onJumpConsumed()
        }
        LaunchedEffect(book.id) {
            snapshotFlow { pagerState.currentPage }.collectLatest { pg ->
                pages.getOrNull(pg)?.let { onChapter(it.chapter) }
            }
        }
        Box(Modifier.fillMaxSize()) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { p ->
                val page = pages[p]
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    if (page.chapterStart) {
                        Text(book.chapters[page.chapter].title,
                            fontSize = (fontSize + 5).sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                    }
                    when (page) {
                        is NovelTextPage -> Text(page.text, fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.75f).sp)
                        is NovelImagePage -> AsyncImage(model = page.file, contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(0.32f).fillMaxHeight().clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    scope.launch {
                        if (pagerState.currentPage > 0)
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                    }
                })
                Box(Modifier.weight(0.36f).fillMaxHeight().clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    onToggleChrome()
                })
                Box(Modifier.weight(0.32f).fillMaxHeight().clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    scope.launch {
                        if (pagerState.currentPage < pages.size - 1)
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    }
                })
            }
            if (pages.isNotEmpty()) {
                PageBadge("${pagerState.currentPage + 1} / ${pages.size}",
                    Modifier.align(Alignment.BottomEnd).padding(14.dp))
            }
        }
    }
}
