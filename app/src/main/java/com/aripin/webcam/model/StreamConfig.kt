package com.aripin.webcam.model

enum class ResolutionOption(val label: String, val width: Int, val height: Int) {
    SD_480P("640 x 480 (SD)", 640, 480),
    HD_720P("1280 x 720 (HD)", 1280, 720),
    FHD_1080P("1920 x 1080 (Full HD)", 1920, 1080)
}

enum class FpsOption(val label: String, val fps: Int) {
    FPS_15("15 FPS (Hemat Baterai)", 15),
    FPS_24("24 FPS (Sinematik)", 24),
    FPS_30("30 FPS (Standar)", 30),
    FPS_60("60 FPS (Ultra Smooth)", 60)
}

enum class QualityOption(val label: String, val jpegQuality: Int, val targetBitrateKbps: Int) {
    LOW("Low (~1.5 Mbps)", 58, 1500),
    MEDIUM("Medium (~3.5 Mbps)", 75, 3500),
    HIGH("High (~6.5 Mbps)", 86, 6500),
    ULTRA("Ultra (~10 Mbps)", 94, 10000)
}

enum class FocusModeOption(val label: String) {
    AUTO("Continuous Auto Focus"),
    MANUAL("Manual Focus Slider")
}

enum class WhiteBalanceOption(val label: String, val awbMode: Int) {
    AUTO("Auto", 1),
    DAYLIGHT("Daylight", 5),
    CLOUDY("Cloudy", 6),
    TUNGSTEN("Tungsten", 3),
    FLUORESCENT("Fluorescent", 2)
}

enum class AudioSampleRateOption(val label: String, val hz: Int) {
    RATE_16K("16 kHz (Voice Low-Latency)", 16000),
    RATE_44K("44.1 kHz (CD Quality)", 44100),
    RATE_48K("48 kHz (Studio Broadcast)", 48000)
}

enum class ConnectionMode(val label: String) {
    WIFI("Wi-Fi / LAN"),
    USB("USB (ADB / Tethering)")
}

enum class OrientationMode(val label: String) {
    AUTO("Auto Rotate"),
    PORTRAIT("Lock Portrait"),
    LANDSCAPE("Lock Landscape")
}

data class StreamConfig(
    val isBackCamera: Boolean = true,
    val resolution: ResolutionOption = ResolutionOption.HD_720P,
    val fps: FpsOption = FpsOption.FPS_30,
    val quality: QualityOption = QualityOption.HIGH,
    val focusMode: FocusModeOption = FocusModeOption.AUTO,
    val manualFocusNormalized: Float = 0.0f,
    val exposureIndex: Int = 0,
    val whiteBalance: WhiteBalanceOption = WhiteBalanceOption.AUTO,
    val torchEnabled: Boolean = false,
    val audioEnabled: Boolean = true,
    val audioSampleRate: AudioSampleRateOption = AudioSampleRateOption.RATE_44K,
    val audioGain: Float = 1.0f,
    val noiseSuppression: Boolean = true,
    val connectionMode: ConnectionMode = ConnectionMode.WIFI,
    val orientationMode: OrientationMode = OrientationMode.LANDSCAPE,
    val videoPort: Int = 4747,
    val audioPort: Int = 4748
)
