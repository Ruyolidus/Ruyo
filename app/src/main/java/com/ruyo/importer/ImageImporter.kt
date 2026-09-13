package com.ruyo.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import kotlin.math.max
import kotlin.math.sqrt

data class ImportedImage(val bitmap: Bitmap, val name: String, val reducedForPreview: Boolean)

object ImageImporter {
    // Preview is intentionally bounded; full-resolution webtoon tiling is a later module.
    fun load(context: Context, uri: Uri): ImportedImage {
        var reduced = false
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val size = info.size
            val pixels = size.width.toDouble() * size.height.toDouble()
            val scale = max(1.0, max(sqrt(pixels / 5_000_000.0), max(size.width, size.height) / 8192.0))
            if (scale > 1.0) {
                reduced = true
                decoder.setTargetSize(max(1, (size.width / scale).toInt()), max(1, (size.height / scale).toInt()))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: "Imported image"
        return ImportedImage(bitmap, name, reduced)
    }
}

