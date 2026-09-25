package com.ruyo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ruyo.ai.BubbleOcr
import com.ruyo.ai.OcrLine
import com.ruyo.ai.OcrScript
import com.ruyo.reader.AutoBubbleDetector
import com.ruyo.reader.BubbleEdit
import com.ruyo.reader.BubbleEditRenderer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** User-supplied regression pages; included only in the test APK, never the app APK.
 * The Japanese fixture strings test cleanup/fitting, not a live model's translation quality.
 */
@RunWith(AndroidJUnit4::class)
class ComicRegressionTest {
    @Test fun paleDialogueAndLetteredEffect() = checkPage("pale-dialogue", listOf("didyouhave", "funtoday", "giggle"))
    @Test fun outlinedNarration() = checkPage("outlined-caption", listOf("normally", "justwalkedpast", "secondglance"))
    @Test fun survivingEnglishCommentRows() = checkPage("page-comments", listOf("whatawasteoftime", "thesmartonesquitfirst"))
    @Test fun blueCaption() = checkPage("blue-caption", listOf("battleagainstevil", "darkmages", "gamblersinstinct", "chanceofwinning"))
    @Test fun joinedDialogueAndUnenclosedText() = checkPage("joined-dialogue", listOf("icameto", "father", "ordered", "thisplace", "war", "dontletanyone", "approach"))

    @Test fun rankDialogueAndContraction() = checkPage("rank-dialogue", listOf("atleast", "srank", "puthim", "onparwith", "ssrankhunter", "orhigher"))

    private fun normalized(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
    private fun checkPage(name: String, expected: List<String>) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null), "translation-diagnostics").apply { mkdirs() }
        val screenshot = InstrumentationRegistry.getInstrumentation().context.assets.open("regressions/$name.jpg").use { requireNotNull(BitmapFactory.decodeStream(it)) }
        // Remove Ruyo's own toolbar/footer. Keep the actual comic pixels and resolution.
        val source = Bitmap.createBitmap(screenshot, 0, 284, screenshot.width, 1180)
        screenshot.recycle()
        val report = JSONObject().put("fixture", name).put("expected", JSONArray(expected))
        val detectorBoxes = JSONArray();val crops = JSONArray()
        val ocr = BubbleOcr(context,
            onDetected = { boxes -> boxes.forEach { detectorBoxes.put(JSONArray(listOf(it.left,it.top,it.right,it.bottom))) } },
            onCrop = { box,mode,lines -> crops.put(JSONObject().put("mode",mode).put("bounds",JSONArray(listOf(box.left,box.top,box.right,box.bottom)))
                .put("lines",JSONArray().apply { lines.forEach { put(JSONObject().put("text",it.text).put("confidence",it.confidence).put("bounds",JSONArray(listOf(it.left,it.top,it.right,it.bottom)))) } })) })
        try {
            val started = System.nanoTime()
            val lines = withTimeout(120_000) { ocr.lines(source, OcrScript.LATIN) }
            fun json(line: OcrLine) = JSONObject().put("text", line.text).put("confidence", line.confidence).put("angle", line.angle).put("bounds", JSONArray(listOf(line.left, line.top, line.right, line.bottom)))
            report.put("detectorBoxes",detectorBoxes).put("cropReadings",crops)
            report.put("ocr", JSONArray().apply { lines.forEach { put(json(it)) } })
            report.put("ocrMilliseconds", (System.nanoTime() - started) / 1_000_000)
            if (name == "pale-dialogue") {
                val korean = withTimeout(120_000) { ocr.lines(source, OcrScript.KOREAN) }
                report.put("koreanOcr", JSONArray().apply { korean.forEach { put(json(it)) } })
            }
            val candidates = AutoBubbleDetector.detect(source, lines).flatMap { it.bubbles }
            report.put("candidates", JSONArray().apply { candidates.forEach { put(JSONObject().put("text", it.source).put("inpaint", it.region.inpaint).put("review", it.needsReview)
                .put("bounds", JSONArray(listOf(it.region.left, it.region.top, it.region.width, it.region.height)))) } })
            val edits = mutableListOf<BubbleEdit>(); val failed = JSONArray()
            for (candidate in candidates) {
                val edit = BubbleEdit(region = candidate.region, japanese = "読めた。", margin = maxOf(2, minOf(candidate.region.width, candidate.region.height) / 14))
                BubbleEditRenderer.preview(source, edit).onSuccess { edits += edit; it.crop.recycle(); it.fit.ink.recycle() }
                    .onFailure { failed.put(JSONObject().put("text", candidate.source).put("error", it.message)) }
            }
            report.put("fitFailures", failed).put("rendered", edits.size)
            val rendered = BubbleEditRenderer.composite(source, edits)
            File(directory, "$name-original.png").outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(directory, "$name-cleanup.png").outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }
            rendered.recycle()
            val recognized = normalized(lines.joinToString(" ") { it.text })
            val accepted = normalized(candidates.joinToString(" ") { it.source })
            val missingOcr = expected.filterNot { it in recognized }
            val missingCleanup = expected.filter { it in recognized && it !in accepted }
            report.put("missingOcr", JSONArray(missingOcr)).put("missingCleanup", JSONArray(missingCleanup))
            File(directory, "$name.json").writeText(report.toString(2))
            assertTrue("$name missed OCR: $missingOcr; cleanup: $missingCleanup; fit failures: $failed; details: $report", missingOcr.isEmpty() && missingCleanup.isEmpty() && failed.length() == 0)
        } finally {
            File(directory, "$name.json").writeText(report.toString(2))
            android.util.Log.i("RuyoRegression", report.toString())
            if (android.os.Build.VERSION.SDK_INT >= 29) directory.listFiles().orEmpty().filter { it.name.startsWith(name) }.forEach { file ->
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, if (file.extension == "png") "image/png" else "application/json")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/ruyo-diagnostics")
                }
                val uri = requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
                context.contentResolver.openOutputStream(uri)!!.use { output -> file.inputStream().use { it.copyTo(output) } }
            }
            ocr.close(); source.recycle()
        }
    }
}
