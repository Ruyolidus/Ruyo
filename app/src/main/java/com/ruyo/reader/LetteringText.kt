package com.ruyo.reader

/** Decode accidental literal line separators without interpreting arbitrary JSON or markup. */
object LetteringText {
    fun normalize(value: String): String = value.replace("\\r\\n", "\n").replace("\\n", "\n")
        .replace("\r\n", "\n").replace('\r', '\n')
}
