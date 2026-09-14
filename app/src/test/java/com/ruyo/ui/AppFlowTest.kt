package com.ruyo.ui

import android.graphics.BitmapFactory
import android.net.Uri
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
        compose.onNodeWithTag("japanese-input").performScrollTo().performTextReplacement("あ".repeat(512))
        compose.onNodeWithTag("preview-edit").performScrollTo().performClick()
        awaitState { model.draft?.previewError != null && !model.busy }
        compose.onNodeWithTag("preview-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("save-edit").assertIsNotEnabled()
        capture("bubble-fit-error")
        compose.onNodeWithTag("japanese-input").performScrollTo().performTextReplacement("待って！")
        compose.onNodeWithTag("preview-edit").performScrollTo().performClick()
        awaitState { model.draft?.previewVersion == 3 && !model.busy }
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
        capture("web-browser")
        compose.onNodeWithTag("web-address").performTextInput("file:///private/image.png")
        compose.onNodeWithTag("web-go").performClick()
        compose.onNodeWithText("Use an HTTPS website address without a username or custom port.").assertIsDisplayed()
    }

    @Test fun discoveredWebImagesCanBeSelectedBeforeDownload() {
        val source = WebChapter("https://example.com/chapter", "Chapter one", listOf(
            WebImage("https://example.com/1.png", "1.png", 900, 1500),
            WebImage("https://example.com/2.png", "2.png", 900, 1500),
            WebImage("https://example.com/banner.png", "banner.png", 900, 140),
        ))
        var selected: Set<String>? = null
        compose.setContent { RuyoTheme("light") { WebImagesSheet(source, {}) { selected = it } } }
        awaitTag("web-images-review")
        capture("web-images-review", "web-images-review")
        compose.onNodeWithText("Download 2 images").performClick()
        assertEquals(setOf(source.images[0].url, source.images[1].url), selected)
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
