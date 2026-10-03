package com.ruyolidus.multiplayerbridge.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

data class AppIdentity(
    val label: String,
    val packageName: String,
    val versionName: String,
    val minSdk: Int,
)

data class ImportedGame(
    val id: String,
    val app: AppIdentity,
    val originalName: String,
    val bytes: Long,
    val importedAt: Long,
)

data class LibrarySnapshot(val games: List<ImportedGame>, val unreadableCount: Int = 0)
data class ImportResult(val game: ImportedGame, val alreadyPresent: Boolean)

fun interface ApkInspector {
    fun inspect(apk: File): AppIdentity
}

/** The library publishes a complete directory only after copy and inspection succeed. */
class GameVault(private val root: File, private val inspector: ApkInspector) {
    init {
        check(root.isDirectory || root.mkdirs()) { "Could not create the game library." }
        // A single application-scoped instance owns this directory. These are interrupted operations.
        root.listFiles().orEmpty().filter {
            it.name.startsWith("pending-") || it.name.startsWith("removed-")
        }.forEach { it.deleteRecursively() }
    }

    @Synchronized
    fun load(): LibrarySnapshot {
        var unreadable = 0
        val games = root.listFiles().orEmpty().filter { it.isDirectory && isId(it.name) }.mapNotNull {
            try {
                read(it)
            } catch (_: Exception) {
                unreadable++
                null
            }
        }
        return LibrarySnapshot(games.sortedByDescending { it.importedAt }, unreadable)
    }

    @Synchronized
    fun importGame(input: InputStream, originalName: String, now: Long = System.currentTimeMillis()): ImportResult {
        val stage = File(root, "pending-${UUID.randomUUID()}")
        check(stage.mkdir()) { "Could not prepare storage for this game." }
        try {
            val archive = File(stage, "game.apk")
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(archive).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
                output.fd.sync()
            }
            require(archive.length() > 0) { "This file is empty. Choose an Android APK file." }
            val id = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val destination = File(root, id)
            if (destination.exists()) return ImportResult(read(destination), alreadyPresent = true)

            val identity = inspector.inspect(archive)
            val game = ImportedGame(id, identity, originalName, archive.length(), now)
            write(File(stage, "metadata.properties"), game)
            Files.move(stage.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return ImportResult(game, alreadyPresent = false)
        } finally {
            stage.deleteRecursively()
        }
    }

    @Synchronized
    fun remove(id: String) {
        require(isId(id)) { "Invalid game identifier." }
        val directory = File(root, id)
        if (!directory.exists()) return
        val removed = File(root, "removed-${UUID.randomUUID()}")
        Files.move(directory.toPath(), removed.toPath(), StandardCopyOption.ATOMIC_MOVE)
        // A failed cleanup is retried on next process start. The source file is never touched.
        removed.deleteRecursively()
    }

    private fun read(directory: File): ImportedGame {
        val p = Properties().apply { File(directory, "metadata.properties").inputStream().use(::load) }
        fun required(key: String) = p.getProperty(key) ?: throw IOException("Missing game information.")
        val archive = File(directory, "game.apk")
        val size = required("bytes").toLong()
        require(archive.isFile && archive.length() == size && size > 0) { "The saved game file is incomplete." }
        return ImportedGame(
            directory.name,
            AppIdentity(required("label"), required("package"), required("version"), required("minSdk").toInt()),
            required("originalName"), size, required("importedAt").toLong(),
        )
    }

    private fun write(file: File, game: ImportedGame) {
        val p = Properties().apply {
            setProperty("label", game.app.label)
            setProperty("package", game.app.packageName)
            setProperty("version", game.app.versionName)
            setProperty("minSdk", game.app.minSdk.toString())
            setProperty("originalName", game.originalName)
            setProperty("bytes", game.bytes.toString())
            setProperty("importedAt", game.importedAt.toString())
        }
        FileOutputStream(file).use { output ->
            p.store(output, "Multiplayer Bridge game metadata")
            output.fd.sync()
        }
    }

    private fun isId(value: String) = value.matches(Regex("[a-f0-9]{64}"))
}
