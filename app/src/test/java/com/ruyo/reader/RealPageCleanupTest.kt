package com.ruyo.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.ruyo.ai.OcrLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID
import org.robolectric.RuntimeEnvironment
import com.ruyo.data.LocalBookStore

/** Pixel regressions use the user's original English lettering, not a synthetic font. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RealPageCleanupTest {
    private fun source(name: String): Bitmap {
        val image = requireNotNull(BitmapFactory.decodeFile(File(System.getProperty("ruyo.fixtureDir"), "$name.jpg").path))
        return Bitmap.createBitmap(image, 0, 284, image.width, 1180).also { image.recycle() }
    }
    private fun clean(source: Bitmap, region: BubbleRegion): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val crop = Bitmap.createBitmap(BubbleEditRenderer.cleanedPixels(source, region), region.width, region.height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(crop, region.left.toFloat(), region.top.toFloat(), null); crop.recycle()
        return output
    }
    private fun preview(name: String, bitmap: Bitmap) {
        val file = File(System.getProperty("ruyo.previewDir"), "actual-$name-cleanup.png")
        file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun joinedBalloonParagraphsHaveCompleteMasksWithoutTouchingTheirSpikyBorders() {
        val source = source("joined-dialogue")
        val upper = listOf(OcrLine("I CAME TO",192,409,327,444), OcrLine("CLEAN UP AS FATHER",109,450,396,477), OcrLine("ORDERED BUT...",136,488,366,519))
        val lower = listOf(OcrLine("THIS PLACE",363,594,524,623), OcrLine("LOOKS LIKE A WAR",304,630,578,668), OcrLine("BROKE OUT",346,670,529,703))
        var result = source.copy(Bitmap.Config.ARGB_8888, true)
        for (lines in listOf(upper, lower)) {
            val region = requireNotNull(TextRegionRepair.select(source, lines, upper + lower))
            val next = clean(result, region); result.recycle(); result = next
            for (line in lines) {
                var remaining = 0
                for (y in line.top until line.bottom) for (x in line.left until line.right) if (Color.red(result.getPixel(x,y)) < 130) remaining++
                assertTrue("Original letters survived in ${line.text}: $remaining", remaining < 8)
            }
        }
        assertEquals(source.getPixel(445,430),result.getPixel(445,430))
        preview("joined",result); result.recycle(); source.recycle()
    }
    @Test fun brownEffectRemovesSolidLetterCentersAndPreservesTheBlackBalloonLine() {
        val source = source("pale-dialogue")
        val line = OcrLine("GIGGLE",230,77,454,209)
        val region = requireNotNull(TextRegionRepair.select(source,listOf(line)))
        assertFalse("A white background must not be rebuilt from remaining brown ink",region.inpaint)
        val result = clean(source,region)
        var remaining = 0; var original = 0
        fun brown(c:Int) = Color.red(c) in 45..190 && Color.red(c) > Color.green(c) * 1.35 && Color.green(c) > Color.blue(c) * 1.1
        for (y in 77 until 209) for (x in 230 until 454) {
            if (brown(source.getPixel(x,y))) original++
            if (brown(result.getPixel(x,y))) remaining++
        }
        assertTrue("Brown source ink remains: $remaining / $original", remaining < original * .02)
        assertEquals(source.getPixel(218,126),result.getPixel(218,126))
        preview("effect",result); result.recycle(); source.recycle()
    }
    @Test fun italicCaptionLeadingStrokeOutsideTheOcrBoxIsAlsoRemoved() {
        val source = source("outlined-caption")
        val lines = listOf(OcrLine("NORMALLY, I WOULD HAVE",179,33,498,64),OcrLine("JUST WALKED PAST WITHOUT",160,73,522,101),OcrLine("A SECOND GLANCE, BUT...",204,111,495,148))
        val region = requireNotNull(TextRegionRepair.select(source,lines))
        val result = clean(source,region)
        var remaining = 0
        for (y in 28 until 153) for (x in 155 until 527) if (Color.red(result.getPixel(x,y)) < 100) remaining++
        assertTrue("Dark caption strokes survived cleanup: $remaining",remaining < 8)
        var outline = 0
        val surface = requireNotNull(region.backgroundSurface)
        for (y in 28 until 153) for (x in 155 until 527) {
            val actual=result.getPixel(x,y);val expected=surface.colorAt(x,y)
            if (kotlin.math.abs(Color.red(actual)-Color.red(expected)) > 8) outline++
        }
        assertTrue("Caption outlines or shadows survived cleanup: $outline",outline < 20)
        preview("caption",result); result.recycle(); source.recycle()
    }
    @Test fun adjacentCommentRowsDoNotDisappearWhenOnlyTheirEmptyPaddingTouches() = runBlocking {
        val source = source("page-comments")
        val lines = listOf(OcrLine("oo(111.222)",40,573,194,605),OcrLine("THE SMART ONES QUIT FIRST.",20,626,344,651))
        val areas = AutoBubbleDetector.analyze(source,lines)
        assertEquals(2,areas.size)
        val regions=areas.map { requireNotNull(it.region) { "Skipped ${it.source}" } }
        assertFalse(regions[0].overlaps(regions[1]))
        var result=source.copy(Bitmap.Config.ARGB_8888,true)
        for (region in regions) { val next=clean(result,region);result.recycle();result=next }
        var remaining=0
        for(y in 623..653) for(x in 17..346) if(Color.red(result.getPixel(x,y))<90) remaining++
        assertTrue("The English sentence was not completely removed: $remaining",remaining<8)
        val surface=requireNotNull(regions[1].backgroundSurface) { "A pale comment row needs smooth background repair" }
        var outlines=0
        for(y in 623..653) for(x in 17..346) if(kotlin.math.abs(Color.red(result.getPixel(x,y))-Color.red(surface.colorAt(x,y)))>12) outlines++
        assertTrue("Pale outlines remain after removing the black letters: $outlines",outlines<25)
        preview("comment",result);result.recycle();source.recycle()
    }
    @Test fun blueCaptionRemovesBothBrightLettersAndTheirDarkShadowsAndReopensExactly() = runBlocking {
        val source=source("blue-caption")
        val lines=listOf(OcrLine("A BATTLE AGAINST EVIL",150,424,536,464),OcrLine("DARK MAGES HAS BEGUN!",138,476,548,518))
        val region=requireNotNull(TextRegionRepair.select(source,lines))
        assertFalse("A smooth blue panel should not reuse letter shadows as inpainting colors",region.inpaint)
        val surface=requireNotNull(region.backgroundSurface)
        val result=clean(source,region)
        var remnants=0
        for(y in 416..530) for(x in 126..558) {
            val actual=result.getPixel(x,y);val bg=surface.colorAt(x,y)
            if(maxOf(kotlin.math.abs(Color.red(actual)-Color.red(bg)),kotlin.math.abs(Color.green(actual)-Color.green(bg)),kotlin.math.abs(Color.blue(actual)-Color.blue(bg)))>12) remnants++
        }
        assertTrue("Original caption shadows survived: $remnants",remnants<25)
        preview("blue-shadows",result)
        val root=File(RuntimeEnvironment.getApplication().cacheDir,"curved-"+UUID.randomUUID())
        try {
            val store=LocalBookStore(RuntimeEnvironment.getApplication(),root,File(root,"stage"))
            val book=store.addBitmap("Blue caption",source)
            val edit=BubbleEdit(region=region,japanese="読めた。",margin=4)
            store.saveEdit(book.id,source,edit)
            val reopened=store.open(book)
            assertEquals(surface,reopened.edits.single().region.backgroundSurface)
            val expected=BubbleEditRenderer.composite(source,listOf(edit))
            assertTrue(expected.sameAs(reopened.displayed));expected.recycle()
        } finally { root.deleteRecursively();result.recycle();source.recycle() }
    }
}
