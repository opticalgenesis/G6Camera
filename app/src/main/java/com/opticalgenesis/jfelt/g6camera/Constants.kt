package com.opticalgenesis.jfelt.g6camera

import android.os.Environment
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import java.io.File
import java.lang.Long
import java.util.*


class Constants {
    companion object {
        val ORIENTATIONS = SparseIntArray()

        val STATE_PREVIEW = 0
        val STATE_WAITING_LOCK = 1
        val STATE_WAITING_PRECAPTURE = 2
        val STATE_WAITING_NON_PRECAPTURE = 3
        val STATE_PICTURE_TAKEN = 4

        fun compareSizesByArea(lhs: Size, rhs: Size) = Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)

        fun chooseOptimalSize(sizes: Array<Size>, textureViewWidth: Int, textureViewHeight: Int,
                              maxWidth: Int, maxHeight: Int, aspectRatio: Size): Size {
            val bigEnough = ArrayList<Size>()
            val notBigEnough = ArrayList<Size>()

            val w = aspectRatio.width
            val h = aspectRatio.height
            sizes.forEach {
                if (it.width <= maxWidth && it.height <= maxHeight && it.height == it.width * h / w) {
                    if (it.width >= textureViewWidth && it.height >= textureViewHeight) bigEnough.add(it) else notBigEnough.add(it)
                }
            }
            if (bigEnough.size > 0) return Collections.max(bigEnough, { lhs, rhs -> compareSizesByArea(lhs, rhs) })
            else if (notBigEnough.size > 0) return Collections.max(notBigEnough, { lhs, rhs -> compareSizesByArea(lhs, rhs) })
            return sizes[0]
        }

        fun setupFile(): File {
            val c = Calendar.getInstance()
            val y = c[Calendar.YEAR]
            val m = c[Calendar.MONTH]
            val d = c[Calendar.DAY_OF_MONTH]
            val s = c[Calendar.SECOND]
            val mm = c[Calendar.MINUTE]
            val h = c[Calendar.HOUR_OF_DAY]

            return File("${Environment.getExternalStorageDirectory()}/G6Camera/$y$m$d$h$mm$s.jpg")
        }

        fun areDimensSwapped(dispRot: Int, sensorRot: Int): Boolean {
            var dimensAreSwapped = false

            when (dispRot) {
                Surface.ROTATION_0, Surface.ROTATION_180 -> {
                    if (sensorRot == 90 || sensorRot == 270) dimensAreSwapped = true
                }

                Surface.ROTATION_90, Surface.ROTATION_270 -> {
                    if (sensorRot == 0 || sensorRot == 180) dimensAreSwapped = true
                }

                else -> Log.e(MainActivity.TAG, "Invalid display rotation")
            }

            return dimensAreSwapped
        }

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }
    }
}