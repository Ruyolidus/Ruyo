package com.ruyo.web

import com.ruyo.data.LocalBookStore
import org.json.JSONObject
import org.json.JSONTokener
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

object WebAddress {
    fun normalize(input: String): String {
        val text = input.trim()
        require(text.isNotBlank() && text.length <= 8192 && text.none { it.isWhitespace() || it.code < 32 }) { "Enter a valid HTTPS address." }
        val value = if (text.contains("://") || text.startsWith("data:") || text.startsWith("javascript:") || text.startsWith("file:")) text else "https://$text"
        val uri = URI(value)
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.port in listOf(-1, 443)) {
            "Use an HTTPS website address without a username or custom port."
        }
        return uri.toASCIIString().substringBefore('#')
    }
    fun origin(url: String): String { val uri = URI(normalize(url)); return "https://${uri.rawAuthority}/" }
    fun imageName(url: String): String = runCatching { URI(url).path.substringAfterLast('/').take(160).ifBlank { "Web image" } }.getOrDefault("Web image")
}

data class WebImage(val url: String, val name: String, val width: Int, val height: Int) {
    val likelyPage: Boolean get() = width >= 300 && height >= 250 || width == 0 || height == 0
}
data class WebChapter(val url: String, val title: String, val images: List<WebImage>)

object WebImageDiscovery {
    // Called only by the native Find images button. No addJavascriptInterface / native bridge.
    val script = """
        (function() {
          const seen = new Set(), images = [];
          for (const img of document.images) {
            if (images.length >= 200) break;
            const style = getComputedStyle(img);
            if (style.display === 'none' || style.visibility === 'hidden') continue;
            const lazy = img.getAttribute('data-src') || img.getAttribute('data-lazy-src') || img.getAttribute('data-original');
            const raw = (img.naturalWidth > 100 ? img.currentSrc : lazy) || img.currentSrc || img.src;
            if (!raw) continue;
            try {
              const url = new URL(raw.trim(), document.baseURI);
              if (url.protocol !== 'https:' || url.username || url.password) continue;
              url.hash = '';
              if (seen.has(url.href)) continue;
              seen.add(url.href);
              const w = img.naturalWidth || Number(img.getAttribute('width')) || 0;
              const h = img.naturalHeight || Number(img.getAttribute('height')) || 0;
              if (w > 0 && h > 0 && (w < 160 || h < 120)) continue;
              images.push({url: url.href, width: w, height: h});
            } catch (_) {}
          }
          return JSON.stringify({url: location.href, title: document.title.slice(0,120), images: images});
        })();
    """.trimIndent()

    fun parse(result: String, expectedPage: String): WebChapter {
        require(result.length <= 2_000_000) { "This page returned too much image information." }
        val decoded = JSONTokener(result).nextValue()
        val data = if (decoded is String) JSONObject(decoded) else decoded as? JSONObject ?: error("Could not inspect this page. Wait for it to load and retry.")
        val url = WebAddress.normalize(data.getString("url"))
        require(url == WebAddress.normalize(expectedPage)) { "The page changed while finding images. Please try again." }
        val array = data.getJSONArray("images")
        val images = (0 until minOf(array.length(), LocalBookStore.MAX_PAGES)).mapNotNull { i ->
            runCatching {
                val item = array.getJSONObject(i)
                val link = WebAddress.normalize(item.getString("url"))
                WebImage(link, WebAddress.imageName(link), item.optInt("width").coerceAtLeast(0), item.optInt("height").coerceAtLeast(0))
            }.getOrNull()
        }.distinctBy { it.url }
        return WebChapter(url, data.optString("title").take(120).ifBlank { URI(url).host }, images)
    }
}

/** No app server. Browser cookies are scoped to the exact initial image URL and never forwarded on redirect. */
class WebImageDownload(private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    fun open(imageUrl: String, pageUrl: String, userAgent: String, cookie: String?, checkCancelled: () -> Unit = {}): InputStream {
        var current = WebAddress.normalize(imageUrl)
        val referer = WebAddress.origin(pageUrl)
        repeat(6) { hop ->
            checkCancelled()
            val connection = connect(URL(current))
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "image/avif,image/webp,image/*;q=0.9")
                connection.setRequestProperty("User-Agent", userAgent.filter { it.code >= 32 && it.code != 127 }.take(1024))
                if (hop == 0) {
                    connection.setRequestProperty("Referer", referer)
                    if (!cookie.isNullOrBlank() && cookie.none { it == '\r' || it == '\n' }) connection.setRequestProperty("Cookie", cookie)
                }
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = requireNotNull(connection.getHeaderField("Location")) { "The image redirect is incomplete." }
                    current = WebAddress.normalize(URI(current).resolve(location).toString())
                    connection.disconnect()
                } else {
                    require(code in 200..299) { "The site did not provide this image (HTTP $code). Try reloading the chapter." }
                    require(connection.contentLengthLong <= LocalBookStore.MAX_IMAGE_BYTES) { "This image exceeds the 40 MB import limit." }
                    val mime = connection.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                    require(mime.startsWith("image/") || mime == "application/octet-stream" || mime.isEmpty()) { "The site returned a web page instead of an image." }
                    val deadline = System.nanoTime() + 60_000_000_000L
                    return object : FilterInputStream(connection.inputStream) {
                        private var bytes = 0L
                        private fun checkRead(count: Int): Int {
                            checkCancelled()
                            require(System.nanoTime() <= deadline) { "Image download timed out. Try again." }
                            if (count > 0) bytes += count
                            require(bytes <= LocalBookStore.MAX_IMAGE_BYTES) { "This image exceeds the 40 MB import limit." }
                            return count
                        }
                        override fun read(): Int { val value = super.read(); checkRead(if (value < 0) -1 else 1); return value }
                        override fun read(buffer: ByteArray, off: Int, len: Int): Int = checkRead(`in`.read(buffer, off, len))
                        override fun close() { try { super.close() } finally { connection.disconnect() } }
                    }
                }
            } catch (error: Exception) { connection.disconnect(); throw error }
        }
        error("The image redirected too many times.")
    }
}
