package com.ruyo.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import androidx.test.core.app.ApplicationProvider
import com.ruyo.data.LocalBookStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BubbleAreasTest {
    private fun fixture(three: Boolean = false): Pair<Bitmap, List<Point>> {
        val points = if (three) listOf(Point(110, 110), Point(238, 238), Point(366, 366)) else listOf(Point(110, 110), Point(238, 238))
        val side = if (three) 480 else 352
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(28, 38, 51))
        val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        points.forEach { canvas.drawCircle(it.x.toFloat(), it.y.toFloat(), 99f, paint) }
        paint.color = Color.BLACK; paint.textSize = 22f; paint.textAlign = Paint.Align.CENTER
        points.forEachIndexed { i, p -> canvas.drawText("Part " + (i + 1), p.x.toFloat(), p.y + 7f, paint) }
        return bitmap to points
    }

    @Test fun connectedLobesHaveDisjointCompleteRepairOwnership() {
        for (three in listOf(false, true)) {
            val (source, centers) = fixture(three)
            val region = (BubbleSelector.select(source, 110, 85) as SelectionResult.Selected).region
            val local = centers.map { Point(it.x - region.left, it.y - region.top) }
            val parts = BubbleAreas.split(region, local)
            assertEquals(centers.size, parts.size)
            assertEquals(centers.size, BubbleAreas.suggest(region).size)
            for (y in 0 until region.height) for (x in 0 until region.width) {
                val count = parts.count { it.contains(region.left + x, region.top + y) }
                assertEquals(if (region.interior[x, y]) 1 else 0, count)
            }
            assertEquals(region.eraseMask.count { it }, parts.sumOf { it.eraseMask.count { bit -> bit } })
            val edits = parts.mapIndexed { i, part -> BubbleEdit(region = part, japanese = if (i == 0) "Hello." else "We should leave before the rain starts.", margin = 6, languageTag = "en") }
            edits.forEach { edit -> assertTrue(BubbleEditRenderer.preview(source, edit).isSuccess) }
            val first = BubbleEditRenderer.composite(source, listOf(edits[0]))
            for (y in 0 until source.height) for (x in 0 until source.width) if (!parts[0].contains(x, y)) assertEquals(source.getPixel(x, y), first.getPixel(x, y))
            val combined = BubbleEditRenderer.composite(source, edits)
            for (y in 0 until source.height) for (x in 0 until source.width) if (!region.contains(x, y)) assertEquals(source.getPixel(x, y), combined.getPixel(x, y))
        }
    }

    @Test fun refusesCutsThroughLettersAndInvalidCenters() {
        val mask = BooleanArray(200 * 100) { true }
        val ink = BooleanArray(mask.size) { it % 200 in 85..115 && it / 200 in 40..60 }
        val region = BubbleRegion(0, 0, PixelMask(200, 100, mask), ink, Color.WHITE)
        assertTrue(runCatching { BubbleAreas.split(region, listOf(Point(40, 50), Point(160, 50))) }.isFailure)
        assertTrue(runCatching { BubbleAreas.split(region, listOf(Point(-1, 50))) }.isFailure)
        assertTrue(runCatching { BubbleAreas.split(region, listOf(Point(20, 20), Point(20, 20))) }.isFailure)
        assertEquals(1, BubbleAreas.split(region, listOf(Point(40, 50))).size)
    }

    @Test fun savedAreasAndIndependentEditsSurviveReopening() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = LocalBookStore(context)
        val (source, centers) = fixture()
        val book = store.addBitmap("Joined balloons", source)
        try {
            val open = store.openPage(book, book.pages.first())
            val region = (BubbleSelector.select(open.original, 110, 85) as SelectionResult.Selected).region
            val local = centers.map { Point(it.x - region.left, it.y - region.top) }
            store.saveAreas(open, region, local)
            assertEquals(local, store.areaCenters(store.openPage(book, book.pages.first()), region))
            val parts = BubbleAreas.split(region, local)
            val edits = parts.mapIndexed { i, r -> BubbleEdit(region = r, japanese = if (i == 0) "First." else "Second.", margin = 6, languageTag = "en") }
            edits.forEach { store.saveEdit(book.id, source, it, book.pages.first().id) }
            val reopened = store.openPage(book, book.pages.first())
            assertEquals(2, reopened.edits.size)
            assertTrue(runCatching {
                store.saveEdit(book.id, source, BubbleEdit(region = region, japanese = "Overlapping edit", margin = 6, languageTag = "en"), book.pages.first().id)
            }.isFailure)
            assertTrue(runCatching { store.saveAreas(reopened, region, listOf(local.first())) }.isFailure)
            assertEquals(2, store.openPage(book, book.pages.first()).edits.size)
            assertEquals(1, reopened.edits.count { it.region.contains(110, 110) })
            assertEquals(1, reopened.edits.count { it.region.contains(238, 238) })
            store.removeEdit(book.id, edits[0].id, book.pages.first().id)
            assertEquals(edits[1].id, store.openPage(book, book.pages.first()).edits.single().id)
        } finally { store.removeBook(book.id) }
    }
}
