package com.logcam.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

class MainActivity : Activity(), SurfaceHolder.Callback {

    private enum class Sel { NONE, ISO, SS, FOCUS, EV }

    private lateinit var camera: CameraController
    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())

    // views
    private lateinit var preview: SurfaceView
    private lateinit var focusRing: View
    private lateinit var tvInfo: TextView
    private lateinit var tvInfo2: TextView
    private lateinit var tvTimer: TextView
    private lateinit var btnRecord: ImageView
    private lateinit var btnSettings: ImageView
    private lateinit var sliderBar: View
    private lateinit var sbMain: SeekBar
    private lateinit var tvAuto: TextView
    private lateinit var tvSlider: TextView
    private lateinit var pillLens: TextView
    private lateinit var pillIso: TextView
    private lateinit var pillShutter: TextView
    private lateinit var pillWb: TextView
    private lateinit var pillFocus: TextView
    private lateinit var pillEv: TextView
    private lateinit var settingsPanel: View
    private lateinit var vRes: TextView
    private lateinit var vGamut: TextView
    private lateinit var vStab: TextView
    private lateinit var vTen: TextView
    private lateinit var vAudio: TextView
    private lateinit var vAssist: TextView
    private lateinit var vRot: TextView
    private lateinit var btnLibrary: ImageView
    private lateinit var libraryPanel: View
    private lateinit var libraryList: LinearLayout
    private lateinit var tvLibEmpty: TextView
    private lateinit var btnLibClose: TextView
    private var stateLoaded = false

    private class Clip(val uri: Uri, val name: String, val dateMs: Long, val size: Long, val durMs: Long, val path: String)

    // device info
    private var lenses: List<LensInfo> = emptyList()
    private var modes: List<Mode> = emptyList()
    private var wbModes: List<Pair<Int, String>> = emptyList()
    private var stabOptions: List<Pair<Int, String>> = listOf(0 to "Off")
    private var isoMin = 50
    private var isoMax = 3200
    private var minFocus = 0f
    private val shutterDen = intArrayOf(24, 25, 30, 48, 50, 60, 96, 100, 120, 125, 200, 250, 500, 1000, 2000, 4000)

    // user state
    private var lensIdx = 0
    private var modeIdx = 0
    private var wbIdx = 0
    private var stabIdx = 0
    private var gamutCine = true
    private var tenBitPref = true
    private var audioPref = true
    private var assistPref = false
    private var autoExp = true
    private var autoFocus = true
    private var isoProgress = 20
    private var shutterIdx = 0
    private var focusProgress = 0
    private var evProgress = 12
    private var focusX = -1f
    private var focusY = -1f
    private var selected = Sel.NONE
    private var lastIso = 0
    private var lastExpNs = 0L

    // runtime
    private var surfaceReady = false
    private var hasCamPerm = false
    private var running = false
    private var gl: GlPipeline? = null
    private var camSurface: Surface? = null
    private var activeHlg = false
    private var activeTenBit = false
    private var recorder: Recorder? = null
    private var recording = false
    private var recStartMs = 0L

    private val AMBER = Color.parseColor("#FFC23C")

    private val timerRunnable = object : Runnable {
        override fun run() {
            val s = (SystemClock.elapsedRealtime() - recStartMs) / 1000
            tvTimer.text = String.format(Locale.US, "● %02d:%02d", s / 60, s % 60)
            ui.postDelayed(this, 500)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) { applyDisplayRotation() }
    }

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("logcam", MODE_PRIVATE)
        camera = CameraController(this)
        bindViews()

        loadFlags()
        lenses = try { camera.backCameras() } catch (t: Throwable) { emptyList() }
        lensIdx = lenses.indexOfFirst { it.id == prefs.getString("lensId", "") }.coerceAtLeast(0)
        if (lenses.isNotEmpty()) rebuildModes()
        loadValues()
        stateLoaded = true

        wire()

        camera.cbReady = { hlg -> activeHlg = hlg; gl?.sourceHlg = hlg; updateAll() }
        camera.cbError = { msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        camera.cbStab = { code -> stabIdx = stabOptions.indexOfFirst { it.first == code }.coerceAtLeast(0); updateAll() }
        camera.cbResult = { iso, expNs ->
            lastIso = iso; lastExpNs = expNs
            updatePills(); updateSliderLabel()
        }
        updateAll()
        requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (code == 2) { showLibrary(); return }
        hasCamPerm = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCamPerm) Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        startPipeline()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        refreshLastThumb()
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, null)
        hasCamPerm = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        startPipeline()
    }

    override fun onPause() {
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        stopPipeline()
        super.onPause()
    }

    override fun onDestroy() {
        camera.shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyDisplayRotation()
    }

    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; startPipeline() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { stopPipeline(); surfaceReady = false }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.hide(WindowInsets.Type.systemBars())
    }

    private fun applyDisplayRotation() {
        val d = displayRotationDeg()
        gl?.displayRotationDeg = d
        gl?.manualQuarterTurns = manualFor(d)
        updateRows()
    }

    // ------------------------------------------------------------------ pipeline
    private fun startPipeline() {
        if (running || !surfaceReady || !hasCamPerm) return
        val lens = lenses.getOrNull(lensIdx) ?: return
        val mode = modes.getOrNull(modeIdx) ?: return
        running = true
        val g = GlPipeline(preview.holder.surface, tenBitPref)
        gl = g
        g.monitorRec709 = assistPref
        g.useCineGamut = gamutCine
        g.evStops = (evProgress - 12) / 4f
        g.displayRotationDeg = displayRotationDeg()
        g.sensorOrientation = camera.characteristics(lens.id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        g.manualQuarterTurns = manualFor(displayRotationDeg())
        g.start(mode.w, mode.h, { st, tenBit ->
            if (gl !== g) return@start
            activeTenBit = tenBit
            val s = Surface(st)
            camSurface = s
            camera.open(lens.id, mode, s, tenBitPref)
            pushSettings()
            updateAll()
        }, { t ->
            running = false
            Toast.makeText(this, "GL error: ${t.message}", Toast.LENGTH_LONG).show()
        })
    }

    private fun stopPipeline() {
        if (!running) return
        if (recording) stopRecording()
        camera.close()
        gl?.release(); gl = null
        camSurface?.release(); camSurface = null
        running = false
    }

    private fun restartPipeline() { stopPipeline(); startPipeline() }

    // ------------------------------------------------------------------ recording
    private fun startRecording() {
        val g = gl ?: return
        val lens = lenses.getOrNull(lensIdx) ?: return
        val mode = modes.getOrNull(modeIdx) ?: return
        val realtime = camera.timestampIsRealtime(lens.id)
        val nowFn: () -> Long = if (realtime) { { SystemClock.elapsedRealtimeNanos() } } else { { System.nanoTime() } }
        val t0 = nowFn()
        val audio = audioPref && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val rec = Recorder(this, mode.w, mode.h, mode.fps, g.isTenBit, audio, nowFn, t0)
        try {
            rec.prepare()
            rec.start()
        } catch (t: Throwable) {
            rec.abort()
            Toast.makeText(this, "Encoder error: ${t.message}. Try turning off 10-bit.", Toast.LENGTH_LONG).show()
            return
        }
        recorder = rec
        g.startRecording(rec.inputSurface, mode.w, mode.h, t0, displayRotationDeg()) {
            recording = true
            recStartMs = SystemClock.elapsedRealtime()
            btnRecord.setImageResource(R.drawable.rec_active)
            tvTimer.visibility = View.VISIBLE
            ui.post(timerRunnable)
            updateAll()
        }
    }

    private fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        recording = false
        ui.removeCallbacks(timerRunnable)
        tvTimer.visibility = View.GONE
        btnRecord.setImageResource(R.drawable.rec_idle)
        val finish = {
            Thread {
                val saved = rec.stop()
                runOnUiThread {
                    Toast.makeText(this, if (saved != null) "Saved to DCIM/LogCam" else "Nothing was recorded", Toast.LENGTH_SHORT).show()
                    refreshLastThumb()
                }
            }.start()
            Unit
        }
        val g = gl
        if (g != null) g.stopRecording { finish() } else finish()
        updateAll()
    }

    private fun lockedToast() = Toast.makeText(this, "Stop recording first", Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ wiring
    private fun bindViews() {
        preview = findViewById(R.id.preview)
        preview.holder.addCallback(this)
        focusRing = findViewById(R.id.focusRing)
        tvInfo = findViewById(R.id.tvInfo); tvInfo2 = findViewById(R.id.tvInfo2)
        tvTimer = findViewById(R.id.tvTimer)
        btnRecord = findViewById(R.id.btnRecord); btnSettings = findViewById(R.id.btnSettings)
        sliderBar = findViewById(R.id.sliderBar); sbMain = findViewById(R.id.sbMain)
        tvAuto = findViewById(R.id.tvAuto); tvSlider = findViewById(R.id.tvSlider)
        pillLens = findViewById(R.id.pillLens); pillIso = findViewById(R.id.pillIso)
        pillShutter = findViewById(R.id.pillShutter); pillWb = findViewById(R.id.pillWb)
        pillFocus = findViewById(R.id.pillFocus); pillEv = findViewById(R.id.pillEv)
        settingsPanel = findViewById(R.id.settingsPanel)
        vRes = findViewById(R.id.vRes); vGamut = findViewById(R.id.vGamut); vStab = findViewById(R.id.vStab)
        vTen = findViewById(R.id.vTen); vAudio = findViewById(R.id.vAudio)
        vAssist = findViewById(R.id.vAssist); vRot = findViewById(R.id.vRot)
        btnLibrary = findViewById(R.id.btnLibrary); libraryPanel = findViewById(R.id.libraryPanel)
        libraryList = findViewById(R.id.libraryList); tvLibEmpty = findViewById(R.id.tvLibEmpty)
        btnLibClose = findViewById(R.id.btnLibClose)
        btnLibrary.clipToOutline = true

        sbMain.progressTintList = ColorStateList.valueOf(Color.WHITE)
        sbMain.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        sbMain.progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#66FFFFFF"))
    }

    private fun wire() {
        btnRecord.setOnClickListener { if (recording) stopRecording() else startRecording() }
        btnLibrary.setOnClickListener { openLibrary() }
        btnLibClose.setOnClickListener { libraryPanel.visibility = View.GONE }
        btnSettings.setOnClickListener {
            settingsPanel.visibility = if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        preview.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) onPreviewTap(e.x, e.y)
            true
        }

        // bottom pills
        pillLens.setOnClickListener {
            if (recording) lockedToast()
            else if (lenses.size > 1) { lensIdx = (lensIdx + 1) % lenses.size; rebuildModes(); restartPipeline() }
        }
        pillWb.setOnClickListener {
            if (wbModes.isNotEmpty()) { wbIdx = (wbIdx + 1) % wbModes.size; pushSettings(); updateAll() }
        }
        pillIso.setOnClickListener { toggleSel(Sel.ISO) }
        pillShutter.setOnClickListener { toggleSel(Sel.SS) }
        pillFocus.setOnClickListener { toggleSel(Sel.FOCUS) }
        pillEv.setOnClickListener { toggleSel(Sel.EV) }

        tvAuto.setOnClickListener {
            when (selected) {
                Sel.ISO, Sel.SS -> { if (autoExp) seedManual(); autoExp = !autoExp }
                Sel.FOCUS -> if (minFocus > 0f) { autoFocus = !autoFocus; if (autoFocus) focusX = -1f }
                else -> {}
            }
            pushSettings(); refreshSlider(); updateAll()
        }

        sbMain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                if ((selected == Sel.ISO || selected == Sel.SS) && autoExp) { seedManual(); autoExp = false; styleAuto(false) }
                when (selected) {
                    Sel.ISO -> isoProgress = p
                    Sel.SS -> shutterIdx = p
                    Sel.FOCUS -> { focusProgress = p; autoFocus = false; focusX = -1f; styleAuto(false) }
                    Sel.EV -> evProgress = p
                    else -> {}
                }
                pushSettings(); updatePills(); updateSliderLabel()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // settings sheet
        findViewById<View>(R.id.rowRes).setOnClickListener {
            if (recording) { lockedToast(); return@setOnClickListener }
            if (modes.isNotEmpty()) {
                modeIdx = (modeIdx + 1) % modes.size
                focusX = -1f; defaultShutter(); restartPipeline(); updateAll()
            }
        }
        findViewById<View>(R.id.rowGamut).setOnClickListener {
            gamutCine = !gamutCine; gl?.useCineGamut = gamutCine; updateAll()
        }
        findViewById<View>(R.id.rowStab).setOnClickListener {
            if (recording) { lockedToast(); return@setOnClickListener }
            stabIdx = (stabIdx + 1) % stabOptions.size; pushSettings(); updateAll()
        }
        findViewById<View>(R.id.rowTen).setOnClickListener {
            if (recording) { lockedToast(); return@setOnClickListener }
            if (!supportsMain10()) { Toast.makeText(this, "10-bit needs Android 13+ and encoder support", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            tenBitPref = !tenBitPref; restartPipeline(); updateAll()
        }
        findViewById<View>(R.id.rowAudio).setOnClickListener {
            if (recording) { lockedToast(); return@setOnClickListener }
            audioPref = !audioPref; updateAll()
        }
        findViewById<View>(R.id.rowAssist).setOnClickListener {
            assistPref = !assistPref; gl?.monitorRec709 = assistPref; updateAll()
        }
        findViewById<View>(R.id.rowRot).setOnClickListener {
            if (recording) { lockedToast(); return@setOnClickListener }
            val d = displayRotationDeg()
            val n = (manualFor(d) + 1) and 3
            prefs.edit().putInt("rot$d", n).apply()
            gl?.manualQuarterTurns = n
            updateRows()
        }
    }

    private fun toggleSel(s: Sel) {
        selected = if (selected == s) Sel.NONE else s
        refreshSlider(); updateAll()
    }

    // ------------------------------------------------------------------ tap to focus
    private fun onPreviewTap(x: Float, y: Float) {
        if (settingsPanel.visibility == View.VISIBLE) { settingsPanel.visibility = View.GONE; return }
        val g = gl ?: return
        val mode = modes.getOrNull(modeIdx) ?: return
        val vw = preview.width.toFloat(); val vh = preview.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val ca = mode.w.toFloat() / mode.h
        val cw: Float; val ch: Float; val left: Float; val top: Float
        if (vw / vh > ca) { ch = vh; cw = vh * ca; left = (vw - cw) / 2f; top = 0f }
        else { cw = vw; ch = vw / ca; left = 0f; top = (vh - ch) / 2f }
        val nx = (x - left) / cw
        val ny = (y - top) / ch
        if (nx !in 0f..1f || ny !in 0f..1f) return

        // undo the on-screen rotation to get a point in the raw sensor frame
        val (rx, ry) = when (g.totalRotationDeg()) {
            90 -> ny to (1f - nx)
            180 -> (1f - nx) to (1f - ny)
            270 -> (1f - ny) to nx
            else -> nx to ny
        }
        focusX = rx; focusY = ry
        autoFocus = true
        showFocusRing(x, y)
        pushSettings(); updateAll()
        if (selected == Sel.FOCUS) refreshSlider()
    }

    private fun showFocusRing(x: Float, y: Float) {
        val size = if (focusRing.width > 0) focusRing.width else (76 * resources.displayMetrics.density).toInt()
        focusRing.animate().cancel()
        focusRing.x = x - size / 2f
        focusRing.y = y - size / 2f
        focusRing.alpha = 1f
        focusRing.scaleX = 1.4f; focusRing.scaleY = 1.4f
        focusRing.visibility = View.VISIBLE
        focusRing.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(180).withEndAction {
            focusRing.animate().alpha(0f).setStartDelay(900).setDuration(300).withEndAction {
                focusRing.visibility = View.GONE
                focusRing.alpha = 1f
            }.start()
        }.start()
    }

    // ------------------------------------------------------------------ state -> camera
    private fun rebuildModes() {
        val lens = lenses.getOrNull(lensIdx) ?: return
        modes = try { camera.modes(lens.id) } catch (t: Throwable) { emptyList() }
        val savedMode = prefs.getString("mode", "")
        modeIdx = modes.indexOfFirst { "${it.w},${it.h},${it.fps}" == savedMode }.takeIf { it >= 0 }
            ?: modes.indexOfFirst { it.w >= 3840 && it.fps == 30 }.takeIf { it >= 0 } ?: 0

        val c = camera.characteristics(lens.id)
        c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { isoMin = it.lower; isoMax = it.upper }
        minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        if (minFocus <= 0f) autoFocus = true

        val wanted = listOf(
            CaptureRequest.CONTROL_AWB_MODE_AUTO to "WB Auto",
            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT to "WB Daylight",
            CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "WB Cloudy",
            CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT to "WB Tungsten",
            CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT to "WB Fluor."
        )
        val avail = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet() ?: setOf(CaptureRequest.CONTROL_AWB_MODE_AUTO)
        wbModes = wanted.filter { avail.contains(it.first) }
        wbIdx = wbModes.indexOfFirst { it.first == prefs.getInt("wb", CaptureRequest.CONTROL_AWB_MODE_AUTO) }.coerceAtLeast(0)

        stabOptions = try { camera.stabOptions(lens.id) } catch (t: Throwable) { listOf(0 to "Off") }
        stabIdx = stabOptions.indexOfFirst { it.first == prefs.getInt("stab", 1) }.takeIf { it >= 0 }
            ?: stabOptions.indexOfFirst { it.first == 1 }.takeIf { it >= 0 } ?: 0

        focusX = -1f; focusY = -1f
        lastIso = 0; lastExpNs = 0L
        defaultShutter()
        updateAll()
    }

    private fun defaultShutter() {
        val fps = modes.getOrNull(modeIdx)?.fps ?: 30
        shutterIdx = shutterDen.indexOfFirst { it >= fps * 2 }.takeIf { it >= 0 } ?: 0
    }

    private fun isoValue(): Int {
        val lo = ln(isoMin.toDouble()); val hi = ln(isoMax.toDouble())
        return exp(lo + (hi - lo) * isoProgress / 100.0).toInt()
    }

    /** When leaving auto exposure, start the manual values from what auto was using. */
    private fun seedManual() {
        if (lastIso > 0 && isoMax > isoMin) {
            val lo = ln(isoMin.toDouble()); val hi = ln(isoMax.toDouble())
            val v = ln(lastIso.toDouble().coerceIn(isoMin.toDouble(), isoMax.toDouble()))
            isoProgress = (100.0 * (v - lo) / (hi - lo)).roundToInt().coerceIn(0, 100)
        }
        if (lastExpNs > 0) {
            val den = 1e9 / lastExpNs
            shutterIdx = shutterDen.indices.minByOrNull { abs(shutterDen[it] - den) } ?: shutterIdx
        }
    }

    private fun pushSettings() {
        val den = shutterDen[shutterIdx.coerceIn(0, shutterDen.size - 1)]
        val ev = (evProgress - 12) / 4f
        gl?.evStops = ev
        camera.update(ManualSettings(
            autoExposure = autoExp,
            iso = isoValue(),
            exposureNs = 1_000_000_000L / den,
            autoFocus = autoFocus || minFocus <= 0f,
            focusDiopters = minFocus * focusProgress / 100f,
            awbMode = wbModes.getOrNull(wbIdx)?.first ?: CaptureRequest.CONTROL_AWB_MODE_AUTO,
            stab = stabOptions.getOrNull(stabIdx)?.first ?: 0,
            focusX = focusX,
            focusY = focusY
        ))
    }

    // ------------------------------------------------------------------ UI refresh
    private fun updateAll() {
        updateInfo(); updatePills(); updateRows(); updateSliderLabel()
        if (stateLoaded) saveState()
    }

    private fun updateInfo() {
        val m = modes.getOrNull(modeIdx)
        tvInfo.text = (m?.label ?: "—") + "  ·  S-Log3  ·  " + (if (gamutCine) "S-Gamut3.Cine" else "S-Gamut3")
        tvInfo2.text = (if (activeHlg) "HLG10" else "SDR") + " → " + (if (activeTenBit) "10-bit" else "8-bit") + " HEVC"
    }

    private fun stylePill(v: TextView, sel: Boolean, manual: Boolean) {
        v.setBackgroundResource(if (sel) R.drawable.bg_pill_selected else R.drawable.bg_pill)
        v.setTextColor(if (sel) Color.BLACK else if (manual) AMBER else Color.WHITE)
    }

    private fun styleAuto(on: Boolean) {
        tvAuto.setBackgroundResource(if (on) R.drawable.bg_pill_selected else R.drawable.bg_pill)
        tvAuto.setTextColor(if (on) Color.BLACK else Color.WHITE)
    }

    private fun focusText(): String {
        if (autoFocus) return "AF"
        if (focusProgress == 0) return "MF ∞"
        val d = (minFocus * focusProgress / 100f).coerceAtLeast(0.01f)
        return String.format(Locale.US, "MF %.1fm", 1f / d)
    }

    private fun shownIso() = if (autoExp && lastIso > 0) lastIso else isoValue()
    private fun shownDen() =
        if (autoExp && lastExpNs > 0) (1e9 / lastExpNs).roundToInt() else shutterDen[shutterIdx.coerceIn(0, shutterDen.size - 1)]

    private fun updatePills() {
        val ev = (evProgress - 12) / 4f
        pillLens.text = lenses.getOrNull(lensIdx)?.label?.substringAfterLast("·")?.trim() ?: "—"
        pillIso.text = "ISO ${shownIso()}"
        pillShutter.text = "1/${shownDen()}"
        pillWb.text = wbModes.getOrNull(wbIdx)?.second ?: "WB Auto"
        pillFocus.text = focusText()
        pillEv.text = String.format(Locale.US, "EV %+.1f", ev)

        stylePill(pillLens, false, false)
        stylePill(pillIso, selected == Sel.ISO, !autoExp)
        stylePill(pillShutter, selected == Sel.SS, !autoExp)
        stylePill(pillWb, false, wbIdx != 0)
        stylePill(pillFocus, selected == Sel.FOCUS, !autoFocus)
        stylePill(pillEv, selected == Sel.EV, evProgress != 12)
    }

    private fun updateRows() {
        vRes.text = modes.getOrNull(modeIdx)?.label ?: "—"
        vGamut.text = if (gamutCine) "S-Gamut3.Cine" else "S-Gamut3"
        vStab.text = stabOptions.getOrNull(stabIdx)?.second ?: "Off"
        vTen.text = if (tenBitPref) "On" else "Off"
        vAudio.text = if (audioPref) "On" else "Off"
        vAssist.text = if (assistPref) "On" else "Off"
        vRot.text = "${manualFor(displayRotationDeg()) * 90}°"
    }

    private fun refreshSlider() {
        if (selected == Sel.NONE) { sliderBar.visibility = View.GONE; return }
        sliderBar.visibility = View.VISIBLE
        tvAuto.visibility = View.VISIBLE
        when (selected) {
            Sel.ISO -> { sbMain.max = 100; sbMain.progress = isoProgress; tvAuto.text = "AUTO"; styleAuto(autoExp) }
            Sel.SS -> { sbMain.max = shutterDen.size - 1; sbMain.progress = shutterIdx; tvAuto.text = "AUTO"; styleAuto(autoExp) }
            Sel.FOCUS -> { sbMain.max = 100; sbMain.progress = focusProgress; tvAuto.text = "AF"; styleAuto(autoFocus) }
            Sel.EV -> { sbMain.max = 24; sbMain.progress = evProgress; tvAuto.visibility = View.GONE }
            else -> {}
        }
        updateSliderLabel()
    }

    private fun updateSliderLabel() {
        tvSlider.text = when (selected) {
            Sel.ISO -> "ISO ${shownIso()}"
            Sel.SS -> "1/${shownDen()}"
            Sel.FOCUS -> focusText()
            Sel.EV -> String.format(Locale.US, "%+.2f EV", (evProgress - 12) / 4f)
            else -> ""
        }
    }

    // ------------------------------------------------------------------ saved settings
    private fun loadFlags() {
        gamutCine = prefs.getBoolean("gamut", true)
        tenBitPref = prefs.getBoolean("ten", true) && supportsMain10()
        audioPref = prefs.getBoolean("audio", true)
        assistPref = prefs.getBoolean("assist", false)
    }

    private fun loadValues() {
        isoProgress = prefs.getInt("iso", isoProgress).coerceIn(0, 100)
        shutterIdx = prefs.getInt("shutter", shutterIdx).coerceIn(0, shutterDen.size - 1)
        focusProgress = prefs.getInt("focus", focusProgress).coerceIn(0, 100)
        evProgress = prefs.getInt("ev", evProgress).coerceIn(0, 24)
        autoExp = prefs.getBoolean("autoExp", true)
        autoFocus = prefs.getBoolean("autoFocus", true) || minFocus <= 0f
    }

    private fun saveState() {
        prefs.edit()
            .putString("lensId", lenses.getOrNull(lensIdx)?.id ?: "")
            .putString("mode", modes.getOrNull(modeIdx)?.let { "${it.w},${it.h},${it.fps}" } ?: "")
            .putInt("wb", wbModes.getOrNull(wbIdx)?.first ?: CaptureRequest.CONTROL_AWB_MODE_AUTO)
            .putInt("stab", stabOptions.getOrNull(stabIdx)?.first ?: 1)
            .putBoolean("gamut", gamutCine)
            .putBoolean("ten", tenBitPref)
            .putBoolean("audio", audioPref)
            .putBoolean("assist", assistPref)
            .putBoolean("autoExp", autoExp)
            .putBoolean("autoFocus", autoFocus)
            .putInt("iso", isoProgress)
            .putInt("shutter", shutterIdx)
            .putInt("focus", focusProgress)
            .putInt("ev", evProgress)
            .apply()
    }

    // ------------------------------------------------------------------ recordings library
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun openLibrary() {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED && !prefs.getBoolean("libAsked", false)) {
            prefs.edit().putBoolean("libAsked", true).apply()
            requestPermissions(arrayOf(perm), 2)
            return
        }
        showLibrary()
    }

    private fun showLibrary() {
        settingsPanel.visibility = View.GONE
        libraryPanel.visibility = View.VISIBLE
        libraryList.removeAllViews()
        Thread {
            val clips = try { queryClips() } catch (t: Throwable) { emptyList() }
            runOnUiThread {
                tvLibEmpty.visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
                for (c in clips) libraryList.addView(buildRow(c))
            }
        }.start()
    }

    private fun queryClips(): List<Clip> {
        val out = ArrayList<Clip>()
        val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.RELATIVE_PATH
        )
        contentResolver.query(base, proj, MediaStore.Video.Media.DISPLAY_NAME + " LIKE ?", arrayOf("LOG_%"),
            MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { c ->
            while (c.moveToNext() && out.size < 60) {
                out.add(Clip(
                    ContentUris.withAppendedId(base, c.getLong(0)),
                    c.getString(1) ?: "video",
                    c.getLong(2) * 1000L,
                    c.getLong(3),
                    c.getLong(4),
                    c.getString(5) ?: ""
                ))
            }
        }
        return out
    }

    private fun buildRow(c: Clip): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(28), dp(8), dp(28), dp(8))
        row.isClickable = true

        val iv = ImageView(this)
        iv.layoutParams = LinearLayout.LayoutParams(dp(128), dp(72))
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.setBackgroundColor(Color.parseColor("#22FFFFFF"))

        val tv = TextView(this)
        tv.setTextColor(Color.WHITE)
        tv.textSize = 13f
        tv.setPadding(dp(16), 0, 0, 0)
        val dur = c.durMs / 1000
        tv.text = c.name + "\n" +
            SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(c.dateMs)) +
            "  ·  " + String.format(Locale.US, "%d:%02d", dur / 60, dur % 60) +
            "  ·  " + String.format(Locale.US, "%.0f MB", c.size / 1048576.0) + "\n" + c.path

        row.addView(iv)
        row.addView(tv)
        row.setOnClickListener { openClip(c.uri) }
        row.setOnLongClickListener { confirmDelete(c); true }

        Thread {
            val bmp = try { contentResolver.loadThumbnail(c.uri, Size(320, 180), null) } catch (t: Throwable) { null }
            if (bmp != null) runOnUiThread { iv.setImageBitmap(bmp) }
        }.start()
        return row
    }

    private fun openClip(uri: Uri) {
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, "video/*")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(i, "Open with")) }
        catch (t: Throwable) { Toast.makeText(this, "No app found to play this video", Toast.LENGTH_SHORT).show() }
    }

    private fun confirmDelete(c: Clip) {
        AlertDialog.Builder(this)
            .setTitle("Delete recording?")
            .setMessage(c.name)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                try {
                    contentResolver.delete(c.uri, null, null)
                    showLibrary(); refreshLastThumb()
                } catch (t: Throwable) {
                    Toast.makeText(this, "Can't delete this one here - delete it from your Gallery", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun refreshLastThumb() {
        Thread {
            val c = try { queryClips().firstOrNull() } catch (t: Throwable) { null }
            val bmp = if (c != null) try { contentResolver.loadThumbnail(c.uri, Size(160, 160), null) } catch (t: Throwable) { null } else null
            runOnUiThread {
                if (bmp != null) {
                    btnLibrary.setPadding(0, 0, 0, 0)
                    btnLibrary.scaleType = ImageView.ScaleType.CENTER_CROP
                    btnLibrary.setImageBitmap(bmp)
                } else {
                    btnLibrary.setPadding(dp(13), dp(13), dp(13), dp(13))
                    btnLibrary.scaleType = ImageView.ScaleType.FIT_CENTER
                    btnLibrary.setImageResource(R.drawable.ic_library)
                }
            }
        }.start()
    }

    override fun onBackPressed() {
        when {
            libraryPanel.visibility == View.VISIBLE -> libraryPanel.visibility = View.GONE
            settingsPanel.visibility == View.VISIBLE -> settingsPanel.visibility = View.GONE
            else -> super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ helpers
    private fun manualFor(deg: Int) = prefs.getInt("rot$deg", 0)

    private fun displayRotationDeg(): Int {
        val rot = if (Build.VERSION.SDK_INT >= 30) (display?.rotation ?: 0)
        else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        return when (rot) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
    }

    private fun supportsMain10(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.contains("video/hevc") &&
                info.getCapabilitiesForType("video/hevc").profileLevels.any {
                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                }
        }
    }
}
