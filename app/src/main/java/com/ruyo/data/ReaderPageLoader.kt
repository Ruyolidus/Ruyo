package com.ruyo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serialize decode/composition; retain at most 3 pages and 48 MiB between viewport requests. */
class ReaderPageLoader(private val store: LocalBookStore) {
    private val lock = Mutex()
    private val cache = LinkedHashMap<String, OpenBook>(4, 0.75f, true)
    private var epoch = 0
    suspend fun load(book: LocalBook, page: LocalPage): OpenBook = withContext(Dispatchers.IO) {
        lock.withLock {
            currentCoroutineContext().ensureActive()
            val key = "${book.id}:${page.id}"
            cache[key]?.let { return@withLock it }
            // Drop cached pages before allocating the new full bitmap pair.
            val estimate = page.width.toLong() * page.height * 8
            while (cache.isNotEmpty() && (cache.size >= 3 || cache.values.sumOf(::bytes) + estimate > 48L * 1024 * 1024)) cache.remove(cache.keys.first())
            val version = epoch
            val loaded = store.openPage(book, page)
            currentCoroutineContext().ensureActive()
            if (version == epoch) cache[key] = loaded
            loaded
        }
    }
    suspend fun clear() = withContext(Dispatchers.IO) { lock.withLock { epoch++; cache.clear() } }
    private fun bytes(page: OpenBook): Long = page.original.allocationByteCount.toLong() + if (page.displayed === page.original) 0 else page.displayed.allocationByteCount
}
