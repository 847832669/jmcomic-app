package com.denia.jmreader

import android.content.Context
import android.database.Cursor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.zip.ZipFile

/** Local bookshelf content — the Android port of the iOS v2.30 local-book
 *  engine. Unlike the hand-written Swift stack, Android gets ZIP/DEFLATE
 *  and GB18030 from the platform, so only the format logic (EPUB spine,
 *  TXT chapter splitting, HTML stripping) is ported. Layout mirrors iOS:
 *  filesDir/LocalBooks/<id>/ with a JSON manifest; reading progress lives
 *  in SharedPreferences and is kept OUT of the books list state (the
 *  v2.19 silent-state lesson applies here too). */
enum class LocalKind { COMIC, PDF, NOVEL }

data class NovelChapter(val title: String, val file: String)

/** One piece of a novel chapter: a run of text, or an illustration
 *  stored as a file inside the book directory (EPUB imports; the
 *  Android port of the iOS build-67 blocks model). */
data class NovelBlock(val kind: String, val text: String? = null, val file: String? = null)

data class LocalBook(
    val id: String,
    val title: String,
    val kind: LocalKind,
    val dirName: String,
    val sourceName: String,
    val fileCount: Int,
    val byteCount: Long,
    val pageFiles: List<String>,
    val coverFile: String?,
    val chapters: List<NovelChapter>,
    val createdAt: Long,
)

object LocalBooks {
    private val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")

    private var appCtx: Context? = null
    private var root: File? = null
    var books by mutableStateOf<List<LocalBook>>(emptyList())
        private set
    var progressTick by mutableIntStateOf(0)
        private set
    private val progress = mutableMapOf<String, Pair<Int, Long>>()

