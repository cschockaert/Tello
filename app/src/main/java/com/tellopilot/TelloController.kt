package com.tellopilot

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Owns the command + state UDP links to a Ryze/DJI Tello (standard SDK).
 *
 * - Commands are plain UDP text sent to 192.168.10.1:8889.
 * - State is received as a broadcast string on local UDP 0.0.0.0:8890.
 * - The drone auto-lands if it receives no command for ~15s, so once flying we
 *   push an `rc a b c d` packet at ~20 Hz to keep the link alive.
 *
 * All socket I/O runs on dedicated threads; never call from the main thread
 * except the lightweight setters (setRc / takeoff / land / emergency).
 *
 * IMPORTANT: the caller MUST bind the process to the Tello Wi-Fi network
 * (ConnectivityManager.bindProcessToNetwork) BEFORE calling [connect], otherwise
 * Android may route these packets over mobile data and they never reach the drone.
 */
class TelloController(
    private val onState: (TelloState) -> Unit,
    private val onLog: (String) -> Unit
) {
    companion object {
        private const val TAG = "TelloController"
        const val CMD_IP = "192.168.10.1"
        const val CMD_PORT = 8889
        const val STATE_PORT = 8890
        private const val RC_PERIOD_MS = 50L // ~20 Hz
        // Socket read timeout. Must exceed RESPONSE_TIMEOUT_MS so a slow "ok" still reaches us.
        private const val CMD_TIMEOUT_MS = 8000
        // Reference (DJITelloPy): wait for "ok" and retry control commands before giving up.
        private const val RETRY_COUNT = 3
        private const val RESPONSE_TIMEOUT_MS = 7000L
        private const val TAKEOFF_TIMEOUT_MS = 20_000L
    }

    private val running = AtomicBoolean(false)
    private val flying = AtomicBoolean(false)
    private val sdkReady = AtomicBoolean(false)

    private var cmdSocket: DatagramSocket? = null
    private var stateSocket: DatagramSocket? = null
    private var cmdAddress: InetAddress? = null

    private var rcThread: Thread? = null
    private var stateThread: Thread? = null
    private var recvThread: Thread? = null

    // Serializes control commands (takeoff/land/streamon) so only one awaits an "ok" at a time.
    @Volatile private var controlExecutor: ExecutorService? = null
    // The thread currently running a control command; land/emergency interrupt it to preempt.
    @Volatile private var controlThread: Thread? = null
    // True while a control command is blocked waiting for an "ok" (so we only interrupt then).
    @Volatile private var awaitingResponse = false

    // Latest rc channels, packed as roll, pitch, throttle, yaw in [-100,100].
    private val rcRoll = AtomicInteger(0)
    private val rcPitch = AtomicInteger(0)
    private val rcThrottle = AtomicInteger(0)
    private val rcYaw = AtomicInteger(0)

    // Command-channel responses ("ok"/"error"/"85"…). One slot is consumed per awaited command.
    private val responses = LinkedBlockingQueue<String>()

    val isConnected: Boolean get() = running.get()
    val isFlying: Boolean get() = flying.get()
    val isSdkReady: Boolean get() = sdkReady.get()

    /**
     * Opens the sockets, starts the reader/state threads and enters SDK mode.
     *
     * The SDK handshake is the critical part: we send `command` and WAIT for the
     * drone's `ok` (retrying), exactly like the reference SDK. Only once SDK mode
     * is confirmed do we start the rc keep-alive and invoke [onReady] (where the
     * caller sends `streamon`). Sending `takeoff`/`streamon` before that `ok` is
     * silently ignored by the drone, which is why telemetry (a passive 8890
     * broadcast, independent of SDK mode) worked while takeoff and video did not.
     *
     * [onFailed] fires (off the UI thread) if SDK mode is never confirmed, so the
     * caller can tear the half-open session down instead of showing a dead "connected".
     *
     * Safe to call once; call [disconnect] before reconnecting.
     */
    fun connect(onReady: () -> Unit = {}, onFailed: () -> Unit = {}) {
        if (running.getAndSet(true)) return
        sdkReady.set(false)
        controlExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "tello-control").also { controlThread = it }
        }
        try {
            cmdAddress = InetAddress.getByName(CMD_IP)
            cmdSocket = DatagramSocket().apply { soTimeout = CMD_TIMEOUT_MS }
            stateSocket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(STATE_PORT))
                soTimeout = 2000
            }
        } catch (e: Exception) {
            Log.e(TAG, "socket open failed", e)
            onLog("Erreur ouverture sockets: ${e.message}")
            controlExecutor?.shutdownNow()
            controlExecutor = null
            running.set(false)
            return
        }

        startResponseReader()
        startStateListener()

        // Enter SDK mode on the control thread so the handshake and every later
        // control command share one serialized request/reply exchange (no two
        // threads racing for the same "ok"). Only start the rc keep-alive (which
        // would otherwise flood the channel) and signal readiness once SDK mode is up.
        submitControl {
            onLog("Passage en mode SDK…")
            // The very first datagram is often dropped, so warm up with a throwaway
            // "command" before the awaited one — avoids a full RESPONSE_TIMEOUT stall.
            sendRaw("command")
            sleepQuiet(100)
            if (!sendCommandWaitOk("command", RESPONSE_TIMEOUT_MS, RETRY_COUNT)) {
                onLog("Échec mode SDK : vérifie le WiFi Tello et reconnecte")
                onFailed()
                return@submitControl
            }
            sdkReady.set(true)
            onLog("Mode SDK actif")
            startRcLoop()
            onReady()
        }
    }

    /** Stops everything and closes the sockets. */
    fun disconnect() {
        if (!running.getAndSet(false)) return
        flying.set(false)
        sdkReady.set(false)
        resetRc()
        // shutdownNow interrupts any control command still waiting for an "ok".
        try { controlExecutor?.shutdownNow() } catch (_: Exception) {}
        controlExecutor = null
        try { rcThread?.interrupt() } catch (_: Exception) {}
        try { stateThread?.interrupt() } catch (_: Exception) {}
        try { recvThread?.interrupt() } catch (_: Exception) {}
        try { cmdSocket?.close() } catch (_: Exception) {}
        try { stateSocket?.close() } catch (_: Exception) {}
        cmdSocket = null
        stateSocket = null
        onLog("Déconnecté")
    }

    // ---- High level commands ---------------------------------------------

    fun takeoff() {
        submitControl {
            if (!sdkReady.get()) { onLog("Pas encore en mode SDK, décollage ignoré"); return@submitControl }
            resetRc()
            if (sendCommandWaitOk("takeoff", TAKEOFF_TIMEOUT_MS, RETRY_COUNT)) {
                flying.set(true)
                onLog("Décollage OK")
            } else {
                onLog("Échec décollage (pas de 'ok' du Tello)")
            }
        }
    }

    /**
     * Lands now. Sent directly (not queued) and preempts any in-flight command so
     * LAND never waits behind a retrying takeoff on the control thread.
     */
    fun land() {
        resetRc()
        flying.set(false)
        preemptInFlight()
        sendRaw("land")
    }

    /** Cuts the motors immediately. Use as the panic button — direct send, preempts everything. */
    fun emergency() {
        resetRc()
        flying.set(false)
        preemptInFlight()
        sendRaw("emergency")
    }

    /** Interrupts a control command blocked on an "ok" so land/emergency take effect at once. */
    private fun preemptInFlight() {
        if (awaitingResponse) controlThread?.interrupt()
    }

    fun streamOn() = submitControl {
        if (sendCommandWaitOk("streamon", RESPONSE_TIMEOUT_MS, RETRY_COUNT)) onLog("Flux vidéo activé")
        else onLog("Échec streamon (pas de 'ok' du Tello)")
    }

    fun streamOff() = submitControl { sendCommandWaitOk("streamoff", RESPONSE_TIMEOUT_MS, RETRY_COUNT) }

    fun requestBattery() = sendRaw("battery?")

    /** Runs a control command on the serialized control thread, if still connected. */
    private fun submitControl(block: () -> Unit) {
        try { controlExecutor?.execute(block) } catch (_: Exception) {}
    }

    /**
     * Sends [command] and waits for the drone's reply, retrying up to [retries].
     * Returns true once a reply containing "ok" arrives. rc packets get no reply so
     * they never pollute this exchange; [responses] is cleared before each attempt.
     */
    private fun sendCommandWaitOk(command: String, timeoutMs: Long, retries: Int): Boolean {
        controlThread = Thread.currentThread()
        repeat(retries) { attempt ->
            if (!running.get()) return false
            responses.clear()
            sendRaw(command)
            val resp = try {
                awaitingResponse = true
                responses.poll(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                return false
            } finally {
                awaitingResponse = false
            }
            when {
                resp == null -> onLog("Pas de réponse à '$command' (tentative ${attempt + 1}/$retries)")
                resp.lowercase().contains("ok") -> return true
                else -> onLog("Tello: '$resp' en réponse à '$command'")
            }
        }
        return false
    }

    /**
     * Updates the continuous control channels. Inputs are normalized [-1,1];
     * they are scaled to the [-100,100] integer range the SDK expects.
     *
     * @param roll     left/right strafe (a)
     * @param pitch    forward/back (b)
     * @param throttle up/down (c)
     * @param yaw      rotation (d)
     */
    fun setRc(roll: Float, pitch: Float, throttle: Float, yaw: Float) {
        rcRoll.set(toChannel(roll))
        rcPitch.set(toChannel(pitch))
        rcThrottle.set(toChannel(throttle))
        rcYaw.set(toChannel(yaw))
    }

    private fun resetRc() {
        rcRoll.set(0); rcPitch.set(0); rcThrottle.set(0); rcYaw.set(0)
    }

    private fun toChannel(v: Float): Int =
        (v.coerceIn(-1f, 1f) * 100f).toInt().coerceIn(-100, 100)

    // ---- Threads ----------------------------------------------------------

    /** Sends `rc a b c d` at ~20 Hz to keep the link alive while connected. */
    private fun startRcLoop() {
        rcThread = thread(name = "tello-rc") {
            while (running.get()) {
                try {
                    val a = rcRoll.get()
                    val b = rcPitch.get()
                    val c = rcThrottle.get()
                    val d = rcYaw.get()
                    sendRaw("rc $a $b $c $d")
                    sleepQuiet(RC_PERIOD_MS)
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "rc loop hiccup", e)
                }
            }
        }
    }

    /** Reads command responses (e.g. battery?) on the command socket. */
    private fun startResponseReader() {
        recvThread = thread(name = "tello-resp") {
            val buf = ByteArray(1518)
            while (running.get()) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    cmdSocket?.receive(packet)
                    val msg = String(packet.data, 0, packet.length).trim()
                    if (msg.isNotEmpty()) responses.offer(msg)
                } catch (e: java.net.SocketTimeoutException) {
                    // no reply pending; keep looping
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "resp reader stopped", e)
                }
            }
        }
    }

    /** Listens for the telemetry broadcast on UDP 8890 and parses each line. */
    private fun startStateListener() {
        stateThread = thread(name = "tello-state") {
            val buf = ByteArray(2048)
            while (running.get()) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    stateSocket?.receive(packet)
                    val line = String(packet.data, 0, packet.length)
                    TelloState.parse(line)?.let(onState)
                } catch (e: java.net.SocketTimeoutException) {
                    // no telemetry yet (drone not powered / not connected)
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "state listener stopped", e)
                }
            }
        }
    }

    // ---- Raw send ---------------------------------------------------------

    @Synchronized
    private fun sendRaw(command: String) {
        val sock = cmdSocket ?: return
        val addr = cmdAddress ?: return
        try {
            val data = command.toByteArray(Charsets.US_ASCII)
            sock.send(DatagramPacket(data, data.size, addr, CMD_PORT))
        } catch (e: Exception) {
            if (running.get()) Log.w(TAG, "send failed: $command", e)
        }
    }

    private fun sleepQuiet(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }
}
