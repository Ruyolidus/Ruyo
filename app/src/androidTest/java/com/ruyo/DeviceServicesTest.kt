package com.ruyo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ruyo.ai.*
import com.ruyo.reader.BubbleRegion
import com.ruyo.reader.PixelMask
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeviceServicesTest {
    @Test fun nativeArtworkRepairRunsOnAndroidAndOnlyChangesItsMask() {
        val w = 240; val h = 180
        val original = IntArray(w * h) { i -> if (i / w < 80 + 24 * kotlin.math.sin(i % w * .035)) Color.rgb(225, 130, 35) else Color.rgb(35, 45, 65) }
        val mask = BooleanArray(w * h) { i -> i % w in 75..81 && i / w in 30..135 }
        val source = original.copyOf().also { pixels -> mask.indices.forEach { if (mask[it]) pixels[it] = Color.WHITE } }
        val repaired = com.ruyo.reader.LocalInpainter.repair(source, w, h, mask)
        var wrong = 0
        for (i in original.indices) if (!mask[i]) assertEquals(original[i], repaired[i]) else if (original[i] != repaired[i]) wrong++
        assertTrue("Native patch repair did not reconstruct the curved boundary: $wrong", wrong < 60)
        val output = Bitmap.createBitmap(repaired, w, h, Bitmap.Config.ARGB_8888)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val context = ApplicationProvider.getApplicationContext<android.content.Context>()
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "native-artwork-repair.png")
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/ruyo-diagnostics")
                }
                val uri = requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
                context.contentResolver.openOutputStream(uri)!!.use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally { output.recycle() }
    }

    @Test fun androidKeystoreEncryptsAndReopensTheVault() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val alias = "ruyo.test." + UUID.randomUUID()
        val file = File(context.noBackupFilesDir, "test-" + UUID.randomUUID() + ".vault")
        try {
            val key = EncryptedProfileStore.androidKey(alias)
            assertNull("Keystore keys must be non-exportable", key.encoded)
            val profile = ProviderProfile(name = "Device test", kind = ProviderKind.OPENAI, baseUrl = "https://api.openai.com/v1", model = "test-model")
            val fixtureKey = "not-a-real-api-key"
            EncryptedProfileStore(file) { key }.save(profile, fixtureKey)
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(fixtureKey))
            val reopened = EncryptedProfileStore(file) { EncryptedProfileStore.androidKey(alias) }
            assertEquals(fixtureKey, reopened.get(profile.id).apiKey)
            reopened.delete(profile.id); assertTrue(reopened.list().isEmpty())
        } finally { file.delete(); KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
    }

    @Test fun bundledOcrRecognizesOnlyTheSelectedRegion() = runBlocking {
        val source = Bitmap.createBitmap(1100, 340, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        Canvas(source).apply {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 52f }
            drawText("Ruyo reads comic dialogue.", 30f, 110f, paint)
            drawText("Neighbor must be excluded.", 30f, 280f, paint)
        }
        val mask = PixelMask(1050, 155, BooleanArray(1050 * 155) { true })
        val region = BubbleRegion(0, 0, mask, BooleanArray(1050 * 155), Color.WHITE)
        try {
            val recognized = withTimeout(60_000) { BubbleOcr().recognize(source, region, OcrScript.LATIN) }
            assertTrue(recognized, recognized.lowercase().contains("comic"))
            assertTrue(recognized, recognized.lowercase().contains("dialogue"))
            assertFalse(recognized.lowercase().contains("neighbor"))
            val lines = withTimeout(60_000) { BubbleOcr().lines(source, OcrScript.LATIN) }
            assertTrue(lines.any { it.text.lowercase().contains("dialogue") && it.y < 155 })
            assertTrue(lines.any { it.text.lowercase().contains("neighbor") && it.top > 155 })
            assertTrue(lines.all { it.right > it.left && it.bottom > it.top })
        } finally { source.recycle() }
    }

    @Test fun outlinedDialogueAtAnOcrTileBoundaryIsRecognizedOnce() = runBlocking {
        val source = Bitmap.createBitmap(900, 2600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(218, 223, 230)) }
        val canvas = Canvas(source)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 42f; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = 6f
        canvas.drawText("We walk across the river.", 60f, 1538f, paint)
        paint.color = Color.BLACK; paint.style = Paint.Style.FILL
        canvas.drawText("We walk across the river.", 60f, 1538f, paint)
        canvas.drawText("Another page of dialogue.", 60f, 2260f, paint)
        try {
            val lines = withTimeout(60_000) { BubbleOcr().lines(source, OcrScript.LATIN) }
            val boundary = lines.filter { it.text.lowercase().contains("across") }
            assertEquals(lines.toString(), 1, boundary.size)
            assertTrue(boundary.single().text.lowercase().contains("river"))
            assertTrue(boundary.single().y in 1470..1560)
            assertTrue(lines.any { it.text.lowercase().contains("another") && it.top > 2000 })
        } finally { source.recycle() }
    }

    @Test fun appStartsWithTheProductionViewModelFactory() {
        ActivityScenario.launch(MainActivity::class.java).use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            it.onActivity { activity -> assertFalse(activity.isFinishing) }
        }
    }
}
