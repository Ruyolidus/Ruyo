package com.ruyo.ui

import android.graphics.BitmapFactory
import android.net.Uri
import com.ruyo.ai.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import com.ruyo.web.WebChapter
import com.ruyo.web.WebImage
import androidx.compose.ui.geometry.Offset
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.ruyo.data.LocalBookStore
import com.ruyo.sample.SampleChapter
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun resetState() {
        context.filesDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
        context.getSharedPreferences("settings", 0).edit().clear().commit()
    }

    @Test fun libraryReaderStudyAndThemeWorkTogether() {
        val model = RuyoModel(context)
        compose.setContent { RuyoApp(model) }
        awaitTag("book-sample")
        capture("library-light")
        compose.onNodeWithTag("book-sample").performClick()
        awaitTag("sample-reader")
        capture("reader")
        compose.onNodeWithTag("sample-panel-0").performTouchInput { click(Offset(width * 0.5f, height * 0.195f)) }
        awaitTag("study-sheet")
        capture("study", "study-sheet")
        compose.onNodeWithTag("save-sentence").performClick()
        awaitState { model.saved.isNotEmpty() }
        compose.onNodeWithContentDescription("Close lesson").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("nav-saved").performClick()
        compose.onNodeWithText("待って！").assertIsDisplayed()
        capture("saved")
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithTag("theme-dark").performClick()
        assertEquals("dark", context.getSharedPreferences("settings", 0).getString("theme", null))
        capture("settings-dark")
        compose.onNodeWithTag("nav-library").performClick()
        capture("library-dark")
    }

    @Test fun importedImageCanBeEditedThroughTheScreenAndReopened() {
        val store = LocalBookStore(context)
        val source = SampleChapter.build().first().original
        val book = store.addBitmap("Test chapter", source)
        val model = RuyoModel(context)
        compose.setContent { RuyoApp(model) }
        awaitTag("book-${book.id}")
        compose.onNodeWithTag("book-${book.id}").performClick()
        awaitTag("imported-page")
        compose.onNodeWithContentDescription("Edit bubbles").performClick()
        compose.onNodeWithTag("imported-page").performTouchInput { click(Offset(width * 0.5f, height * 0.105f)) }
        awaitTag("japanese-input")
        capture("bubble-cleanup")
        compose.onNodeWithTag("japanese-input").performTextInput("待って！")
        compose.onNodeWithTag("japanese-input").assertTextContains("待って！")
        compose.onNodeWithTag("preview-edit").performScrollTo().assertIsEnabled().performClick()
        try { awaitState { model.draft?.preview != null } }
        catch (error: Exception) { capture("preview-failure"); throw error }
        compose.onNodeWithTag("preview-visible").assertIsDisplayed()
        capture("bubble-preview")
        val firstPreview = model.draft!!.preview!!.crop
        compose.onNodeWithTag("japanese-input").performScrollTo().performTextReplacement("ありがとう。")
        compose.onNodeWithTag("save-edit").assertIsNotEnabled()
        compose.onNodeWithTag("preview-edit").performScrollTo().performClick()
        awaitState { model.draft?.previewVersion == 2 && !model.busy }
        compose.onNodeWithTag("preview-visible").assertIsDisplayed()
        assertFalse(firstPreview.sameAs(model.draft!!.preview!!.crop))
        compose.onNodeWithTag("japanese-input").performScrollTo().performTextReplacement("W".repeat(512))
        compose.onNodeWithTag("preview-edit").performScrollTo().performClick()
        awaitState { model.draft?.previewError != null && !model.busy }
        compose.onNodeWithTag("preview-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("save-edit").assertIsNotEnabled()
        capture("bubble-fit-error")
        compose.onNodeWithTag("japanese-input").performScrollTo().performTextReplacement("待って！")
        compose.onNodeWithTag("preview-edit").performScrollTo().performClick()
        awaitState { model.draft?.previewVersion == 3 && !model.busy }
        compose.onNodeWithTag("preview-visible").assertIsDisplayed()
        val beforePadding = model.draft!!.previewVersion
        compose.onNodeWithTag("text-padding").performScrollTo().performTouchInput { click(Offset(width * 0.18f, height * 0.5f)) }
        awaitState { model.draft!!.previewVersion > beforePadding && !model.busy }
        compose.onNodeWithTag("preview-visible").assertIsDisplayed()
        compose.onNodeWithTag("save-edit").performClick()
        awaitTag("book-reader")
        awaitState { !model.busy }
        val reopened = LocalBookStore(context).open(book)
        assertEquals("待って！", reopened.edits.single().japanese)
        assertTrue(source.sameAs(reopened.original))
        assertFalse(source.sameAs(reopened.displayed))
        awaitTag("imported-page")
        capture("imported-reader")
        compose.onNodeWithContentDescription("Edit bubbles").performClick()
        compose.onNodeWithTag("imported-page").performTouchInput { click(Offset(width * 0.5f, height * 0.105f)) }
        awaitTag("japanese-input")
        assertTrue(model.draft!!.existing)
        compose.onNodeWithTag("japanese-input").performTextReplacement("行こう！")
        compose.onNodeWithTag("preview-edit").performScrollTo().performClick()
        awaitState { model.draft?.preview != null && !model.busy }
        compose.onNodeWithTag("preview-visible").assertIsDisplayed()
        compose.onNodeWithTag("save-edit").performClick()
        awaitTag("imported-page")
        assertEquals("行こう！", store.open(book).edits.single().japanese)
    }

    @Test fun multipleImagesCanBeReviewedReorderedReadAndAppended() {
        val source = SampleChapter.build().first().original
        val files = listOf("page10.png", "page2.png", "page1.png").map { name ->
            File(context.cacheDir, name).apply { outputStream().use { source.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        }
        val model = RuyoModel(context)
        compose.setContent { RuyoApp(model) }
        awaitTag("book-sample")
        compose.runOnIdle { model.importImages(files.map { Uri.fromFile(it) }) }
        awaitTag("import-review")
        capture("chapter-import-before-order")
        assertEquals(model.importing!!.failures.joinToString("; "), listOf("page1.png", "page2.png", "page10.png"), model.importing!!.pages.map { it.page.name })
        val first = model.importing!!.pages.first().page.id
        compose.onNodeWithTag("move-down-$first").performScrollTo().performClick()
        assertEquals(first, model.importing!!.pages[1].page.id)
        capture("chapter-import")
        compose.onNodeWithTag("chapter-title").performScrollTo().performTextReplacement("The journey")
        compose.onNodeWithTag("save-chapter").performClick()
        awaitTag("imported-page")
        assertEquals(3, model.chapter!!.pages.size)
        val chapter = model.chapter!!
        compose.onNodeWithTag("chapter-scroll").performScrollToIndex(1)
        val second = chapter.pages[1].id
        awaitTag("imported-page-$second")
        compose.runOnIdle { model.flushPosition() }
        awaitState { model.store.progress(chapter).pageId == second }
        capture("chapter-reader")
        compose.onNodeWithContentDescription("Manage chapter pages").performClick()
        awaitTag("chapter-pages")
        capture("chapter-pages")
        compose.onNodeWithTag("move-up-$second").performScrollTo().performClick()
        compose.onNodeWithTag("save-page-order").performClick()
        awaitTag("book-reader")
        awaitState { !model.busy }
        assertEquals(second, model.chapter!!.pages.first().id)
        compose.runOnIdle { model.importImages(listOf(Uri.fromFile(files.first())), chapter.id) }
        awaitTag("import-review")
        assertEquals("The journey", model.importing!!.title)
        compose.onNodeWithTag("save-chapter").performClick()
        awaitTag("book-reader")
        awaitState { !model.busy }
        assertEquals(4, model.store.readBook(chapter.id).pages.size)
    }

    @Test fun browserAddressAndImageReviewAreUsable() {
        val model = RuyoModel(context)
        compose.setContent { RuyoApp(model) }
        awaitTag("book-sample")
        compose.onNodeWithContentDescription("Browse websites").performClick()
        awaitTag("web-browser")
        compose.onNodeWithTag("web-address").assertDoesNotExist()
        capture("web-browser-collapsed")
        compose.onNodeWithTag("toggle-web-address").performClick()
        awaitTag("web-address")
        compose.onNodeWithText("Paste a chapter link").assertIsDisplayed()
        compose.onNodeWithText("Website address").assertIsDisplayed()
        capture("web-browser")
        compose.runOnIdle { model.changeTheme("dark") }
        compose.onNodeWithText("Paste a chapter link").assertIsDisplayed()
        capture("web-browser-dark")
        compose.onNodeWithTag("web-address").performTextInput("file:///private/image.png")
        compose.onNodeWithTag("web-address").assertTextContains("file:///private/image.png")
        capture("web-address-entered-dark")
        compose.onNodeWithTag("web-go").performClick()
        compose.onNodeWithText("Use an HTTPS website address without a username or custom port.").assertIsDisplayed()
        compose.onNodeWithTag("toggle-web-address").performClick()
        compose.onNodeWithTag("web-address").assertDoesNotExist()
        compose.onNodeWithTag("toggle-web-address").performClick()
        compose.onNodeWithTag("web-address").assertTextContains("file:///private/image.png")
    }

    @Test fun discoveredWebImagesCanBeSelectedBeforeDownload() {
        val source = WebChapter("https://example.com/chapter", "Chapter one", listOf(
            WebImage("https://example.com/1.png", "1.png", 900, 1500),
            WebImage("https://example.com/2.png", "2.png", 900, 1500),
            WebImage("https://example.com/banner.png", "banner.png", 900, 140),
            WebImage("https://example.com/reaction.png", "reaction.png", 1000, 1400, "Comment image"),
        ))
        var selected: Set<String>? = null
        compose.setContent { RuyoTheme("light") { WebImagesSheet(source, {}) { selected = it } } }
        awaitTag("web-images-review")
        capture("web-images-review", "web-images-review")
        compose.onNodeWithText("Comment image").assertDoesNotExist()
        compose.onNodeWithText("Download 2 images").performClick()
        assertEquals(setOf(source.images[0].url, source.images[1].url), selected)
        compose.onNodeWithTag("show-other-web-images").performClick()
        compose.onNodeWithText("Comment image").assertIsDisplayed()
        compose.onNodeWithText("Download 2 images").assertIsDisplayed()
    }


    @Test fun defaultLanguageAndFontControlsCreateAPersistedFrenchEdit() {
        val source = SampleChapter.build().first().original
        val book = LocalBookStore(context).addBitmap("Font test", source)
        val model = RuyoModel(context)
        compose.setContent { RuyoApp(model) }
        awaitTag("book-sample")
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithTag("default-language").performScrollTo().performClick()
        compose.onNodeWithTag("language-fr").performClick()
        assertEquals("fr", model.targetLanguage)
        compose.onNodeWithTag("nav-library").performClick()
        compose.onNodeWithTag("book-" + book.id).performClick()
        awaitTag("imported-page")
        compose.onNodeWithContentDescription("Edit bubbles").performClick()
        compose.onNodeWithTag("imported-page").performTouchInput { click(Offset(width * 0.5f, height * 0.105f)) }
        awaitTag("japanese-input")
        assertEquals("fr", model.draft!!.edit.languageTag)
        compose.onNodeWithTag("japanese-input").performTextInput("Allons-y !")
        compose.onNodeWithTag("lettering-font").performScrollTo().performClick()
        compose.onNodeWithTag("font-serif").performClick()
        awaitState { model.draft?.preview != null && !model.busy }
        compose.onNodeWithTag("font-bold").performScrollTo().performClick()
        awaitState { model.draft?.edit?.bold == true && model.draft?.preview != null && !model.busy }
        capture("french-font-preview")
        compose.onNodeWithTag("save-edit").performClick()
        awaitTag("book-reader")
        awaitState { !model.busy }
        val saved = LocalBookStore(context).open(book).edits.single()
        assertEquals("fr", saved.languageTag)
        assertEquals("serif", saved.fontFamily)
        assertTrue(saved.bold)
        assertEquals("fr", context.getSharedPreferences("settings", 0).getString("targetLanguage", null))
    }

    @Test fun recognizedTextTranslatesPreviewsAndSavesWithoutLosingTheEditor() {
        val source = SampleChapter.build().first().original
        val store = LocalBookStore(context); val book = store.addBitmap("AI test", source)
        val profiles = TestProfiles()
        var translationCalls = 0
        val model = RuyoModel(context, profiles, OcrService { _, _, _ -> "Wait!" }, TranslationService { _, original, target ->
            translationCalls++; assertEquals("Wait!", original); assertEquals("ja", target); "待って！"
        })
        compose.setContent { RuyoApp(model) }
        awaitTag("book-sample")
        compose.onNodeWithTag("book-" + book.id).performClick()
        awaitTag("imported-page")
        compose.onNodeWithContentDescription("Edit bubbles").performClick()
        compose.onNodeWithTag("imported-page").performTouchInput { click(Offset(width * .5f, height * .105f)) }
        awaitTag("bubble-editor")
        val editId = model.draft!!.edit.id
        awaitState { model.draft?.preview != null && !model.busy && model.aiStatus == null }
        assertEquals("Wait!", model.draft!!.sourceText)
        assertEquals(1, translationCalls)
        compose.onNodeWithTag("toggle-ai").performScrollTo().performClick()
        compose.onNodeWithText("Manage").performScrollTo().performClick()
        awaitTag("provider-profiles")
        capture("provider-profiles")
        compose.onNodeWithTag("add-provider").performScrollTo().performClick()
        awaitTag("provider-form")
        capture("provider-form")
        compose.onNodeWithTag("provider-name").performScrollTo().performTextInput("Invalid address")
        compose.onNodeWithTag("provider-model").performScrollTo().performTextInput("test-model")
        compose.onNodeWithTag("provider-endpoint").performScrollTo().performTextReplacement("http://example.com/v1")
        compose.onNodeWithTag("save-provider").performScrollTo().performClick()
        awaitState { model.profileError != null && !model.busy }
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.onNodeWithTag("add-provider").assertIsEnabled()
        compose.onNodeWithContentDescription("Back").performClick()
        awaitTag("bubble-editor")
        assertEquals(editId, model.draft!!.edit.id)
        assertEquals("Wait!", model.draft!!.sourceText)
        awaitState { model.draft?.preview != null && !model.busy && model.aiStatus == null }
        assertEquals(1, translationCalls)
        assertEquals("待って！", model.draft!!.edit.japanese)
        capture("ai-preview")
        compose.onNodeWithTag("save-edit").performClick()
        awaitTag("book-reader")
        awaitState { !model.busy }
        assertEquals("待って！", store.open(book).edits.single().japanese)
    }

    @Test fun cancellingAiAndChangingTextRejectsALateProviderResult() {
        val source = SampleChapter.build().first().original
        val store = LocalBookStore(context); val book = store.addBitmap("Delayed AI", source)
        val release = CompletableDeferred<Unit>(); val entered = AtomicBoolean(); val finished = AtomicBoolean()
        val model = RuyoModel(context, TestProfiles(), OcrService { _, _, _ -> "Source" }, TranslationService { _, _, _ ->
            withContext(NonCancellable) { entered.set(true); release.await(); finished.set(true); "遅い返事" }
        })
        compose.setContent { RuyoApp(model) }
        awaitTag("book-sample")
        compose.onNodeWithTag("book-" + book.id).performClick()
        awaitTag("imported-page")
        compose.onNodeWithContentDescription("Edit bubbles").performClick()
        compose.onNodeWithTag("imported-page").performTouchInput { click(Offset(width * .5f, height * .105f)) }
        awaitTag("bubble-editor")
        awaitState { entered.get() }
        compose.runOnIdle { model.changeText("Manual text") }
        release.complete(Unit)
        awaitState { finished.get() && model.aiStatus == null }
        assertEquals("Manual text", model.draft!!.edit.japanese)
        assertNull(model.draft!!.preview)
        compose.runOnIdle { model.cancelEditor() }
        assertEquals("book", model.route)
        assertNull(model.draft)
    }

    @Test fun websiteReadingCanEditImmediatelyAndTranslateAheadWithoutImporting() {
        val source = android.graphics.Bitmap.createBitmap(600, 1100, android.graphics.Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.rgb(35, 45, 60))
        android.graphics.Canvas(source).apply {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = android.graphics.Color.WHITE
            drawOval(130f, 40f, 470f, 320f, paint)
            paint.color = android.graphics.Color.BLACK; paint.textSize = 38f; paint.textAlign = android.graphics.Paint.Align.CENTER
            drawText("Wait!", 300f, 180f, paint)
        }
        val encoded = java.io.ByteArrayOutputStream().also { source.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        var calls = 0
        val ocr = object : OcrService {
            override suspend fun recognize(source: android.graphics.Bitmap, region: com.ruyo.reader.BubbleRegion, script: OcrScript) = "Wait!"
            override suspend fun lines(source: android.graphics.Bitmap, script: OcrScript) = listOf(OcrLine("Wait!", 250, 143, 350, 185))
        }
        val model = RuyoModel(context, TestProfiles(), ocr, TranslationService { _, _, _ -> calls++; "待って！" },
            webSessionFactory = { app, chapter, cookies, agent ->
                com.ruyo.web.WebReadingSession(app, chapter, cookies, agent) { _, check -> check(); java.io.ByteArrayInputStream(encoded) }
            })
        compose.setContent { RuyoApp(model) }
        awaitTag("book-sample")
        val chapter = WebChapter("https://example.com/chapter", "Website fixture",
            (0..2).map { WebImage("https://example.com/image-" + it + ".png", "Image " + it, 600, 1100) })
        compose.runOnIdle { model.readWebsite(chapter, emptyMap(), "Fixture") }
        awaitTag("web-page-0")
        assertEquals(0, calls)
        assertTrue(LocalBookStore(context).list().isEmpty())
        awaitState { !model.busy }
        capture("web-reader-before-edit")
        compose.onNodeWithTag("web-page-0").performTouchInput { click(Offset(width * .5f, width * .30f)) }
        try { awaitTag("bubble-editor") } catch (error: Exception) {
            capture("web-reader-tap-failure")
            throw AssertionError("Web edit: route=" + model.route + " busy=" + model.busy + " selection=" + model.selectionError + " message=" + model.message, error)
        }
        awaitState { model.draft?.preview != null && !model.busy && model.aiStatus == null }
        assertEquals(1, calls)
        compose.runOnIdle { model.changeFont("serif") }
        awaitState { model.draft?.preview != null && !model.busy }
        compose.runOnIdle { model.changeBold(true) }
        awaitState { model.draft?.preview != null && !model.busy }
        assertTrue(model.draft!!.edit.bold)
        compose.onNodeWithTag("save-edit").performClick()
        awaitTag("web-reader")
        awaitState { !model.busy }
        compose.onNodeWithTag("scroll-translate").performClick()
        awaitState { model.scrollTranslation?.notes?.keys?.containsAll(listOf(0, 1)) == true }
        assertEquals(2, calls)
        compose.onNodeWithTag("web-reading-scroll").performScrollToIndex(2)
        awaitState { model.scrollTranslation?.notes?.containsKey(2) == true }
        assertEquals(3, calls)
        compose.onNodeWithTag("web-reading-scroll").performScrollToIndex(0)
        awaitState { model.webPosition.first == 0 }
        capture("web-reader-translated")
        val normalHeight = compose.onNodeWithTag("web-reader").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithContentDescription("Hide reader toolbar").performClick()
        compose.waitForIdle()
        assertTrue(compose.onNodeWithTag("web-reader").fetchSemanticsNode().boundsInRoot.height > normalHeight)
        capture("web-reader-minimal")
        compose.runOnIdle { model.readerImmersive = false }
        assertEquals(3, calls)
        val sessionId = model.webReading!!.id
        val expanded = chapter.copy(images = chapter.images + WebImage("https://example.com/image-3.png", "Image 3", 600, 1100))
        compose.runOnIdle { model.viewModelScope.launch { model.acceptWebDiscovery(sessionId, expanded, emptyMap()) } }
        awaitState { model.webReading!!.images.size == 4 }
        compose.onNodeWithTag("web-reading-scroll").performScrollToIndex(3)
        awaitState { model.scrollTranslation?.notes?.containsKey(3) == true }
        assertEquals(4, calls)
        compose.onNodeWithTag("web-reading-scroll").performScrollToIndex(0)
        awaitState { model.webPosition.first == 0 }
        compose.onNodeWithTag("web-page-0").performTouchInput { click(Offset(width * .5f, width * .30f)) }
        awaitTag("bubble-editor")
        awaitState { model.draft?.preview != null && !model.busy }
        assertTrue(model.draft!!.existing)
        assertEquals("serif", model.draft!!.edit.fontFamily)
        assertTrue(model.draft!!.edit.bold)
        assertEquals(4, calls)
        compose.runOnIdle { model.cancelEditor() }
        awaitTag("web-reader")
        assertEquals(0, model.webPosition.first)
        assertTrue(LocalBookStore(context).list().isEmpty())
        compose.runOnIdle { model.home() }
        source.recycle()
    }

    private class TestProfiles : ProfileStore {
        private var entries = listOf(ProviderSecret(ProviderProfile(name = "Test profile", kind = ProviderKind.OPENAI, baseUrl = "https://api.openai.com/v1", model = "test-model", hasKey = true), "fixture-only"))
        override fun list() = entries.map { it.profile }
        override fun get(id: String) = entries.first { it.profile.id == id }
        override fun save(profile: ProviderProfile, replacementKey: String?, removeKey: Boolean) { profile.validate(); entries = entries.filterNot { it.profile.id == profile.id } + ProviderSecret(profile, replacementKey.orEmpty()) }
        override fun delete(id: String) { entries = entries.filterNot { it.profile.id == id } }
    }

    private fun awaitTag(tag: String) { compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }; compose.waitForIdle() }
    private fun awaitState(condition: () -> Boolean) { compose.waitUntil(15_000) { compose.waitForIdle(); condition() } }
    private fun capture(name: String, tag: String = "app-root") {
        compose.waitForIdle()
        val file = File(System.getProperty("ruyo.previewDir"), "ui-$name.png")
        file.parentFile?.mkdirs()
        compose.onNodeWithTag(tag).captureRoboImage(file.absolutePath)
        assertTrue("The screen capture must be written", file.isFile)
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path))
        assertTrue(bitmap.width > 100 && bitmap.height > 100)
        bitmap.recycle()
    }
}
