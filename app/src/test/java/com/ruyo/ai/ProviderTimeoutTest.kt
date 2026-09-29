package com.ruyo.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderTimeoutTest {
    private fun secret(timeout: RequestTimeout = RequestTimeout.DEFAULT) = ProviderSecret(
        ProviderProfile(name = "Slow local model", kind = ProviderKind.COMPATIBLE,
            baseUrl = "http://127.0.0.1:11434/v1", model = "fixture", requestTimeout = timeout), "")

    private fun response(content: JSONObject): String = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", content.toString())))).toString()

    @Test fun everyProtocolCarriesTheSavedTimeoutForTranslationBatchAndExplanation() {
        for (kind in ProviderKind.entries) for (timeout in RequestTimeout.entries) {
            val value = ProviderSecret(secret(timeout).profile.copy(kind = kind, baseUrl = kind.endpoint), "fixture-key")
            val requests = listOf(TranslationClient.request(value, "Wait", "ja"),
                TranslationClient.batchRequest(value, listOf(SourceDialogue("a", "Wait")), "ja"),
                ExplanationClient.request(value, "待って", "ja"))
            requests.forEach { assertEquals(timeout, it.timeout) }
        }
    }

    @Test fun slowSingleBatchAndLessonResponsesCompleteAfterTheOldDeadlines() = runTest {
        val lesson = AiLesson("Wait.", "まって", listOf(LessonPoint("待って", "A casual request.")),
            listOf(LessonWord("待って", "まって", "wait", "")),
            listOf(LessonExample("待って！", "まって！", "Matte!", "Ask a friend to wait.",
                listOf(LessonWord("待って", "まって", "wait", "")))),
            listOf(LessonExercise("Ask a friend to wait.", "待って", "It means wait.", "待って (matte): wait.", "まって")))
        for (mode in 0..2) {
            val body = when (mode) {
                0 -> JSONObject().put("translation", "待って")
                1 -> JSONObject().put("translations", JSONArray().put(JSONObject().put("id", "a").put("translation", "待って")))
                else -> ExplanationClient.encode(lesson)
            }
            val http = WaitingHttp(response(body))
            val client = TranslationClient { http }
            val result = async {
                when (mode) {
                    0 -> client.translate(secret(), "Wait", "ja")
                    1 -> client.translateBatch(secret(), listOf(SourceDialogue("a", "Wait")), "ja")["a"]
                    else -> ExplanationClient(client).explain(secret(), "待って", "ja").meaning
                }
            }
            try {
                runCurrent()
                assertTrue(http.sent.await(5, TimeUnit.SECONDS))
                assertTrue("The request is sent before waiting for the model", http.output.size() > 0)
                assertEquals(600_000, http.readTimeout)
                assertEquals(30_000, http.connectTimeout)
                advanceTimeBy(90_000); runCurrent()
                assertFalse("Mode $mode stopped at an old deadline", result.isCompleted)
                assertFalse(http.closed.get())
                finishResponse(http, result)
                assertEquals(if (mode == 2) "Wait." else "待って", result.await())
                assertTrue(http.closed.get())
            } finally { http.release.countDown(); result.cancel() }
        }
    }

    @Test fun chosenDeadlineStopsWaitingWithoutCancellingTheCallerOrRetrying() = runTest {
        val http = WaitingHttp(""); val attempts = AtomicInteger()
        val client = TranslationClient { attempts.incrementAndGet(); http }
        val result = async { runCatching { client.translate(secret(RequestTimeout.TWO_MINUTES), "Wait", "ja") } }
        try {
            runCurrent(); assertTrue(http.sent.await(5, TimeUnit.SECONDS))
            advanceTimeBy(119_999); runCurrent(); assertFalse(result.isCompleted)
            advanceTimeBy(1); runCurrent()
            assertTrue(result.isCompleted)
            val error = result.await().exceptionOrNull()
            assertTrue(error is TranslationFailure)
            assertTrue(error!!.message.orEmpty().contains("2 minutes"))
            assertTrue(error.message.orEmpty().contains("Request timeout"))
            assertTrue(currentCoroutineContext().isActive)
            assertTrue(http.closed.get()); assertEquals(1, attempts.get())
        } finally { http.release.countDown(); result.cancel() }
    }

    @Test fun noLimitHasNoReadOrOverallDeadlineAndCanStillBeCancelled() = runTest {
        val http = WaitingHttp("")
        val result = async { TranslationClient { http }.translate(secret(RequestTimeout.UNLIMITED), "Wait", "ja") }
        try {
            runCurrent(); assertTrue(http.sent.await(5, TimeUnit.SECONDS))
            assertEquals(0, http.readTimeout)
            advanceTimeBy(24 * 60 * 60 * 1000L); runCurrent()
            assertFalse(result.isCompleted); assertFalse(http.closed.get())
            result.cancel(); runCurrent()
            assertTrue(result.isCancelled); assertTrue(http.closed.get())
        } finally { http.release.countDown(); result.cancel() }
    }

    @Test fun parentCancellationIsNotConvertedIntoAProviderTimeout() = runTest {
        val http = WaitingHttp("")
        val result = async { runCatching { withTimeout(1_000) { TranslationClient { http }.translate(secret(), "Wait", "ja") } } }
        try {
            runCurrent(); assertTrue(http.sent.await(5, TimeUnit.SECONDS))
            advanceTimeBy(1_000); runCurrent()
            assertTrue(result.isCompleted)
            assertTrue(result.await().exceptionOrNull() is TimeoutCancellationException)
            assertTrue(http.closed.get())
        } finally { http.release.countDown(); result.cancel() }
    }

    @Test fun serverTimeoutIsReportedSeparatelyWithoutRetryOrRawErrorLeak() = runBlocking {
        val http = WaitingHttp("secret server diagnostic", status = 504).apply { release.countDown() }
        val attempts = AtomicInteger()
        val error = runCatching { TranslationClient { attempts.incrementAndGet(); http }
            .translate(secret(RequestTimeout.UNLIMITED), "Wait", "ja") }.exceptionOrNull()
        assertTrue(error is TranslationFailure)
        assertTrue(error!!.message.orEmpty().contains("server time limits"))
        assertFalse(error.message.orEmpty().contains("secret server diagnostic"))
        assertEquals(1, attempts.get()); assertTrue(http.closed.get())
    }

    /** Let real IO finish while keeping virtual time fixed at the simulated response time. */
    private fun TestScope.finishResponse(http: WaitingHttp, result: Deferred<*>) {
        http.release.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!result.isCompleted && System.nanoTime() < deadline) { runCurrent(); Thread.sleep(1) }
        assertTrue("HTTP response was not delivered", result.isCompleted)
    }

    private class WaitingHttp(private val body: String, private val status: Int = 200) : HttpURLConnection(URL("http://127.0.0.1:11434/v1")) {
        val output = ByteArrayOutputStream()
        val sent = CountDownLatch(1); val release = CountDownLatch(1); val closed = AtomicBoolean()
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() { closed.set(true); release.countDown() }
        override fun getOutputStream() = output
        override fun getResponseCode(): Int {
            sent.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Test did not release its fake server" }
            return status
        }
        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    }
}