    fun init(context: Context) {
        if (root != null) return
        appCtx = context.applicationContext
        val r = File(context.filesDir, "LocalBooks").apply { mkdirs() }
        root = r
        loadManifest(r)
        val raw = context.getSharedPreferences("jm.localbooks", Context.MODE_PRIVATE)
            .getString("progress", "{}") ?: "{}"
        val obj = try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val a = obj.optJSONArray(k)
            if (a != null && a.length() >= 2) progress[k] = a.optInt(0) to a.optLong(1)
        }
    }

    fun dirOf(book: LocalBook): File = File(root, book.dirName)
    fun coverFile(book: LocalBook): File? =
        book.coverFile?.let { File(dirOf(book), it) }?.takeIf { it.exists() }

    fun progressOf(bookId: String): Int = progress[bookId]?.first ?: 0
    fun lastReadAt(bookId: String): Long = progress[bookId]?.second ?: 0L

    fun recordProgress(bookId: String, position: Int) {
        progress[bookId] = position to System.currentTimeMillis()
        progressTick++
        val obj = JSONObject()
        for ((k, v) in progress) obj.put(k, JSONArray().put(v.first).put(v.second))
        appCtx?.getSharedPreferences("jm.localbooks", Context.MODE_PRIVATE)
            ?.edit()?.putString("progress", obj.toString())?.apply()
    }

    fun chapterText(book: LocalBook, chapter: NovelChapter): String =
        File(dirOf(book), chapter.file).readText(Charsets.UTF_8)

    /** Ordered text/image blocks for a chapter. EPUB imports write
     *  blocks/<idx>.json at staging time; TXT imports and older books
     *  fall back to the chapter text file as a single text block. */
    fun chapterBlocks(book: LocalBook, chapterIndex: Int): List<NovelBlock> {
        val ch = book.chapters.getOrNull(chapterIndex) ?: return emptyList()
        val bf = File(dirOf(book), "blocks/%03d.json".format(chapterIndex))
        if (bf.exists()) {
            try {
                val arr = JSONArray(bf.readText(Charsets.UTF_8))
                val out = mutableListOf<NovelBlock>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(NovelBlock(
                        o.optString("k", "text"),
                        o.optString("t").ifEmpty { null },
                        o.optString("f").ifEmpty { null }))
                }
                if (out.isNotEmpty()) return out
            } catch (e: Exception) { /* fall through to text */ }
        }
        val text = chapterText(book, ch)
        return if (text.isBlank()) emptyList() else listOf(NovelBlock("text", text))
    }

    fun delete(book: LocalBook) {
        dirOf(book).deleteRecursively()
        books = books.filter { it.id != book.id }
        progress.remove(book.id)
        saveManifest()
    }

    // ---------------- import ----------------

    suspend fun importUris(context: Context, uris: List<Uri>): Pair<List<String>, List<String>> =
        withContext(Dispatchers.IO) {
            val added = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for (uri in uris) {
                val name = displayName(context, uri) ?: "file"
                try {
                    val tmp = File(context.cacheDir, "import-${System.nanoTime()}")
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("cannot open")
                    input.use { inp -> tmp.outputStream().use { out -> inp.copyTo(out) } }
                    val book = stageImport(tmp, name)
                    added.add(book.title)
                    tmp.delete()
                } catch (e: Exception) {
                    failed.add(name)
                }
            }
            added to failed
        }

    private fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(uri, null, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        } catch (e: Exception) { null } finally { cursor?.close() }
    }

    private fun stageImport(src: File, displayName: String): LocalBook {
        val r = root ?: throw IllegalStateException("not initialized")
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val base = displayName.substringBeforeLast('.', displayName)
        val id = UUID.randomUUID().toString()
        val dir = File(r, id).apply { mkdirs() }
        try {
            val book = when (ext) {
                "cbz", "zip" -> stageZipBook(src, dir, id, base, displayName)
                "pdf" -> {
                    val dest = File(dir, "content.pdf")
                    src.copyTo(dest, overwrite = true)
                    val pages = pdfPageCount(dest)
                    LocalBook(id, base, LocalKind.PDF, id, "content.pdf", pages,
                        dest.length(), emptyList(), null, emptyList(), System.currentTimeMillis())
                }
                "txt" -> stageTxt(src.readBytes(), dir, id, base, displayName)
                "epub" -> stageEpub(src, dir, id, base, displayName)
                else -> throw IllegalArgumentException("unsupported format: .$ext")
            }
            books = listOf(book) + books
            saveManifest()
            return book
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    private fun stageZipBook(src: File, dir: File, id: String, base: String, displayName: String): LocalBook {
        ZipFile(src).use { zip ->
            val entries = zip.entries().toList().filter { !it.isDirectory }
            val images = entries.filter { it.name.substringAfterLast('.', "").lowercase() in imageExts }
                .sortedWith { a, b -> naturalCompare(a.name.lowercase(), b.name.lowercase()) }
            if (images.isNotEmpty()) {
                val names = mutableListOf<String>()
                images.forEachIndexed { i, entry ->
                    val data = zip.getInputStream(entry).readBytes()
                    val out = File(dir, "%05d.%s".format(i, entry.name.substringAfterLast('.', "jpg").lowercase()))
                    out.writeBytes(data)
                    names.add(out.name)
                }
                val bytes = names.sumOf { File(dir, it).length() }
                return LocalBook(id, base, LocalKind.COMIC, id, displayName, names.size,
                    bytes, names, names.firstOrNull(), emptyList(), System.currentTimeMillis())
            }
            // No images: a novel may be hiding inside the archive.
            val txt = entries.firstOrNull { it.name.lowercase().endsWith(".txt") }
            if (txt != null) return stageTxt(zip.getInputStream(txt).readBytes(), dir, id, base, displayName)
            val epub = entries.firstOrNull { it.name.lowercase().endsWith(".epub") }
            if (epub != null) {
                val tmp = File(dir, "nested.epub")
                zip.getInputStream(epub).use { inp -> tmp.outputStream().use { out -> inp.copyTo(out) } }
                val book = stageEpub(tmp, dir, id, base, displayName)
                tmp.delete()
                return book
            }
            throw IllegalArgumentException("archive has no readable content")
        }
    }

    private val chapterMarker = Regex(
        """^\s*(第[0-9０-９零〇一二三四五六七八九十百千两]+[章节回卷部篇]\s*.*|Chapter\s+\d+.*)$""",
        RegexOption.MULTILINE)

    private fun decodeText(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, charset("GB18030"))
        }
    }

    private fun stageTxt(bytes: ByteArray, dir: File, id: String, base: String, displayName: String): LocalBook {
        val text = decodeText(bytes)
        val marks = chapterMarker.findAll(text).toList()
        val chapters = mutableListOf<NovelChapter>()
        if (marks.size < 2) {
            val f = File(dir, "000.txt"); f.writeText(text, Charsets.UTF_8)
            chapters.add(NovelChapter(base, f.name))
        } else {
            marks.forEachIndexed { i, m ->
                val end = if (i + 1 < marks.size) marks[i + 1].range.first else text.length
                val body = text.substring(m.range.first, end).trim()
                val f = File(dir, "%03d.txt".format(i)); f.writeText(body, Charsets.UTF_8)
                chapters.add(NovelChapter(m.value.trim().ifEmpty { "第${i + 1}章" }, f.name))
            }
        }
        return LocalBook(id, base, LocalKind.NOVEL, id, displayName, chapters.size,
            bytes.size.toLong(), emptyList(), null, chapters, System.currentTimeMillis())
    }

    private fun stageEpub(src: File, dir: File, id: String, base: String, displayName: String): LocalBook {
        ZipFile(src).use { zip ->
            val byName = zip.entries().toList().filter { !it.isDirectory }.associateBy { it.name }
            fun entryData(name: String): ByteArray? =
                byName[name]?.let { zip.getInputStream(it).readBytes() }
            val container = entryData("META-INF/container.xml")?.let { decodeLoose(it) }
                ?: throw IllegalArgumentException("bad epub (no container)")
            val opfPath = xmlAttr(container, "rootfile", "full-path")
                ?: throw IllegalArgumentException("bad epub (no rootfile)")
            val opf = entryData(opfPath)?.let { decodeLoose(it) }
                ?: throw IllegalArgumentException("bad epub (no opf)")
            val baseDir = opfPath.substringBeforeLast('/', "")
            val title = xmlElementText(opf, "dc:title")?.ifEmpty { null } ?: base
            // manifest
            data class Item(val href: String, val type: String, val props: String)
            val manifest = mutableMapOf<String, Item>()
            for (tag in xmlTags(opf, "item")) {
                val iid = xmlAttrValue(tag, "id"); val href = xmlAttrValue(tag, "href")
                if (iid != null && href != null) {
                    manifest[iid] = Item(href, xmlAttrValue(tag, "media-type") ?: "",
                        xmlAttrValue(tag, "properties") ?: "")
                }
            }
            // spine (linear="no" pages are not reading content)
            val spine = mutableListOf<String>()
            for (tag in xmlTags(opf, "itemref")) {
                val ref = xmlAttrValue(tag, "idref") ?: continue
                if (xmlAttrValue(tag, "linear") != "no") spine.add(ref)
            }
            // Chapter titles come from the navigation document (EPUB 3
            // nav, else the EPUB 2 NCX) — per-file <title> elements
            // repeat the book title on Sigil-style EPUBs.
            val navTitles = mutableMapOf<String, String>()
            val navItem = manifest.values.firstOrNull { it.props.split(' ').contains("nav") }
            if (navItem != null) {
                val navPath = joinPath(baseDir, navItem.href)
                entryData(navPath)?.let { raw ->
                    val navDir = navPath.substringBeforeLast('/', "")
                    for ((href, label) in epubNavLinks(decodeLoose(raw))) {
                        val key = joinPath(navDir, href)
                        if (!navTitles.containsKey(key)) navTitles[key] = label
                    }
                }
            }
            if (navTitles.isEmpty()) {
                val ncxItem = manifest.values.firstOrNull { it.type.contains("ncx") }
                if (ncxItem != null) {
                    val ncxPath = joinPath(baseDir, ncxItem.href)
                    entryData(ncxPath)?.let { raw ->
                        val ncxDir = ncxPath.substringBeforeLast('/', "")
                        for ((href, label) in epubNcxLinks(decodeLoose(raw))) {
                            val key = joinPath(ncxDir, href)
                            if (!navTitles.containsKey(key)) navTitles[key] = label
                        }
                    }
                }
            }
            // Spine pages as ordered segments (text runs and image
            // references in document order; illustrations are kept).
            data class Seg(val kind: String, val value: String)
            data class EpubPage(val href: String, val segs: MutableList<Seg>, var text: String)
            val imageFiles = mutableMapOf<String, String>()
            var imageSeq = 0
            fun storeImage(archivePath: String): String? {
                imageFiles[archivePath]?.let { return it }
                val ext = archivePath.substringAfterLast('.', "").lowercase()
                if (ext !in setOf("jpg", "jpeg", "png", "webp", "gif")) return null
                val raw = entryData(archivePath) ?: return null
                val imagesDir = File(dir, "images").apply { mkdirs() }
                val name = "img-%04d.%s".format(imageSeq, ext); imageSeq++
                File(imagesDir, name).writeBytes(raw)
                val rel = "images/$name"
                imageFiles[archivePath] = rel
                return rel
            }
            val pages = mutableListOf<EpubPage>()
            for (ref in spine) {
                val item = manifest[ref] ?: continue
                if (!item.type.contains("html")) continue
                val pagePath = joinPath(baseDir, item.href)
                val data = entryData(pagePath) ?: continue
                val html = decodeLoose(data)
                val pageDir = pagePath.substringBeforeLast('/', "")
                val segs = mutableListOf<Seg>()
                for ((kind, value) in epubSegments(html)) {
                    segs.add(if (kind == "image") Seg("image", joinPath(pageDir, value)) else Seg(kind, value))
                }
                // Running head: the first line of the first text
                // segment repeats the book title (from the file's
                // <title>) — strip it.
                val fileTitle = xmlElementText(html, "title") ?: ""
                val ti = segs.indexOfFirst { it.kind == "text" }
                if (ti >= 0) {
                    val lines = segs[ti].value.split('\n')
                    val head = lines.firstOrNull()?.trim() ?: ""
                    if (head.isNotEmpty() && (head == fileTitle || head == title)) {
                        segs[ti] = Seg("text", lines.drop(1).joinToString("\n").trim())
                    }
                }
                val text = segs.filter { it.kind == "text" }.map { it.value }
                    .filter { it.isNotEmpty() }.joinToString("\n")
                pages.add(EpubPage(pagePath, segs, text))
            }
            // Kept chapters: image-only pages drop out anywhere; short
            // front-matter notices ahead of the first substantial
            // chapter drop too.
            val firstBig = pages.indexOfFirst { it.text.length >= 1000 }
            val keptIdx = mutableListOf<Int>()
            for ((i, page) in pages.withIndex()) {
                if (page.text.length < 10) continue
                if (firstBig >= 0 && i < firstBig && page.text.length < 200) continue
                keptIdx.add(i)
            }
            if (keptIdx.isEmpty()) throw IllegalArgumentException("bad epub (no chapters)")
            val firstKept = keptIdx.first(); val lastKept = keptIdx.last()
            val chapters = mutableListOf<NovelChapter>()
            var frontImages = mutableListOf<NovelBlock>()
            for ((i, page) in pages.withIndex()) {
                if (i in keptIdx) {
                    val blocks = mutableListOf<NovelBlock>()
                    blocks.addAll(frontImages); frontImages = mutableListOf()
                    for (seg in page.segs) {
                        if (seg.kind == "image") {
                            storeImage(seg.value)?.let { blocks.add(NovelBlock("image", file = it)) }
                        } else if (seg.value.isNotEmpty()) {
                            blocks.add(NovelBlock("text", text = seg.value))
                        }
                    }
                    // Title: nav label, else a heading-like first line,
                    // else Chapter N. A heading-like first line leaves
                    // the body.
                    var chTitle = navTitles[page.href] ?: ""
                    val bi = blocks.indexOfFirst { it.kind == "text" }
                    if (bi >= 0) {
                        val firstLine = blocks[bi].text?.split('\n')?.firstOrNull()?.trim() ?: ""
                        if (isChapterHeading(firstLine)) {
                            if (chTitle.isEmpty()) chTitle = firstLine
                            val rest = (blocks[bi].text ?: "").split('\n').drop(1)
                                .joinToString("\n").trim()
                            blocks[bi] = NovelBlock("text", text = rest)
                        }
                    }
                    if (chTitle.isEmpty() || chTitle == title) chTitle = "第${chapters.size + 1}章"
                    // plain-text file (fallback + TXT-style readers)
                    val f = File(dir, "%03d.txt".format(chapters.size))
                    f.writeText(blocks.filter { it.kind == "text" }.mapNotNull { it.text }
                        .joinToString("\n"), Charsets.UTF_8)
                    // blocks file
                    val bdir = File(dir, "blocks").apply { mkdirs() }
                    val barr = JSONArray()
                    for (b in blocks) {
                        val o = JSONObject().put("k", b.kind)
                        if (b.text != null) o.put("t", b.text)
                        if (b.file != null) o.put("f", b.file)
                        barr.put(o)
                    }
                    File(bdir, "%03d.json".format(chapters.size)).writeText(barr.toString(), Charsets.UTF_8)
                    chapters.add(NovelChapter(chTitle, f.name))
                } else if (i < firstKept) {
                    // Front matter illustrations open the book — except
                    // the publisher TOC page image.
                    if (navTitles[page.href] != "目录") {
                        for (seg in page.segs) {
                            if (seg.kind == "image") {
                                storeImage(seg.value)?.let { frontImages.add(NovelBlock("image", file = it)) }
                            }
                        }
                    }
                } else if (i < lastKept && chapters.isNotEmpty()) {
                    // Image-only page between chapters: illustrations
                    // close out the previous chapter.
                    val extra = mutableListOf<NovelBlock>()
                    for (seg in page.segs) {
                        if (seg.kind == "image") {
                            storeImage(seg.value)?.let { extra.add(NovelBlock("image", file = it)) }
                        }
                    }
                    if (extra.isNotEmpty()) {
                        val ci = chapters.size - 1
                        val bf = File(dir, "blocks/%03d.json".format(ci))
                        val arr = JSONArray(bf.readText(Charsets.UTF_8))
                        for (b in extra) arr.put(JSONObject().put("k", "image").put("f", b.file))
                        bf.writeText(arr.toString(), Charsets.UTF_8)
                    }
                }
                // Pages after the last chapter contribute nothing.
            }
            if (chapters.isEmpty()) throw IllegalArgumentException("bad epub (no chapters)")
            // cover: properties=cover-image, then <meta name="cover">, then first image
            var coverPath: String? = manifest.values.firstOrNull { it.props.contains("cover-image") }
                ?.let { joinPath(baseDir, it.href) }
            if (coverPath == null) {
                val metaId = xmlTags(opf, "meta").firstOrNull { xmlAttrValue(it, "name") == "cover" }
                    ?.let { xmlAttrValue(it, "content") }
                if (metaId != null) manifest[metaId]?.let { coverPath = joinPath(baseDir, it.href) }
            }
            if (coverPath == null) {
                manifest.values.firstOrNull { it.type.startsWith("image/") }
                    ?.let { coverPath = joinPath(baseDir, it.href) }
            }
            var coverName: String? = null
            val cp = coverPath
            if (cp != null) {
                entryData(cp)?.let { data ->
                    val out = File(dir, "cover." + cp.substringAfterLast('.', "jpg").lowercase())
                    out.writeBytes(data)
                    coverName = out.name
                }
            }
            val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            return LocalBook(id, title, LocalKind.NOVEL, id, displayName, chapters.size,
                bytes, emptyList(), coverName, chapters, System.currentTimeMillis())
        }
    }

    private fun pdfPageCount(file: File): Int {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try {
            val renderer = PdfRenderer(fd)
            val count = renderer.pageCount
            renderer.close()
            count
        } finally {
            fd.close()
        }
    }

    private fun decodeLoose(bytes: ByteArray): String = try {
        decodeText(bytes)
    } catch (e: Exception) {
        String(bytes, Charsets.UTF_8)
    }

    // ---------------- manifest ----------------

    private fun saveManifest() {
        val r = root ?: return
        val arr = JSONArray()
        for (b in books) {
            val o = JSONObject()
            o.put("id", b.id).put("title", b.title).put("kind", b.kind.name)
                .put("dirName", b.dirName).put("sourceName", b.sourceName)
                .put("fileCount", b.fileCount).put("byteCount", b.byteCount)
                .put("coverFile", b.coverFile ?: JSONObject.NULL)
                .put("createdAt", b.createdAt)
            val pages = JSONArray(); b.pageFiles.forEach { pages.put(it) }; o.put("pageFiles", pages)
            val chs = JSONArray()
            for (c in b.chapters) chs.put(JSONObject().put("title", c.title).put("file", c.file))
            o.put("chapters", chs)
            arr.put(o)
        }
        File(r, "index.json").writeText(arr.toString(), Charsets.UTF_8)
    }

    private fun loadManifest(r: File) {
        val f = File(r, "index.json")
        if (!f.exists()) return
        val arr = try { JSONArray(f.readText(Charsets.UTF_8)) } catch (e: Exception) { return }
        val out = mutableListOf<LocalBook>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pages = o.optJSONArray("pageFiles")
            val chs = o.optJSONArray("chapters")
            out.add(LocalBook(
                id = o.optString("id"),
                title = o.optString("title"),
                kind = runCatching { LocalKind.valueOf(o.optString("kind")) }.getOrDefault(LocalKind.NOVEL),
                dirName = o.optString("dirName"),
                sourceName = o.optString("sourceName"),
                fileCount = o.optInt("fileCount"),
                byteCount = o.optLong("byteCount"),
                pageFiles = if (pages == null) emptyList() else (0 until pages.length()).map { pages.optString(it) },
                coverFile = if (o.isNull("coverFile")) null else o.optString("coverFile").ifEmpty { null },
                chapters = if (chs == null) emptyList() else (0 until chs.length()).map {
                    val c = chs.optJSONObject(it); NovelChapter(c?.optString("title") ?: "", c?.optString("file") ?: "")
                },
                createdAt = o.optLong("createdAt"),
            ))
        }
        books = out.filter { File(r, it.dirName).exists() }
    }
}

