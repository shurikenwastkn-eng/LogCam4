package com.logcam.app

import android.graphics.Color

/** One place that holds the interface (accent) colour so every screen and custom view can follow it. */
object Theme {
    val DEFAULT: Int = Color.parseColor("#FFC23C")

    @JvmField
    var accent: Int = DEFAULT

    fun alpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or ((a and 0xFF) shl 24)

    /** Dark or light text, whichever reads better on top of the accent colour. */
    fun onAccent(c: Int = accent): Int {
        val lum = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
        return if (lum > 150) Color.parseColor("#14110A") else Color.WHITE
    }

    fun hex(c: Int): String = String.format("#%06X", c and 0xFFFFFF)
}
