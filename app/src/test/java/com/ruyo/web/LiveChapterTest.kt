package com.ruyo.web

import org.junit.Assert.*
import org.junit.Test

class LiveChapterTest {
    private fun page(n: Int) = WebImage("https://example.com/" + n + ".png", "Page " + n, 800, 60, chapterImage = true)
    @Test fun delayedImagesAreInsertedWithoutDroppingVirtualizedEarlierPages() {
        val current = listOf(page(0), page(2), page(4))
        val result = LiveChapter.merge(current, listOf(page(1), page(2), page(3), page(4), page(5)))
        assertEquals((0..5).map(::page), result)
        assertEquals(result, LiveChapter.merge(result, listOf(page(4), page(5))))
        assertEquals(result, LiveChapter.merge(result, listOf(page(4), page(4), page(5))))
    }
    @Test fun commentsDoNotExtendTheReaderAndTheLimitIsExplicit() {
        val comment = page(1).copy(excludedReason = "Comment image")
        assertEquals(listOf(page(0)), LiveChapter.merge(listOf(page(0)), listOf(comment)))
        assertEquals(200, LiveChapter.merge(emptyList(), (0..250).map(::page)).size)
    }
}
