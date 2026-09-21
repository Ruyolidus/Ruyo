package com.ruyo.web

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap
import com.ruyo.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.util.UUID

/** A temporary chapter cache, isolated from the user's imported library. */
class WebReadingSession(
    context: Context,
    val chapter: WebChapter,
    cookies: Map<String, String>,
    private val userAgent: String,
    private val download: ((WebImage, () -> Unit) -> InputStream)? = null,
) {
    val id: String = UUID.randomUUID().toString()
    private val directory = File(context.cacheDir, "web-reading/" + id)
    val store = LocalBookStore(context, directory, File(directory, "staging"))
    val loader = ReaderPageLoader(store)
    private val lock = Mutex()
    private val books = mutableMapOf<String, LocalBook>()
    var images by mutableStateOf(chapter.images); private set
    fun updateImages(value: List<WebImage>) { images = value }
    private val liveCookies = ConcurrentHashMap(cookies)
    fun updateCookies(value: Map<String, String>) { liveCookies.putAll(value) }
    private var bytes = 0L

    suspend fun load(index: Int): OpenBook = withContext(Dispatchers.IO) {
        val book = lock.withLock {
            currentCoroutineContext().ensureActive()
            val image = images.getOrNull(index) ?: error("This chapter image is unavailable.")
            books[image.url] ?: run {
                val coroutine = currentCoroutineContext()
                val staged = store.stageStream(image.name, { coroutine.ensureActive() }) { download?.invoke(image) { coroutine.ensureActive() } ?: WebImageDownload().open(image.url, chapter.url, userAgent, liveCookies[image.url]) { coroutine.ensureActive() } }
                try {
                    val size = staged.folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    require(bytes + size <= 512L * 1024 * 1024) { "This reading session reached its 512 MB cache limit. Return to the website and open a shorter chapter." }
                    coroutine.ensureActive()
                    store.commitChapter(chapter.title, listOf(staged), sourceUrl = chapter.url).also {
                        bytes += size; books[image.url] = it
                    }
                } finally { store.discard(listOf(staged)) }
            }
        }
        loader.load(book, book.pages.first())
    }
    suspend fun changed() = loader.clear()
    suspend fun close() = withContext(Dispatchers.IO) {
        lock.withLock { loader.clear(); books.clear(); directory.deleteRecursively() }
    }
    companion object {
        fun clearAbandoned(context: Context) { File(context.cacheDir, "web-reading").deleteRecursively() }
    }
}
