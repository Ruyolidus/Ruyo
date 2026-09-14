package com.ruyo.ui

import android.graphics.BitmapFactory
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
        compose.onNodeWithTag("sample-panel-0").performClick()
        awaitTag("study-sheet")
        capture("study", "study-sheet")
        compose.onNodeWithTag("save-sentence").performClick()
        compose.waitUntil(15_000) { model.saved.isNotEmpty() }
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
        awaitTag("book-reader")
        compose.onNodeWithContentDescription("Edit bubbles").performClick()
        compose.onNodeWithTag("imported-page").performTouchInput { click(Offset(width * 0.5f, height * 0.105f)) }
        awaitTag("japanese-input")
        capture("bubble-cleanup")
        compose.onNodeWithTag("japanese-input").performTextInput("待って！")
        compose.onNodeWithTag("preview-edit").performScrollTo().performClick()
        compose.waitUntil(15_000) { model.draft?.preview != null }
        compose.onNodeWithTag("editor-canvas").performScrollTo()
        capture("bubble-preview")
        compose.onNodeWithTag("save-edit").performClick()
        awaitTag("book-reader")
        compose.waitUntil(15_000) { !model.busy }
        val reopened = LocalBookStore(context).open(book)
        assertEquals("待って！", reopened.edits.single().japanese)
        assertTrue(source.sameAs(reopened.original))
        assertFalse(source.sameAs(reopened.displayed))
        capture("imported-reader")
    }

    private fun awaitTag(tag: String) { compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }; compose.waitForIdle() }
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
