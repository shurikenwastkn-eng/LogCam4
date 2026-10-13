package com.logcam.app

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** HEVC (Main10 when available) + AAC -> MP4 in Movies/LogCam. All timestamps are relative to [startNs]. */
class Recorder(
    private val ctx: Context,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val tenBit: Boolean,
    private val wantAudio: Boolean,
    private val nowNs: () -> Long,
    private val startNs: Long
) {
    lateinit var inputSurface: Surface
        private set

    private lateinit var vCodec: MediaCodec
    private var aCodec: MediaCodec? = null
    private var aRec: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var audioEnabled = false

    private val lock = Any()
    private var vTrack = -1
    private var aTrack = -1
    private var started = false
    private class Sample(val video: Boolean, val data: ByteArray, val pts: Long, val flags: Int)
    private val pending = ArrayList<Sample>()

    @Volatile private var stopping = false
    private var vThread: Thread? = null
    private var aThread: Thread? = null

    fun prepare() {
        val name = "LOG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/LogCam")
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val u = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create output file")
        uri = u
        pfd = ctx.contentResolver.openFileDescriptor(u, "rw")
        muxer = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // ---- video
        val bitrate = if (width >= 3840) 100_000_000 else 40_000_000
        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
            if (tenBit) setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
        }
        vCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        vCodec.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = vCodec.createInputSurface()

        // ---- audio
        if (wantAudio) {
            try {
                val rate = 48000
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                val rec = AudioRecord(MediaRecorder.AudioSource.CAMCORDER, rate,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, min * 4)
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                    }
                    val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    ac.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    aCodec = ac; aRec = rec; audioEnabled = true
                } else rec.release()
            } catch (t: Throwable) {
                Log.w(TAG, "audio disabled: $t")
                audioEnabled = false
            }
        }
    }

    fun start() {
        vCodec.start()
        vThread = Thread({ while (!drainOnce(vCodec, true, 10_000)) { /* loop */ } }, "venc-drain").also { it.start() }
        if (audioEnabled) {
            aCodec!!.start()
            aRec!!.startRecording()
            aThread = Thread({ audioLoop() }, "aenc").also { it.start() }
        }
    }

    /** Blocking; call off the main thread. */
    fun stop(): Uri? {
        stopping = true
        try { vCodec.signalEndOfInputStream() } catch (t: Throwable) { Log.w(TAG, "eos: $t") }
        vThread?.join(4000)
        aThread?.join(4000)
        val wasStarted = synchronized(lock) { started }
        synchronized(lock) {
            try { if (started) muxer?.stop() } catch (t: Throwable) { Log.w(TAG, "muxer.stop: $t") }
            try { muxer?.release() } catch (_: Throwable) {}
        }
        try { vCodec.stop(); vCodec.release() } catch (_: Throwable) {}
        try { aCodec?.stop(); aCodec?.release() } catch (_: Throwable) {}
        try { aRec?.release() } catch (_: Throwable) {}
        try { inputSurface.release() } catch (_: Throwable) {}
        try { pfd?.close() } catch (_: Throwable) {}
        if (!wasStarted) {
            uri?.let { try { ctx.contentResolver.delete(it, null, null) } catch (_: Throwable) {} }
            return null
        }
        uri?.let {
            val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            ctx.contentResolver.update(it, v, null, null)
        }
        return uri
    }

    /** Clean up after a failed prepare()/start(). */
    fun abort() {
        try { vCodec.release() } catch (_: Throwable) {}
        try { aCodec?.release() } catch (_: Throwable) {}
        try { aRec?.release() } catch (_: Throwable) {}
        try { muxer?.release() } catch (_: Throwable) {}
        try { pfd?.close() } catch (_: Throwable) {}
        uri?.let { try { ctx.contentResolver.delete(it, null, null) } catch (_: Throwable) {} }
    }

    // ------------------------------------------------------------ muxing
    private fun onFormat(video: Boolean, fmt: MediaFormat) {
        synchronized(lock) {
            val m = muxer ?: return
            if (video) vTrack = m.addTrack(fmt) else aTrack = m.addTrack(fmt)
            if (vTrack >= 0 && (!audioEnabled || aTrack >= 0)) {
                m.start()
                started = true
                for (s in pending) writeNow(m, s.video, ByteBuffer.wrap(s.data), s.pts, s.flags, s.data.size)
                pending.clear()
            }
        }
    }

    private fun writeSample(video: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            val m = muxer ?: return
            if (started) {
                writeNow(m, video, buf, info.presentationTimeUs, info.flags, info.size)
            } else {
                val arr = ByteArray(info.size)
                buf.get(arr)
                pending.add(Sample(video, arr, info.presentationTimeUs, info.flags))
            }
        }
    }

    private fun writeNow(m: MediaMuxer, video: Boolean, buf: ByteBuffer, pts: Long, flags: Int, size: Int) {
        val bi = MediaCodec.BufferInfo()
        if (buf.position() != 0 && buf.limit() >= size) { /* codec buffer already positioned */ }
        bi.set(buf.position(), size, pts, flags)
        try {
            m.writeSampleData(if (video) vTrack else aTrack, buf, bi)
        } catch (t: Throwable) {
            Log.w(TAG, "write: $t")
        }
    }

    private fun drainOnce(codec: MediaCodec, video: Boolean, timeoutUs: Long): Boolean {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, timeoutUs)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return false
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { onFormat(video, codec.outputFormat); continue }
            if (idx < 0) continue
            val buf = codec.getOutputBuffer(idx)
            val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
            val cfgFlag = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            if (buf != null && info.size > 0 && !cfgFlag) {
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                writeSample(video, buf, info)
            }
            codec.releaseOutputBuffer(idx, false)
            if (eos) return true
        }
    }

    private fun audioLoop() {
        val rec = aRec ?: return
        val codec = aCodec ?: return
        val rate = 48000
        var totalFrames = 0L
        var basePtsUs = -1L
        try {
            while (!stopping) {
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    val buf = codec.getInputBuffer(idx)!!
                    buf.clear()
                    val n = rec.read(buf, 4096)
                    if (n > 0) {
                        val frames = n / 4
                        if (basePtsUs < 0) {
                            val durNs = frames * 1_000_000_000L / rate
                            basePtsUs = maxOf(0L, (nowNs() - durNs - startNs) / 1000)
                        }
                        val pts = basePtsUs + totalFrames * 1_000_000L / rate
                        totalFrames += frames
                        codec.queueInputBuffer(idx, 0, n, pts, 0)
                    } else {
                        codec.queueInputBuffer(idx, 0, 0, 0, 0)
                    }
                }
                drainOnce(codec, false, 0)
            }
            // end of stream
            var queued = false
            var tries = 0
            while (!queued && tries++ < 50) {
                val idx = codec.dequeueInputBuffer(20_000)
                if (idx >= 0) {
                    val pts = maxOf(0L, basePtsUs) + totalFrames * 1_000_000L / rate
                    codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queued = true
                }
            }
            var done = false
            tries = 0
            while (!done && tries++ < 100) done = drainOnce(codec, false, 20_000)
        } catch (t: Throwable) {
            Log.e(TAG, "audioLoop", t)
        }
    }

    companion object { private const val TAG = "LogCam.Rec" }
}
