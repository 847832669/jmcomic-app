package com.denia.jmreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var pdfUri by mutableStateOf<android.net.Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent?.action == android.content.Intent.ACTION_VIEW) {
            pdfUri = intent.data
        }
        setContent { JmApp(pdfUri) { pdfUri = null } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // A launcher (MAIN) entry must clear any stale external-PDF state,
        // otherwise the app would keep reopening that PDF instead of its own UI.
        pdfUri = if (intent.action == android.content.Intent.ACTION_VIEW) intent.data else null
    }
}

/** Copies a PDF handed to us by the system (e.g. tapped in the Files app) to a local
 *  file, then hands it to the viewer via [onReady]; the Uri itself is consumed at once. */
@Composable
fun ExternalPdfOpener(uri: android.net.Uri, onReady: (java.io.File) -> Unit, onFail: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(uri) {
        val dest = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val out = java.io.File(context.cacheDir, "opened-external.pdf")
                val input = context.contentResolver.openInputStream(uri) ?: error("no stream")
                input.use { inp -> out.outputStream().use { o -> inp.copyTo(o) } }
                out
            }.getOrNull()
        }
        if (dest != null) onReady(dest) else onFail()
    }
    Box(Modifier.fillMaxSize())
}

// Display order: 首页 / 分类 / 个人 / 搜索 (same as the iOS app)
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JmApp(externalPdf: android.net.Uri? = null, onExternalPdfDone: () -> Unit = {}) {
    val context = LocalContext.current
    val account = remember { AccountStore(context).apply { syncApi() } }
    val library = remember { LibraryStore(context) }
    val history = remember { HistoryStore(context) }
    remember { BlockTags.init(context); ReadingStats.init(context); RemovedFavs.init(context) }
    remember { Lines.init(context) }
    remember { Settings.init(context) }
    remember { FolderRegistry.init(context) }
    LaunchedEffect(Unit) {
        JmApi.imgHost = Lines.currentImgHost()
        launch { Lines.refresh(false) }
    }
    val router = remember { Router() }
    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()
    val tab = pagerState.currentPage

    BackHandler(enabled = router.stack.isNotEmpty()) { router.pop() }
    BackHandler(enabled = router.stack.isEmpty() && tab != 0) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    var viewingExternal by remember { mutableStateOf<java.io.File?>(null) }
    JmTheme {
        val wallpaper = rememberGlassBackdrop()
        val scene = rememberGlassBackdrop()
        CompositionLocalProvider(LocalGlassBackdrop provides wallpaper) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().glassBackdrop(scene)) {
                    Box(Modifier.fillMaxSize().glassBackdrop(wallpaper)) { GlassWallpaper() }
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                        when {
                            viewingExternal != null -> PdfViewerScreen(viewingExternal!!) { viewingExternal = null }
                            externalPdf != null -> ExternalPdfOpener(externalPdf,
                                onReady = { f -> viewingExternal = f; onExternalPdfDone() },
                                onFail = { onExternalPdfDone() })
                            else -> {
                                val top = router.stack.lastOrNull()
                                if (top == null) {
                                    HorizontalPager(state = pagerState) { page ->
                                        when (page) {
                                            0 -> HomeScreen(router,
                                                goProfile = { scope.launch { pagerState.animateScrollToPage(2) } },
                                                goSearch = { scope.launch { pagerState.animateScrollToPage(3) } })
                                            1 -> CategoriesScreen(router)
                                            2 -> ProfileScreen(router, account, library)
                                            else -> SearchScreen(router, history)
                                        }
                                    }
                                } else {
                                    when (top) {
                                        is DetailDest -> DetailScreen(top, router, account, library)
                                        is ReaderDest -> ReaderScreen(top, router)
                                        is AuthorDest -> AuthorScreen(top, router)
                                        is CategoryDest -> CategoryPage(top, router)
                                        DownloadsDest -> DownloadsScreen(router)
                                        BlockedTagsDest -> BlockedTagsScreen(router)
                                        StorageDest -> StorageScreen(router)
                                        SettingsDest -> SettingsScreen(router, account)
                                        LinesDest -> LinesScreen(router)
                                    }
                                }
                            }
                        }
                    }
                }
                if (router.stack.isEmpty() && viewingExternal == null && externalPdf == null) {
                    LiquidGlassBottomBar(
                        selectedPage = tab,
                        pagePosition = pagerState.currentPage + pagerState.currentPageOffsetFraction,
                        backdrop = scene,
                        onSelect = { page -> scope.launch { pagerState.animateScrollToPage(page) } },
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}
