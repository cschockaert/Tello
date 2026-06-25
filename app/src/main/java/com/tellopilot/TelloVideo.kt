package com.tellopilot

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.TextureView
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Receives the Tello's raw H264 stream (UDP 0.0.0.0:11111), reconstructs Annex-B
 * NAL units, decodes them with [MediaCodec] onto a [TextureView] surface, and
 * optionally muxes the same stream to an MP4 file via [MediaMuxer].
 *
 * The Tello SDK has no "take photo" command, so a photo is just the current
 * decoded frame grabbed from the TextureView and saved as JPEG.
 *
 * All network + codec work happens off the main thread.
 */
class TelloVideo(
    private val context: Context,
    private val textureView: TextureView,
    private val onLog: (String) -> Unit
) {
    companion object {
        private const val TAG = "TelloVideo"
        const val VIDEO_PORT = 11111
        private const val MIME = "video/avc" // MediaFormat.MIMETYPE_VIDEO_AVC
        private const val DEFAULT_W = 960   // Tello standard stream is 960x720
        private const val DEFAULT_H = 720
        private const val FPS = 30
    }

    private val running = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var socket: DatagramSocket? = null
    private var recvThread: Thread? = null
    private var decodeThread: Thread? = null

    private val nalQueue = LinkedBlockingQueue<ByteArray>(512)

    private var codec: MediaCodec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    @Volatile private var videoWidth = DEFAULT_W
    @Volatile private var videoHeight = DEFAULT_H

    // ---- Recording state (guarded by muxerLock) --------------------------
    private val muxerLock = Any()
    private var muxer: MediaMuxer? = null
    private var muxerPfd: ParcelFileDescriptor? = null
    private var muxerUri: android.net.Uri? = null
    private var muxerTrack = -1
    private var muxerStarted = false
    @Volatile private var recording = false
    private var frameIndex = 0L

    val isRecording: Boolean get() = recording

    /** Opens the video socket and starts the receive + decode threads. */
    fun start() {
        if (running.getAndSet(true)) return
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(VIDEO_PORT))
                soTimeout = 2000
            }
        } catch (e: Exception) {
            Log.e(TAG, "video socket open failed", e)
            onLog("Erreur ouverture socket vidéo: ${e.message}")
            running.set(false)
            return
        }
        startReceiver()
        startDecoder()
        onLog("Réception vidéo démarrée (UDP $VIDEO_PORT)")
    }

    /** Stops decoding/receiving and tears down the codec. Finalizes any recording. */
    fun stop() {
        if (!running.getAndSet(false)) return
        if (recording) stopRecording()
        try { recvThread?.interrupt() } catch (_: Exception) {}
        try { decodeThread?.interrupt() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        nalQueue.clear()
        releaseCodec()
        onLog("Réception vidéo arrêtée")
    }

    // ---- Receiver: UDP -> Annex-B NAL units ------------------------------

    private fun startReceiver() {
        recvThread = thread(name = "tello-video-rx") {
            val packet = DatagramPacket(ByteArray(2048), 2048)
            val splitter = NalSplitter { nal ->
                // Drop frames if the decoder falls behind rather than blocking the socket.
                if (!nalQueue.offer(nal)) {
                    nalQueue.poll()
                    nalQueue.offer(nal)
                }
            }
            while (running.get()) {
                try {
                    socket?.receive(packet)
                    splitter.append(packet.data, packet.length)
                } catch (e: java.net.SocketTimeoutException) {
                    // no video yet (streamon not sent / drone off)
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "video rx stopped", e)
                }
            }
        }
    }

    // ---- Decoder: NAL units -> Surface (+ optional muxer) ----------------

    private fun startDecoder() {
        decodeThread = thread(name = "tello-video-decode") {
            val info = MediaCodec.BufferInfo()
            while (running.get()) {
                try {
                    val nal = nalQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    val type = nalType(nal)
                    when (type) {
                        7 -> sps = nal
                        8 -> pps = nal
                    }
                    if (codec == null) {
                        if (sps != null && pps != null) configureCodec()
                        else continue // wait for parameter sets before decoding
                    }
                    feed(nal)
                    drain(info)
                    maybeWriteSample(nal, type)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "decode loop error", e)
                }
            }
        }
    }

    private fun configureCodec() {
        val surface = surfaceOrNull() ?: return
        try {
            val format = MediaFormat.createVideoFormat(MIME, videoWidth, videoHeight).apply {
                sps?.let { setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
                pps?.let { setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
            }
            codec = MediaCodec.createDecoderByType(MIME).also {
                it.configure(format, surface, null, 0)
                it.start()
            }
            onLog("Décodeur H264 configuré (${videoWidth}x${videoHeight})")
        } catch (e: Exception) {
            Log.e(TAG, "codec configure failed", e)
            onLog("Erreur configuration décodeur: ${e.message}")
            releaseCodec()
        }
    }

    private fun feed(nal: ByteArray) {
        val mc = codec ?: return
        val inIndex = mc.dequeueInputBuffer(10_000)
        if (inIndex < 0) return
        val buffer = mc.getInputBuffer(inIndex) ?: return
        buffer.clear()
        buffer.put(nal)
        mc.queueInputBuffer(inIndex, 0, nal.size, ptsUs(), 0)
    }

    private fun drain(info: MediaCodec.BufferInfo) {
        val mc = codec ?: return
        var outIndex = mc.dequeueOutputBuffer(info, 0)
        while (outIndex >= 0) {
            mc.releaseOutputBuffer(outIndex, true) // render to the TextureView
            outIndex = mc.dequeueOutputBuffer(info, 0)
        }
        if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            val f = mc.outputFormat
            videoWidth = f.getInteger(MediaFormat.KEY_WIDTH, videoWidth)
            videoHeight = f.getInteger(MediaFormat.KEY_HEIGHT, videoHeight)
        }
    }

    private fun ptsUs(): Long = System.nanoTime() / 1000

    // ---- Recording -------------------------------------------------------

    /** Begins muxing the live H264 stream to an MP4 in Movies/TelloPilot/. */
    fun startRecording() {
        synchronized(muxerLock) {
            if (recording) return
            val name = "TELLO_${System.currentTimeMillis()}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TelloPilot")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            try {
                val resolver = context.contentResolver
                val uri = resolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
                ) ?: throw IllegalStateException("MediaStore insert returned null")
                val pfd = resolver.openFileDescriptor(uri, "rw")
                    ?: throw IllegalStateException("openFileDescriptor returned null")
                muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxerPfd = pfd
                muxerUri = uri
                muxerTrack = -1
                muxerStarted = false
                frameIndex = 0
                recording = true
                onLog("Enregistrement démarré: $name")
            } catch (e: Exception) {
                Log.e(TAG, "startRecording failed", e)
                onLog("Erreur démarrage REC: ${e.message}")
                cleanupMuxerLocked()
            }
        }
    }

    /** Stops muxing, finalizes the MP4 and publishes it to the gallery. */
    fun stopRecording() {
        synchronized(muxerLock) {
            if (!recording) return
            recording = false
            try {
                if (muxerStarted) muxer?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "muxer stop failed", e)
            }
            try { muxer?.release() } catch (_: Exception) {}
            try { muxerPfd?.close() } catch (_: Exception) {}

            val uri = muxerUri
            if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    context.contentResolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    Log.w(TAG, "publish video failed", e)
                }
            }
            onLog("Enregistrement terminé")
            muxer = null
            muxerPfd = null
            muxerUri = null
            muxerStarted = false
            muxerTrack = -1
        }
    }

    /** Adds the video track once SPS/PPS are known, then writes VCL samples. */
    private fun maybeWriteSample(nal: ByteArray, type: Int) {
        if (!recording) return
        synchronized(muxerLock) {
            val mx = muxer ?: return
            if (!muxerStarted) {
                val s = sps ?: return
                val p = pps ?: return
                try {
                    val format = MediaFormat.createVideoFormat(MIME, videoWidth, videoHeight).apply {
                        setByteBuffer("csd-0", ByteBuffer.wrap(s))
                        setByteBuffer("csd-1", ByteBuffer.wrap(p))
                        setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                    }
                    muxerTrack = mx.addTrack(format)
                    mx.start()
                    muxerStarted = true
                } catch (e: Exception) {
                    Log.e(TAG, "muxer start failed", e)
                    return
                }
            }
            // Only mux slice NAL units (1 = non-IDR, 5 = IDR); SPS/PPS are in csd.
            if (type != 1 && type != 5) return
            try {
                val info = MediaCodec.BufferInfo().apply {
                    offset = 0
                    size = nal.size
                    presentationTimeUs = frameIndex * 1_000_000L / FPS
                    flags = if (type == 5) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                }
                mx.writeSampleData(muxerTrack, ByteBuffer.wrap(nal), info)
                frameIndex++
            } catch (e: Exception) {
                Log.w(TAG, "writeSampleData failed", e)
            }
        }
    }

    private fun cleanupMuxerLocked() {
        try { muxer?.release() } catch (_: Exception) {}
        try { muxerPfd?.close() } catch (_: Exception) {}
        val uri = muxerUri
        if (uri != null) {
            try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
        }
        muxer = null
        muxerPfd = null
        muxerUri = null
        muxerStarted = false
        muxerTrack = -1
        recording = false
    }

    // ---- Photo -----------------------------------------------------------

    /**
     * Grabs the current decoded frame from the TextureView and saves it as a
     * JPEG in Pictures/TelloPilot/. [onResult] is invoked on the main thread.
     */
    fun capturePhoto(onResult: (Boolean) -> Unit) {
        mainHandler.post {
            val bmp: Bitmap? = if (textureView.isAvailable) textureView.bitmap else null
            if (bmp == null) {
                onLog("Aucune frame à capturer (vidéo non démarrée ?)")
                onResult(false)
                return@post
            }
            thread(name = "tello-photo-save") {
                val ok = saveJpeg(bmp)
                mainHandler.post { onResult(ok) }
            }
        }
    }

    private fun saveJpeg(bmp: Bitmap): Boolean {
        val name = "TELLO_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TelloPilot")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        var uri: android.net.Uri? = null
        return try {
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert returned null")
            val out: OutputStream = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("openOutputStream returned null")
            out.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
            onLog("Photo enregistrée: $name")
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveJpeg failed", e)
            onLog("Erreur enregistrement photo: ${e.message}")
            uri?.let { try { resolver.delete(it, null, null) } catch (_: Exception) {} }
            false
        }
    }

    // ---- Helpers ---------------------------------------------------------

    private fun surfaceOrNull(): Surface? {
        val st = textureView.surfaceTexture ?: return null
        return Surface(st)
    }

    private fun releaseCodec() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
    }

    /** NAL unit type: first byte after the 00 00 01 start code, low 5 bits. */
    private fun nalType(nal: ByteArray): Int {
        // nal is Annex-B: 00 00 01 <header> ...
        return if (nal.size > 3) (nal[3].toInt() and 0x1F) else -1
    }

    /**
     * Streaming Annex-B splitter. Accumulates bytes across UDP packets and emits
     * each NAL unit (start-code prefixed) as soon as the next start code arrives.
     */
    private class NalSplitter(private val onNal: (ByteArray) -> Unit) {
        private var buf = ByteArray(1 shl 16)
        private var len = 0

        fun append(data: ByteArray, length: Int) {
            ensure(len + length)
            System.arraycopy(data, 0, buf, len, length)
            len += length
            extract()
        }

        private fun extract() {
            var nalStart = findStartCode(0)
            if (nalStart < 0) {
                // No start code yet; cap memory if the stream is garbage.
                if (len > 4 * 1024 * 1024) len = 0
                return
            }
            var next = findStartCode(nalStart + 3)
            while (next >= 0) {
                onNal(buf.copyOfRange(nalStart, next))
                nalStart = next
                next = findStartCode(nalStart + 3)
            }
            // Keep the trailing (incomplete) NAL for the next packet.
            System.arraycopy(buf, nalStart, buf, 0, len - nalStart)
            len -= nalStart
        }

        private fun findStartCode(from: Int): Int {
            var i = if (from < 0) 0 else from
            val limit = len - 3
            while (i <= limit) {
                if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1) {
                    return i
                }
                i++
            }
            return -1
        }

        private fun ensure(capacity: Int) {
            if (capacity <= buf.size) return
            var newSize = buf.size * 2
            while (newSize < capacity) newSize *= 2
            buf = buf.copyOf(newSize)
        }
    }
}