// ---------------- shared text helpers (ports of the iOS ones) ----------------

/** Numeric-aware name comparison so page 2 sorts before page 10. */
fun naturalCompare(a: String, b: String): Int {
    var i = 0; var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]; val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var na = ""; while (i < a.length && a[i].isDigit()) { na += a[i]; i++ }
            var nb = ""; while (j < b.length && b[j].isDigit()) { nb += b[j]; j++ }
            val cmp = na.trimStart('0').length.compareTo(nb.trimStart('0').length)
            if (cmp != 0) return cmp
            val cmp2 = na.trimStart('0').compareTo(nb.trimStart('0'))
            if (cmp2 != 0) return cmp2
        } else {
            if (ca != cb) return ca.compareTo(cb)
            i++; j++
        }
    }
    return (a.length - i).compareTo(b.length - j)
}

private fun xmlTags(xml: String, name: String): List<String> {
    val out = mutableListOf<String>()
    val needle = "<$name"
    var from = 0
    while (true) {
        val start = xml.indexOf(needle, from)
        if (start < 0) break
        val after = start + needle.length
        if (after < xml.length && xml[after] in listOf(' ', '/', '>', '\n', '\r', '\t')) {
            val end = xml.indexOf('>', after)
            if (end < 0) break
            out.add(xml.substring(start, end + 1))
            from = end + 1
        } else from = after
    }
    return out
}

