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
    @Test fun detectorBoundsRecoverClippedLeadingLettersWithoutJoiningDifferentLines() {
        val lines=mutableListOf(OcrLine("I CAME TO",196,407,325,441,.70f),OcrLine("NEXT LINE",109,450,396,477,.80f))
        OcrTiles.coverDetectedLettering(lines,listOf(android.graphics.Rect(169,407,331,444),android.graphics.Rect(100,403,400,480)))
        assertEquals(169,lines[0].left);assertEquals(331,lines[0].right)
        assertEquals(407,lines[0].top);assertEquals(441,lines[0].bottom)
        assertEquals(109,lines[1].left);assertEquals(450,lines[1].top)
        assertEquals("I CAME TO",lines[0].text)
    }
    @Test fun overlappingSlantedLinesMatchTheClosestReadingInsteadOfTheFirstBox() {
        val lines=mutableListOf(OcrLine("Don't let anyone",203,871,370,934,.55f),OcrLine("arareochis",236,902,348,955,.23f))
        OcrTiles.merge(lines,listOf(OcrLine("t",254,888,273,922,.43f),OcrLine("approach.",238,904,342,953,.45f)))
        assertEquals(listOf("Don't let anyone","approach."),lines.map { it.text })
        assertFalse(lines.first().alternatives.contains("approach."))
        assertFalse(lines.any { "t" in it.alternatives })
    }
    @Test fun wordRetryRequiresBetterConfidenceAndASmallUniqueCorrection() {
        val word=OcrWord("TINSTINCT",30,10,130,35,.55f)
        val line=OcrLine("GAMBLER'S TINSTINCT HAS ACTIVATED!",0,10,400,35,.81f)
        val better=OcrLine("INSTINCT",30,10,130,35,.90f)
        assertEquals("GAMBLER'S INSTINCT HAS ACTIVATED!",OcrTiles.correctWord(line,word,better).text)
        assertEquals(line,OcrTiles.correctWord(line,word,better.copy(confidence=.40f)))
        assertEquals(line,OcrTiles.correctWord(line,word,better.copy(text="DIFFERENT")))
        assertEquals(line,OcrTiles.correctWord(line,word,better.copy(text="INSTINCT HAS")))
        val ambiguous=line.copy(text="TINSTINCT TINSTINCT")
        assertEquals(ambiguous,OcrTiles.correctWord(ambiguous,word,better))
    }

    @Test fun weakReadingsUseTheBetterScoreEvenWhenTheImprovementIsSmall() {
        val lines=mutableListOf(OcrLine("agproach.",239,908,343,952,.4956597f))
        OcrTiles.merge(lines,listOf(OcrLine("approach.",238,906,343,952,.49739584f)))
        assertEquals("approach.",lines.single().text)
    }
    @Test fun contrastPassKeepsDarkStrokesAndDropsSmoothBackgroundShading() {
        val bitmap = Bitmap.createBitmap(100, 90, Bitmap.Config.ARGB_8888)
        for (y in 0 until 90) for (x in 0 until 100) bitmap.setPixel(x, y, Color.rgb(180 + y / 2, 180 + y / 2, 180 + y / 2))
        for (y in 30..55) for (x in 44..47) bitmap.setPixel(x, y, Color.BLACK)
        OcrTiles.contrast(bitmap)
        assertEquals(Color.BLACK, bitmap.getPixel(45, 42)); assertEquals(Color.WHITE, bitmap.getPixel(70, 42)); assertEquals(Color.WHITE, bitmap.getPixel(80, 80))
    }
}
