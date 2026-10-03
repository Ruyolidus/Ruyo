package com.ruyo

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ruyo.web.LiveChapter
import com.ruyo.web.WebImageDiscovery
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class LiveWebViewTest {
    @SuppressLint("SetJavaScriptEnabled")
    @Test fun coveredWebsiteStillLoadsLazyChapterStripsWhenAdvanced() {
        val bitmap = Bitmap.createBitmap(800, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val loaded = CountDownLatch(1)
        lateinit var web: WebView
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                web = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                            WebResourceResponse("image/png", null, ByteArrayInputStream(bytes))
                        override fun onPageFinished(view: WebView, url: String?) { loaded.countDown() }
                    }
                }
                val parent = FrameLayout(activity)
                parent.addView(web, FrameLayout.LayoutParams(-1, -1))
                parent.addView(View(activity).apply { setBackgroundColor(Color.DKGRAY) }, FrameLayout.LayoutParams(-1, -1))
                activity.setContentView(parent)
                web.loadDataWithBaseURL("https://reader.example/chapter", """
                    <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head><body>
                    <div class="reading-content">
                      <img src="https://reader.example/first.png" width="800" height="80">
                      <div style="height:1800px"></div><div id="later" style="height:20px"></div>
                    </div><div style="height:2000px"></div>
                    <script>
                      const observer = new IntersectionObserver(entries => {
                        if (entries.some(e => e.isIntersecting)) {
                          const img = document.createElement('img');
                          img.width=800; img.height=80; img.src='https://reader.example/second.png';
                          document.querySelector('.reading-content').appendChild(img);
                          observer.disconnect();
                        }
                      });
                      observer.observe(document.querySelector('#later'));
                    </script></body></html>
                """.trimIndent(), "text/html", "UTF-8", null)
            }
            assertTrue("The fixture page must load", loaded.await(20, TimeUnit.SECONDS))
            val first = WebImageDiscovery.parse(evaluate(web, WebImageDiscovery.script), "https://reader.example/chapter")
            assertEquals(1, first.images.size)
            var discovered = first
            for (step in 0 until 12) {
                evaluate(web, LiveChapter.advanceScript(first.images.first().url, 1f, true))
                android.os.SystemClock.sleep(100)
                discovered = WebImageDiscovery.parse(evaluate(web, WebImageDiscovery.script), first.url)
                if (discovered.images.size == 2) break
            }
            assertEquals("IntersectionObserver must still deliver a new image behind the native reader", 2, discovered.images.size)
            assertTrue(discovered.images.all { it.likelyPage && it.chapterImage })
            assertEquals("https://reader.example/second.png", discovered.images.last().url)
            scenario.onActivity { web.destroy() }
        }
    }

    private fun evaluate(web: WebView, script: String): String {
        val done = CountDownLatch(1)
        val value = AtomicReference("null")
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            web.evaluateJavascript(script) { value.set(it); done.countDown() }
        }
        assertTrue("JavaScript evaluation timed out", done.await(10, TimeUnit.SECONDS))
        return value.get()
    }
}