private fun xmlAttrValue(tag: String, attr: String): String? {
    val needle = "$attr=\""
    val start = tag.indexOf(needle)
    if (start < 0) return null
    val valueStart = start + needle.length
    val end = tag.indexOf('"', valueStart)
    if (end < 0) return null
    return tag.substring(valueStart, end)
}

private fun xmlAttr(xml: String, tag: String, attr: String): String? {
    for (t in xmlTags(xml, tag)) xmlAttrValue(t, attr)?.let { return it }
    return null
}

private fun xmlElementText(xml: String, name: String): String? {
    val start = xml.indexOf("<$name")
    if (start < 0) return null
    val open = xml.indexOf('>', start)
    if (open < 0) return null
    val close = xml.indexOf("</$name>", open)
    if (close < 0) return null
    return stripHtml(xml.substring(open + 1, close)).trim().ifEmpty { null }
}

private fun joinPath(base: String, rel: String): String {
    val r = if (rel.startsWith("/")) rel.substring(1) else rel
    val joined = if (base.isEmpty()) r else "$base/$r"
    val parts = mutableListOf<String>()
    for (c in joined.split('/')) {
        when (c) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts.add(c)
        }
    }
    return parts.joinToString("/")
}

/** Splits an XHTML page into ordered segments: ("text", stripped
 *  text) and ("image", raw href), in document order. Port of iOS. */
