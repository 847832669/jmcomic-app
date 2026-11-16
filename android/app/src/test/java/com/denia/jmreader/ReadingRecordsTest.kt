package com.denia.jmreader

import org.junit.Assert.*
import org.junit.Test

class ReadingRecordsTest {
    private fun entry(album: String = "a", chapter: String = "c1", page: Int = 7, ts: Long = 100,
                      offset: Int = 321) = ReadEntry(album, "漫画 $album", chapter, page, ts, offset, "第 1 话", 30)

    @Test fun migratesLegacyProgressAndPreservesItAfterRoundTrip() {
        val legacy = """[{"albumId":"a","title":"旧漫画","chapterId":"c2","page":8,"ts":123}]"""
        val records = ReadingRecords.decode(legacy)
        val saved = records.resume("a", listOf("c1", "c2"))!!
        assertEquals(ComicPosition(8, 0), readingStart(saved, null, null, 30))
        assertEquals(records, ReadingRecords.decode(records.encode()))
    }

    @Test fun remembersEachChapterAndResumesMostRecentlyReadAvailableChapter() {
        val records = ReadingRecords().record(entry()).record(entry(chapter = "c2", page = 12, ts = 200))
            .record(entry(page = 3, ts = 300))
        assertEquals(3, records.resume("a", listOf("c1", "c2"))!!.page)
        assertEquals(12, records.chapter("a", "c2")!!.page)
        assertEquals("c2", records.resume("a", listOf("c2"))!!.chapterId)
        assertNull(records.resume("a", listOf("removed")))
        assertEquals(records.positions.toSet(), ReadingRecords.decode(records.encode()).positions.toSet())
    }

    @Test fun savesExactScrollPositionAndAllowsExplicitRestart() {
        val saved = ReadingRecords.decode(ReadingRecords().record(entry()).encode()).chapter("a", "c1")
        assertEquals(ComicPosition(7, 321), readingStart(saved, null, null, 30))
        assertEquals(ComicPosition(0, 0), readingStart(saved, 0, null, 30))
        assertEquals(ComicPosition(4, 90), readingStart(saved, 4, 90, 30))
        assertEquals(ComicPosition(2, 0), readingStart(saved, null, null, 3))
        assertEquals(ComicPosition(0, 0), readingStart(saved, null, null, 0))
    }

    @Test fun corruptAndDuplicateRecordsCannotBreakResume() {
        assertTrue(ReadingRecords.decode("broken").recent.isEmpty())
        val raw = """[null,{}, {"albumId":"a","chapterId":"c1","page":2,"ts":3},
            {"albumId":"a","chapterId":"c1","page":-1,"scrollOffset":-99,"ts":8}]"""
        val records = ReadingRecords.decode(raw)
        assertEquals(1, records.positions.size)
        assertEquals(ComicPosition(0, 0), readingStart(records.chapter("a", "c1"), null, null, 10))
    }

    @Test fun olderAlbumsKeepProgressBeyondTheOldFiftyAlbumLimit() {
        val records = (1..75).fold(ReadingRecords()) { r, id -> r.record(entry(album = "$id", ts = id.toLong())) }
        assertEquals(75, records.recent.size)
        assertNotNull(ReadingRecords.decode(records.encode()).chapter("1", "c1"))
        assertEquals("75", records.recent.first().albumId)
    }

    @Test fun browseHistoryDeduplicatesSortsCapsAndSurvivesRestart() {
        val data = (1..505).fold(BrowsingRecords()) { r, id -> r.visit(BrowseEntry("$id", "漫画$id", "作者", id.toLong())) }
            .visit(BrowseEntry("9", "新标题", "新作者", 900))
        assertEquals(500, data.entries.size)
        assertEquals("9", data.entries.first().albumId)
        assertEquals(1, data.entries.count { it.albumId == "9" })
        assertEquals(data, BrowsingRecords.decode(data.encode()))
        assertTrue(BrowsingRecords.decode("bad").entries.isEmpty())
        assertTrue(BrowsingRecords.decode("[null,{}]").entries.isEmpty())
    }

    @Test fun removingBrowseHistoryLeavesReadingProgressIntact() {
        val progress = ReadingRecords().record(entry())
        val history = BrowsingRecords().visit(BrowseEntry("a", "标题", "作者", 100))
        assertTrue(history.remove("a").entries.isEmpty())
        assertEquals(7, progress.chapter("a", "c1")!!.page)
    }
}
