package com.logcam.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.graphics.Rect
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor
import kotlin.math.hypot
import kotlin.math.roundToInt

data class LensInfo(val id: String, val label: String)

data class Mode(val w: Int, val h: Int, val fps: Int, val range: Range<Int>) {
    val label: String get() = (if (w >= 3840) "4K" else "${h}p") + " $fps"
}

data class ManualSettings(
    val autoExposure: Boolean = true,
    val iso: Int = 100,
    val exposureNs: Long = 20_833_333L,
    val autoFocus: Boolean = true,
    val focusDiopters: Float = 0f,
    val awbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO,
    val stab: Int = 1,          // 0 off, 1 OIS, 2 standard (EIS), 3 cinematic (preview stabilization)
    val focusX: Float = -1f,    // tap point, normalised in the raw sensor frame (-1 = none)
    val focusY: Float = -1f
)

class CameraController(private val ctx: Context) {
    private val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("camera").also { it.start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val main = Handler(Looper.getMainLooper())

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var mode: Mode? = null
    private var chars: CameraCharacteristics? = null
    private var settings = ManualSettings()
    private var frameCounter = 0

    var cbReady: ((hlg: Boolean) -> Unit)? = null
    var cbError: ((String) -> Unit)? = null
    var cbStab: ((Int) -> Unit)? = null
    var cbResult: ((iso: Int, exposureNs: Long) -> Unit)? = null

    fun characteristics(id: String): CameraCharacteristics = manager.getCameraCharacteristics(id)

    fun backCameras(): List<LensInfo> {
        val out = ArrayList<LensInfo>()
        for (id in manager.cameraIdList) {
            try {
                val c = manager.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
                if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) continue
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: continue
                if (sizes.none { it.width == 1920 && it.height == 1080 }) continue
                val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                val ps = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val label = if (f != null && ps != null) {
                    val eq = f * 43.27f / hypot(ps.width, ps.height)
                    "Cam $id  ·  ${eq.roundToInt()}mm"
                } else "Cam $id"
                out.add(LensInfo(id, label))
            } catch (t: Throwable) {
                Log.w(TAG, "skip camera $id: $t")
            }
        }
        return out
    }

    fun modes(id: String): List<Mode> {
        val c = manager.getCameraCharacteristics(id)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: return emptyList()
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return emptyList()
        val out = ArrayList<Mode>()
        for (s in listOf(Size(3840, 2160), Size(1920, 1080))) {
            if (!sizes.contains(s)) continue
            val minDur = map.getOutputMinFrameDuration(SurfaceTexture::class.java, s)
            for (fps in listOf(24, 30, 60)) {
                if (minDur > 1_000_000_000L / fps + 1_000_000L) continue
                val r = pickRange(ranges, fps) ?: continue
                out.add(Mode(s.width, s.height, fps, r))
            }
        }
        return out
    }

    private fun pickRange(r: Array<Range<Int>>, fps: Int): Range<Int>? =
        r.firstOrNull { it.lower == fps && it.upper == fps }
            ?: r.filter { it.upper == fps && it.lower <= fps }.maxByOrNull { it.lower }
            ?: r.filter { it.lower <= fps && it.upper >= fps }.minByOrNull { it.upper - it.lower }

    fun supportsHlg(id: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val p = manager.getCameraCharacteristics(id).get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
        return p?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true
    }

    fun timestampIsRealtime(id: String): Boolean =
        manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

