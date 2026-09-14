package com.ruyo.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.sqrt

data class ImportedImage(val bitmap: Bitmap, val name: String, val reducedForPreview: Boolean)

object ImageImporter {
    fun name(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "Imported image"

    fun load(context: Context, uri: Uri): ImportedImage = decode(ImageDecoder.createSource(context.contentResolver, uri), name(context, uri))
    fun load(file: File, name: String): ImportedImage = FileInputStream(file).channel.use { channel ->
        val bytes = channel.size()
        require(bytes in 1..(40L * 1024 * 1024)) { "This image is empty or exceeds the 40 MB import limit." }
        // Avoid an extra encoded-image heap copy, and exercise the same native decoder in JVM CI.
        val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, bytes)
        decode(ImageDecoder.createSource(buffer), name)
    }

    private fun decode(source: ImageDecoder.Source, name: String): ImportedImage {
        var reduced = false
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val size = info.size
            require(size.width > 0 && size.height > 0) { "This file is not a readable image." }
            val pixels = size.width.toDouble() * size.height.toDouble()
            // Keep a working copy for editing. Original bytes are archived separately.
            val scale = max(1.0, max(sqrt(pixels / 2_000_000.0), max(size.width, size.height) / 8192.0))
            if (scale > 1.0) {
                reduced = true
                decoder.setTargetSize(max(1, (size.width / scale).toInt()), max(1, (size.height / scale).toInt()))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return ImportedImage(bitmap, name, reduced)
    }
}

/** Numeric filename chunks compare without integer overflow: 1, 2, 10. Stable ties retain selection order. */
object NaturalOrder : Comparator<String> {
    private val chunks = Regex("[0-9]+|[^0-9]+")
    override fun compare(a: String, b: String): Int {
        val left = chunks.findAll(a.lowercase(java.util.Locale.ROOT)).map { it.value }.toList()
        val right = chunks.findAll(b.lowercase(java.util.Locale.ROOT)).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]; val y = right[i]
            val comparison = if (x[0].isDigit() && y[0].isDigit()) {
                val xx = x.trimStart('0').ifEmpty { "0" }; val yy = y.trimStart('0').ifEmpty { "0" }
                xx.length.compareTo(yy.length).takeIf { it != 0 } ?: xx.compareTo(yy)
            } else x.compareTo(y)
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }
}
