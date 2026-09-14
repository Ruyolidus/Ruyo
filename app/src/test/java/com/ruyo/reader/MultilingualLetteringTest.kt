package com.ruyo.reader

import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.RectF
import com.ruyo.data.LocalBookStore
import com.ruyo.data.SavedLine
import com.ruyo.sample.SampleChapter
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
class MultilingualLetteringTest {
    private fun mask() = PixelMask.fromPath(520, 240, Path().apply { addOval(RectF(0f, 0f, 520f, 240f), Path.Direction.CW) }).inset(14)

    @Test fun differentScriptsKeepCompleteTextInsideTheRegion() {
        val examples = listOf(
            "ja" to "一緒に行こう！",
            "fr" to "Allons-y ! Le café est prêt.",
            "ar" to "هيا نذهب معًا 25!",
            "hi" to "चलो साथ चलें।",
            "ko" to "함께 가자!",
            "ru" to "Пойдём вместе!",
            "th" to "ไปด้วยกัน",
        )
        val region = mask()
        val directory = File(System.getProperty("ruyo.previewDir")).apply { mkdirs() }
        for ((language, text) in examples) {
            val result = BubbleFitter().fit(text, region, 36f, 12f, languageTag = language)
            assertTrue(language + ": " + result, result is FitResult.Accepted)
            val fit = result as FitResult.Accepted
            assertEquals(text, fit.lines.joinToString("") { text.substring(it.start, it.end) })
            assertEquals(0, region.outsideInkCount(fit.ink))
            File(directory, "lettering-" + language + ".png").outputStream().use { fit.ink.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fit.ink.recycle()
        }
    }

    @Test fun chosenFontChangesTheRenderedLettering() {
        val text = "Au revoir !"
        val plain = BubbleFitter().fit(text, mask(), 44f, 12f, languageTag = "fr", typeface = LetteringFont.SANS.typeface(false, false)) as FitResult.Accepted
        val serif = BubbleFitter().fit(text, mask(), 44f, 12f, languageTag = "fr", typeface = LetteringFont.SERIF.typeface(true, true)) as FitResult.Accepted
        assertFalse("Font selection must affect pixels", plain.ink.sameAs(serif.ink))
        assertEquals(0, mask().outsideInkCount(serif.ink))
    }

    @Test fun sourceSizeLanguageAndTypefaceSurviveSaving() {
        val store = LocalBookStore(RuntimeEnvironment.getApplication())
        val source = SampleChapter.build().first().original
        val book = store.addBitmap("Languages", source)
        val region = (BubbleSelector.select(source, 450, 105) as SelectionResult.Selected).region
        val height = SourceLettering.estimateHeight(region)
        assertNotNull(height)
        val edit = BubbleEdit(region = region, japanese = "Allons-y !", margin = 12, languageTag = "fr",
            fontFamily = "serif", bold = true, italic = true, sourceLetterHeight = height, matchSourceSize = true)
        val preview = BubbleEditRenderer.preview(source, edit).getOrThrow()
        assertTrue(preview.fit.fontSize <= BubbleEditRenderer.preferredSize(source.width, edit))
        store.saveEdit(book.id, source, edit)
        val saved = LocalBookStore(RuntimeEnvironment.getApplication()).open(book).edits.single()
        assertEquals("fr", saved.languageTag)
        assertEquals("serif", saved.fontFamily)
        assertTrue(saved.bold && saved.italic && saved.matchSourceSize)
        assertEquals(height, saved.sourceLetterHeight)
        val line = SavedLine("language-test", saved.japanese, book.title, languageTag = saved.languageTag)
        store.toggleSaved(line)
        assertEquals("fr", store.savedLines().first { it.id == line.id }.languageTag)
    }

    @Test fun languageCodesAreExtensibleAndLegacyEditsDefaultToJapanese() {
        assertEquals("zh-Hant", TextLanguages.normalize("zh-hant"))
        assertEquals("fon", TextLanguages.normalize("fon"))
        assertTrue(runCatching { TextLanguages.normalize("not a language") }.isFailure)
        val region = BubbleRegion(0, 0, mask(), BooleanArray(520 * 240), android.graphics.Color.WHITE)
        val legacy = BubbleEdit(region = region, japanese = "はい", margin = 2)
        assertEquals("ja", legacy.languageTag)
        assertFalse(legacy.matchSourceSize)
        val composed = BubbleFitter().fit("Cafe\u0301", mask(), 32f, 12f, languageTag = "fr")
        assertTrue(composed is FitResult.Accepted)
    }
}
