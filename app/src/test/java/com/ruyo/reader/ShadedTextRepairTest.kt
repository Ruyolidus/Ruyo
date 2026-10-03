package com.ruyo.reader

import android.graphics.*
import com.ruyo.ai.OcrLine
import com.ruyo.data.LocalBookStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShadedTextRepairTest {
    private fun write(bitmap: Bitmap, name: String) {
        val file = File(System.getProperty("ruyo.previewDir"), name + ".png")
        file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun outlinedTextAcrossABackgroundTransitionIsRepairedWithoutTouchingTheBorder() = runBlocking {
        val source = Bitmap.createBitmap(540, 340, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val canvas = Canvas(source); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(232, 237, 244); canvas.drawRect(0f, 169f, 540f, 340f, paint)
        paint.textSize = 32f; paint.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        val lines = mutableListOf<OcrLine>()
        for ((text, baseline) in listOf("ARE YOU READY?" to 146f, "WE CAN GO NOW." to 188f)) {
            val bounds = Rect(); paint.getTextBounds(text, 0, text.length, bounds)
            paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = 7f; canvas.drawText(text, 110f, baseline, paint)
            paint.color = Color.BLACK; paint.style = Paint.Style.FILL; canvas.drawText(text, 110f, baseline, paint)
            lines += OcrLine(text, 110 + bounds.left, baseline.toInt() + bounds.top, 110 + bounds.right, baseline.toInt() + bounds.bottom)
        }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; canvas.drawRect(40f, 55f, 490f, 265f, paint)
        val selected = requireNotNull(TextRegionRepair.select(source, lines))
        assertTrue(selected.backgroundSurface!!.rows.isNotEmpty())
        val edit = BubbleEdit(region = selected, japanese = "準備はいい？もう行けるよ。", margin = 4)
        val preview = BubbleEditRenderer.preview(source, edit).getOrThrow()
        assertEquals(0, selected.interior.inset(4).outsideInkCount(preview.fit.ink))
        var unmaskedLettering = 0
        for (y in selected.top until selected.top + selected.height) for (x in selected.left until selected.left + selected.width) {
            val expected = if (y < 169) Color.WHITE else Color.rgb(232, 237, 244)
            val actual = source.getPixel(x, y)
            val difference = maxOf(abs(Color.red(expected) - Color.red(actual)), abs(Color.green(expected) - Color.green(actual)), abs(Color.blue(expected) - Color.blue(actual)))
            if (difference > 6 && !selected.eraseMask[(y - selected.top) * selected.width + x - selected.left]) unmaskedLettering++
        }
        assertEquals("Neither original dark lettering nor its pale outline should remain", 0, unmaskedLettering)
        for (y in 0 until selected.height) for (x in 0 until selected.width) if (selected.eraseMask[y * selected.width + x] && Color.alpha(preview.fit.ink.getPixel(x, y)) == 0) {
            val expected = if (selected.top + y < 169) Color.WHITE else Color.rgb(232, 237, 244)
            assertEquals("The background transition must remain straight after removing an outline", expected, preview.crop.getPixel(x, y))
        }
        val output = BubbleEditRenderer.composite(source, listOf(edit))
        for (y in 0 until source.height) for (x in 0 until source.width) if (!selected.contains(x, y)) assertEquals(source.getPixel(x, y), output.getPixel(x, y))
        write(source, "shaded-outline-original"); write(output, "shaded-outline-translated")
        val root = File(RuntimeEnvironment.getApplication().cacheDir, UUID.randomUUID().toString())
        try {
            val store = LocalBookStore(RuntimeEnvironment.getApplication(), root, File(root, "stage"))
            val book = store.addBitmap("Shaded fixture", source); store.saveEdit(book.id, source, edit)
            val reopened = store.open(book)
            assertEquals(selected.backgroundSurface, reopened.edits.single().region.backgroundSurface); assertTrue(output.sameAs(reopened.displayed))
            store.removeEdit(book.id, edit.id); assertTrue(source.sameAs(store.open(book).displayed))
        } finally { root.deleteRecursively() }
    }
    @Test fun artworkOutlineMaskIncludesTheDarkCentersBeforeInpainting() {
        val source = Bitmap.createBitmap(400,220,Bitmap.Config.ARGB_8888)
        val background = IntArray(400*220) { i -> val wave=(sin(i%400/23.0)*14+sin(i/400/13.0)*9).toInt();Color.rgb(55+wave,125+wave,185+wave) }
        source.setPixels(background,0,400,0,0,400,220)
        val glyphs=Bitmap.createBitmap(400,220,Bitmap.Config.ARGB_8888)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize=42f;typeface=Typeface.create("sans-serif",Typeface.BOLD) }
        val canvas=Canvas(glyphs); val bounds=Rect();paint.getTextBounds("Ready",0,5,bounds)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=6f;paint.color=Color.WHITE;canvas.drawText("Ready",90f,126f,paint)
        paint.style=Paint.Style.FILL;paint.color=Color.BLACK;canvas.drawText("Ready",90f,126f,paint)
        Canvas(source).drawBitmap(glyphs,0f,0f,null)
        val region=requireNotNull(TextRegionRepair.selectArtwork(source,listOf(OcrLine("Ready",90+bounds.left,126+bounds.top,90+bounds.right,126+bounds.bottom))))
        var omitted=0;var total=0
        for(y in 0 until 220) for(x in 0 until 400) if(Color.alpha(glyphs.getPixel(x,y))>200) {
            total++
            if(!region.contains(x,y)||!region.eraseMask[(y-region.top)*region.width+x-region.left]) omitted++
        }
        assertTrue("Original stroke centers or outlines were left outside the cleanup mask: $omitted / $total",omitted<total*.01)
        val clean=Bitmap.createBitmap(BubbleEditRenderer.cleanedPixels(source,region),region.width,region.height,Bitmap.Config.ARGB_8888)
        write(clean,"outlined-art-complete-mask");clean.recycle();source.recycle();glyphs.recycle()
    }
    @Test fun localInpaintingPreservesEveryUnmaskedPixelAndReconstructsNarrowTextStrokes() {
        val w = 260; val h = 140
        val background = IntArray(w * h) { i ->
            val wave = (sin(i % w / 19.0) * 7 + sin(i / w / 17.0) * 6).toInt()
            Color.rgb(80 + wave, 140 + wave, 185 + wave)
        }
        val source = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(background, 0, w, 0, 0, w, h) }
        val glyphs = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 30f }
        Canvas(glyphs).drawText("Ready?", 75f, 83f, paint); Canvas(source).drawBitmap(glyphs, 0f, 0f, null)
        val mask = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) if (Color.alpha(glyphs.getPixel(x, y)) > 0) for (dy in -2..2) for (dx in -2..2) mask[(y + dy) * w + x + dx] = true
        val pixels = IntArray(w * h); source.getPixels(pixels, 0, w, 0, 0, w, h)
        val repaired = LocalInpainter.repair(pixels, w, h, mask)
        var error = 0L; var count = 0
        for (i in pixels.indices) if (mask[i]) {
            error += abs(Color.red(repaired[i]) - Color.red(background[i])); count++
            assertTrue("Repair invented a dark or bright streak",Color.red(repaired[i]) in 67..93)
        }
        else assertEquals(pixels[i], repaired[i])
        assertTrue("Local shading should remain continuous", error.toDouble() / count < 12)
        assertTrue(runCatching { LocalInpainter.repair(pixels, w, h, BooleanArray(w * h) { true }) }.isFailure)
        write(Bitmap.createBitmap(repaired, w, h, Bitmap.Config.ARGB_8888), "texture-inpainted")
        val line = OcrLine("Ready?", 75, 58, 180, 86)
        val detected = TextRegionRepair.selectArtwork(source, listOf(line))
        assertNotNull("High-contrast text over gentle texture should be selectable", detected)
        val neighbor = OcrLine("Separate", 78, 87, 188, 109)
        assertNull(TextRegionRepair.selectArtwork(source, listOf(line), listOf(line, neighbor)))
    }
}
