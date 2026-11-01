package com.denia.jmreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { JmApp() }
    }
}

// Display order: 首页 / 分类 / 个人 / 搜索 (same as the iOS app)
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JmApp() {
    val context = LocalContext.current
    val account = remember { AccountStore(context).apply { syncApi() } }
    val library = remember { LibraryStore(context) }
    val history = remember { HistoryStore(context) }
    val router = remember { Router() }
    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()
    val tab = pagerState.currentPage

    BackHandler(enabled = router.stack.isNotEmpty()) { router.pop() }
    BackHandler(enabled = router.stack.isEmpty() && tab != 0) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    JmTheme {
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
                    }
                }
            }
        }
    }
}