    @SuppressLint("MissingPermission")
    fun open(id: String, m: Mode, target: Surface, wantHlg: Boolean) {
        close()
        mode = m
        surface = target
        chars = manager.getCameraCharacteristics(id)
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    createSession(wantHlg && supportsHlg(id))
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); device = null }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); device = null
                    main.post { cbError?.invoke("Camera error $error") }
                }
            }, handler)
        } catch (t: Throwable) {
            cbError?.invoke("Cannot open camera: $t")
        }
    }

    private var currentHlg = false

    fun stabOptions(id: String): List<Pair<Int, String>> {
        val c = manager.getCameraCharacteristics(id)
        val out = ArrayList<Pair<Int, String>>()
        out.add(0 to "Off")
        if (c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) out.add(1 to "OIS")
        val v = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        if (v?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true) out.add(2 to "Standard")
        if (Build.VERSION.SDK_INT >= 33 &&
            v?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) == true) out.add(3 to "Cinematic")
        return out
    }

    private fun eisFor(s: ManualSettings): Int = when (s.stab) {
        2 -> CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
        3 -> if (Build.VERSION.SDK_INT >= 33) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
             else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
        else -> CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
    }

    private fun oisFor(s: ManualSettings): Boolean = s.stab == 1 || s.stab == 3

    private fun meteringRect(c: CameraCharacteristics, m: Mode, fx: Float, fy: Float): MeteringRectangle {
        val a = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)
        val aspect = m.w.toFloat() / m.h
        var cw = a.width()
        var ch = (cw / aspect).toInt()
        if (ch > a.height()) { ch = a.height(); cw = (ch * aspect).toInt() }
        val left = a.left + (a.width() - cw) / 2
        val top = a.top + (a.height() - ch) / 2
        val cx = left + (fx * cw).toInt()
        val cy = top + (fy * ch).toInt()
        val half = (ch * 0.08f).toInt().coerceAtLeast(16)
        val x0 = (cx - half).coerceIn(a.left, a.right - 2 * half - 1)
        val y0 = (cy - half).coerceIn(a.top, a.bottom - 2 * half - 1)
        return MeteringRectangle(x0, y0, 2 * half, 2 * half, MeteringRectangle.METERING_WEIGHT_MAX)
    }

    private fun createSession(hlg: Boolean) {
        val dev = device ?: return
        val s = surface ?: return
        try {
            val oc = OutputConfiguration(s)
            if (hlg && Build.VERSION.SDK_INT >= 33) oc.setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
            val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(oc), executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(sess: CameraCaptureSession) {
                        session = sess
                        currentHlg = hlg
                        applyRequest()
                        main.post { cbReady?.invoke(hlg) }
                    }
                    override fun onConfigureFailed(sess: CameraCaptureSession) {
                        if (eisFor(settings) != CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF) {
                            settings = settings.copy(stab = 1)
                            main.post {
                                cbStab?.invoke(1)
                                cbError?.invoke("That stabilization mode isn't supported at this setting - using OIS")
                            }
                            createSession(hlg)
                        } else if (hlg) createSession(false)
                        else main.post { cbError?.invoke("Camera session configuration failed") }
                    }
                })
            try {
                val sp = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                sp.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, eisFor(settings))
                cfg.sessionParameters = sp.build()
            } catch (_: Throwable) { }
            dev.createCaptureSession(cfg)
        } catch (t: Throwable) {
            if (hlg) createSession(false) else main.post { cbError?.invoke("Session error: $t") }
        }
    }

    fun update(s: ManualSettings) {
        val prevEis = eisFor(settings)
        settings = s
        handler.post {
            if (eisFor(s) != prevEis && session != null) createSession(currentHlg) else applyRequest()
        }
    }

    private fun applyRequest() {
        val dev = device ?: return
        val sess = session ?: return
        val m = mode ?: return
        val c = chars ?: return
        val tgt = surface ?: return
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            b.addTarget(tgt)
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, m.range)

            // keep the image as unprocessed as the HAL allows
            if (c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                    ?.contains(CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL) == true)
                b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL)
            if (c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.contains(CaptureRequest.EDGE_MODE_OFF) == true)
                b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, eisFor(settings))
            if (c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                    ?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true)
                b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    if (oisFor(settings)) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)

            // exposure
            if (settings.autoExposure) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            } else {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                val isoR = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val expR = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val frameNs = 1_000_000_000L / m.fps
                var exp = settings.exposureNs.coerceAtMost(frameNs)
                if (expR != null) exp = exp.coerceIn(expR.lower, expR.upper)
                val iso = if (isoR != null) settings.iso.coerceIn(isoR.lower, isoR.upper) else settings.iso
                b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
            }

            // focus
            if (settings.autoFocus) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            } else {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, settings.focusDiopters)
            }

            // white balance
            b.set(CaptureRequest.CONTROL_AWB_MODE, settings.awbMode)

            if (settings.focusX >= 0f && settings.focusY >= 0f) {
                val r = meteringRect(c, m, settings.focusX, settings.focusY)
                if (settings.autoFocus && (c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0)
                    b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(r))
                if (settings.autoExposure && (c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0)
                    b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(r))
            }

            sess.setRepeatingRequest(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    if (++frameCounter % 10 == 0) {
                        val iso = res.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
                        val exp = res.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
                        main.post { cbResult?.invoke(iso, exp) }
                    }
                }
            }, handler)
        } catch (t: Throwable) {
            Log.e(TAG, "applyRequest", t)
        }
    }

    fun close() {
        try { session?.close() } catch (_: Throwable) {}
        try { device?.close() } catch (_: Throwable) {}
        session = null; device = null
    }

    fun shutdown() {
        close()
        thread.quitSafely()
    }

    companion object { private const val TAG = "LogCam.Cam" }
}
