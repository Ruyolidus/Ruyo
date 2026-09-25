package com.ruyo.ai

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OcrTilesTest {
    @Test fun overlappingTilesCoverLongPagesAndDuplicateReadingsMerge() {
        val tiles = OcrTiles.regions(1500, 7100)
        assertTrue(tiles.all { it.width() <= 1280 && it.height() <= 1536 })
        for (y in 0 until 7100 step 31) for (x in 0 until 1500 step 29) assertTrue(tiles.any { it.contains(x, y) })
        val found = mutableListOf(OcrLine("Ready", 40, 1490, 100, 1520))
        OcrTiles.merge(found, listOf(OcrLine("Ready now", 38, 1489, 160, 1521), OcrLine("Ready now", 39, 1490, 161, 1522), OcrLine("Next", 500, 1500, 550, 1530)))
        assertEquals(listOf("Ready now", "Next"), found.map { it.text })
    }
    @Test fun clearerReadingsReplaceLowConfidenceOcrWithoutDroppingNeighboringText() {
        val found = mutableListOf(OcrLine("GAIVBLER", 20, 20, 180, 60, .4f), OcrLine("Next", 250, 20, 340, 60, .99f))
        OcrTiles.merge(found, listOf(OcrLine("GAMBLER", 21, 20, 181, 60, .97f)))
        assertEquals(listOf("GAMBLER", "Next"), found.map { it.text })
        assertEquals(listOf("GAIVBLER"), found.first().alternatives)
    }
    @Test fun confidentButConflictingOcrKeepsTheAlternativeForTranslation() {
        val found=mutableListOf(OcrLine("ID PUT HIM",20,20,180,60,.85f))
        OcrTiles.merge(found,listOf(OcrLine("I'D PUT HIM",20,20,180,60,.70f)))
        assertEquals("ID PUT HIM",found.single().text)
        assertEquals(listOf("I'D PUT HIM"),found.single().alternatives)
        val crops=OcrTiles.retryRegions(listOf(android.graphics.Rect(10,10,200,90),android.graphics.Rect(30,55,180,125)),
            listOf(android.graphics.Rect(250,10,400,70)))
        assertEquals(listOf(android.graphics.Rect(10,10,200,125),android.graphics.Rect(250,10,400,70)),crops)
    }
    @Test fun contrastPassKeepsDarkStrokesAndDropsSmoothBackgroundShading() {
        val bitmap = Bitmap.createBitmap(100, 90, Bitmap.Config.ARGB_8888)
        for (y in 0 until 90) for (x in 0 until 100) bitmap.setPixel(x, y, Color.rgb(180 + y / 2, 180 + y / 2, 180 + y / 2))
        for (y in 30..55) for (x in 44..47) bitmap.setPixel(x, y, Color.BLACK)
        OcrTiles.contrast(bitmap)
        assertEquals(Color.BLACK, bitmap.getPixel(45, 42)); assertEquals(Color.WHITE, bitmap.getPixel(70, 42)); assertEquals(Color.WHITE, bitmap.getPixel(80, 80))
    }
}
