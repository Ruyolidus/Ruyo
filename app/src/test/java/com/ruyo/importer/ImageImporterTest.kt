package com.ruyo.importer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageImporterTest {
    @Test @Config(sdk = [31, 35]) fun ordinaryLongChapterStripRetainsItsOriginalPixels() {
        val source = Bitmap.createBitmap(900, 5600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val paint = Paint().apply { color = Color.BLACK; strokeWidth = 1f }
        Canvas(source).apply { for (y in 20 until source.height step 40) drawLine(10f, y.toFloat(), 890f, y.toFloat(), paint) }
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "sharp-strip.png")
        file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val imported = ImageImporter.load(file, file.name)
        assertEquals(900, imported.bitmap.width)
        assertEquals(5600, imported.bitmap.height)
        assertFalse(imported.reducedForPreview)
        assertTrue("Fine source lines must not be resampled under the working-image limit", source.sameAs(imported.bitmap))
        source.recycle(); imported.bitmap.recycle(); file.delete()
    }

    @Test fun oversizedImageStillHasABoundedWorkingBitmap() {
        val source = Bitmap.createBitmap(1200, 6500, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "oversized-strip.png")
        file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        source.recycle()
        val imported = ImageImporter.load(file, file.name)
        assertTrue(imported.reducedForPreview)
        assertTrue(imported.bitmap.width.toLong() * imported.bitmap.height <= ImageImporter.MAX_WORKING_PIXELS)
        assertTrue(imported.bitmap.width > 1000)
        assertTrue(imported.bitmap.height <= ImageImporter.MAX_WORKING_DIMENSION)
        imported.bitmap.recycle(); file.delete()
    }
}
