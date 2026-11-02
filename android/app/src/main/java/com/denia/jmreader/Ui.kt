package com.denia.jmreader

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

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

// ---------- downloads (pages staged to disk first, then streamed into a PDF) ----------
object DownloadManager {
    enum class State { RUNNING, DONE, FAILED }
    class Task(val album: JmApi.Album, state0: State, done0: Int, total0: Int) {
        val albumId: String = album.id
        val title: String = album.name
        var state by androidx.compose.runtime.mutableStateOf(state0)
        var done by androidx.compose.runtime.mutableIntStateOf(done0)
        var total by androidx.compose.runtime.mutableIntStateOf(total0)
        var error by androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    val tasks = mutableStateListOf<Task>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun dir(context: Context): File = File(context.filesDir, "Downloads").apply { mkdirs() }
    private fun stagingDir(context: Context, albumId: String): File =
        File(dir(context), ".staging-$albumId").apply { mkdirs() }
    private fun pageFile(stage: File, idx: Int) = File(stage, "%05d.jpg".format(idx))

    fun files(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && (f.extension == "pdf" || f.extension == "cbz") }
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

    /** Best effort: also drop a copy into the system Downloads folder (Android 10+). */
    private fun exportToDownloads(context: Context, file: File) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/JMReader")
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            var ok = false
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                ok = true
            } catch (e: Exception) { /* fall through to cleanup */ }
            if (!ok) runCatching { resolver.delete(uri, null, null) }
        } catch (e: Exception) { /* export is best-effort; the in-app copy is authoritative */ }
    }

    fun enqueue(context: Context, album: JmApi.Album) {
        val existing = tasks.firstOrNull { it.albumId == album.id }
        if (existing?.state == State.RUNNING) return
        val task = existing ?: Task(album, State.RUNNING, 0, 0).also { tasks.add(0, it) }
        task.state = State.RUNNING
        task.error = null
        val appCtx = context.applicationContext
        scope.launch {
            try {
                val detail = JmApi.album(album.id)
                val chapters = parseSeries(detail, album.id)
                val chapterImages: List<List<String>> = coroutineScope {
                    chapters.map { (cid, _) ->
                        async {
                            val ch = JmApi.chapter(cid)
                            val imgs = ch.optJSONArray("images")
                            (0 until (imgs?.length() ?: 0)).map { imgs!!.optString(it) }
                        }
                    }.awaitAll()
                }
                val pages = mutableListOf<Pair<String, String>>()
                chapters.zip(chapterImages).forEach { (ref, files) ->
                    files.forEach { pages.add(ref.first to it) }
                }
                val stage = stagingDir(appCtx, album.id)
                val counter = AtomicInteger(pages.indices.count { pageFile(stage, it).let { f -> f.exists() && f.length() > 0 } })
                withContext(Dispatchers.Main) { task.total = pages.size; task.done = counter.get() }
                // Stage 1: fetch + descramble each page to a JPEG on disk (8-way parallel,
                // 3 attempts per page with a short backoff). Pages already on disk are
                // skipped, so a failed or killed download resumes where it stopped
                // instead of starting over.
                val sem = Semaphore(8)
                coroutineScope {
                    pages.mapIndexed { idx, (cid, f) ->
                        async {
                            val out = pageFile(stage, idx)
                            if (out.exists() && out.length() > 0) return@async
                            sem.withPermit {
                                var lastErr: Exception? = null
                                var ok = false
                                var attempt = 0
                                while (!ok && attempt < 3) {
                                    attempt++
                                    try {
                                        val bytes = JmApi.fetchBytes(JmApi.photoUrl(cid, f))
                                        val bmp = Descramble.decode(bytes, cid, f)
                                        out.outputStream().buffered().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                                        if (!bmp.isRecycled) bmp.recycle()
                                        ok = true
                                    } catch (e: Exception) {
                                        lastErr = e
                                        if (!ok && attempt < 3) kotlinx.coroutines.delay(300L * attempt)
                                    }
                                }
                                if (!ok) throw lastErr ?: IllegalStateException("page failed")
                            }
                            val n = counter.incrementAndGet()
                            withContext(Dispatchers.Main) { task.done = n }
                        }
                    }.awaitAll()
                }
                // Stage 2: assemble the PDF one page at a time from disk, so a long book
                // no longer sits in memory as raw bitmaps before anything is written.
                val outFile = File(dir(appCtx), "${album.id}_${album.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)}.pdf")
                val doc = android.graphics.pdf.PdfDocument()
                try {
                    for (idx in pages.indices) {
                        val bmp = BitmapFactory.decodeFile(pageFile(stage, idx).absolutePath)
                            ?: throw IllegalStateException("bad page $idx")
                        val info = android.graphics.pdf.PdfDocument.PageInfo.Builder(bmp.width, bmp.height, idx + 1).create()
                        val page = doc.startPage(info)
                        page.canvas.drawBitmap(bmp, 0f, 0f, null)
                        doc.finishPage(page)
                        bmp.recycle()
                    }
                    outFile.outputStream().buffered().use { doc.writeTo(it) }
                } finally {
                    doc.close()
                }
                exportToDownloads(appCtx, outFile)
                stage.deleteRecursively()
                withContext(Dispatchers.Main) { task.state = State.DONE }
            } catch (e: Exception) {
                // Staged pages stay on disk: tapping 继续 resumes from them.
                withContext(Dispatchers.Main) {
                    task.error = e.message ?: e.javaClass.simpleName
                    task.state = State.FAILED
                }
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
