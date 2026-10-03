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
        listOf(LessonWord("待つ", "まつ", "to wait", "N5")), listOf(LessonExample("少し待って。", "すこしまって。", "Sukoshi matte.", "Wait a little. The te-form asks someone to wait.", listOf(LessonWord("少し", "すこし", "a little", "N5"), LessonWord("待って", "まって", "wait, as a casual request", "N5")))),
        listOf(LessonExercise("Give the dictionary form.", "待つ", "This verb uses the te-form.", "The dialogue says wait, as a request.", "まつ / matsu")), chunks = listOf(LessonWord("待って", "まって", "wait", "N5")))
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
    @Test fun missingOptionalPartsKeepUsableMeaningAndReading() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.remove("grammar") },
            { it.getJSONArray("examples").getJSONObject(0).remove("reading") },
            { it.getJSONArray("examples").getJSONObject(0).put("reading", "少し待って") },
            { it.getJSONArray("examples").getJSONObject(0).getJSONArray("words").remove(0) },
            { it.getJSONArray("exercises").getJSONObject(0).remove("hint") })
        mutations.forEach { mutate ->
            val body = ExplanationClient.encode(lesson()); mutate(body)
            val decoded = ExplanationClient.decode(body)
            assertEquals(lesson().meaning, decoded.meaning)
            assertEquals(lesson().reading, decoded.reading)
            assertTrue(decoded.missingSections.isNotEmpty())
        }
    }
    @Test fun invalidMeaningStillFailsAndInvalidVocabularyLevelIsOmitted() {
        for (bad in listOf<Any>(42, "a".repeat(1201))) assertTrue(runCatching { ExplanationClient.decode(ExplanationClient.encode(lesson()).put("meaning", bad)) }.isFailure)
        val body = ExplanationClient.encode(lesson())
        body.getJSONArray("vocabulary").getJSONObject(0).put("level", "N0")
        assertEquals("", ExplanationClient.decode(body).vocabulary.first().level)
    }
    @Test fun truncatedResponseRetainsOnlyWholeFieldsAndNeverInventsTheRest() {
        val content = """{"meaning":"Wait, please.","reading":"まって","note":"An escaped \"word\" and {brace}","grammar":[{"text":"待って""""
        val response = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("finish_reason", "length").put("message", JSONObject().put("content", content)))).toString()
        val decoded = ExplanationClient.decode(TranslationClient.objectContent(ProviderKind.OPENAI, response, allowPartial = true))
        assertEquals("Wait, please.", decoded.meaning); assertEquals("まって", decoded.reading)
        assertTrue(decoded.grammar.isEmpty()); assertTrue(decoded.missingSections.contains("response"))
        assertTrue(runCatching { TranslationClient.objectContent(ProviderKind.OPENAI, response) }.isFailure)
    }
    @Test fun preferencesAndSourceAreTextOnlyAndSeparateTheCache() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cache = LessonCache(context); val p = profile()
        val defaults = cache.key("待って", "ja", p)
        val options = LessonPreferences(LearnerLevel.N4, "fr", "Wait!")
        assertNotEquals(defaults, cache.key("待って", "ja", p, options))
        val body = ExplanationClient.request(ProviderSecret(p, "test-key"), "待って", "ja", options).body
        assertTrue(body.contains("N4")); assertTrue(body.contains("Wait!")); assertTrue(body.contains("explanationLanguage"))
        assertFalse(body.contains("test-key"))
        val core = JSONObject(body).getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(core.contains("Do not add exercises"))
        val practice = ExplanationClient.request(ProviderSecret(p, "test-key"), "待って", "ja", options, practice = true).body
        assertTrue(practice.contains("EVERY target-language word"))
    }
    @Test fun followUpRequestsOnlyMissingSectionsAndKeepsPreviouslyValidFields() = kotlinx.coroutines.runBlocking {
        val previous = lesson().copy(reading = "", missingSections = listOf("reading"))
        val output = java.io.ByteArrayOutputStream()
        val reply = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("finish_reason", "stop")
            .put("message", JSONObject().put("content", "{\"reading\":\"まって\"}")))).toString()
        val http = object : java.net.HttpURLConnection(java.net.URL("https://example.com")) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getOutputStream() = output
            override fun getInputStream() = java.io.ByteArrayInputStream(reply.toByteArray())
        }
        val actual = ExplanationClient(TranslationClient { http }).complete(ProviderSecret(profile(), "test-key"), "待って", "ja", LessonPreferences(), previous)
        assertEquals("まって", actual.reading); assertEquals(previous.meaning, actual.meaning)
        assertEquals(previous.examples, actual.examples); assertTrue(actual.missingSections.isEmpty())
        assertTrue(output.toString().contains("Return ONLY these JSON fields: reading"))
    }
    @Test fun quickChoicesRoundTripAndRejectAnAmbiguousAnswer() {
        val value = lesson().copy(note = "It's a quick 'hang on!' you can say to a friend.", exercises = listOf(
            LessonExercise("Your friend walks away. Ask them to wait.", "待って", "It means wait.", "待って (matte): wait; ありがとう (arigatou): thanks; おはよう (ohayou): morning.", "まって", listOf("待って", "ありがとう", "おはよう"))))
        assertEquals(value, ExplanationClient.decode(ExplanationClient.encode(value)))
        val wrong = ExplanationClient.encode(value)
        wrong.getJSONArray("exercises").getJSONObject(0).put("answer", "not an option")
        assertTrue(ExplanationClient.decode(wrong).exercises.isEmpty())
        val duplicate = ExplanationClient.encode(value)
        duplicate.getJSONArray("exercises").getJSONObject(0).getJSONArray("choices").put(1, "待って")
        assertTrue(ExplanationClient.decode(duplicate).exercises.isEmpty())
    }
    @Test fun oldCachedLessonsCannotHideTheNewExampleReadings() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cache = LessonCache(context); val key = cache.key("待って！", "ja", profile())
        java.io.File(context.cacheDir, "lessons-v4/$key.json").delete()
        val old = java.io.File(context.cacheDir, "lessons-v2/$key.json")
        old.parentFile!!.mkdirs(); old.writeText(ExplanationClient.encode(lesson()).toString())
        assertNull(cache.read(key))
        old.delete()
    }
}