private fun epubSegments(html: String): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    val re = Regex("""(?is)<img[^>]*?\bsrc="([^"]+)"[^>]*>|<image[^>]*?(?:xlink:href|href)="([^"]+)"[^>]*>""")
    var pos = 0
    for (m in re.findAll(html)) {
        if (m.range.first > pos) {
            val t = stripHtml(html.substring(pos, m.range.first))
            if (t.isNotEmpty()) out.add("text" to t)
        }
        val href = m.groupValues[1].ifEmpty { m.groupValues[2] }
        if (href.isNotEmpty()) out.add("image" to href)
        pos = m.range.last + 1
    }
    if (pos < html.length) {
        val t = stripHtml(html.substring(pos))
        if (t.isNotEmpty()) out.add("text" to t)
    }
    return out
}

/** EPUB 3 nav document links: (href without fragment, label). */
private fun epubNavLinks(html: String): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    val re = Regex("""(?is)<a\s[^>]*href="([^"]+)"[^>]*>(.*?)</a>""")
    for (m in re.findAll(html)) {
        val href = m.groupValues[1].substringBefore('#')
        val label = stripHtml(m.groupValues[2]).trim()
        if (label.isNotEmpty()) out.add(href to label)
    }
    return out
}

/** EPUB 2 NCX links: (src without fragment, label). */
private fun epubNcxLinks(ncx: String): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    val re = Regex("""(?is)<text>(.*?)</text>\s*</navLabel>\s*<content[^>]*?src="([^"]+)"""")
    for (m in re.findAll(ncx)) {
        val label = stripHtml(m.groupValues[1]).trim()
        val href = m.groupValues[2].substringBefore('#')
        if (label.isNotEmpty()) out.add(href to label)
    }
    return out
}

