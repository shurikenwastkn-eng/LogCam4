package com.logcam.app

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.ScrollView
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

class MainActivity : Activity(), SurfaceHolder.Callback {

    private enum class Sel { NONE, ISO, SS, FOCUS, EV, ZOOM }

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
    private lateinit var verticalControlPanel: View
    private lateinit var verticalSlider: VerticalValueSlider
    private lateinit var tvVerticalTitle: TextView
    private lateinit var btnVerticalAuto: TextView
    private lateinit var btnZoomPlus: TextView
    private lateinit var btnZoomMinus: TextView
    private lateinit var sbMain: SeekBar
    private lateinit var tvAuto: TextView
    private lateinit var tvSlider: TextView
    private lateinit var pillZoom: TextView
    private lateinit var pillIso: TextView
    private lateinit var pillShutter: TextView
    private lateinit var pillWb: TextView
    private lateinit var pillFocus: TextView
    private lateinit var pillEv: TextView
    private lateinit var settingsPanel: View
    private lateinit var exposureControls: View
    private lateinit var vControlPosition: TextView
    private lateinit var vAccent: TextView
    private var lastLayoutKey = ""
    private val MP = LinearLayout.LayoutParams.MATCH_PARENT
    private val WC = LinearLayout.LayoutParams.WRAP_CONTENT
    private var controlPosition = "Side"
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
    private var zoomButtonDirection = 0
    private var lastUpdateCheckAt = 0L
    @Volatile private var updateCheckInFlight = false

    private lateinit var levelView: LevelView
    private lateinit var tvLock: TextView
    private lateinit var btnSetClose: TextView
    private lateinit var vGrid: TextView
    private lateinit var vLevel: TextView
    private lateinit var libTitle: TextView
    private lateinit var libActions: View
    private lateinit var btnLibShare: TextView
    private lateinit var btnLibExport: TextView
    private lateinit var btnLibDelete: TextView

    private var focusLocked = false
    private var gridIdx = 0
    private val gridNames = listOf("Off", "Rule of thirds", "Thirds + diagonals", "Square grid", "Square grid + diagonals", "Center cross")
    private var levelOn = false

    private val selClips = LinkedHashMap<Uri, Clip>()
    private val libSel = HashMap<Uri, View>()
    private var libClips: List<Clip> = emptyList()
    private val thumbPool = Executors.newFixedThreadPool(3)

    private lateinit var sensors: SensorManager
    private val grav = FloatArray(3)
    private var gravInit = false

    private lateinit var guideView: GuideView
    private lateinit var recBorder: View
    private lateinit var zoomChips: LinearLayout
    private lateinit var vGuide: TextView
    private lateinit var vZoomSpeed: TextView

    private class Chip(val factor: Float, val lensIdx: Int, val zoom: Float)
    private var chips: List<Chip> = emptyList()
    private val chipViews = ArrayList<TextView>()
    private var zoomMin = 1f
    private var zoomMax = 1f
    private var zoom = 1f
    private var zoomShown = 1f
    private var curLensFactor = 1f
    private var zoomSpeedIdx = 1
    private val zoomSpeeds = floatArrayOf(0.25f, 0.6f, 1.5f, 0f)
    private val zoomSpeedNames = arrayOf("Slow", "Medium", "Fast", "Instant")

    private var guideIdx = 0
    private val guides = listOf(
        "Off" to 0f,
        "IMAX 1.43:1" to 1.43f,
        "IMAX Digital 1.90:1" to 1.90f,
        "Cinema Flat 1.85:1" to 1.85f,
        "Univisium 2:1" to 2.0f,
        "Cinema Scope 2.39:1" to 2.39f,
        "Ultra Panavision 2.76:1" to 2.76f,
        "4:3" to (4f / 3f),
        "1:1" to 1f
    )

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

    private val AMBER: Int get() = Theme.accent

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
        Theme.accent = prefs.getInt("accent", Theme.DEFAULT)
        camera = CameraController(this)
        bindViews()
        restyleTree(findViewById<View>(android.R.id.content))

