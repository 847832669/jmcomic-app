package com.denia.jmreader

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Built-in viewer for downloaded PDFs (system PdfRenderer), vertical scroll. */
@Composable
fun PdfViewerScreen(file: File, onBack: () -> Unit) {
    val fd = remember(file) {
        runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
    }
    val renderer = remember(fd) { fd?.let { runCatching { PdfRenderer(it) }.getOrNull() } }
    val mutex = remember(renderer) { Mutex() }
    DisposableEffect(renderer, fd) {
        onDispose {
            runCatching { renderer?.close() }
            runCatching { fd?.close() }
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = file.name) { onBack() }
        if (renderer == null) {
            Box(Modifier.fillMaxSize()) { Text("无法打开这个文件") }
        } else {
            val density = LocalDensity.current
            BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
                val targetW = with(density) { maxWidth.toPx() }.toInt().coerceAtLeast(1)
                LazyColumn(Modifier.fillMaxSize()) {
                    items(renderer.pageCount) { idx ->
                        PdfPage(renderer, idx, mutex, targetW)
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(renderer: PdfRenderer, index: Int, mutex: Mutex, targetW: Int) {
    val bmp by produceState<Bitmap?>(null, renderer, index, targetW) {
        value = withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    renderer.openPage(index).use { page ->
                        val scale = targetW.toFloat() / page.width.toFloat()
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val b = Bitmap.createBitmap(targetW, h, Bitmap.Config.ARGB_8888)
                        b.eraseColor(AndroidColor.WHITE)
                        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        b
                    }
                }.getOrNull()
            }
        }
    }
    val b = bmp
    if (b != null) {
        Image(
            b.asImageBitmap(), contentDescription = null,
            modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth
        )
    } else {
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f).background(Color.DarkGray))
    }
}
