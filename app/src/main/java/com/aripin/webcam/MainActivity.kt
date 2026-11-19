package com.aripin.webcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Range
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aripin.webcam.databinding.ActivityMainBinding
import com.aripin.webcam.model.*
import com.aripin.webcam.service.WebcamForegroundService
import com.aripin.webcam.util.NetworkUtils

class MainActivity : AppCompatActivity(), WebcamForegroundService.StreamStateListener {

    private lateinit var binding: ActivityMainBinding
    private var webcamService: WebcamForegroundService? = null
    private var isBound = false
    private var config = StreamConfig()
    private var supportsManualFocus = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraGranted = grants[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted) {
            bindAndInitService()
        } else {
            Toast.makeText(this, "Izin Kamera wajib diberikan agar webcam berfungsi.", Toast.LENGTH_LONG).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WebcamForegroundService.LocalBinder
            webcamService = binder.getService().also { srv ->
                isBound = true
                srv.setListener(this@MainActivity)
                srv.attachPreview(binding.previewView.surfaceProvider)
                config = srv.currentConfig
                syncUiWithConfig()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            webcamService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSpinners()
        setupControls()
        updateEndpointDisplay(NetworkUtils.detectLocalEndpoints().primaryIp)
        requestRequiredPermissions()
    }

    private fun requestRequiredPermissions() {
        val needed = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            bindAndInitService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun bindAndInitService() {
        val intent = Intent(this, WebcamForegroundService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupSpinners() {
        binding.spinnerResolution.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ResolutionOption.entries.map { it.label }
        )
        binding.spinnerResolution.setSelection(config.resolution.ordinal)
        binding.spinnerResolution.onItemSelectedListener = simpleItemListener { pos ->
            config = config.copy(resolution = ResolutionOption.entries[pos])
            webcamService?.updateConfig(config)
        }

        binding.spinnerFps.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            FpsOption.entries.map { it.label }
        )
        binding.spinnerFps.setSelection(config.fps.ordinal)
        binding.spinnerFps.onItemSelectedListener = simpleItemListener { pos ->
            config = config.copy(fps = FpsOption.entries[pos])
            webcamService?.updateConfig(config)
        }

        binding.spinnerQuality.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            QualityOption.entries.map { it.label }
        )
        binding.spinnerQuality.setSelection(config.quality.ordinal)
        binding.spinnerQuality.onItemSelectedListener = simpleItemListener { pos ->
            config = config.copy(quality = QualityOption.entries[pos])
            webcamService?.updateConfig(config)
        }

        binding.spinnerWhiteBalance.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            WhiteBalanceOption.entries.map { it.label }
        )
        binding.spinnerWhiteBalance.setSelection(config.whiteBalance.ordinal)
        binding.spinnerWhiteBalance.onItemSelectedListener = simpleItemListener { pos ->
            config = config.copy(whiteBalance = WhiteBalanceOption.entries[pos])
            webcamService?.updateConfig(config)
        }

        binding.spinnerOrientation.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            OrientationMode.entries.map { it.label }
        )
        binding.spinnerOrientation.setSelection(config.orientationMode.ordinal)
        binding.spinnerOrientation.onItemSelectedListener = simpleItemListener { pos ->
            val mode = OrientationMode.entries[pos]
            config = config.copy(orientationMode = mode)
            applyScreenOrientation(mode)
        }

