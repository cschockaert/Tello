package com.tellopilot

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.view.TextureView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tellopilot.databinding.ActivityMainBinding

/**
 * Assembles the UI, handles runtime permissions, binds the process to the Tello
 * Wi-Fi network (so UDP isn't routed over mobile data), and wires the joysticks
 * to [TelloController] + the [TextureView] to [TelloVideo].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var controller: TelloController
    private var video: TelloVideo? = null

    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var boundNetwork: Network? = null

    // Mode-2 channel state (normalized [-1,1]) accumulated from both sticks.
    private var chRoll = 0f       // right stick X
    private var chPitch = 0f      // right stick Y
    private var chThrottle = 0f   // left stick Y
    private var chYaw = 0f        // left stick X

    private var fastMode = false
    private val speedNormal = 0.5f
    private val speedFast = 1.0f

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (!granted) {
            toast("Localisation refusée : impossible d'identifier le WiFi Tello sur Android 12")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        connectivityManager = getSystemService(ConnectivityManager::class.java)

        controller = TelloController(
            onState = { state -> runOnUiThread { renderTelemetry(state) } },
            onLog = { msg -> runOnUiThread { binding.txtStatus.text = msg } }
        )

        setupJoysticks()
        setupButtons()
        setupTexture()
        requestRuntimePermissions()
    }

    // ---- Permissions -----------------------------------------------------

    private fun requestRuntimePermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions.launch(missing.toTypedArray())
    }

    // ---- Joysticks -------------------------------------------------------

    private fun setupJoysticks() {
        // Left stick: X -> yaw, Y -> throttle
        binding.joystickLeft.listener = JoystickView.Listener { x, y ->
            chYaw = x
            chThrottle = y
            pushRc()
        }
        // Right stick: X -> roll, Y -> pitch
        binding.joystickRight.listener = JoystickView.Listener { x, y ->
            chRoll = x
            chPitch = y
            pushRc()
        }
    }

    private fun pushRc() {
        val k = if (fastMode) speedFast else speedNormal
        controller.setRc(
            roll = chRoll * k,
            pitch = chPitch * k,
            throttle = chThrottle * k,
            yaw = chYaw * k
        )
    }

    // ---- Buttons ---------------------------------------------------------

    private fun setupButtons() = with(binding) {
        btnConnect.setOnClickListener { toggleConnection() }
        btnTakeoff.setOnClickListener {
            if (ensureConnected()) controller.takeoff()
        }
        btnLand.setOnClickListener {
            if (ensureConnected()) controller.land()
        }
        btnEmergency.setOnClickListener {
            // Always allowed when connected — this is the panic button.
            if (controller.isConnected) controller.emergency() else toast("Non connecté")
        }
        btnSpeed.setOnClickListener {
            fastMode = !fastMode
            btnSpeed.text = getString(if (fastMode) R.string.speed_fast else R.string.speed_normal)
            btnSpeed.setBackgroundColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (fastMode) R.color.speed_fast else R.color.overlay_dim
                )
            )
            pushRc()
        }
        btnPhoto.setOnClickListener {
            val v = video
            if (v == null) toast("Vidéo non démarrée") else v.capturePhoto { ok ->
                toast(if (ok) "Photo enregistrée" else "Échec photo")
            }
        }
        btnRec.setOnClickListener { toggleRecording() }
    }

    private fun toggleRecording() {
        val v = video ?: run { toast("Vidéo non démarrée"); return }
        if (v.isRecording) {
            v.stopRecording()
            binding.btnRec.text = getString(R.string.rec_start)
        } else {
            v.startRecording()
            binding.btnRec.text = getString(R.string.rec_stop)
        }
    }

    private fun ensureConnected(): Boolean {
        if (!controller.isConnected) {
            toast("Connecte-toi d'abord (CONNECT)")
            return false
        }
        return true
    }

    // ---- Connection + network binding ------------------------------------

    private fun toggleConnection() {
        if (controller.isConnected) {
            disconnectAll()
        } else {
            bindWifiThenConnect()
        }
    }

    /**
     * Requests the Wi-Fi transport network and binds the whole process to it so
     * UDP reaches the drone even though the Tello network has no internet.
     */
    private fun bindWifiThenConnect() {
        binding.txtStatus.text = "Recherche du réseau WiFi…"
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                boundNetwork = network
                connectivityManager.bindProcessToNetwork(network)
                runOnUiThread {
                    binding.txtStatus.text = "WiFi lié — connexion au Tello…"
                    startTelloSession()
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread { binding.txtStatus.text = "Réseau WiFi perdu" }
            }
        }
        networkCallback = callback
        try {
            connectivityManager.requestNetwork(request, callback)
        } catch (e: SecurityException) {
            binding.txtStatus.text = "Permission réseau manquante: ${e.message}"
        }
    }

    private fun startTelloSession() {
        binding.btnConnect.text = getString(R.string.disconnect)

        // Open the video socket first so it's listening when the stream starts.
        val v = TelloVideo(applicationContext, binding.textureView) { msg ->
            runOnUiThread { binding.txtStatus.text = msg }
        }
        video = v
        v.start()

        // streamon must wait until SDK mode is confirmed, otherwise the drone
        // ignores it and the screen stays black. Send it from the onReady callback.
        // If SDK mode never comes up, tear down so the UI doesn't show a dead "connected".
        controller.connect(
            onReady = { controller.streamOn() },
            onFailed = {
                runOnUiThread {
                    disconnectAll()
                    toast("Mode SDK KO — vérifie le WiFi Tello et reconnecte")
                }
            }
        )
    }

    private fun disconnectAll() {
        video?.let {
            if (it.isRecording) {
                it.stopRecording()
                binding.btnRec.text = getString(R.string.rec_start)
            }
            controller.streamOff()
            it.stop()
        }
        video = null
        controller.disconnect()
        binding.btnConnect.text = getString(R.string.connect)
        releaseNetwork()
    }

    private fun releaseNetwork() {
        try {
            connectivityManager.bindProcessToNetwork(null)
            networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        } catch (_: Exception) {
        }
        networkCallback = null
        boundNetwork = null
    }

    // ---- Telemetry -------------------------------------------------------

    private fun renderTelemetry(state: TelloState) {
        binding.txtTelemetry.text =
            "Bat: ${state.batteryPct} %  |  Alt: ${state.heightCm} cm  |  Sol: ${state.tofCm} cm"
    }

    // ---- Surface ---------------------------------------------------------

    private fun setupTexture() {
        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        if (controller.isConnected) disconnectAll()
    }
}
