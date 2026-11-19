package com.aripin.webcam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.aripin.webcam.MainActivity
import com.aripin.webcam.R
import com.aripin.webcam.audio.MicrophoneStreamer
import com.aripin.webcam.model.FocusModeOption
import com.aripin.webcam.model.StreamConfig
import com.aripin.webcam.streaming.MjpegStreamServer
import com.aripin.webcam.util.NetworkUtils
import com.aripin.webcam.util.YuvFrameEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WebcamForegroundService : LifecycleService() {

    inner class LocalBinder : Binder() {
        fun getService(): WebcamForegroundService = this@WebcamForegroundService
    }

    interface StreamStateListener {
        fun onServerStateChanged(isStreaming: Boolean, ipAddress: String, clientCount: Int)
        fun onFpsMeasured(measuredFps: Float)
        fun onCameraCapabilitiesUpdated(
            hasFlash: Boolean,
            exposureRange: Range<Int>,
            supportsManualFocus: Boolean
        )
        fun onRemoteCommandReceived(command: String)
    }

    private val binder = LocalBinder()
    private var listener: StreamStateListener? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewSurfaceProvider: Preview.SurfaceProvider? = null
    private lateinit var analysisExecutor: ExecutorService

    private var mjpegServer: MjpegStreamServer? = null
    private var micStreamer: MicrophoneStreamer? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    val isStreaming = AtomicBoolean(false)
    var currentConfig = StreamConfig()
        private set

    private var lastFrameTimestampNs = 0L
    private var frameCounter = 0
    private var fpsWindowStartMs = System.currentTimeMillis()
    private var currentMeasuredFps = 0f

    override fun onCreate() {
        super.onCreate()
        analysisExecutor = Executors.newSingleThreadExecutor()
        createNotificationChannel()
        registerNetworkWatcher()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP_STREAM) {
            stopStreaming()
            stopSelf()
        }
        return START_STICKY
    }

    fun setListener(streamStateListener: StreamStateListener?) {
        this.listener = streamStateListener
        val endpoints = NetworkUtils.detectLocalEndpoints()
        listener?.onServerStateChanged(
            isStreaming.get(),
            endpoints.primaryIp,
            mjpegServer?.getActiveClientCount() ?: 0
        )
    }

    fun attachPreview(surfaceProvider: Preview.SurfaceProvider?) {
        this.previewSurfaceProvider = surfaceProvider
        bindCameraUseCases()
    }

    fun startStreaming(config: StreamConfig) {
        currentConfig = config
        if (!isStreaming.getAndSet(true)) {
            acquireLocks()
            startForeground(NOTIFICATION_ID, buildNotification("Server Aktif • Menunggu koneksi PC"))

            mjpegServer = MjpegStreamServer(
                port = config.videoPort,
                onClientCountChanged = { count ->
                    val ip = NetworkUtils.detectLocalEndpoints().primaryIp
                    updateNotification(
                        if (count > 0) "Terhubung ($count client) • $ip:${config.videoPort}"
                        else "Menunggu PC • $ip:${config.videoPort}"
                    )
                    listener?.onServerStateChanged(true, ip, count)
                },
                onRemoteCommand = { cmd ->
                    listener?.onRemoteCommandReceived(cmd)
                }
            ).apply { start() }

            micStreamer = MicrophoneStreamer(port = config.audioPort).apply {
                audioEnabled = config.audioEnabled
                sampleRateHz = config.audioSampleRate.hz
                gainMultiplier = config.audioGain
                useNoiseSuppression = config.noiseSuppression
                start()
            }
        } else {
            applyAudioConfig(config)
        }

        bindCameraUseCases()
        val ip = NetworkUtils.detectLocalEndpoints().primaryIp
        listener?.onServerStateChanged(true, ip, mjpegServer?.getActiveClientCount() ?: 0)
    }

    fun stopStreaming() {
        if (!isStreaming.getAndSet(false)) return
        mjpegServer?.stop()
        mjpegServer = null
        micStreamer?.stop()
        micStreamer = null
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        val ip = NetworkUtils.detectLocalEndpoints().primaryIp
        listener?.onServerStateChanged(false, ip, 0)
    }

    fun updateConfig(newConfig: StreamConfig) {
        val needsCameraRebind =
            newConfig.isBackCamera != currentConfig.isBackCamera ||
            newConfig.resolution != currentConfig.resolution ||
            newConfig.fps != currentConfig.fps

        currentConfig = newConfig
        applyAudioConfig(newConfig)

        if (needsCameraRebind) {
            bindCameraUseCases()
        } else {
            applyLiveCameraControls(newConfig)
        }
    }

    private fun applyAudioConfig(config: StreamConfig) {
        micStreamer?.let { mic ->
            mic.audioEnabled = config.audioEnabled
            mic.sampleRateHz = config.audioSampleRate.hz
            mic.gainMultiplier = config.audioGain
            mic.useNoiseSuppression = config.noiseSuppression
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun bindCameraUseCases() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                provider.unbindAll()

                val cameraSelector = if (currentConfig.isBackCamera) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }

                val targetSize = Size(currentConfig.resolution.width, currentConfig.resolution.height)
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            targetSize,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val previewBuilder = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)

                val analysisBuilder = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

                val fpsRange = Range(currentConfig.fps.fps, currentConfig.fps.fps)
                Camera2Interop.Extender(previewBuilder)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                Camera2Interop.Extender(analysisBuilder)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)

                val preview = previewBuilder.build().also {
                    it.setSurfaceProvider(previewSurfaceProvider)
                }

                val imageAnalysis = analysisBuilder.build().also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        try {
                            if (isStreaming.get()) {
                                val minIntervalNs = 1_000_000_000L / currentConfig.fps.fps
                                val nowNs = System.nanoTime()
                                if (nowNs - lastFrameTimestampNs >= (minIntervalNs * 0.85).toLong()) {
                                    lastFrameTimestampNs = nowNs
                                    val jpeg = YuvFrameEncoder.encodeImageProxyToJpeg(
                                        image = imageProxy,
                                        jpegQuality = currentConfig.quality.jpegQuality,
                                        mirrorHorizontal = !currentConfig.isBackCamera
                                    )
                                    if (jpeg != null) {
                                        mjpegServer?.pushFrame(jpeg)
                                        trackMeasuredFps()
                                    }
                                }
                            }
                        } finally {
                            imageProxy.close()
                        }
                    }
                }

                camera = provider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

                inspectAndNotifyCapabilities()
                applyLiveCameraControls(currentConfig)
            } catch (_: Exception) {
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun inspectAndNotifyCapabilities() {
        val cam = camera ?: return
        val hasFlash = cam.cameraInfo.hasFlashUnit()
        val expRange = cam.cameraInfo.exposureState.exposureCompensationRange

        val minFocusDist = try {
            Camera2CameraInfo.from(cam.cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        } catch (_: Exception) {
            0f
        }
        listener?.onCameraCapabilitiesUpdated(
            hasFlash = hasFlash,
            exposureRange = expRange,
            supportsManualFocus = minFocusDist > 0f
        )
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyLiveCameraControls(config: StreamConfig) {
        val cam = camera ?: return

        if (cam.cameraInfo.hasFlashUnit()) {
            cam.cameraControl.enableTorch(config.torchEnabled)
        }

        val expState = cam.cameraInfo.exposureState
        if (expState.isExposureCompensationSupported) {
            val clamped = config.exposureIndex.coerceIn(
                expState.exposureCompensationRange.lower,
                expState.exposureCompensationRange.upper
            )
            cam.cameraControl.setExposureCompensationIndex(clamped)
        }

        try {
            val cam2Control = Camera2CameraControl.from(cam.cameraControl)
            val optionsBuilder = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    config.whiteBalance.awbMode
                )

            if (config.focusMode == FocusModeOption.MANUAL) {
                val minFocusDist = Camera2CameraInfo.from(cam.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                if (minFocusDist > 0f) {
                    val targetDiopters = config.manualFocusNormalized.coerceIn(0f, 1f) * minFocusDist
                    optionsBuilder
                        .setCaptureRequestOption(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_OFF
                        )
                        .setCaptureRequestOption(
                            CaptureRequest.LENS_FOCUS_DISTANCE,
                            targetDiopters
                        )
                }
            } else {
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
            }

            cam2Control.setCaptureRequestOptions(optionsBuilder.build())
        } catch (_: Exception) {
        }
    }

    fun triggerAutoFocus(meteringPoint: MeteringPoint?) {
        val cam = camera ?: return
        if (meteringPoint != null) {
            val action = FocusMeteringAction.Builder(
                meteringPoint,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            )
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
            cam.cameraControl.startFocusAndMetering(action)
        } else {
            cam.cameraControl.cancelFocusAndMetering()
        }
    }

    private fun trackMeasuredFps() {
        frameCounter++
        val now = System.currentTimeMillis()
        val elapsed = now - fpsWindowStartMs
        if (elapsed >= 1000L) {
            currentMeasuredFps = (frameCounter * 1000f) / elapsed
            frameCounter = 0
            fpsWindowStartMs = now
            mjpegServer?.telemetryJson =
                """{"streaming":true,"fps":${"%.1f".format(currentMeasuredFps)},"resolution":"${currentConfig.resolution.width}x${currentConfig.resolution.height}","clients":${mjpegServer?.getActiveClientCount() ?: 0},"audio":${currentConfig.audioEnabled}}"""
            listener?.onFpsMeasured(currentMeasuredFps)
        }
    }

    private fun registerNetworkWatcher() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val ip = NetworkUtils.detectLocalEndpoints().primaryIp
                    listener?.onServerStateChanged(
                        isStreaming.get(),
                        ip,
                        mjpegServer?.getActiveClientCount() ?: 0
                    )
                }

                override fun onLost(network: Network) {
                    val ip = NetworkUtils.detectLocalEndpoints().primaryIp
                    listener?.onServerStateChanged(
                        isStreaming.get(),
                        ip,
                        mjpegServer?.getActiveClientCount() ?: 0
                    )
                }
            }
            networkCallback = cb
            cm.registerNetworkCallback(request, cb)
        } catch (_: Exception) {
        }
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidCamAlt::StreamWakeLock").apply {
                acquire(6 * 60 * 60 * 1000L)
            }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DroidCamAlt::WifiLock").apply {
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DroidCam Alternative Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menjaga koneksi kamera & mikrofon tetap aktif di latar belakang"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, WebcamForegroundService::class.java).apply { action = ACTION_STOP_STREAM },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DroidCam Alternative Aktif")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_webcam)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop Server", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    override fun onDestroy() {
        stopStreaming()
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "droidcam_alt_stream_channel"
        const val NOTIFICATION_ID = 4747
        const val ACTION_STOP_STREAM = "com.aripin.webcam.ACTION_STOP_STREAM"
    }
}
