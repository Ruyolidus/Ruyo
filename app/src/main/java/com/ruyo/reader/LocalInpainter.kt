package com.ruyo.reader

/** Offline patch reconstruction. The source and every unmasked pixel remain unchanged. */
object LocalInpainter {
    fun repair(source: IntArray, width: Int, height: Int, mask: BooleanArray, excluded: BooleanArray = BooleanArray(source.size)): IntArray {
        require(width > 0 && height > 0 && width.toLong() * height <= 2_000_000)
        require(source.size == width * height && mask.size == source.size && excluded.size == source.size)
        val count = mask.count { it }
        require(count <= 400_000 && count < source.size * .75) { "Leave original artwork around the lettering." }
        if (count == 0) return source.copyOf()
        return NativeArtworkRepair.repairPixels(source, width, height, mask, excluded)
    }
}

internal object NativeArtworkRepair {
    init {
        val host = System.getProperty("ruyo.nativeLibrary")
        if (host.isNullOrBlank()) System.loadLibrary("ruyo_repair") else {
            // Robolectric uses isolated classloaders. Each loads its own copy of the real JNI library.
            val copy = java.io.File.createTempFile("ruyo-repair-", ".so")
            java.io.File(host).copyTo(copy, overwrite = true); copy.deleteOnExit(); System.load(copy.absolutePath)
        }
    }
    external fun repairPixels(source: IntArray, width: Int, height: Int, mask: BooleanArray, excluded: BooleanArray): IntArray
}
