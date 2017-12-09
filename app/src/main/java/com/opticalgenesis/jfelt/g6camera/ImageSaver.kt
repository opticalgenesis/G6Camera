package com.opticalgenesis.jfelt.g6camera

import android.media.Image
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


class ImageSaver(private val img: Image, val file: File) : Runnable {
    override fun run() {
        val buffer = img.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer[bytes]
        var outputStream: FileOutputStream? = null
        try {
            outputStream = FileOutputStream(file).apply { write(bytes) }
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            img.close()
            outputStream?.let {
                try {
                    it.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}