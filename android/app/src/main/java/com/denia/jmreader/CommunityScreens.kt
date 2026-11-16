package com.denia.jmreader

import android.graphics.Typeface
import android.text.style.StyleSpan
import android.text.style.URLSpan
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.HtmlCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Display server HTML as selectable native text, without a WebView or script execution. */
@Composable
fun CommunityText(html: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE,
                  onOverflow: ((Boolean) -> Unit)? = null) {
    val linkColor = MaterialTheme.colorScheme.primary
    val text = remember(html, linkColor) {
        val safe = html.replace(Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>"), "")
            .replace(Regex("(?is)<img\\b[^>]*>"), "[图片]")
        val markup = if (Regex("(?is)</?[a-z][^>]*>").containsMatchIn(safe)) safe
            else safe.replace("\r\n", "\n").replace("\n", "<br>")
        val plain = HtmlCompat.fromHtml(markup, HtmlCompat.FROM_HTML_MODE_LEGACY)
        buildAnnotatedString {
            append(plain.toString().trimEnd())
            plain.getSpans(0, plain.length, Any::class.java).forEach { span ->
                val start = plain.getSpanStart(span).coerceIn(0, length)
                val end = plain.getSpanEnd(span).coerceIn(start, length)
                if (start < end) when (span) {
                    is StyleSpan -> addStyle(SpanStyle(
                        fontWeight = if (span.style and Typeface.BOLD != 0) FontWeight.Bold else null,
                        fontStyle = if (span.style and Typeface.ITALIC != 0) FontStyle.Italic else null), start, end)
                    is URLSpan -> if (span.url.startsWith("https://", true) || span.url.startsWith("http://", true)) {
                        addLink(LinkAnnotation.Url(span.url), start, end)
                        addStyle(SpanStyle(color = linkColor), start, end)
                    }
                }
            }
        }
    }
    SelectionContainer {
        Text(text, modifier, fontSize = 14.sp, maxLines = maxLines,
            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface,
            onTextLayout = { onOverflow?.invoke(it.hasVisualOverflow) })
    }
}

@Composable
fun AlbumDescription(html: String) {
    var expanded by rememberSaveable(html) { mutableStateOf(false) }
    var overflow by remember(html) { mutableStateOf(false) }
    val plain = remember(html) { HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim() }
    IosCard(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("描述", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (plain.isBlank()) Text("暂无描述", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                CommunityText(html, maxLines = if (expanded) Int.MAX_VALUE else 6,
                    onOverflow = { if (!expanded) overflow = it })
                if (overflow || expanded) TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起描述" else "展开描述")
                }
            }
        }
    }
}

@Composable
private fun CommentCard(comment: JmComment, depth: Int = 0) {
    IosCard(Modifier.fillMaxWidth().padding(start = (depth.coerceAtMost(4) * 14).dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val avatar = JmApi.avatarUrl(comment.photo)
                if (avatar != null) {
                    AsyncImage(avatar, contentDescription = null,
                        modifier = Modifier.size(32.dp).clip(CircleShape))
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(comment.username, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (comment.time.isNotBlank()) Text(comment.time, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            CommunityText(comment.html)
        }
    }
}

@Composable
private fun CommentThreadCard(thread: JmCommentThread, depth: Int = 0) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CommentCard(thread.comment, depth = depth)
        thread.replies.forEach { CommentThreadCard(it, depth + 1) }
    }
}

@Composable
fun CommentsScreen(dest: CommentsDest, onBack: () -> Unit, isLoggedIn: Boolean,
                   onLogin: () -> Unit, source: CommunitySource = JmCommunitySource) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var comments by remember(dest.id) { mutableStateOf(emptyList<JmComment>()) }
    var nextPage by remember(dest.id) { mutableIntStateOf(1) }
    var pageSize by remember(dest.id) { mutableIntStateOf(10) }
    var total by remember(dest.id) { mutableStateOf<Int?>(null) }
    var hasMore by remember(dest.id) { mutableStateOf(true) }
    var loading by remember(dest.id) { mutableStateOf(false) }
    var loaded by remember(dest.id) { mutableStateOf(false) }
    var failure by remember(dest.id) { mutableStateOf<String?>(null) }
    var failedPage by remember(dest.id) { mutableIntStateOf(1) }
    var reload by remember(dest.id) { mutableIntStateOf(0) }
    var draft by rememberSaveable(dest.id) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun load(page: Int) {
        if (loading) return
        loading = true
        failure = null
        try {
            val result = source.comments(dest.id, page, pageSize)
            comments = ((if (page == 1) emptyList() else comments) + result.comments).distinctBy { it.stableKey }
            nextPage = page + 1
            pageSize = result.pageSize
            total = result.total
            hasMore = result.hasMore
            loaded = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failedPage = page; failure = e.message ?: "评论加载失败" }
        finally { loading = false }
    }

    val visibleThreads by remember(dest.id) {
        derivedStateOf {
            filterCommentThreadsByKeywords(threadComments(comments), Settings.commentBlockedKeywords)
        }
    }
    LaunchedEffect(dest.id, reload) { load(1) }
    LaunchedEffect(dest.id, listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: return@snapshotFlow null
            // Wait for appended rows to be measured before checking the old viewport again.
            if (layout.totalItemsCount != visibleThreads.size + 1) return@snapshotFlow null
            // Include the page number so a fully filtered page can immediately advance again.
            if (loaded && hasMore && !loading && failure == null &&
                lastVisible >= layout.totalItemsCount - 2) nextPage else null
        }.distinctUntilChanged().collect { page ->
            if (page != null) load(page)
        }
    }
    Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
        TopBar("评论${total?.let { " · $it" } ?: ""}", onBack)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(dest.title.ifBlank { "作品评论" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f), fontSize = 13.sp)
            TextButton(onClick = { reload++ }, enabled = !loading && !sending) { Text("刷新评论") }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(visibleThreads, key = { it.comment.stableKey }) { CommentThreadCard(it) }
            item {
                when {
                    loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(22.dp))
                            Text("正在加载评论", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    failure != null -> ErrorText(failure!!) { scope.launch { load(failedPage) } }
                    loaded && comments.isEmpty() -> Text("暂无评论，来发表第一条吧")
                    loaded && visibleThreads.isEmpty() && comments.isNotEmpty() && hasMore -> Text(
                        "正在筛选并加载更多评论", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    loaded && visibleThreads.isEmpty() && comments.isNotEmpty() -> Text(
                        "当前评论均命中屏蔽关键词", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    loaded && hasMore -> Spacer(Modifier.height(4.dp))
                    loaded -> Text("已显示全部评论", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (isLoggedIn) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                message?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassTextField(draft, { if (!sending) draft = it }, enabled = !sending,
                        placeholder = { Text("写下你的评论") }, maxLines = 5, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    GlassButton(onClick = {
                        if (!sending && draft.isNotBlank()) {
                            sending = true
                            message = null
                            scope.launch {
                                try {
                                    message = source.send(dest.id, draft.trim())
                                    draft = ""
                                    reload++
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { message = e.message ?: "发送结果未知，请刷新评论后确认" }
                                finally { sending = false }
                            }
                        }
                    }, enabled = !sending && !loading && draft.isNotBlank()) { Text(if (sending) "发送中" else "发送") }
                }
            }
        } else {
            GlassOutlinedButton(onLogin, Modifier.fillMaxWidth().padding(16.dp)) { Text("登录后发表评论") }
        }
    }
}