        controlPosition = prefs.getString("controlPosition", "Side") ?: "Side"
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
        registerLevel()
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, null)
        hasCamPerm = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        startPipeline()
        checkForUpdates()
    }

    override fun onPause() {
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        sensors.unregisterListener(levelListener); gravInit = false
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
            recBorder.visibility = View.VISIBLE
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
        recBorder.visibility = View.GONE
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
        verticalControlPanel = findViewById(R.id.verticalControlPanel)
        verticalSlider = findViewById(R.id.verticalSlider)
        tvVerticalTitle = findViewById(R.id.tvVerticalTitle)
        btnVerticalAuto = findViewById(R.id.btnVerticalAuto)
        btnZoomPlus = findViewById(R.id.btnZoomPlus); btnZoomMinus = findViewById(R.id.btnZoomMinus)
        tvAuto = findViewById(R.id.tvAuto); tvSlider = findViewById(R.id.tvSlider)
        pillZoom = findViewById(R.id.pillZoom); pillIso = findViewById(R.id.pillIso)
        pillShutter = findViewById(R.id.pillShutter); pillWb = findViewById(R.id.pillWb)
        pillFocus = findViewById(R.id.pillFocus); pillEv = findViewById(R.id.pillEv)
        settingsPanel = findViewById(R.id.settingsPanel)
        settingsPanel.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(18).toFloat())
            }
        }
        settingsPanel.clipToOutline = true
        val settingsScroll = findViewById<ScrollView>(R.id.settingsPanel).let { panel ->
            (panel as? ViewGroup)?.let { group ->
                fun findScroll(v: View): ScrollView? {
                    if (v is ScrollView) return v
                    if (v is ViewGroup) for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
                    return null
                }
                findScroll(group)
            }
        }
        settingsScroll?.apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
            clipToOutline = true
            clipChildren = true
            clipToPadding = true
        }
        exposureControls = findViewById(R.id.exposureControls)
        vControlPosition = findViewById(R.id.vControlPosition)
        vAccent = findViewById(R.id.vAccent)
        vRes = findViewById(R.id.vRes); vGamut = findViewById(R.id.vGamut); vStab = findViewById(R.id.vStab)
        vTen = findViewById(R.id.vTen); vAudio = findViewById(R.id.vAudio)
        vAssist = findViewById(R.id.vAssist); vRot = findViewById(R.id.vRot)
        btnLibrary = findViewById(R.id.btnLibrary); libraryPanel = findViewById(R.id.libraryPanel)
        libraryList = findViewById(R.id.libraryList); tvLibEmpty = findViewById(R.id.tvLibEmpty)
        btnLibClose = findViewById(R.id.btnLibClose)
        levelView = findViewById(R.id.levelView); tvLock = findViewById(R.id.tvLock)
        btnSetClose = findViewById(R.id.btnSetClose)
        vGrid = findViewById(R.id.vGrid); vLevel = findViewById(R.id.vLevel)
        libTitle = findViewById(R.id.libTitle); libActions = findViewById(R.id.libActions)
        btnLibShare = findViewById(R.id.btnLibShare); btnLibExport = findViewById(R.id.btnLibExport)
        btnLibDelete = findViewById(R.id.btnLibDelete)
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        guideView = findViewById(R.id.guideView); recBorder = findViewById(R.id.recBorder)
        zoomChips = findViewById(R.id.zoomChips); vGuide = findViewById(R.id.vGuide); vZoomSpeed = findViewById(R.id.vZoomSpeed)
        btnLibrary.clipToOutline = true

        sbMain.progressTintList = ColorStateList.valueOf(Color.WHITE)
        sbMain.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        sbMain.progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#66FFFFFF"))
    }

    private fun wire() {
        btnRecord.setOnClickListener { dismissCameraControl(); if (recording) stopRecording() else startRecording() }
        btnLibrary.setOnClickListener { dismissCameraControl(); openLibrary() }
        btnLibClose.setOnClickListener { if (selClips.isNotEmpty()) clearSelection() else libraryPanel.visibility = View.GONE }
        btnLibShare.setOnClickListener { shareClips(selClips.values.toList()) }
        btnLibExport.setOnClickListener { exportClips(selClips.values.toList()) }
        btnLibDelete.setOnClickListener { confirmDelete(selClips.values.toList()) }
        btnSetClose.setOnClickListener { settingsPanel.visibility = View.GONE; dismissCameraControl() }
        btnVerticalAuto.setOnClickListener { toggleAutoForSelection() }
        verticalSlider.onProgressChanged = { p -> handleSliderProgress(p) }
        wireZoomButton(btnZoomPlus, 1)
        wireZoomButton(btnZoomMinus, -1)
        btnSettings.setOnClickListener {
            val show = settingsPanel.visibility != View.VISIBLE
            settingsPanel.visibility = if (show) View.VISIBLE else View.GONE
            if (show) dismissCameraControl()
        }
        // tap = focus here, hold = lock exposure + focus here
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean { onPreviewTap(e.x, e.y, false); return true }
            override fun onLongPress(e: MotionEvent) { onPreviewTap(e.x, e.y, true) }
        })
        preview.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) dismissCameraControl()
            gestures.onTouchEvent(e)
            true
        }

        // bottom pills
        pillZoom.setOnClickListener { toggleSel(Sel.ZOOM) }
        pillWb.setOnClickListener {
            if (wbModes.isNotEmpty()) {
                dismissCameraControl()
                settingsPanel.visibility = View.GONE
                libraryPanel.visibility = View.GONE
                showGlassChoice("White balance", wbModes.map { it.second }, wbIdx) { index ->
                    wbIdx = index; pushSettings(); updateAll()
                }
            }
        }
        pillIso.setOnClickListener { toggleSel(Sel.ISO) }
        pillShutter.setOnClickListener { toggleSel(Sel.SS) }
        pillFocus.setOnClickListener { toggleSel(Sel.FOCUS) }
        tvAuto.setOnClickListener { toggleAutoForSelection() }

        sbMain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                handleSliderProgress(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        // settings sheet
        findViewById<View>(R.id.rowRes).setOnClickListener {
            if (recording) { lockedToast(); return@setOnClickListener }
            showGlassChoice("Resolution / frame rate", modes.map { it.label }, modeIdx) { index ->
                if (index != modeIdx) {
                    modeIdx = index
                    prefs.edit().putString("mode", "${modes[index].w},${modes[index].h},${modes[index].fps}").apply()
                    focusX = -1f; clearLock(); defaultShutter(); restartPipeline(); updateAll()
                }
            }
        }
        findViewById<View>(R.id.rowGamut).setOnClickListener {
            val choices = listOf("S-Gamut3.Cine", "S-Gamut3")
            showGlassChoice("Color gamut", choices, if (gamutCine) 0 else 1) { index ->
                gamutCine = index == 0
                gl?.useCineGamut = gamutCine
                updateAll()
            }
        }
        findViewById<View>(R.id.rowStab).setOnClickListener {
            if (recording) { lockedToast(); return@setOnClickListener }
            showGlassChoice("Stabilization", stabOptions.map { it.second }, stabIdx) { index ->
                stabIdx = index; pushSettings(); updateAll()
            }
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
            val choices = listOf("Auto", "Rotate 90°", "Rotate 180°", "Rotate 270°")
            showGlassChoice("Fix orientation", choices, manualFor(d)) { index ->
                prefs.edit().putInt("rot$d", index).apply()
                gl?.manualQuarterTurns = index
                updateRows()
            }
        }
        findViewById<View>(R.id.rowGuide).setOnClickListener {
            showGlassChoice("Frame guide", guides.map { it.first }, guideIdx) { index -> guideIdx = index; updateAll() }
        }
        findViewById<View>(R.id.rowZoomSpeed).setOnClickListener {
            showGlassChoice("Zoom speed", zoomSpeedNames.toList(), zoomSpeedIdx) { index ->
                zoomSpeedIdx = index
                prefs.edit().putInt("zoomSpeed", index).apply()
                updateAll()
            }
        }
        findViewById<View>(R.id.rowGrid).setOnClickListener {
            showGlassChoice("Grid lines", gridNames, gridIdx) { index -> gridIdx = index; updateAll() }
        }
        findViewById<View>(R.id.rowControlPosition).setOnClickListener {
            val choices = listOf("Top", "Bottom", "Side")
            showGlassChoice("Control position", choices, choices.indexOf(controlPosition).coerceAtLeast(0)) { index ->
                controlPosition = choices[index]
                prefs.edit().putString("controlPosition", controlPosition).apply()
                applyControlPosition()
                updateRows()
            }
        }
        findViewById<View>(R.id.rowAccent).setOnClickListener { showAccentPicker() }
        findViewById<View>(R.id.rowLevel).setOnClickListener {
            levelOn = !levelOn
            if (levelOn) registerLevel() else { sensors.unregisterListener(levelListener); gravInit = false; levelView.visibility = View.GONE }
            updateAll()
        }
    }

    private fun toggleSel(s: Sel) {
        settingsPanel.visibility = View.GONE
        libraryPanel.visibility = View.GONE
        selected = if (selected == s) Sel.NONE else s
        refreshSlider(); updateAll()
    }

    private fun dismissCameraControl() {
        selected = Sel.NONE
        zoomButtonDirection = 0
        ui.removeCallbacks(zoomButtonRunnable)
        refreshSlider()
        updatePills()
    }

    private fun toggleAutoForSelection() {
        when (selected) {
            Sel.ISO, Sel.SS -> { if (autoExp) seedManual(); autoExp = !autoExp }
            Sel.FOCUS -> if (minFocus > 0f) { autoFocus = !autoFocus; if (autoFocus) focusX = -1f; clearLock() }
            else -> {}
        }
        pushSettings(); refreshSlider(); updateAll()
    }

    private fun handleSliderProgress(p: Int) {
        if ((selected == Sel.ISO || selected == Sel.SS) && autoExp) { seedManual(); autoExp = false; styleAuto(false); styleVerticalAuto(false) }
        when (selected) {
            Sel.ISO -> isoProgress = p.coerceIn(0, 100)
            Sel.SS -> shutterIdx = p.coerceIn(0, shutterDen.size - 1)
            Sel.FOCUS -> { focusProgress = p.coerceIn(0, 100); autoFocus = false; focusX = -1f; clearLock(); styleAuto(false); styleVerticalAuto(false) }
            Sel.ZOOM -> { zoom = sliderToZoom(p).coerceIn(zoomMin, uiZoomMax()); startZoomTick() }
            Sel.EV -> evProgress = p.coerceIn(0, 24)
            else -> {}
        }
        pushSettings(); updatePills(); updateSliderLabel(); updateVerticalSlider()
    }

    private fun wireZoomButton(button: TextView, direction: Int) {
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (selected != Sel.ZOOM) return@setOnTouchListener true
                    zoomButtonDirection = direction
                    ui.removeCallbacks(zoomButtonRunnable)
                    ui.post(zoomButtonRunnable)
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    zoomButtonDirection = 0
                    ui.removeCallbacks(zoomButtonRunnable)
                    v.isPressed = false
                    true
                }
                else -> true
            }
        }
    }

    // ------------------------------------------------------------------ tap to focus
    private fun onPreviewTap(x: Float, y: Float, lock: Boolean) {
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
        focusLocked = lock
        showFocusRing(x, y, lock)
        if (lock) preview.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        pushSettings()
        if (lock) camera.triggerAf()
        updateAll()
        if (selected == Sel.FOCUS) refreshSlider()
    }

    private fun clearLock() {
        if (!focusLocked) return
        focusLocked = false
        focusRing.animate().cancel()
        focusRing.visibility = View.GONE
    }

    private fun shape(fill: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Float = 0f, oval: Boolean = false): GradientDrawable {
        val g = GradientDrawable()
        g.shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        g.setColor(fill)
        if (!oval) g.cornerRadius = radiusDp * resources.displayMetrics.density
        if (strokeDp > 0f) g.setStroke((strokeDp * resources.displayMetrics.density).toInt(), strokeColor)
        return g
    }

    private fun showFocusRing(x: Float, y: Float, locked: Boolean) {
        val size = if (focusRing.width > 0) focusRing.width else (76 * resources.displayMetrics.density).toInt()
        focusRing.animate().cancel()
        if (locked) focusRing.background = shape(Theme.alpha(AMBER, 0x22), 0f, AMBER, 3f, true)
        else focusRing.background = shape(Color.TRANSPARENT, 0f, AMBER, 2f, true)
        focusRing.x = x - size / 2f
        focusRing.y = y - size / 2f
        focusRing.alpha = 1f
        focusRing.scaleX = 1.4f; focusRing.scaleY = 1.4f
        focusRing.visibility = View.VISIBLE
        if (locked) {
            focusRing.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(180).start()
        } else {
            focusRing.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(180).withEndAction {
                focusRing.animate().alpha(0f).setStartDelay(900).setDuration(300).withEndAction {
                    focusRing.visibility = View.GONE
                    focusRing.alpha = 1f
                }.start()
            }.start()
        }
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
        focusLocked = false; focusRing.visibility = View.GONE
        lastIso = 0; lastExpNs = 0L
        val zr = try { camera.zoomRange(lens.id) } catch (t: Throwable) { 1f to 1f }
        zoomMin = zr.first; zoomMax = maxOf(zr.second, zr.first)
        zoom = 1f.coerceIn(zoomMin, zoomMax); zoomShown = zoom
        rebuildChips()
        defaultShutter()
        updateAll()
    }

    private fun defaultShutter() {
        val fps = modes.getOrNull(modeIdx)?.fps ?: 30
        shutterIdx = shutterDen.indexOfFirst { it >= fps * 2 }.takeIf { it >= 0 } ?: 0
    }

    private fun isoValue(): Int = isoValueAt(isoProgress)

    private fun isoValueAt(progress: Int): Int {
        val lo = ln(isoMin.toDouble()); val hi = ln(isoMax.toDouble())
        return exp(lo + (hi - lo) * progress.coerceIn(0, 100) / 100.0).toInt()
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
            focusY = focusY,
            zoom = zoomShown,
            lock = focusLocked && autoFocus
        ))
    }

    // ------------------------------------------------------------------ UI refresh
    private fun updateAll() {
        updateInfo(); updatePills(); updateRows(); updateSliderLabel(); updateGuide(); updateChips()
        if (stateLoaded) saveState()
    }

    private fun updateInfo() {
        val m = modes.getOrNull(modeIdx)
        tvInfo.text = (m?.label ?: "—") + "  ·  S-Log3  ·  " + (if (gamutCine) "S-Gamut3.Cine" else "S-Gamut3")
        tvInfo2.text = (if (activeHlg) "HLG10" else "SDR") + " → " + (if (activeTenBit) "10-bit" else "8-bit") + " HEVC" +
            (if (guideIdx > 0) "  ·  guide " + guides[guideIdx].first else "")
    }

    private fun stylePill(v: TextView, sel: Boolean, manual: Boolean) {
        v.background = pillBg(sel)
        v.setTextColor(if (sel) Theme.onAccent() else if (manual) AMBER else Color.WHITE)
    }

    private fun styleAuto(on: Boolean) {
        tvAuto.background = pillBg(on)
        tvAuto.setTextColor(if (on) Theme.onAccent() else Color.WHITE)
    }

    private fun focusText(): String {
        if (focusLocked) return "AF LOCK"
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
        tvLock.visibility = if (focusLocked) View.VISIBLE else View.GONE
        pillZoom.text = fmtX(curLensFactor * zoomShown)
        pillIso.text = "ISO ${shownIso()}"
        pillShutter.text = "1/${shownDen()}"
        pillWb.text = wbModes.getOrNull(wbIdx)?.second ?: "WB Auto"
        pillFocus.text = focusText()
        pillEv.text = String.format(Locale.US, "EV %+.1f", ev)

        stylePill(pillZoom, selected == Sel.ZOOM, abs(zoom - 1f) > 0.01f)
        stylePill(pillIso, selected == Sel.ISO, !autoExp)
        stylePill(pillShutter, selected == Sel.SS, !autoExp)
        stylePill(pillWb, false, wbIdx != 0)
        stylePill(pillFocus, selected == Sel.FOCUS, !autoFocus || focusLocked)
        stylePill(pillEv, selected == Sel.EV, evProgress != 12)
    }

    private fun updateRows() {
        applyControlPosition()
        vControlPosition.text = controlPosition
        vAccent.text = "●  " + Theme.hex(Theme.accent)
        vAccent.setTextColor(Theme.accent)
        vRes.text = modes.getOrNull(modeIdx)?.label ?: "—"
        vGamut.text = if (gamutCine) "S-Gamut3.Cine" else "S-Gamut3"
        vStab.text = stabOptions.getOrNull(stabIdx)?.second ?: "Off"
        vTen.text = if (tenBitPref) "On" else "Off"
        vAudio.text = if (audioPref) "On" else "Off"
        vAssist.text = if (assistPref) "On" else "Off"
        vRot.text = "${manualFor(displayRotationDeg()) * 90}°"
        vGuide.text = guides[guideIdx].first
        vZoomSpeed.text = zoomSpeedNames[zoomSpeedIdx]
        vGrid.text = gridNames[gridIdx]
        vLevel.text = if (levelOn) "On" else "Off"
    }

    // ------------------------------------------------------------------ theme + glass surfaces
    private fun near(a: Int, b: Int): Boolean =
        abs(Color.red(a) - Color.red(b)) < 4 && abs(Color.green(a) - Color.green(b)) < 4 && abs(Color.blue(a) - Color.blue(b)) < 4

    private fun pillBg(selected: Boolean): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.RECTANGLE
        g.cornerRadius = dp(8).toFloat()
        if (selected) {
            g.setColor(Theme.accent)
        } else {
            g.setColor(Color.parseColor("#B30C0D10"))
            g.setStroke(dp(1), Theme.alpha(Theme.accent, 0x44))
        }
        return g
    }

    /** Re-tints the hairline borders and accent text of the views that come from the XML layout. */
    private fun restyleTree(v: View) {
        if (v === libraryList || v === zoomChips) return
        if (v is TextView && v.tag == "accent") v.setTextColor(Theme.accent)
        val bg = v.background
        if (bg is GradientDrawable && v.id != R.id.recBorder && v.id != R.id.focusRing) {
            (bg.mutate() as GradientDrawable).setStroke(dp(1), Theme.alpha(Theme.accent, 0x44))
        }
        if (v is ViewGroup) for (k in 0 until v.childCount) restyleTree(v.getChildAt(k))
    }

    private fun applyTheme() {
        restyleTree(findViewById<View>(android.R.id.content))
        guideView.invalidate(); levelView.invalidate()
        if (::verticalSlider.isInitialized) verticalSlider.invalidate()
        updatePills(); updateChips(); updateRows()
        if (selected != Sel.NONE) refreshSlider()
    }

    private fun hairline(): View {
        val v = View(this)
        v.setBackgroundColor(Color.parseColor("#26FFFFFF"))
        return v
    }

    private fun pressable(): StateListDrawable {
        val st = StateListDrawable()
        st.addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(Color.parseColor("#22FFFFFF")))
        st.addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        return st
    }

    private fun glassButton(text: String, color: Int, bold: Boolean, action: () -> Unit): TextView {
        val b = TextView(this)
        b.text = text
        b.setTextColor(color)
        b.textSize = 15f
        b.gravity = Gravity.CENTER
        b.typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        b.background = pressable()
        b.isClickable = true
        b.setOnClickListener { action() }
        return b
    }

    /** Shows [root] in a frosted-glass window (real background blur on Android 12+ when the phone allows it). */
    private fun presentGlass(dialog: Dialog, root: View, widthDp: Int, dim: Float = 0.5f) {
        val blur = Build.VERSION.SDK_INT >= 31 && windowManager.isCrossWindowBlurEnabled
        val corner = dp(24).toFloat()
        root.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, corner) }
        }
        root.clipToOutline = true
        dialog.setContentView(root)
        val w = dialog.window
        if (w != null) {
            val glass = GradientDrawable()
            glass.shape = GradientDrawable.RECTANGLE
            glass.cornerRadius = corner
            glass.setColor(Color.parseColor(if (blur) "#F2121212" else "#F5151515"))
            glass.setStroke(dp(1), Theme.alpha(AMBER, 0x55))
            w.setBackgroundDrawable(glass)
            if (blur) w.setBackgroundBlurRadius(dp(24))
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(dim)
            w.setWindowAnimations(0)
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        w?.setLayout(dp(widthDp), WindowManager.LayoutParams.WRAP_CONTENT)
        w?.setGravity(Gravity.CENTER)
        if (Build.VERSION.SDK_INT >= 30) w?.insetsController?.hide(WindowInsets.Type.systemBars())
        root.alpha = 0f
        root.scaleX = 0.95f; root.scaleY = 0.95f
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(100).start()
    }

    /** Centred, minimal selection sheet (replaces the platform PopupMenu). */
    private fun showGlassChoice(title: String, options: List<String>, selectedIndex: Int, onChoose: (Int) -> Unit) {
        if (options.isEmpty()) return
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        val heading = TextView(this)
        heading.text = title.uppercase(Locale.getDefault())
        heading.setTextColor(AMBER)
        heading.textSize = 11f
        heading.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        heading.letterSpacing = 0.16f
        heading.gravity = Gravity.CENTER
        heading.setPadding(dp(16), dp(18), dp(16), dp(14))
        root.addView(heading, LinearLayout.LayoutParams(MP, WC))
        root.addView(hairline(), LinearLayout.LayoutParams(MP, 1))

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        options.forEachIndexed { index, label ->
            val on = index == selectedIndex
            val row = FrameLayout(this)
            row.background = if (on) ColorDrawable(Theme.alpha(AMBER, 0x24)) else pressable()
            row.isClickable = true

            val name = TextView(this)
            name.text = label
            name.gravity = Gravity.CENTER
            name.textSize = 15f
            name.setTextColor(if (on) AMBER else Color.WHITE)
            name.typeface = Typeface.create(if (on) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            name.setPadding(dp(44), 0, dp(44), 0)   // equal padding both sides keeps the text truly centred
            row.addView(name, FrameLayout.LayoutParams(MP, MP))

            if (on) {
                val mark = TextView(this)
                mark.text = "✓"
                mark.textSize = 17f
                mark.setTextColor(AMBER)
                mark.gravity = Gravity.CENTER
                val mlp = FrameLayout.LayoutParams(dp(32), MP, Gravity.END or Gravity.CENTER_VERTICAL)
                mlp.marginEnd = dp(10)
                row.addView(mark, mlp)
            }
            row.setOnClickListener { dialog.dismiss(); onChoose(index) }
            list.addView(row, LinearLayout.LayoutParams(MP, dp(50)))
            if (index < options.lastIndex) {
                val hl = LinearLayout.LayoutParams(MP, 1)
                hl.marginStart = dp(20); hl.marginEnd = dp(20)
                list.addView(hairline(), hl)
            }
        }
        list.measure(
            View.MeasureSpec.makeMeasureSpec(dp(280), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val maxH = (resources.displayMetrics.heightPixels * 0.5f).toInt()
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(MP, minOf(list.measuredHeight, maxH)))

        root.addView(hairline(), LinearLayout.LayoutParams(MP, 1))
        root.addView(glassButton("Cancel", Color.parseColor("#D9FFFFFF"), false) { dialog.dismiss() }, LinearLayout.LayoutParams(MP, dp(50)))
        presentGlass(dialog, root, 280)
    }

    /** Pick any interface colour; the whole app follows it live. */
    private fun showAccentPicker() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val vMin = 0.35f
        val hsv = FloatArray(3)
        Color.colorToHSV(Theme.accent, hsv)
        hsv[2] = hsv[2].coerceIn(vMin, 1f)
        var refresh: () -> Unit = { }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        val heading = TextView(this)
        heading.text = "INTERFACE COLOR"
        heading.textSize = 11f
        heading.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        heading.letterSpacing = 0.16f
        heading.gravity = Gravity.CENTER
        heading.setPadding(dp(16), dp(18), dp(16), dp(14))
        root.addView(heading, LinearLayout.LayoutParams(MP, WC))
        root.addView(hairline(), LinearLayout.LayoutParams(MP, 1))

        val body = LinearLayout(this)
        body.orientation = LinearLayout.HORIZONTAL
        body.setPadding(dp(22), dp(16), dp(22), dp(16))

        // left: preview + presets
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        left.gravity = Gravity.CENTER_HORIZONTAL
        val swatch = View(this)
        left.addView(swatch, LinearLayout.LayoutParams(dp(150), dp(52)))
        val hexText = TextView(this)
        hexText.setTextColor(Color.WHITE)
        hexText.textSize = 13f
        hexText.typeface = Typeface.MONOSPACE
        hexText.gravity = Gravity.CENTER
        hexText.setPadding(0, dp(8), 0, dp(8))
        left.addView(hexText, LinearLayout.LayoutParams(WC, WC))

        val presets = listOf("#FFC23C", "#FF8A3D", "#FF5252", "#FF5FA2", "#A78BFA", "#4DA3FF", "#3DD6E8", "#4CD964").map { Color.parseColor(it) }
        val dots = ArrayList<Pair<View, Int>>()
        for (r in 0..1) {
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            for (k in 0..3) {
                val pc = presets[r * 4 + k]
                val dot = View(this)
                dot.setOnClickListener {
                    Color.colorToHSV(pc, hsv)
                    hsv[2] = hsv[2].coerceIn(vMin, 1f)
                    refresh()
                }
                val lp = LinearLayout.LayoutParams(dp(30), dp(30))
                lp.setMargins(dp(4), dp(4), dp(4), dp(4))
                line.addView(dot, lp)
                dots.add(dot to pc)
            }
            left.addView(line)
        }
        body.addView(left, LinearLayout.LayoutParams(dp(170), WC))

        // right: hue / saturation / brightness
        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        fun barLabel(text: String): TextView {
            val t = TextView(this)
            t.text = text
            t.setTextColor(Color.parseColor("#99FFFFFF"))
            t.textSize = 11f
            t.setPadding(dp(4), dp(2), 0, 0)
            return t
        }
        val hueBar = GradientBar(this)
        val satBar = GradientBar(this)
        val valBar = GradientBar(this)
        hueBar.setColors(listOf("#FF0000", "#FFFF00", "#00FF00", "#00FFFF", "#0000FF", "#FF00FF", "#FF0000").map { Color.parseColor(it) }.toIntArray())
        hueBar.onChange = { p -> hsv[0] = (p * 359.9f); refresh() }
        satBar.onChange = { p -> hsv[1] = p; refresh() }
        valBar.onChange = { p -> hsv[2] = vMin + p * (1f - vMin); refresh() }
        right.addView(barLabel("Hue")); right.addView(hueBar, LinearLayout.LayoutParams(MP, dp(30)))
        right.addView(barLabel("Saturation")); right.addView(satBar, LinearLayout.LayoutParams(MP, dp(30)))
        right.addView(barLabel("Brightness")); right.addView(valBar, LinearLayout.LayoutParams(MP, dp(30)))
        val rlp = LinearLayout.LayoutParams(0, WC, 1f)
        rlp.marginStart = dp(22)
        body.addView(right, rlp)
        root.addView(body)

        root.addView(hairline(), LinearLayout.LayoutParams(MP, 1))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(glassButton("Reset", Color.parseColor("#D9FFFFFF"), false) {
            Color.colorToHSV(Theme.DEFAULT, hsv); refresh()
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        row.addView(View(this).also { it.setBackgroundColor(Color.parseColor("#26FFFFFF")) }, LinearLayout.LayoutParams(1, MP))
        val done = glassButton("Done", AMBER, true) { dialog.dismiss() }
        row.addView(done, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(row)

        refresh = {
            val c = Color.HSVToColor(hsv)
            if (c != Theme.accent) {
                Theme.accent = c
                prefs.edit().putInt("accent", c).apply()
                applyTheme()
            }
            heading.setTextColor(c)
            done.setTextColor(c)
            swatch.background = shape(c, 14f, Color.parseColor("#33FFFFFF"), 1f)
            hexText.text = Theme.hex(c)
            satBar.setColors(intArrayOf(Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])), Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2]))))
            valBar.setColors(intArrayOf(Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], vMin)), Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f))))
            hueBar.setProgress(hsv[0] / 360f)
            satBar.setProgress(hsv[1])
            valBar.setProgress((hsv[2] - vMin) / (1f - vMin))
            for ((v, pc) in dots) {
                v.background = shape(pc, 0f, if (near(pc, c)) Color.WHITE else Color.TRANSPARENT, 2f, true)
            }
        }
        refresh()
        presentGlass(dialog, root, 540, 0.2f)
    }

    /** Controls sit in a column on the left (Side) or in a row along the top / bottom edge. */
    private fun applyControlPosition() {
        if (!::exposureControls.isInitialized) return
        val box = exposureControls as? LinearLayout ?: return
        val lp = box.layoutParams as? FrameLayout.LayoutParams ?: return
        val chipsShown = ::zoomChips.isInitialized && zoomChips.visibility == View.VISIBLE
        val key = controlPosition + "|" + chipsShown
        if (key == lastLayoutKey) return
        lastLayoutKey = key

        val horizontal = controlPosition != "Side"
        box.orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        lp.gravity = when (controlPosition) {
            "Top" -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            "Bottom" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            else -> Gravity.START or Gravity.CENTER_VERTICAL
        }
        lp.leftMargin = if (controlPosition == "Side") dp(8) else 0
        lp.rightMargin = 0
        lp.topMargin = if (controlPosition == "Top") dp(64) else 0
        // keep clear of the zoom levels that live along the bottom edge
        lp.bottomMargin = if (controlPosition == "Bottom") (if (chipsShown) dp(70) else dp(18)) else 0
        box.layoutParams = lp

        for (k in 0 until box.childCount) {
            val child = box.getChildAt(k)
            val cl = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            if (horizontal) cl.setMargins(dp(3), 0, dp(3), 0) else cl.setMargins(0, dp(3), 0, dp(3))
            child.layoutParams = cl
        }
        box.requestLayout()
    }

    private fun refreshSlider() {
        applyControlPosition()
        val visible = selected != Sel.NONE
        sliderBar.visibility = View.GONE
        verticalControlPanel.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        tvVerticalTitle.text = when (selected) {
            Sel.ISO -> "ISO"
            Sel.SS -> "SHUTTER"
            Sel.FOCUS -> "FOCUS"
            Sel.ZOOM -> "ZOOM"
            Sel.EV -> "EXPOSURE"
            else -> ""
        }
        val autoSupported = selected == Sel.ISO || selected == Sel.SS || selected == Sel.FOCUS
        btnVerticalAuto.visibility = if (autoSupported) View.VISIBLE else View.GONE
        btnVerticalAuto.text = if (selected == Sel.FOCUS) "AF" else "AUTO"
        btnZoomPlus.visibility = if (selected == Sel.ZOOM) View.VISIBLE else View.GONE
        btnZoomMinus.visibility = if (selected == Sel.ZOOM) View.VISIBLE else View.GONE
        tvAuto.visibility = View.GONE
        when (selected) {
            Sel.ISO -> { sbMain.max = 100; sbMain.progress = isoProgress; styleVerticalAuto(autoExp) }
            Sel.SS -> { sbMain.max = shutterDen.size - 1; sbMain.progress = shutterIdx; styleVerticalAuto(autoExp) }
            Sel.FOCUS -> { sbMain.max = 100; sbMain.progress = focusProgress; styleVerticalAuto(autoFocus) }
            Sel.ZOOM -> { sbMain.max = 1000; sbMain.progress = zoomToSlider(zoom) }
            Sel.EV -> { sbMain.max = 24; sbMain.progress = evProgress }
            else -> {}
        }
        updateSliderLabel()
        updateVerticalSlider()
    }

    private fun styleVerticalAuto(on: Boolean) {
        btnVerticalAuto.background = pillBg(on)
        btnVerticalAuto.setTextColor(if (on) Theme.onAccent() else Color.WHITE)
        btnVerticalAuto.isSelected = on
        btnVerticalAuto.alpha = if (on) 1f else 0.72f
    }

    private fun updateVerticalSlider() {
        if (!::verticalSlider.isInitialized || selected == Sel.NONE) return
        when (selected) {
            Sel.ISO -> verticalSlider.configure(100, isoProgress) { p -> "ISO " + isoValueAt(p) }
            Sel.SS -> verticalSlider.configure(shutterDen.size - 1, shutterIdx) { p -> "1/" + shutterDen[p.coerceIn(0, shutterDen.lastIndex)] }
            Sel.FOCUS -> verticalSlider.configure(100, focusProgress) { p ->
                if (p == 0) "∞" else String.format(Locale.US, "%.1fm", 1f / (minFocus * p / 100f).coerceAtLeast(0.01f))
            }
            Sel.ZOOM -> verticalSlider.configure(1000, zoomToSlider(zoom)) { p -> fmtX(curLensFactor * sliderToZoom(p)) }
            Sel.EV -> verticalSlider.configure(24, evProgress) { p -> String.format(Locale.US, "%+.1f", (p - 12) / 4f) }
            else -> {}
        }
    }

    private fun updateSliderLabel() {
        tvSlider.text = when (selected) {
            Sel.ISO -> "ISO ${shownIso()}"
            Sel.SS -> "1/${shownDen()}"
            Sel.FOCUS -> focusText()
            Sel.ZOOM -> fmtX(curLensFactor * zoomShown)
            Sel.EV -> String.format(Locale.US, "%+.2f EV", (evProgress - 12) / 4f)
            else -> ""
        }
    }

    // ------------------------------------------------------------------ zoom, lens levels, frame guide
    private fun fmtX(f: Float): String =
        if (abs(f - f.roundToInt()) < 0.05f) "${f.roundToInt()}×" else String.format(Locale.US, "%.1f×", f)

    private fun uiZoomMax() = minOf(zoomMax, 15f).coerceAtLeast(zoomMin)

    private fun sliderToZoom(p: Int): Float {
        val lo = zoomMin; val hi = uiZoomMax()
        if (hi <= lo) return lo
        return (lo * Math.pow((hi / lo).toDouble(), p / 1000.0)).toFloat()
    }

    private fun zoomToSlider(z: Float): Int {
        val lo = zoomMin; val hi = uiZoomMax()
        if (hi <= lo) return 0
        return (1000.0 * ln((z / lo).toDouble()) / ln((hi / lo).toDouble())).roundToInt().coerceIn(0, 1000)
    }

    /** Glides the real zoom towards the slider value, at the speed chosen in settings. */
    private val zoomTick = object : Runnable {
        override fun run() {
            val tgt = zoom.coerceIn(zoomMin, uiZoomMax())
            val speed = zoomSpeeds[zoomSpeedIdx]
            if (speed <= 0f) {
                zoomShown = tgt
            } else {
                val diff = ln(tgt / zoomShown)
                if (abs(diff) < 0.003f) {
                    zoomShown = tgt
                } else {
                    val maxStep = speed * 0.033f
                    val step = (diff * 0.12f).coerceIn(-maxStep, maxStep)
                    zoomShown *= exp(step)
                }
            }
            pushSettings(); updatePills(); updateSliderLabel(); updateChips()
            if (abs(ln(tgt / zoomShown)) > 1e-4f) ui.postDelayed(this, 33)
        }
    }

    private fun startZoomTick() { ui.removeCallbacks(zoomTick); ui.post(zoomTick) }

    private val zoomButtonRunnable = object : Runnable {
        override fun run() {
            val direction = zoomButtonDirection
            if (direction == 0 || selected != Sel.ZOOM) return
            val limit = uiZoomMax()
            val next = (zoom * exp(direction * 0.008f)).coerceIn(zoomMin, limit)
            zoom = next
            startZoomTick()
            updatePills(); updateSliderLabel(); updateVerticalSlider(); updateChips()
            val atLimit = (direction > 0 && zoom >= limit - 0.001f) || (direction < 0 && zoom <= zoomMin + 0.001f)
            if (!atLimit && zoomButtonDirection != 0) ui.postDelayed(this, 32)
        }
    }

    private fun rebuildChips() {
        zoomChips.removeAllViews(); chipViews.clear()
        val eqs = lenses.map { l -> try { camera.focalEq(l.id) } catch (t: Throwable) { null } }
        val valid = eqs.filterNotNull()
        if (valid.isEmpty()) { chips = emptyList(); curLensFactor = 1f; zoomChips.visibility = View.GONE; return }
        val ref = valid.minByOrNull { abs(it - 26f) } ?: valid[0]
        val list = ArrayList<Chip>()
        eqs.forEachIndexed { i, e -> if (e != null) list.add(Chip(e / ref, i, 1f)) }
        curLensFactor = eqs.getOrNull(lensIdx)?.let { it / ref } ?: 1f

        // lenses that sit behind this camera's own zoom range (logical multi-camera)
        val lens = lenses.getOrNull(lensIdx)
        if (lens != null) {
            val phys = try { camera.physicalFactors(lens.id) } catch (t: Throwable) { emptyList() }
            for (pz in phys) {
                if (abs(pz - 1f) < 0.1f || pz < zoomMin - 0.01f || pz > zoomMax) continue
                val f = curLensFactor * pz
                val existing = list.indexOfFirst { abs(it.factor - f) < 0.12f }
                if (existing >= 0) { if (list[existing].lensIdx != lensIdx) list[existing] = Chip(f, lensIdx, pz) }
                else list.add(Chip(f, lensIdx, pz))
            }
        }
        list.sortBy { it.factor }
        chips = list
        zoomChips.visibility = if (list.size > 1) View.VISIBLE else View.GONE
        for (c in list) {
            val tv = TextView(this)
            tv.text = fmtX(c.factor)
            tv.textSize = 12f
            tv.gravity = Gravity.CENTER
            tv.minWidth = dp(44)
            tv.setPadding(dp(12), dp(6), dp(12), dp(6))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(3), 0, dp(3), 0)
            tv.layoutParams = lp
            tv.setOnClickListener { onChip(c) }
            zoomChips.addView(tv)
            chipViews.add(tv)
        }
        updateChips()
    }

    private fun onChip(c: Chip) {
        if (c.lensIdx != lensIdx) {
            if (recording) { lockedToast(); return }
            lensIdx = c.lensIdx
            rebuildModes()
            zoom = c.zoom.coerceIn(zoomMin, zoomMax); zoomShown = zoom
            restartPipeline()
        } else {
            zoom = c.zoom.coerceIn(zoomMin, zoomMax)
            startZoomTick()
        }
        if (selected == Sel.ZOOM) refreshSlider()
        updateAll()
    }

    private fun updateChips() {
        for (i in chips.indices) {
            val c = chips[i]
            val on = c.lensIdx == lensIdx && abs(zoom - c.zoom) / c.zoom < 0.04f
            chipViews.getOrNull(i)?.let { stylePill(it, on, false) }
        }
    }

    private fun updateGuide() {
        val m = modes.getOrNull(modeIdx)
        val aspect = if (m != null) m.w.toFloat() / m.h else 16f / 9f
        val g = guides[guideIdx]
        guideView.setGuide(g.second, aspect, if (g.second > 0f) g.first else "")
        guideView.setGrid(gridIdx)
    }

    // ------------------------------------------------------------------ saved settings
    private fun loadFlags() {
        gamutCine = prefs.getBoolean("gamut", true)
        tenBitPref = prefs.getBoolean("ten", true) && supportsMain10()
        audioPref = prefs.getBoolean("audio", true)
        assistPref = prefs.getBoolean("assist", false)
        guideIdx = prefs.getInt("guide", 0).coerceIn(0, guides.size - 1)
        zoomSpeedIdx = prefs.getInt("zoomSpeed", 1).coerceIn(0, zoomSpeeds.size - 1)
        gridIdx = prefs.getInt("grid", 0).coerceIn(0, gridNames.size - 1)
        levelOn = prefs.getBoolean("level", false)
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
            .putInt("guide", guideIdx)
            .putInt("zoomSpeed", zoomSpeedIdx)
            .putInt("grid", gridIdx)
            .putBoolean("level", levelOn)
            .putBoolean("autoExp", autoExp)
            .putBoolean("autoFocus", autoFocus)
            .putInt("iso", isoProgress)
            .putInt("shutter", shutterIdx)
            .putInt("focus", focusProgress)
            .putInt("ev", evProgress)
            .apply()
    }

    // ------------------------------------------------------------------ level
    private val levelListener = object : SensorEventListener {
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        override fun onSensorChanged(e: SensorEvent) {
            if (!gravInit) { for (k in 0..2) grav[k] = e.values[k]; gravInit = true }
            else for (k in 0..2) grav[k] += 0.15f * (e.values[k] - grav[k])
            val x = grav[0]; val y = grav[1]
            // device axes -> screen axes (sx right, sy up) for the current display rotation
            val sx: Float; val sy: Float
            when (displayRotationDeg()) {
                90 -> { sx = -y; sy = x }
                180 -> { sx = -x; sy = -y }
                270 -> { sx = y; sy = -x }
                else -> { sx = x; sy = y }
            }
            val ang = Math.toDegrees(atan2(sx.toDouble(), sy.toDouble())).toFloat()
            levelView.set(ang, hypot(sx, sy) > 3f)
        }
    }

    private fun registerLevel() {
        if (!levelOn) { levelView.visibility = View.GONE; return }
        levelView.visibility = View.VISIBLE
        val s = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (s != null) sensors.registerListener(levelListener, s, SensorManager.SENSOR_DELAY_GAME)
    }

    // ------------------------------------------------------------------ recordings library
    private fun checkForUpdates() {
        val now = SystemClock.elapsedRealtime()
        if (updateCheckInFlight || (lastUpdateCheckAt != 0L && now - lastUpdateCheckAt < 5 * 60 * 1000L)) return
        lastUpdateCheckAt = now
        updateCheckInFlight = true
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("https://api.github.com/repos/shurikenwastkn-eng/LogCam4/releases/latest").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "LogCam-Android")
                }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@Thread
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val tag = json.optString("tag_name", "")
                val remoteVersion = tag.removePrefix("v")
                val releaseUrl = json.optString("html_url", "https://github.com/shurikenwastkn-eng/LogCam4/releases")
                if (tag.isBlank() || !isVersionNewer(remoteVersion, (packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1"))) return@Thread
                runOnUiThread {
                    val lastPrompted = prefs.getString("lastUpdatePrompted", "")
                    if (lastPrompted == tag || isFinishing || isDestroyed) return@runOnUiThread
                    prefs.edit().putString("lastUpdatePrompted", tag).apply()
                    glassDialog(
                        title = "LogCam update available",
                        message = "Version $remoteVersion is ready. Open the release page to download and install it.",
                        confirmText = "View update",
                        cancelText = "Later"
                    ) {
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl))) }
                        catch (_: Throwable) { Toast.makeText(this, "Couldn't open the release page", Toast.LENGTH_SHORT).show() }
                    }
                }
            } catch (_: Throwable) {
                // Offline, GitHub unavailable, or no published release: keep the camera usable.
            } finally {
                connection?.disconnect()
                updateCheckInFlight = false
            }
        }.start()
    }

    private fun isVersionNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

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
        dismissCameraControl()
        libraryPanel.visibility = View.VISIBLE
        selClips.clear(); libSel.clear()
        libraryList.removeAllViews()
        updateLibHeader()
        Thread {
            val clips = try { queryClips() } catch (t: Throwable) { emptyList() }
            runOnUiThread {
                libClips = clips
                tvLibEmpty.visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
                buildGrid(clips)
                updateLibHeader()
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

    private fun buildGrid(clips: List<Clip>) {
        libraryList.removeAllViews()
        libSel.clear()
        val cols = 3
        val side = dp(28)
        val gap = dp(12)
        val cardW = (resources.displayMetrics.widthPixels - 2 * side - (cols - 1) * gap) / cols
        var row: LinearLayout? = null
        for ((i, c) in clips.withIndex()) {
            if (i % cols == 0) {
                row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.setPadding(side, 0, side, dp(14))
                libraryList.addView(row)
            }
            row?.addView(buildCard(c, cardW, gap))
        }
    }

    private fun buildCard(c: Clip, w: Int, gap: Int): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val lp = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.marginEnd = gap
        card.layoutParams = lp

        val thumb = FrameLayout(this)
        thumb.layoutParams = LinearLayout.LayoutParams(w, w * 9 / 16)
        thumb.background = shape(Color.parseColor("#26FFFFFF"), 12f)
        thumb.clipToOutline = true

        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val secs = c.durMs / 1000
        val dur = TextView(this)
        dur.text = String.format(Locale.US, "%d:%02d", secs / 60, secs % 60)
        dur.setTextColor(Color.WHITE)
        dur.textSize = 11f
        dur.background = shape(Color.parseColor("#A6000000"), 6f)
        dur.setPadding(dp(6), dp(2), dp(6), dp(2))
        val dlp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
        dlp.setMargins(0, 0, dp(6), dp(6))
        thumb.addView(dur, dlp)

        val share = ImageView(this)
        share.setImageResource(R.drawable.ic_share)
        share.background = shape(Color.parseColor("#B30C0D10"), 0f, Theme.alpha(AMBER, 0x55), 1f, true)
        share.setPadding(dp(7), dp(7), dp(7), dp(7))
        share.setOnClickListener { shareClips(listOf(c)) }
        val slp = FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END)
        slp.setMargins(0, dp(6), dp(6), 0)

        val sel = FrameLayout(this)
        sel.background = shape(Theme.alpha(AMBER, 0x33), 12f, AMBER, 3f)
        val chk = TextView(this)
        chk.text = "✓"
        chk.setTextColor(Color.BLACK)
        chk.textSize = 13f
        chk.gravity = Gravity.CENTER
        chk.background = shape(AMBER, 0f, 0, 0f, true)
        val clp = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.START)
        clp.setMargins(dp(8), dp(8), 0, 0)
        sel.addView(chk, clp)
        sel.visibility = if (selClips.containsKey(c.uri)) View.VISIBLE else View.GONE
        libSel[c.uri] = sel

        thumb.addView(share, slp)
        thumb.addView(sel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val name = TextView(this)
        name.text = c.name.removeSuffix(".mp4")
        name.setTextColor(Color.WHITE)
        name.textSize = 12f
        name.isSingleLine = true
        name.ellipsize = TextUtils.TruncateAt.END
        name.setPadding(dp(2), dp(7), 0, 0)

        val meta = TextView(this)
        meta.text = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(c.dateMs)) +
            "  ·  " + String.format(Locale.US, "%.0f MB", c.size / 1048576.0)
        meta.setTextColor(Color.parseColor("#99FFFFFF"))
        meta.textSize = 10f
        meta.setPadding(dp(2), dp(1), 0, 0)

        card.addView(thumb)
        card.addView(name)
        card.addView(meta)
        card.setOnClickListener { if (selClips.isNotEmpty()) toggleSelect(c) else openClip(c.uri) }
        card.setOnLongClickListener { toggleSelect(c); true }

        thumbPool.execute {
            val bmp = try { contentResolver.loadThumbnail(c.uri, Size(480, 270), null) } catch (t: Throwable) { null }
            if (bmp != null) runOnUiThread { iv.setImageBitmap(bmp) }
        }
        return card
    }

    private fun toggleSelect(c: Clip) {
        if (selClips.containsKey(c.uri)) selClips.remove(c.uri) else selClips[c.uri] = c
        libSel[c.uri]?.visibility = if (selClips.containsKey(c.uri)) View.VISIBLE else View.GONE
        updateLibHeader()
    }

    private fun clearSelection() {
        selClips.clear()
        for (v in libSel.values) v.visibility = View.GONE
        updateLibHeader()
    }

    private fun updateLibHeader() {
        libTitle.text = if (selClips.isEmpty()) "Recordings" + (if (libClips.isNotEmpty()) "  ·  ${libClips.size}" else "")
        else "${selClips.size} selected"
        libActions.visibility = if (selClips.isEmpty()) View.GONE else View.VISIBLE
        btnLibClose.text = if (selClips.isEmpty()) "Close" else "Cancel"
    }

    private fun openClip(uri: Uri) {
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, "video/*")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(i, "Open with")) }
        catch (t: Throwable) { Toast.makeText(this, "No app found to play this video", Toast.LENGTH_SHORT).show() }
    }

    private fun shareClips(list: List<Clip>) {
        if (list.isEmpty()) return
        val i: Intent
        if (list.size == 1) {
            i = Intent(Intent.ACTION_SEND)
            i.type = "video/mp4"
            i.putExtra(Intent.EXTRA_STREAM, list[0].uri)
            i.clipData = ClipData.newRawUri("video", list[0].uri)
        } else {
            i = Intent(Intent.ACTION_SEND_MULTIPLE)
            i.type = "video/*"
            val uris = ArrayList<Uri>(list.map { it.uri })
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            val cd = ClipData.newRawUri("video", uris[0])
            for (k in 1 until uris.size) cd.addItem(ClipData.Item(uris[k]))
            i.clipData = cd
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(i, "Share")) }
        catch (t: Throwable) { Toast.makeText(this, "Can't share this", Toast.LENGTH_SHORT).show() }
    }

    /** Copies the chosen clips to Downloads/LogCam so they are easy to find in any file manager. */
    private fun exportClips(list: List<Clip>) {
        if (list.isEmpty()) return
        Toast.makeText(this, "Exporting ${list.size}...", Toast.LENGTH_SHORT).show()
        Thread {
            var ok = 0
            for (c in list) {
                try {
                    val v = ContentValues()
                    v.put(MediaStore.Downloads.DISPLAY_NAME, c.name)
                    v.put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                    v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/LogCam")
                    v.put(MediaStore.Downloads.IS_PENDING, 1)
                    val dst = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: continue
                    contentResolver.openInputStream(c.uri)?.use { input ->
                        contentResolver.openOutputStream(dst)?.use { out -> input.copyTo(out) }
                    }
                    val done = ContentValues()
                    done.put(MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(dst, done, null, null)
                    ok++
                } catch (t: Throwable) { /* skip this one */ }
            }
            runOnUiThread { Toast.makeText(this, "Exported $ok of ${list.size} to Downloads/LogCam", Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun confirmDelete(list: List<Clip>) {
        if (list.isEmpty()) return
        glassDialog(
            title = if (list.size == 1) "Delete recording?" else "Delete ${list.size} recordings?",
            message = if (list.size == 1) list[0].name.removeSuffix(".mp4") else "This can't be undone.",
            confirmText = "Delete"
        ) {
            var failed = 0
            for (c in list) {
                try { contentResolver.delete(c.uri, null, null) } catch (t: Throwable) { failed++ }
            }
            if (failed > 0) Toast.makeText(this, "$failed couldn't be deleted here - delete them from your Gallery", Toast.LENGTH_LONG).show()
            showLibrary(); refreshLastThumb()
        }
    }

    /** Minimal frosted-glass confirmation dialog in the app's amber theme (blurred where the phone supports it). */
    private fun glassDialog(
        title: String,
        message: String?,
        confirmText: String,
        cancelText: String = "Cancel",
        onConfirm: () -> Unit
    ) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val blur = Build.VERSION.SDK_INT >= 31 && windowManager.isCrossWindowBlurEnabled
        val corner = dp(24).toFloat()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, corner) }
        }
        root.clipToOutline = true

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(26), dp(26), dp(26), dp(22))

        val t = TextView(this)
        t.text = title
        t.setTextColor(Color.WHITE)
        t.textSize = 17f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        body.addView(t)

        if (!message.isNullOrEmpty()) {
            val m = TextView(this)
            m.text = message
            m.setTextColor(Color.parseColor("#B3FFFFFF"))
            m.textSize = 13f
            m.maxLines = 3
            m.ellipsize = TextUtils.TruncateAt.END
            m.setPadding(0, dp(8), 0, 0)
            body.addView(m)
        }
        root.addView(body)

        val hair = Color.parseColor("#26FFFFFF")
        val line = View(this)
        line.setBackgroundColor(hair)
        root.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))

        fun button(text: String, color: Int, bold: Boolean, action: () -> Unit): TextView {
            val b = TextView(this)
            b.text = text
            b.setTextColor(color)
            b.textSize = 15f
            b.gravity = Gravity.CENTER
            b.typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            val states = StateListDrawable()
            states.addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(Color.parseColor("#22FFFFFF")))
            states.addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            b.background = states
            b.isClickable = true
            b.setOnClickListener { dialog.dismiss(); action() }
            return b
        }

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(button(cancelText, Color.parseColor("#D9FFFFFF"), false) { }, LinearLayout.LayoutParams(0, dp(52), 1f))
        val vline = View(this)
        vline.setBackgroundColor(hair)
        row.addView(vline, LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT))
        row.addView(button(confirmText, AMBER, true, onConfirm), LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(row)

        dialog.setContentView(root)

        val w = dialog.window
        if (w != null) {
            val glass = GradientDrawable()
            glass.shape = GradientDrawable.RECTANGLE
            glass.cornerRadius = corner
            glass.setColor(Color.parseColor(if (blur) "#F2121212" else "#F5151515"))
            glass.setStroke(dp(1), Theme.alpha(AMBER, 0x55))
            w.setBackgroundDrawable(glass)
            if (blur) w.setBackgroundBlurRadius(dp(48))
            w.setDimAmount(0.55f)
            w.setWindowAnimations(android.R.style.Animation_Toast)
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        w?.setLayout(dp(300), WindowManager.LayoutParams.WRAP_CONTENT)
        w?.setGravity(Gravity.CENTER)
        if (Build.VERSION.SDK_INT >= 30) w?.insetsController?.hide(WindowInsets.Type.systemBars())

        root.alpha = 0f
        root.scaleX = 0.94f; root.scaleY = 0.94f
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170).start()
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
            settingsPanel.visibility == View.VISIBLE -> settingsPanel.visibility = View.GONE
            libraryPanel.visibility == View.VISIBLE -> if (selClips.isNotEmpty()) clearSelection() else libraryPanel.visibility = View.GONE
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
