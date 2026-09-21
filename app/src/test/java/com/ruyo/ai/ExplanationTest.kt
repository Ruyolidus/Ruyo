package com.ruyo.ai

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExplanationTest {
    private fun lesson() = AiLesson("Wait, please.", "matte", listOf(LessonPoint("待って", "A casual request.")),
        listOf(LessonWord("待つ", "まつ", "to wait", "N5")), listOf(LessonPoint("少し待って。", "Wait a little.")),
        listOf(LessonExercise("Give the dictionary form.", "待つ", "This verb uses the te-form.")))
    private fun profile(kind: ProviderKind = ProviderKind.OPENAI) = ProviderProfile(name = "Fixture", kind = kind, baseUrl = kind.endpoint, model = "test-model")
    @Test fun requestsUseEachNativeProtocolAndOnlyDialogueText() {
        ProviderKind.entries.forEach { kind ->
            val request = ExplanationClient.request(ProviderSecret(profile(kind), "fixture-key"), "待って！", "ja")
            val body = JSONObject(request.body)
            assertTrue(request.body.contains("待って！"))
            assertFalse(request.body.contains("fixture-key"))
            assertFalse(request.body.contains("image_url"))
            assertFalse(request.body.contains("inlineData"))
            assertFalse(request.body.contains("base64"))
            assertTrue(body.has(if (kind == ProviderKind.GEMINI) "contents" else "messages"))
        }
    }
    @Test fun cacheSurvivesReopeningAndSeparatesTextLanguageAndModel() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cache = LessonCache(context); val profile = profile()
        val key = cache.key("待って！", "ja", profile)
        cache.write(key, lesson())
        assertEquals(lesson(), LessonCache(context).read(key))
        assertNotEquals(key, cache.key("待って。", "ja", profile))
        assertNotEquals(key, cache.key("待って！", "zh", profile))
        assertNotEquals(key, cache.key("待って！", "ja", profile.copy(model = "different")))
        assertEquals(key, cache.key("待って！", "ja", profile.copy(name = "Renamed")))
    }
    @Test fun malformedIncompleteAndOversizedLessonsAreRejected() {
        assertEquals(lesson(), ExplanationClient.decode(ExplanationClient.encode(lesson())))
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.remove("grammar") },
            { it.put("meaning", 42) },
            { it.put("meaning", "a".repeat(1201)) },
            { it.getJSONArray("vocabulary").getJSONObject(0).put("level", "definitely N0") },
            { it.put("exercises", org.json.JSONArray()) })
        mutations.forEach { mutate ->
            val body = ExplanationClient.encode(lesson()); mutate(body)
            assertTrue(runCatching { ExplanationClient.decode(body) }.isFailure)
        }
    }
}