/** True when a line reads as a chapter heading (CN/JP forms). */
private fun isChapterHeading(line: String): Boolean {
    val t = line.trim()
    if (t.isEmpty() || t.length > 80) return false
    val head = Regex("""^(序章|終章|终章|尾声|番外編|番外|後記|后记|あとがき|プロローグ|エピローグ)""")
    val num = Regex("""^第[0-9０-９零一二三四五六七八九十百千]{1,6}[话話章回節节卷]""")
    return head.containsMatchIn(t) || num.containsMatchIn(t)
}

private val htmlEntities = mapOf(
    "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
    "&quot;" to "\"", "&apos;" to "'", "&#39;" to "'", "&hellip;" to "…",
    "&mdash;" to "—", "&ndash;" to "–", "&lsquo;" to "‘", "&rsquo;" to "’",
    "&ldquo;" to "“", "&rdquo;" to "”",
)

fun stripHtml(html: String): String {
    var s = html
    s = Regex("""(?is)<script.*?</script>""").replace(s, "")
    s = Regex("""(?is)<style.*?</style>""").replace(s, "")
    s = Regex("""(?is)<rt.*?</rt>""").replace(s, "")
    s = Regex("""(?i)<br\s*/?>""").replace(s, "\n")
    s = Regex("""(?i)</(p|div|h[1-6]|li|tr|section|article)>""").replace(s, "\n")
    s = Regex("""<[^>]+>""").replace(s, "")
    for ((k, v) in htmlEntities) s = s.replace(k, v)
    s = Regex("""&#(\d+);""").replace(s) { m ->
        m.groupValues[1].toIntOrNull()?.takeIf { it in 0..0x10FFFF }
            ?.let { String(Character.toChars(it)) } ?: m.value
    }
    s = Regex("""&#x([0-9a-fA-F]+);""").replace(s) { m ->
        m.groupValues[1].toIntOrNull(16)?.takeIf { it in 0..0x10FFFF }
            ?.let { String(Character.toChars(it)) } ?: m.value
    }
    val lines = s.split('\n').map { it.trim() }
    val out = mutableListOf<String>()
    for (line in lines) {
        if (line.isEmpty() && (out.isEmpty() || out.last().isEmpty())) continue
        out.add(line)
    }
    while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.lastIndex)
    return out.joinToString("\n")
}