        binding.spinnerAudioRate.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            AudioSampleRateOption.entries.map { it.label }
        )
        binding.spinnerAudioRate.setSelection(config.audioSampleRate.ordinal)
        binding.spinnerAudioRate.onItemSelectedListener = simpleItemListener { pos ->
            config = config.copy(audioSampleRate = AudioSampleRateOption.entries[pos])
            webcamService?.updateConfig(config)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControls() {
        binding.btnStartStop.setOnClickListener {
            val srv = webcamService ?: return@setOnClickListener
            if (srv.isStreaming.get()) {
                srv.stopStreaming()
            } else {
                srv.startStreaming(config)
            }
        }

        binding.btnSwitchCamera.setOnClickListener {
            config = config.copy(
                isBackCamera = !config.isBackCamera,
                torchEnabled = false
            )
            binding.switchTorch.isChecked = false
            webcamService?.updateConfig(config)
        }

        binding.switchTorch.setOnCheckedChangeListener { _, isChecked ->
            config = config.copy(torchEnabled = isChecked)
            webcamService?.updateConfig(config)
        }

        binding.toggleConnectionMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = if (checkedId == R.id.btnModeUsb) ConnectionMode.USB else ConnectionMode.WIFI
            config = config.copy(connectionMode = mode)
            updateEndpointDisplay(NetworkUtils.detectLocalEndpoints().primaryIp)
        }

        binding.btnCopyUrl.setOnClickListener {
            val urlText = binding.tvIpEndpoint.text.toString()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("DroidCam Alternative URL", urlText))
            Toast.makeText(this, "URL disalin: $urlText", Toast.LENGTH_SHORT).show()
        }

        binding.previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val point = binding.previewView.meteringPointFactory.createPoint(event.x, event.y)
                webcamService?.triggerAutoFocus(point)
                Toast.makeText(this, "Auto-Focus dikunci", Toast.LENGTH_SHORT).show()
            }
            true
        }

        binding.switchManualFocus.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !supportsManualFocus) {
                binding.switchManualFocus.isChecked = false
                Toast.makeText(this, "Sensor kamera ini tidak mendukung manual fokus jarak dekat", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            binding.sliderFocus.isEnabled = isChecked
            config = config.copy(
                focusMode = if (isChecked) FocusModeOption.MANUAL else FocusModeOption.AUTO
            )
            webcamService?.updateConfig(config)
        }

        binding.sliderFocus.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                config = config.copy(manualFocusNormalized = value)
                webcamService?.updateConfig(config)
            }
        }

        binding.sliderExposure.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val idx = value.toInt()
                binding.tvExposureValue.text = if (idx > 0) "+$idx EV" else "$idx EV"
                config = config.copy(exposureIndex = idx)
                webcamService?.updateConfig(config)
            }
        }

        binding.switchAudioEnabled.setOnCheckedChangeListener { _, isChecked ->
            config = config.copy(audioEnabled = isChecked)
            webcamService?.updateConfig(config)
        }

        binding.switchNoiseSuppression.setOnCheckedChangeListener { _, isChecked ->
            config = config.copy(noiseSuppression = isChecked)
            webcamService?.updateConfig(config)
        }

        binding.sliderAudioGain.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.tvAudioGainValue.text = "${"%.1f".format(value)}x"
                config = config.copy(audioGain = value)
                webcamService?.updateConfig(config)
            }
        }
    }

    private fun applyScreenOrientation(mode: OrientationMode) {
        requestedOrientation = when (mode) {
            OrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun updateEndpointDisplay(wifiIp: String) {
        if (config.connectionMode == ConnectionMode.WIFI) {
            binding.tvIpEndpoint.text = "http://$wifiIp:${config.videoPort}/video"
            binding.tvConnectionHint.text =
                "Wi-Fi Mode • Audio: http://$wifiIp:${config.audioPort}/audio • Web UI: http://$wifiIp:${config.videoPort}"
        } else {
            val endpoints = NetworkUtils.detectLocalEndpoints()
            val usbDisplay = endpoints.usbTetherIp ?: "127.0.0.1"
            binding.tvIpEndpoint.text = "http://$usbDisplay:${config.videoPort}/video"
            binding.tvConnectionHint.text =
                "USB Mode • Jalankan: adb forward tcp:4747 tcp:4747 & tcp:4748 tcp:4748"
        }
    }

    private fun syncUiWithConfig() {
        binding.switchTorch.isChecked = config.torchEnabled
        binding.switchAudioEnabled.isChecked = config.audioEnabled
        binding.switchNoiseSuppression.isChecked = config.noiseSuppression
    }

    override fun onServerStateChanged(isStreaming: Boolean, ipAddress: String, clientCount: Int) {
        runOnUiThread {
            updateEndpointDisplay(ipAddress)
            if (isStreaming) {
                binding.btnStartStop.text = "STOP STREAMING"
                binding.btnStartStop.setBackgroundColor(getColor(R.color.status_stop_red))
                binding.tvStatusBadge.text = if (clientCount > 0) {
                    "LIVE • $clientCount PC Terhubung"
                } else {
                    "SERVER AKTIF • Menunggu PC"
                }
                binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_active)
            } else {
                binding.btnStartStop.text = "START WEBCAM"
                binding.btnStartStop.setBackgroundColor(getColor(R.color.brand_primary))
                binding.tvStatusBadge.text = "STANDBY • Server Berhenti"
                binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_idle)
                binding.tvLiveFps.text = "0 FPS"
            }
        }
    }

    override fun onFpsMeasured(measuredFps: Float) {
        runOnUiThread {
            binding.tvLiveFps.text = "${"%.1f".format(measuredFps)} FPS"
        }
    }

    override fun onCameraCapabilitiesUpdated(
        hasFlash: Boolean,
        exposureRange: Range<Int>,
        supportsManualFocus: Boolean
    ) {
        runOnUiThread {
            binding.switchTorch.isEnabled = hasFlash
            this.supportsManualFocus = supportsManualFocus
            if (exposureRange.lower < exposureRange.upper) {
                binding.sliderExposure.isEnabled = true
                binding.sliderExposure.valueFrom = exposureRange.lower.toFloat()
                binding.sliderExposure.valueTo = exposureRange.upper.toFloat()
                binding.sliderExposure.value = config.exposureIndex.coerceIn(
                    exposureRange.lower, exposureRange.upper
                ).toFloat()
            } else {
                binding.sliderExposure.isEnabled = false
            }
        }
    }

    override fun onRemoteCommandReceived(command: String) {
        runOnUiThread {
            when (command) {
                "switch_camera" -> binding.btnSwitchCamera.performClick()
                "toggle_torch" -> if (binding.switchTorch.isEnabled) {
                    binding.switchTorch.isChecked = !binding.switchTorch.isChecked
                }
                "autofocus" -> webcamService?.triggerAutoFocus(null)
            }
        }
    }

    private fun simpleItemListener(onSelected: (Int) -> Unit) =
        object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                onSelected(pos)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

    override fun onDestroy() {
        if (isBound) {
            webcamService?.setListener(null)
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }
}
