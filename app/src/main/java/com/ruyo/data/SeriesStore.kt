package com.ruyo.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ComicSeries(val id: String, val title: String, val chapters: List<String>)

/** Series membership and ordering are one atomic document; existing chapter files are unchanged. */
class SeriesStore(context: Context) {
    private val file = File(context.filesDir, "series.json")
    @Synchronized fun list(): List<ComicSeries> {
        if (!file.exists()) return emptyList()
        val array = JSONArray(AtomicFile(file).readFully().toString(Charsets.UTF_8))
        require(array.length() <= 500)
        val used = mutableSetOf<String>()
        return (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index); val chapters = obj.getJSONArray("chapters")
            val id = obj.getString("id"); validId(id)
            ComicSeries(id, obj.getString("title").take(120), (0 until chapters.length()).map { chapters.getString(it) }.onEach { validId(it); require(used.add(it)) })
        }.also { require(it.map { s -> s.id }.distinct().size == it.size) }
    }
    @Synchronized fun create(title: String): ComicSeries {
        val all = list(); require(all.size < 500) { "The series limit has been reached." }
        val value = ComicSeries(UUID.randomUUID().toString(), clean(title), emptyList())
        write(all + value); return value
    }
    @Synchronized fun rename(id: String, title: String) { write(list().map { if (it.id == id) it.copy(title = clean(title)) else it }) }
    @Synchronized fun assign(chapterId: String, seriesId: String?) {
        validId(chapterId)
        val all = list(); require(seriesId == null || all.any { it.id == seriesId }) { "This series is no longer available." }
        if (all.any { it.id == seriesId && chapterId in it.chapters }) return
        write(all.map { it.copy(chapters = it.chapters.filterNot { id -> id == chapterId } + if (it.id == seriesId) listOf(chapterId) else emptyList()) })
    }
    @Synchronized fun reorder(id: String, chapters: List<String>) {
        val all = list(); val current = all.first { it.id == id }
        require(chapters.distinct().size == chapters.size && chapters.toSet() == current.chapters.toSet())
        write(all.map { if (it.id == id) it.copy(chapters = chapters) else it })
    }
    @Synchronized fun remove(id: String) { write(list().filterNot { it.id == id }) }
    @Synchronized fun removeChapter(id: String) { write(list().map { it.copy(chapters = it.chapters.filterNot { c -> c == id }) }) }
    private fun clean(title: String) = title.trim().take(120).also { require(it.isNotBlank()) { "Enter a series name." } }
    private fun validId(id: String) { require(id.matches(Regex("[a-f0-9-]{36}"))) }
    private fun write(all: List<ComicSeries>) {
        val value = JSONArray().apply { all.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("chapters", JSONArray(it.chapters))) } }.toString()
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try { output.write(value.toByteArray()); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
}
