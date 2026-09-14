package com.ruyo.web

import com.ruyo.data.LocalBookStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebImportTest {
    @Test fun rejectsNonWebSchemesCredentialsPortsAndUnsafeRedirectTargets() {
        assertEquals("https://example.com/chapter", WebAddress.normalize("example.com/chapter#panel"))
        listOf("http://example.com", "file:///data/data/com.ruyo/files/books", "javascript:alert(1)", "intent://app", "https://user:pass@example.com", "https://example.com:1234", "https://example.com/\nheader").forEach {
            assertTrue(it, runCatching { WebAddress.normalize(it) }.isFailure)
        }
    }
    @Test fun discoveryKeepsDomOrderDeduplicatesAndRejectsPageNavigationRaces() {
        val data = JSONObject().put("url", "https://example.com/chapter").put("title", "A chapter").put("images", JSONArray().apply {
            put(JSONObject().put("url", "https://cdn.example.com/10.jpg").put("width", 900).put("height", 1400))
            put(JSONObject().put("url", "https://cdn.example.com/2.jpg").put("width", 900).put("height", 1400))
            put(JSONObject().put("url", "https://cdn.example.com/10.jpg#duplicate"))
            put(JSONObject().put("url", "file:///private/image.png"))
        })
        val parsed = WebImageDiscovery.parse(JSONObject.quote(data.toString()), "https://example.com/chapter")
        assertEquals(listOf("10.jpg", "2.jpg"), parsed.images.map { it.name })
        assertTrue(runCatching { WebImageDiscovery.parse(JSONObject.quote(data.toString()), "https://example.com/other") }.isFailure)
    }
    @Test fun cookiesAreNotForwardedToRedirectTargetsAndClosingDisconnects() {
        val first = FakeConnection(URL("https://cdn.example.com/one"), 302, redirect = "https://other.example.com/two")
        val last = FakeConnection(URL("https://other.example.com/two"), 200)
        val connections = mutableListOf(first, last)
        val downloader = WebImageDownload { connections.removeAt(0) }
        downloader.open(first.url.toString(), "https://example.com/chapter?private=query", "Ruyo test", "session=secret").use { assertArrayEquals(byteArrayOf(1,2,3), it.readBytes()) }
        assertEquals("session=secret", first.getRequestProperty("Cookie"))
        assertEquals("https://example.com/", first.getRequestProperty("Referer"))
        assertNull(last.getRequestProperty("Cookie"))
        assertNull(last.getRequestProperty("Referer"))
        assertTrue(first.disconnected && last.disconnected)
    }
    @Test fun rejectsHtmlOversizeAndDowngradeResponsesWithoutLeakingConnections() {
        listOf(
            FakeConnection(URL("https://example.com/image"), 200, mime = "text/html"),
            FakeConnection(URL("https://example.com/image"), 200, length = LocalBookStore.MAX_IMAGE_BYTES + 1),
            FakeConnection(URL("https://example.com/image"), 302, redirect = "http://example.com/insecure"),
            FakeConnection(URL("https://example.com/image"), 403),
        ).forEach { connection ->
            assertTrue(runCatching { WebImageDownload { connection }.open(connection.url.toString(), "https://example.com", "Test", null) }.isFailure)
            assertTrue(connection.disconnected)
        }
    }
    @Test fun cancellationStopsAnActiveDownloadAndConnectionCloses() {
        val connection = FakeConnection(URL("https://example.com/image"), 200)
        var cancelled = false
        val input = WebImageDownload { connection }.open(connection.url.toString(), "https://example.com", "Test", null) { if (cancelled) throw CancellationException() }
        cancelled = true
        assertTrue(runCatching { input.use { it.readBytes() } }.isFailure)
        assertTrue(connection.disconnected)
    }
    private class FakeConnection(url: URL, private val code: Int, private val mime: String = "image/png", private val length: Long = 3, private val redirect: String? = null) : HttpURLConnection(url) {
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getContentType() = mime
        override fun getContentLengthLong() = length
        override fun getHeaderField(name: String?): String? = if (name == "Location") redirect else null
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf(1,2,3))
    }
}
