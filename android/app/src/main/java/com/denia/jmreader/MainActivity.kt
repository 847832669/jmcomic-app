package com.denia.jmreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    remember { BlockTags.init(context) }
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
        if (viewingExternal != null) {
            PdfViewerScreen(viewingExternal!!) { viewingExternal = null }
            return@JmTheme
        }
        if (externalPdf != null) {
            ExternalPdfOpener(externalPdf,
                onReady = { f -> viewingExternal = f; onExternalPdfDone() },
                onFail = { onExternalPdfDone() })
            return@JmTheme
        }
        Scaffold(
            bottomBar = {
                if (router.stack.isEmpty()) {
                    NavigationBar {
                        val items = listOf(
                            Triple("首页", Icons.Default.Home, 0),
                            Triple("分类", Icons.Default.Apps, 1),
                            Triple("个人", Icons.Default.Person, 2),
                            Triple("搜索", Icons.Default.Search, 3),
                        )
                        items.forEach { (label, icon, page) ->
                            NavigationBarItem(
                                selected = tab == page,
                                onClick = { scope.launch { pagerState.animateScrollToPage(page) } },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                val top = router.stack.lastOrNull()
                if (top == null) {
                    HorizontalPager(state = pagerState) { page ->
                        when (page) {
                            0 -> HomeScreen(router) { scope.launch { pagerState.animateScrollToPage(2) } }
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
                    }
                }
            }
        }
    }
}
