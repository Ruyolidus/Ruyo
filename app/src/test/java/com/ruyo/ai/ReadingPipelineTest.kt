package com.ruyo.ai

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import com.ruyo.data.LocalBookStore
import com.ruyo.reader.*
import com.ruyo.web.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingPipelineTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun fixture(): Pair<Bitmap, List<OcrLine>> {
        val bitmap = Bitmap.createBitmap(352, 352, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(28, 38, 51))
        val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        listOf(110f to 110f, 238f to 238f).forEach { (x, y) -> canvas.drawCircle(x, y, 99f, paint) }
        paint.color = Color.BLACK; paint.textSize = 22f; paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Part one", 110f, 117f, paint); canvas.drawText("Part two", 238f, 245f, paint)
        return bitmap to listOf(OcrLine("Part one",  60, 94, 160, 119), OcrLine("Part two", 188, 222, 288, 247))
    }

    @Test fun fusedBubbleLinesBecomeIndependentTranslationsAndDoNotChangeSourcePixels() = runBlocking {
        val (source, lines) = fixture()
        val directory = File(context.cacheDir, "pipeline-" + UUID.randomUUID())
        val store = LocalBookStore(context, directory, File(directory, "staging"))
        val profiles = Profiles()
        val inputs = mutableListOf<String>()
        val ocr = object : OcrService {
            override suspend fun recognize(source: Bitmap, region: BubbleRegion, script: OcrScript) = error("Page OCR expected")
            override suspend fun lines(source: Bitmap, script: OcrScript) = lines
        }
        try {
            val book = store.addBitmap("Joined", source)
            val pipeline = PageTranslationPipeline(store, ocr, profiles, TranslationService { _, input, _ ->
                inputs += input
                if (input == "Part one") "First." else "Second."
            })
            val settings = TranslationSettings(profiles.profile.id, "en", OcrScript.LATIN)
            val result = pipeline.process(store.open(book), settings, { true }, {}, {})
            assertNotNull(result)
            assertEquals(listOf("Part one", "Part two"), inputs)
            val translated = store.open(book)
            assertEquals(2, translated.edits.size)
            assertTrue(source.sameAs(translated.original))
            val first = translated.edits.first().region
            val second = translated.edits.last().region
            assertFalse(first.overlaps(second))
            for (y in 0 until source.height) for (x in 0 until source.width) {
                if (!first.contains(x, y) && !second.contains(x, y)) assertEquals(source.getPixel(x, y), translated.displayed.getPixel(x, y))
            }
            pipeline.process(translated, settings, { true }, {}, {})
            assertEquals("Saved translations must not incur another request", 2, inputs.size)
        } finally { source.recycle(); directory.deleteRecursively() }
    }

    @Test fun joinedDialogueUsesOneBatchAndRetriesReuseRecognition() = runBlocking {
        val (source, lines) = fixture()
        val directory = File(context.cacheDir, "batch-" + UUID.randomUUID())
        val store = LocalBookStore(context, directory, File(directory, "staging"))
        val profiles = Profiles()
        var recognitions = 0; var requests = 0
        val ocr = object : OcrService {
            override suspend fun recognize(source: Bitmap, region: BubbleRegion, script: OcrScript) = error("Page OCR expected")
            override suspend fun lines(source: Bitmap, script: OcrScript): List<OcrLine> { recognitions++; return lines }
        }
        val service = object : TranslationService {
            override suspend fun translate(secret: ProviderSecret, source: String, target: String) = error("A joined pair should use a batch")
            override suspend fun translateBatch(secret: ProviderSecret, sources: List<SourceDialogue>, target: String): Map<String, String> {
                requests++
                assertEquals(2, sources.size)
                if (requests == 1) error("Temporary provider failure")
                return sources.associate { it.id to if (it.text == "Part one") "First." else "Second." }
            }
        }
        try {
            val book = store.addBitmap("Batched dialogue", source)
            val pipeline = PageTranslationPipeline(store, ocr, profiles, service)
            val settings = TranslationSettings(profiles.profile.id, "en", OcrScript.LATIN)
            assertTrue(runCatching { pipeline.process(store.open(book), settings, { true }, {}, {}) }.isFailure)
            assertTrue(store.open(book).edits.isEmpty())
            pipeline.process(store.open(book), settings, { true }, {}, {})
            assertEquals(1, recognitions)
            assertEquals(2, requests)
            assertEquals(2, store.open(book).edits.size)
            pipeline.process(store.open(book), settings, { true }, {}, {})
            assertEquals(2, requests)
        } finally { directory.deleteRecursively(); source.recycle() }
    }

    @Test fun incompleteLinesAndArtworkAreNotSentForTranslation() = runBlocking {
        val (source, _) = fixture()
        try {
            val lines = listOf(OcrLine("Outside", 2, 2, 26, 16), OcrLine("Crosses image border", -1, 30, 100, 45))
            assertTrue(AutoBubbleDetector.detect(source, lines).all { it.bubbles.isEmpty() })
        } finally { source.recycle() }
    }

    @Test fun missedSourceLineDoesNotGetErasedWithRecognizedText() = runBlocking {
        val (source, lines) = fixture()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 18f; textAlign = Paint.Align.CENTER }
        Canvas(source).drawText("Missed line", 110f, 151f, paint)
        try {
            val detected = AutoBubbleDetector.detect(source, lines).flatMap { it.bubbles }
            // The whole-bubble path must reject incomplete OCR, but a smaller
            // validated text region can translate the recognized line safely.
            val first = detected.single { it.source == "Part one" }
            assertNotNull(first.region.backgroundSurface)
            val composite = BubbleEditRenderer.composite(source, detected.map {
                BubbleEdit(region = it.region, japanese = "Ready.", margin = 3, languageTag = "en")
            })
            for (y in 133..157) for (x in 50..170) {
                assertFalse(first.region.contains(x, y))
                assertEquals("Unrecognized lettering changed at $x,$y", source.getPixel(x, y), composite.getPixel(x, y))
            }
            composite.recycle()
            assertTrue(detected.any { it.source == "Part two" })
        } finally { source.recycle() }
    }

    @Test fun webImagesLoadOnceAndStayOutOfTheLibrary() = runBlocking {
        val (bitmap, _) = fixture()
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val library = LocalBookStore(context).list().map { it.id }
        var downloads = 0
        val chapter = WebChapter("https://example.com/chapter", "Web chapter", listOf(WebImage("https://example.com/page.png", "Page 1", 352, 352)))
        val session = WebReadingSession(context, chapter, emptyMap(), "Test") { _, check ->
            check(); downloads++; ByteArrayInputStream(bytes)
        }
        try {
            val a = async { session.load(0) }; val b = async { session.load(0) }
            assertEquals(a.await().book.id, b.await().book.id)
            assertEquals(1, downloads)
            val page = session.load(0)
            val region = (BubbleSelector.select(page.original, 110, 85) as SelectionResult.Selected).region
            val parts = BubbleAreas.split(region, BubbleAreas.suggest(region))
            session.store.saveEdit(page.book.id, page.original, BubbleEdit(region = parts.first(), japanese = "Saved.", margin = 5, languageTag = "en"), page.page.id)
            session.changed()
            assertEquals("Saved.", session.load(0).edits.single().japanese)
            assertEquals(1, downloads)
            val oldId = session.load(0).book.id
            session.updateImages(listOf(WebImage("https://example.com/new.png", "Newly loaded earlier image", 352, 352)) + session.images)
            assertEquals(oldId, session.load(1).book.id)
            assertEquals("Saved.", session.load(1).edits.single().japanese)
            assertEquals(1, downloads)
            assertEquals(library, LocalBookStore(context).list().map { it.id })
        } finally { session.close(); bitmap.recycle() }
    }

    private class Profiles : ProfileStore {
        val profile = ProviderProfile(name = "Fixture", kind = ProviderKind.COMPATIBLE, baseUrl = "http://localhost:8080/v1", model = "fixture")
        override fun list() = listOf(profile)
        override fun get(id: String) = ProviderSecret(profile, "")
        override fun save(profile: ProviderProfile, replacementKey: String?, removeKey: Boolean) = Unit
        override fun delete(id: String) = Unit
    }
}
