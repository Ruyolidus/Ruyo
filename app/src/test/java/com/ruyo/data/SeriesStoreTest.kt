package com.ruyo.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeriesStoreTest {
    @Test fun membershipIsExclusiveAndOrderingSurvivesReopening() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(context.filesDir, "series.json").delete()
        val store = SeriesStore(context); val first = store.create("First comic"); val other = store.create("Other comic")
        val a = UUID.randomUUID().toString(); val b = UUID.randomUUID().toString()
        store.assign(a, first.id); store.assign(b, first.id)
        store.reorder(first.id, listOf(b, a))
        assertEquals(listOf(b, a), SeriesStore(context).list().first().chapters)
        assertTrue(runCatching { store.reorder(first.id, listOf(a, a)) }.isFailure)
        store.assign(a, other.id)
        assertEquals(listOf(b), store.list().first().chapters)
        assertEquals(listOf(a), store.list().last().chapters)
        store.rename(first.id, "Renamed")
        assertEquals("Renamed", store.list().first().title)
        store.removeChapter(b); assertTrue(store.list().first().chapters.isEmpty())
        store.remove(other.id); assertEquals(1, store.list().size)
    }
}
