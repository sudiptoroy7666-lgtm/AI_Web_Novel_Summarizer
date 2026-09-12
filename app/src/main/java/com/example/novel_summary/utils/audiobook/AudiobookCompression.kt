package com.example.novel_summary.utils.audiobook

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object AudiobookCompression {

    fun compress(text: String): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    fun decompress(compressed: String): String {
        val bytes = Base64.decode(compressed, Base64.NO_WRAP)
        val bis = bytes.inputStream()
        GZIPInputStream(bis).use { gis ->
            val bos = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var len: Int
            while (gis.read(buffer).also { len = it } != -1) {
                bos.write(buffer, 0, len)
            }
            return bos.toString(Charsets.UTF_8.name())
        }
    }
}