package com.tellopilot

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
        private const val CMD_TIMEOUT_MS = 4000
    }

    private val running = AtomicBoolean(false)
    private val flying = AtomicBoolean(false)

    private var cmdSocket: DatagramSocket? = null
    private var stateSocket: DatagramSocket? = null
    private var cmdAddress: InetAddress? = null

    private var rcThread: Thread? = null
    private var stateThread: Thread? = null
    private var recvThread: Thread? = null

    // Latest rc channels, packed as roll, pitch, throttle, yaw in [-100,100].
    private val rcRoll = AtomicInteger(0)
    private val rcPitch = AtomicInteger(0)
    private val rcThrottle = AtomicInteger(0)
    private val rcYaw = AtomicInteger(0)

    // Last command response (e.g. answer to battery?). Used for simple request/reply.
    private val lastResponse = AtomicReference("")

    val isConnected: Boolean get() = running.get()
    val isFlying: Boolean get() = flying.get()

    /**
     * Opens the sockets, starts the reader/state/rc threads and enters SDK mode.
     * Safe to call once; call [disconnect] before reconnecting.
     */
    fun connect() {
        if (running.getAndSet(true)) return
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
            running.set(false)
            return
        }

        startResponseReader()
        startStateListener()

        // SDK mode: send "command" twice, the first datagram is often dropped.
        thread(name = "tello-init") {
            sendRaw("command")
            sleepQuiet(200)
            sendRaw("command")
            sleepQuiet(200)
            sendRaw("battery?")
        }

        startRcLoop()
        onLog("Connecté au Tello (SDK mode demandé)")
    }

    /** Stops everything and closes the sockets. */
    fun disconnect() {
        if (!running.getAndSet(false)) return
        flying.set(false)
        resetRc()
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
        resetRc()
        sendRaw("takeoff")
        flying.set(true)
    }

    fun land() {
        resetRc()
        sendRaw("land")
        flying.set(false)
    }

    /** Cuts the motors immediately. Use as the panic button. */
    fun emergency() {
        resetRc()
        sendRaw("emergency")
        flying.set(false)
    }

    fun streamOn() = sendRaw("streamon")
    fun streamOff() = sendRaw("streamoff")

    fun requestBattery() = sendRaw("battery?")

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
                    lastResponse.set(msg)
                    if (msg.isNotEmpty()) onLog("Tello: $msg")
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
