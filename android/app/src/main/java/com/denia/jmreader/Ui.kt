package com.denia.jmreader

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

val Pink = Color(0xFFFF2E88)
val Blue = Color(0xFF0091FF)

@Composable
fun JmTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = Pink, secondary = Blue,
        background = Color(0xFF0E0E12), surface = Color(0xFF17171D), surfaceVariant = Color(0xFF22222A),
    ) else lightColorScheme(
        primary = Pink, secondary = Blue,
        background = Color(0xFFF7F7FA), surface = Color.White, surfaceVariant = Color(0xFFECECF1),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

// ---------- navigation ----------
sealed interface Dest
data class DetailDest(val id: String) : Dest
data class ReaderDest(val chapterId: String, val albumTitle: String) : Dest
data class AuthorDest(val name: String) : Dest
data class CategoryDest(val slug: String, val name: String) : Dest
object DownloadsDest : Dest

class Router {
    val stack = mutableStateListOf<Dest>()
    fun push(d: Dest) { stack.add(d) }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
}

// ---------- shared bits ----------
@Composable
fun CoverImage(id: String, modifier: Modifier = Modifier) {
    AsyncImage(
        model = JmApi.coverUrl(id),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(8.dp))
    )
}

@Composable
fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Pink)
    }
}

@Composable
fun ErrorText(msg: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(msg, color = MaterialTheme.colorScheme.error)
    }
}

// ---------- downloads (PDF into app-private Downloads, shareable via FileProvider) ----------
object DownloadManager {
    enum class State { RUNNING, DONE, FAILED }
    class Task(val albumId: String, val title: String, state0: State, done0: Int, total0: Int) {
        var state by androidx.compose.runtime.mutableStateOf(state0)
        var done by androidx.compose.runtime.mutableIntStateOf(done0)
        var total by androidx.compose.runtime.mutableIntStateOf(total0)
    }
    val tasks = mutableStateListOf<Task>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun dir(context: Context): File = File(context.filesDir, "Downloads").apply { mkdirs() }
    fun files(context: Context): List<File> =
        dir(context).listFiles { f -> f.extension == "pdf" || f.extension == "cbz" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun share(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension == "pdf") "application/pdf" else "application/vnd.comicbook+zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun enqueue(context: Context, album: JmApi.Album) {
        if (tasks.any { it.albumId == album.id && it.state == State.RUNNING }) return
        val task = Task(album.id, album.name, State.RUNNING, 0, 0)
        tasks.add(0, task)
        val appCtx = context.applicationContext
        scope.launch {
            try {
                val detail = JmApi.album(album.id)
                val chapters = parseSeries(detail, album.id)
                val totalPages = chapters.size // progress counted per chapter-page batch below (pages refine it)
                var pageTotal = 0
                var pageDone = 0
                // first pass: count pages
                val chapterData = mutableListOf<Pair<String, List<String>>>()
                for ((cid, _) in chapters) {
                    val ch = JmApi.chapter(cid)
                    val imgs = ch.optJSONArray("images")
                    val files = (0 until (imgs?.length() ?: 0)).map { imgs!!.optString(it) }
                    chapterData.add(cid to files)
                    pageTotal += files.size
                }
                withContext(Dispatchers.Main) { task.total = pageTotal; task.done = 0 }
                val outFile = File(dir(appCtx), "${album.id}_${album.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)}.pdf")
                val doc = android.graphics.pdf.PdfDocument()
                try {
                    var pageNum = 1
                    for ((cid, files) in chapterData) {
                        for (f in files) {
                            val bytes = JmApi.fetchBytes(JmApi.photoUrl(cid, f))
                            val bmp = Descramble.decode(bytes, cid, f)
                            val info = android.graphics.pdf.PdfDocument.PageInfo.Builder(bmp.width, bmp.height, pageNum).create()
                            val page = doc.startPage(info)
                            page.canvas.drawBitmap(bmp, 0f, 0f, null)
                            doc.finishPage(page)
                            if (!bmp.isRecycled) bmp.recycle()
                            pageNum++
                            pageDone++
                            withContext(Dispatchers.Main) { task.done = pageDone }
                        }
                    }
                    outFile.outputStream().buffered().use { doc.writeTo(it) }
                } finally {
                    doc.close()
                }
                withContext(Dispatchers.Main) { task.state = State.DONE }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { task.state = State.FAILED }
            }
        }
    }
}

/** album.series -> ordered (chapterId, name) list; falls back to the album itself as a single chapter. */
fun parseSeries(detail: JSONObject, albumId: String): List<Pair<String, String>> {
    val arr = detail.optJSONArray("series")
    val out = mutableListOf<Triple<String, String, Int>>()
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Triple(o.optString("id"), o.optString("name"), o.optInt("sort", i)))
        }
    }
    val sorted = out.sortedBy { it.third }.map { it.first to it.second }.filter { it.first.isNotEmpty() }
    return if (sorted.isEmpty()) listOf(albumId to detail.optString("name")) else sorted
}
