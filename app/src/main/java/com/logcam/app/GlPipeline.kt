package com.logcam.app

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * Camera frames (external OES texture) -> shader (linearise -> S-Gamut3 -> S-Log3) ->
 * preview surface + (optionally) the video encoder's input surface.
 * If the EGL context is 10-bit (RGBA1010102) the encoder receives 10-bit data.
 */
class GlPipeline(private val previewSurface: Surface, private val want10bit: Boolean) {

    private val thread = HandlerThread("gl-pipeline").also { it.start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    @Volatile var monitorRec709 = false
    @Volatile var sourceHlg = false
    @Volatile var useCineGamut = true
    @Volatile var evStops = 0f
    @Volatile var displayRotationDeg = 0
    @Volatile var sensorOrientation = 90
    @Volatile var manualQuarterTurns = 0
    @Volatile var lastK = 0
    @Volatile var lastSwapped = false
    @Volatile private var released = false
    @Volatile private var recording = false
    var isTenBit = false
        private set

    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var cfg: EGLConfig? = null
    private var previewEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encEgl: EGLSurface = EGL14.EGL_NO_SURFACE

    private var prog = 0
    private var texId = 0
    private var st: SurfaceTexture? = null
    private var frameW = 0
    private var frameH = 0
    private val texMatrix = FloatArray(16)

    private var encW = 0
    private var encH = 0
    private var recStartNs = 0L
    private var recDisplayDeg = 0
    private var recManual = 0

    private var uTex = 0; private var uRot = 0; private var uSampler = 0; private var uHlg = 0
    private var uGain = 0; private var uMode = 0; private var uGamut = 0; private var uView = 0

    private var cachedKey = -1
    private var gamutM = FloatArray(9)
    private var viewM = FloatArray(9)

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
        )); position(0)
    }

    fun start(camW: Int, camH: Int, onReady: (SurfaceTexture, Boolean) -> Unit, onError: (Throwable) -> Unit) {
        handler.post {
            try {
                try {
                    setupEgl(want10bit)
                } catch (t: Throwable) {
                    if (want10bit) { releaseEgl(); setupEgl(false) } else throw t
                }
                setupGl()
                val tex = SurfaceTexture(texId)
                tex.setDefaultBufferSize(camW, camH)
                tex.setOnFrameAvailableListener({ drawFrame() }, handler)
                st = tex
                frameW = camW; frameH = camH
                main.post { onReady(tex, isTenBit) }
            } catch (t: Throwable) {
                Log.e(TAG, "GL start failed", t)
                main.post { onError(t) }
            }
        }
    }

    fun startRecording(surface: Surface, w: Int, h: Int, startNs: Long, displayDeg: Int, onStarted: () -> Unit) {
        handler.post {
            try {
                encEgl = EGL14.eglCreateWindowSurface(dpy, cfg, surface, intArrayOf(EGL14.EGL_NONE), 0)
                check(encEgl != EGL14.EGL_NO_SURFACE) { "encoder EGL surface failed (0x${Integer.toHexString(EGL14.eglGetError())})" }
                encW = w; encH = h; recStartNs = startNs; recDisplayDeg = displayDeg; recManual = manualQuarterTurns
                recording = true
            } catch (t: Throwable) {
                Log.e(TAG, "startRecording", t)
            }
            main.post(onStarted)
        }
    }

    fun stopRecording(done: () -> Unit) {
        handler.post {
            recording = false
            if (encEgl != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(dpy, previewEgl, previewEgl, ctx)
                EGL14.eglDestroySurface(dpy, encEgl)
                encEgl = EGL14.EGL_NO_SURFACE
            }
            main.post(done)
        }
    }

    fun release() {
        released = true
        recording = false
        handler.post {
            try {
                st?.setOnFrameAvailableListener(null)
                st?.release(); st = null
                if (prog != 0) GLES30.glDeleteProgram(prog)
                releaseEgl()
            } catch (t: Throwable) {
                Log.e(TAG, "release", t)
            }
            thread.quitSafely()
        }
    }

    // ---------------------------------------------------------------- EGL
    private fun setupEgl(tenBit: Boolean) {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        check(EGL14.eglInitialize(dpy, v, 0, v, 1)) { "eglInitialize" }
        val c = if (tenBit) 10 else 8
        val a = if (tenBit) 2 else 8
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, c, EGL14.EGL_GREEN_SIZE, c, EGL14.EGL_BLUE_SIZE, c, EGL14.EGL_ALPHA_SIZE, a,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(dpy, attribs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "no EGL config (tenBit=$tenBit)" }
        cfg = configs[0]
        ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(ctx != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        previewEgl = EGL14.eglCreateWindowSurface(dpy, cfg, previewSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(previewEgl != EGL14.EGL_NO_SURFACE) { "preview EGL surface" }
        check(EGL14.eglMakeCurrent(dpy, previewEgl, previewEgl, ctx)) { "eglMakeCurrent" }
        EGL14.eglSwapInterval(dpy, 0)
        isTenBit = tenBit
    }

    private fun releaseEgl() {
        if (dpy != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (encEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, encEgl)
            if (previewEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, previewEgl)
            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
            EGL14.eglTerminate(dpy)
        }
        dpy = EGL14.EGL_NO_DISPLAY; ctx = EGL14.EGL_NO_CONTEXT
        previewEgl = EGL14.EGL_NO_SURFACE; encEgl = EGL14.EGL_NO_SURFACE
    }

    // ---------------------------------------------------------------- GL
    private fun setupGl() {
        prog = buildProgram(VERT, FRAG)
        uTex = GLES30.glGetUniformLocation(prog, "uTex")
        uRot = GLES30.glGetUniformLocation(prog, "uRot")
        uSampler = GLES30.glGetUniformLocation(prog, "uSampler")
        uHlg = GLES30.glGetUniformLocation(prog, "uHlg")
        uGain = GLES30.glGetUniformLocation(prog, "uGain")
        uMode = GLES30.glGetUniformLocation(prog, "uMode")
        uGamut = GLES30.glGetUniformLocation(prog, "uGamut")
        uView = GLES30.glGetUniformLocation(prog, "uView")

        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        texId = t[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun sh(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader compile: " + GLES30.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, sh(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, sh(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glBindAttribLocation(p, 1, "aUv")
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program link: " + GLES30.glGetProgramInfoLog(p) }
        return p
    }

    private fun refreshMatrices() {
        val key = (if (sourceHlg) 1 else 0) or (if (useCineGamut) 2 else 0)
        if (key == cachedKey) return
        val src = if (sourceHlg) ColorMath.REC2020 else ColorMath.REC709
        val dst = if (useCineGamut) ColorMath.SGAMUT3_CINE else ColorMath.SGAMUT3
        gamutM = ColorMath.convert(src, dst)
        viewM = ColorMath.convert(src, ColorMath.REC709)
        cachedKey = key
    }

    private fun drawFrame() {
        val tex = st ?: return
        if (released || dpy == EGL14.EGL_NO_DISPLAY) return
        try {
            if (!EGL14.eglMakeCurrent(dpy, previewEgl, previewEgl, ctx)) return
            tex.updateTexImage()
            tex.getTransformMatrix(texMatrix)
            val ts = tex.timestamp

            val w = IntArray(1); val h = IntArray(1)
            EGL14.eglQuerySurface(dpy, previewEgl, EGL14.EGL_WIDTH, w, 0)
            EGL14.eglQuerySurface(dpy, previewEgl, EGL14.EGL_HEIGHT, h, 0)
            draw(w[0], h[0], true, if (monitorRec709) 1f else 0f, computeK(displayRotationDeg, manualQuarterTurns, true))
            EGL14.eglSwapBuffers(dpy, previewEgl)

            val es = encEgl
            if (recording && es != EGL14.EGL_NO_SURFACE) {
                val pts = ts - recStartNs
                if (pts >= 0) {
                    EGL14.eglMakeCurrent(dpy, es, es, ctx)
                    draw(encW, encH, false, 0f, computeK(recDisplayDeg, recManual, false))
                    EGLExt.eglPresentationTimeANDROID(dpy, es, pts)
                    EGL14.eglSwapBuffers(dpy, es)
                    EGL14.eglMakeCurrent(dpy, previewEgl, previewEgl, ctx)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "drawFrame", t)
        }
    }

    /** Quarter turns (clockwise) to apply to the image so it is upright for the current phone orientation. */
    private fun computeK(displayDeg: Int, manual: Int, store: Boolean): Int {
        // If the transform matrix swaps axes, the camera delivered a frame upright for the phone's
        // natural (portrait) orientation; the screen needs it turned back by the display rotation.
        // Otherwise it is the raw sensor-oriented frame.
        val swapped = abs(texMatrix[0]) < 0.01f && abs(texMatrix[5]) < 0.01f
        val base = if (swapped) (4 - displayDeg / 90) and 3 else ((sensorOrientation - displayDeg + 360) % 360) / 90
        val k = (base + manual) and 3
        if (store) { lastSwapped = swapped; lastK = k }
        return k
    }

    /** Clockwise rotation (degrees) from the raw sensor image to what the preview shows. */
    fun totalRotationDeg(): Int = ((if (lastSwapped) sensorOrientation else 0) + 90 * lastK) % 360

    private fun draw(w: Int, h: Int, letterbox: Boolean, mode: Float, k: Int) {
        refreshMatrices()
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (letterbox && frameW > 0 && frameH > 0 && w > 0 && h > 0) {
            val ca = frameW.toFloat() / frameH
            val sa = w.toFloat() / h
            if (sa > ca) {
                val vw = (h * ca).toInt()
                GLES30.glViewport((w - vw) / 2, 0, vw, h)
            } else {
                val vh = (w / ca).toInt()
                GLES30.glViewport(0, (h - vh) / 2, w, vh)
            }
        }
        GLES30.glUseProgram(prog)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES30.glUniform1i(uSampler, 0)
        GLES30.glUniformMatrix4fv(uTex, 1, false, texMatrix, 0)
        GLES30.glUniform1i(uRot, k)
        GLES30.glUniform1f(uHlg, if (sourceHlg) 1f else 0f)
        GLES30.glUniform1f(uGain, Math.pow(2.0, evStops.toDouble()).toFloat())
        GLES30.glUniform1f(uMode, mode)
        GLES30.glUniformMatrix3fv(uGamut, 1, false, gamutM, 0)
        GLES30.glUniformMatrix3fv(uView, 1, false, viewM, 0)

        quad.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    companion object {
        private const val TAG = "LogCam.GL"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VERT = """#version 300 es
in vec4 aPos;
in vec2 aUv;
uniform mat4 uTex;
uniform int uRot;   // quarter turns clockwise of the image on screen
out vec2 vUv;
void main() {
    gl_Position = aPos;
    vec2 s = aUv * 2.0 - 1.0;
    vec2 q;
    if (uRot == 0) q = s;
    else if (uRot == 1) q = vec2(-s.y, s.x);
    else if (uRot == 2) q = -s;
    else q = vec2(s.y, -s.x);
    vec2 uv = q * 0.5 + 0.5;
    vUv = (uTex * vec4(uv, 0.0, 1.0)).xy;
}
"""

        // Source -> linear scene reflectance -> S-Gamut3(.Cine) -> S-Log3.
        private const val FRAG = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform samplerExternalOES uSampler;
uniform mat3 uGamut;   // source primaries -> S-Gamut
uniform mat3 uView;    // source primaries -> Rec.709 (view assist)
uniform float uHlg;    // 1 = HLG (BT.2020) source, 0 = SDR (BT.709) source
uniform float uGain;   // exposure offset (linear multiplier)
uniform float uMode;   // 0 = S-Log3 out, 1 = Rec.709 view assist

float hlgInv(float e) {
    const float a = 0.17883277;
    const float b = 0.28466892;
    const float c = 0.55991073;
    return (e <= 0.5) ? (e * e / 3.0) : ((exp((e - c) / a) + b) / 12.0);
}

float slog3(float x) {
    if (x >= 0.01125000) {
        return (420.0 + log((x + 0.01) / (0.18 + 0.01)) / log(10.0) * 261.5) / 1023.0;
    }
    return (x * (171.2102946929 - 95.0) / 0.01125000 + 95.0) / 1023.0;
}

void main() {
    vec3 c = texture(uSampler, vUv).rgb;
    vec3 lin;
    if (uHlg > 0.5) {
        // HLG scene light 1.0 ~ peak; scale so 18% grey lands near 0.18 reflectance
        lin = vec3(hlgInv(c.r), hlgInv(c.g), hlgInv(c.b)) * 3.6;
    } else {
        lin = pow(max(c, vec3(0.0)), vec3(2.2)) * 0.9;
    }
    lin *= uGain;
    if (uMode > 0.5) {
        vec3 v = clamp(uView * lin, 0.0, 1.0);
        fragColor = vec4(pow(v, vec3(1.0 / 2.2)), 1.0);
        return;
    }
    vec3 g = uGamut * lin;
    fragColor = vec4(clamp(vec3(slog3(g.r), slog3(g.g), slog3(g.b)), 0.0, 1.0), 1.0);
}
"""
    }
}
