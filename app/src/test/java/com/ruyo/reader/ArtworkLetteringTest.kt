package com.ruyo.reader

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import com.ruyo.data.LocalBookStore
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkLetteringTest {
    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
    @Test fun hollowLettersLeaveTheirCentersTransparentAndAllStylesFit() {
        val safe = PixelMask(320, 130, BooleanArray(320 * 130) { true }).inset(8)
        val solid = BubbleFitter().fit("OOO", safe, 80f, 10f, languageTag = "en") as FitResult.Accepted
        val hollow = BubbleFitter().fit("OOO", safe, 80f, 10f, languageTag = "en", letteringStyle = LetteringStyle.OUTLINE) as FitResult.Accepted
        val a = pixels(solid.ink); val b = pixels(hollow.ink)
        assertTrue(a.indices.count { Color.alpha(a[it]) > 240 && Color.alpha(b[it]) == 0 } > 150)
        for (style in LetteringStyle.entries) for (text in listOf("待って！一緒に行こう。", "A caption that must shrink to fit.")) {
            val fit = BubbleFitter().fit(text, safe, 80f, 6f, languageTag = if (text.startsWith("A")) "en" else "ja", letteringStyle = style, contrastOutline = true) as FitResult.Accepted
            assertEquals(text, fit.text); assertEquals(0, safe.outsideInkCount(fit.ink))
            assertEquals(text, fit.lines.joinToString("") { text.substring(it.start, it.end) })
            File(System.getProperty("ruyo.previewDir"), "lettering-${style.name}-${if(text.startsWith("A")) "en" else "ja"}.png").apply { parentFile!!.mkdirs() }.outputStream().use { fit.ink.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fit.ink.recycle()
        }
        solid.ink.recycle(); hollow.ink.recycle()
    }
    @Test fun softFillRetainsTranslucentInteriorAndCacheDoesNotLeakAcrossSources() {
        val w = 180; val h = 100
        val shape = PixelMask(w, h, BooleanArray(w*h) { true })
        val mask = BooleanArray(w*h) { it % w in 70..78 && it / w in 25..75 }
        val region = BubbleRegion(0, 0, shape, mask, Color.BLUE, inpaint = true)
        val first = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val second = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val clean = BubbleEditRenderer.cleanedPixels(first, region)
        assertTrue(clean.all { it == Color.BLUE })
        clean.fill(Color.GREEN) // Caller-owned array must not mutate the cached background.
        assertTrue(BubbleEditRenderer.cleanedPixels(first, region).all { it == Color.BLUE })
        assertTrue(BubbleEditRenderer.cleanedPixels(second, region).all { it == Color.RED })
        first.eraseColor(Color.YELLOW)
        assertTrue(BubbleEditRenderer.cleanedPixels(first, region).all { it == Color.YELLOW })
        val result = BubbleFitter().fit("OO", shape.inset(8), 65f, 6f, languageTag = "en", letteringStyle = LetteringStyle.TRANSLUCENT, fillOpacity = .3f) as FitResult.Accepted
        assertTrue(pixels(result.ink).count { Color.alpha(it) in 70..85 } > 100)
        first.recycle(); second.recycle(); result.ink.recycle()
    }
}
