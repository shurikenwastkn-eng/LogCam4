package com.logcam.app

/** Builds linear RGB gamut conversion matrices from chromaticity primaries (D65 white). */
object ColorMath {
    class Prim(val rx: Double, val ry: Double, val gx: Double, val gy: Double, val bx: Double, val by: Double)

    private const val WX = 0.3127
    private const val WY = 0.3290

    val REC709 = Prim(0.640, 0.330, 0.300, 0.600, 0.150, 0.060)
    val REC2020 = Prim(0.708, 0.292, 0.170, 0.797, 0.131, 0.046)
    val SGAMUT3 = Prim(0.730, 0.280, 0.140, 0.855, 0.100, -0.050)
    val SGAMUT3_CINE = Prim(0.766, 0.275, 0.225, 0.800, 0.089, -0.087)

    private fun inv(m: DoubleArray): DoubleArray {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val c00 = e * i - f * h
        val c10 = -(b * i - c * h)
        val c20 = b * f - c * e
        val c01 = -(d * i - f * g)
        val c11 = a * i - c * g
        val c21 = -(a * f - c * d)
        val c02 = d * h - e * g
        val c12 = -(a * h - b * g)
        val c22 = a * e - b * d
        val det = a * c00 + b * c01 + c * c02
        return doubleArrayOf(
            c00 / det, c10 / det, c20 / det,
            c01 / det, c11 / det, c21 / det,
            c02 / det, c12 / det, c22 / det
        )
    }

    private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (k in 0..2) s += a[i * 3 + k] * b[k * 3 + j]
            r[i * 3 + j] = s
        }
        return r
    }

    private fun mulV(m: DoubleArray, v: DoubleArray) = doubleArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2]
    )

    private fun rgbToXyz(p: Prim): DoubleArray {
        val xr = p.rx / p.ry; val zr = (1 - p.rx - p.ry) / p.ry
        val xg = p.gx / p.gy; val zg = (1 - p.gx - p.gy) / p.gy
        val xb = p.bx / p.by; val zb = (1 - p.bx - p.by) / p.by
        val m = doubleArrayOf(xr, xg, xb, 1.0, 1.0, 1.0, zr, zg, zb)
        val w = doubleArrayOf(WX / WY, 1.0, (1 - WX - WY) / WY)
        val s = mulV(inv(m), w)
        return doubleArrayOf(
            xr * s[0], xg * s[1], xb * s[2],
            s[0], s[1], s[2],
            zr * s[0], zg * s[1], zb * s[2]
        )
    }

    /** Returns a column-major float[9] for glUniformMatrix3fv converting linear [src] RGB to linear [dst] RGB. */
    fun convert(src: Prim, dst: Prim): FloatArray {
        val m = mul(inv(rgbToXyz(dst)), rgbToXyz(src))
        return FloatArray(9) { idx -> m[(idx % 3) * 3 + (idx / 3)].toFloat() }
    }
}
