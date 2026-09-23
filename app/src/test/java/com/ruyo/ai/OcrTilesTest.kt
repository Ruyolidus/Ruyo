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
    @Test fun contrastPassKeepsDarkStrokesAndDropsSmoothBackgroundShading() {
        val bitmap = Bitmap.createBitmap(100, 90, Bitmap.Config.ARGB_8888)
        for (y in 0 until 90) for (x in 0 until 100) bitmap.setPixel(x, y, Color.rgb(180 + y / 2, 180 + y / 2, 180 + y / 2))
        for (y in 30..55) for (x in 44..47) bitmap.setPixel(x, y, Color.BLACK)
        OcrTiles.contrast(bitmap)
        assertEquals(Color.BLACK, bitmap.getPixel(45, 42)); assertEquals(Color.WHITE, bitmap.getPixel(70, 42)); assertEquals(Color.WHITE, bitmap.getPixel(80, 80))
    }
}
