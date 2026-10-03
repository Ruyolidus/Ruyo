package com.ruyolidus.multiplayerbridge.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameVaultTest {
    @get:Rule val temporary = TemporaryFolder()
    private val identity = AppIdentity("Dames / test", "example.dames", "1.0", 26)
    private fun vault(root: File, inspector: ApkInspector = ApkInspector { identity }) = GameVault(root, inspector)

    @Test fun importPersistsExactFileAndMetadataAcrossReopening() {
        val root = temporary.newFolder()
        val bytes = ByteArray(150_000) { (it % 239).toByte() }
        val result = vault(root).importGame(bytes.inputStream(), "dames.apk", now = 42)
        val restored = vault(root).load().games.single()
        assertEquals(result.game, restored)
        assertEquals(42L, restored.importedAt)
        assertArrayEquals(bytes, File(root, "${restored.id}/game.apk").readBytes())
    }

    @Test fun duplicateContentsDoNotCreateAnotherLibraryEntry() {
        val root = temporary.newFolder()
        val store = vault(root)
        val first = store.importGame("same APK bytes".byteInputStream(), "first.apk")
        val second = store.importGame("same APK bytes".byteInputStream(), "renamed.apk")
        assertTrue(second.alreadyPresent)
        assertEquals(first.game.id, second.game.id)
        assertEquals(1, store.load().games.size)
        assertEquals(1, root.listFiles()!!.size)
    }

    @Test fun failedInspectionPublishesNoGameAndPreservesExistingFiles() {
        val root = temporary.newFolder()
        val first = vault(root).importGame("good".byteInputStream(), "good.apk").game
        val rejecting = vault(root, ApkInspector { throw IllegalArgumentException("Invalid APK") })
        assertThrows(IllegalArgumentException::class.java) { rejecting.importGame("bad".byteInputStream(), "bad.apk") }
        assertEquals(listOf(first), rejecting.load().games)
        assertEquals(1, root.listFiles()!!.size)
    }

    @Test fun interruptedCopyLeavesNoPartialEntry() {
        val root = temporary.newFolder()
        val failing = object : InputStream() {
            private var reads = 0
            override fun read(): Int = throw IOException("Disconnected provider")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (reads++ == 0) { b[off] = 7; return 1 }
                throw IOException("Disconnected provider")
            }
        }
        val store = vault(root)
        assertThrows(IOException::class.java) { store.importGame(failing, "partial.apk") }
        assertTrue(store.load().games.isEmpty())
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun removalOnlyDeletesTheImportedCopy() {
        val source = temporary.newFile("original.apk").apply { writeText("source bytes") }
        val store = vault(temporary.newFolder())
        val game = source.inputStream().use { store.importGame(it, source.name).game }
        store.remove(game.id)
        assertTrue(store.load().games.isEmpty())
        assertEquals("source bytes", source.readText())
        assertThrows(IllegalArgumentException::class.java) { store.remove("../original.apk") }
    }

    @Test fun damagedEntryIsReportedWithoutBeingDeleted() {
        val root = temporary.newFolder()
        val store = vault(root)
        val game = store.importGame("valid bytes".byteInputStream(), "game.apk").game
        File(root, "${game.id}/metadata.properties").writeText("broken")
        val snapshot = store.load()
        assertEquals(1, snapshot.unreadableCount)
        assertTrue(snapshot.games.isEmpty())
        assertTrue(File(root, "${game.id}/game.apk").exists())
    }
}
