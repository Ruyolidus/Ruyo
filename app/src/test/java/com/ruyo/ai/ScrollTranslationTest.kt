package com.ruyo.ai

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ScrollTranslationTest {
    @Test fun waitsForStartPrioritizesNewViewportAndNeverRepeatsCompletedPages() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val first = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val visited = mutableListOf<Int>()
        val scroll = ScrollTranslation(scope, 10) { index, _, _ ->
            visited += index
            if (index == 0) { entered.complete(Unit); first.await() }
            "Ready"
        }
        try {
            scroll.viewport(listOf(0)); yield()
            assertTrue(visited.isEmpty())
            scroll.start()
            withTimeout(5000) { entered.await() }
            scroll.viewport(listOf(5)); first.complete(Unit)
            yield()
            assertEquals(listOf(0, 5, 6), visited)
            repeat(10) { scroll.viewport(listOf(5, 5)) }
            yield()
            assertEquals(listOf(0, 5, 6), visited)
            scroll.viewport(listOf(0)); yield()
            assertEquals(listOf(0, 5, 6, 1), visited)
            assertNull(scroll.error)
        } finally { scope.cancel() }
    }

    @Test fun shortImagesSharingOneViewportAreAllProcessedBeforeLookahead() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val visited = mutableListOf<Int>()
        val scroll = ScrollTranslation(scope, 8) { index, _, _ -> visited += index; "Ready" }
        try {
            scroll.viewport(listOf(0, 1, 2, 3, 4)); scroll.start(); yield()
            assertEquals(listOf(0, 1, 2, 3, 4, 5), visited)
        } finally { scope.cancel() }
    }

    @Test fun resettingRecognitionAllowsUnsupportedPagesToBeScannedAgain() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var calls = 0
        val scroll = ScrollTranslation(scope, 1) { _, _, _ -> calls++; "No supported dialogue" }
        try {
            scroll.viewport(listOf(0)); scroll.start(); yield()
            assertEquals(1, calls)
            scroll.pause(); scroll.start(); yield()
            assertEquals(1, calls)
            scroll.reset(); scroll.start(); yield()
            assertEquals(2, calls)
        } finally { scope.cancel() }
    }

    @Test fun failurePausesInsteadOfRetryingOnEveryScroll() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var calls = 0
        val scroll = ScrollTranslation(scope, 3) { _, _, _ -> calls++; error("Provider quota reached") }
        try {
            scroll.viewport(listOf(0)); scroll.start(); yield()
            assertEquals(1, calls)
            assertFalse(scroll.running)
            assertEquals("Provider quota reached", scroll.error)
            repeat(5) { scroll.viewport(listOf(1)) }; yield()
            assertEquals(1, calls)
            scroll.start(); yield()
            assertEquals(2, calls)
        } finally { scope.cancel() }
    }

    @Test fun pauseRejectsLateCompletionAndDoesNotProcessAnotherImage() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val visited = mutableListOf<Int>()
        val scroll = ScrollTranslation(scope, 4) { index, _, _ ->
            visited += index
            withContext(NonCancellable) { entered.complete(Unit); release.await() }
            "Stale result"
        }
        try {
            scroll.viewport(listOf(0)); scroll.start()
            withTimeout(5000) { entered.await() }
            val stopped = scroll.pause()
            release.complete(Unit)
            withTimeout(5000) { stopped?.join() }
            assertEquals(listOf(0), visited)
            assertTrue(scroll.notes.isEmpty())
            assertFalse(scroll.running)
            assertNull(scroll.status)
        } finally { scope.cancel() }
    }
}
