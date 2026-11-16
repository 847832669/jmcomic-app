package com.denia.jmreader

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalBooksImportTest {
    @get:Rule val temp = TemporaryFolder()
    private val rootField = LocalBooks::class.java.getDeclaredField("root").apply { isAccessible = true }

    @Before fun prepareLibrary() { rootField.set(LocalBooks, temp.newFolder("library")) }

    @After fun clearLibrary() {
        LocalBooks.books.toList().forEach { LocalBooks.delete(it) }
        rootField.set(LocalBooks, null)
    }

    private fun import(name: String, bytes: ByteArray): LocalBook {
        val source = temp.newFile(name).apply { writeBytes(bytes) }
        val method = LocalBooks::class.java.getDeclaredMethod("stageImport", File::class.java, String::class.java)
            .apply { isAccessible = true }
        return method.invoke(LocalBooks, source, name) as LocalBook
    }

    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test fun cbzPagesFollowNumericOrderAndStayInLibrary() {
        val book = import("sample.cbz", archive(
            "../../page10.png" to byteArrayOf(10),
            "pages/page2.png" to byteArrayOf(2),
            "pages/page1.png" to byteArrayOf(1)))
        assertEquals(LocalKind.COMIC, book.kind)
        assertEquals(listOf(10, 1, 2), book.pageFiles.map { File(LocalBooks.dirOf(book), it).readBytes()[0].toInt() })
        assertTrue(book.pageFiles.all { File(LocalBooks.dirOf(book), it).canonicalPath.startsWith(LocalBooks.dirOf(book).canonicalPath + File.separator) })
        assertTrue(naturalCompare("page2.png", "page10.png") < 0)
    }

    @Test fun txtImportDecodesGb18030AndSplitsChapters() {
        val book = import("sample.txt", "第一章 初见\n你好，世界。\n第二章 重逢\n这里是第二章。".toByteArray(charset("GB18030")))
        assertEquals(LocalKind.NOVEL, book.kind)
        assertEquals(listOf("第一章 初见", "第二章 重逢"), book.chapters.map { it.title })
        assertTrue(LocalBooks.chapterText(book, book.chapters[0]).contains("你好，世界"))
    }

    @Test fun epubKeepsNavTitleAndInlineIllustrationInOrder() {
        val epub = archive(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""".toByteArray(),
            "OPS/book.opf" to """<package><metadata><dc:title>测试小说</dc:title></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="ch" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="pic" href="image.png" media-type="image/png"/></manifest><spine><itemref idref="ch"/></spine></package>""".toByteArray(),
            "OPS/nav.xhtml" to """<nav><a href="chapter.xhtml#start">第一章 来自目录的标题</a></nav>""".toByteArray(),
            "OPS/chapter.xhtml" to """<html><head><title>测试小说</title></head><body><p>这里是插图前的正文，应该完整保留。</p><img src="image.png"/><p>这里是插图后的正文，也应该完整保留。</p></body></html>""".toByteArray(),
            "OPS/image.png" to byteArrayOf(1, 2, 3))
        val book = import("sample.epub", epub)
        assertEquals("测试小说", book.title)
        assertEquals(listOf("第一章 来自目录的标题"), book.chapters.map { it.title })
        val blocks = LocalBooks.chapterBlocks(book, 0)
        assertEquals(listOf("text", "image", "text"), blocks.map { it.kind })
        assertTrue(blocks[0].text!!.contains("插图前"))
        assertArrayEquals(byteArrayOf(1, 2, 3), File(LocalBooks.dirOf(book), blocks[1].file!!).readBytes())
        assertTrue(blocks[2].text!!.contains("插图后"))
    }

    @Test fun htmlPreservesChineseParagraphsAndRemovesScriptAndRubyAnnotations() {
        val text = stripHtml("<style>hidden</style><script>bad()</script><p>你好<br/>世界 &amp; &#x1F600;</p><p><ruby>汉<rt>han</rt></ruby>字</p>")
        assertEquals("你好\n世界 & 😀\n汉字", text)
    }
}
