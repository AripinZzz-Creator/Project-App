# DroidCam Alternative (`com.aripin.webcam`)

Implementasi ulang independen (100% gratis tanpa batasan resolusi, FPS, iklan, maupun paywall) dari aplikasi webcam smartphone-ke-PC menggunakan **Kotlin**, **CameraX + Camera2 Interop**, **AudioRecord**, dan **HTTP MJPEG / PCM Streaming Server** bawaan.

## Fitur Utama
- **Kamera Lengkap**: Switch kamera depan/belakang real-time, Live Preview, Tap-to-Focus, Manual Focus slider, Exposure Compensation (EV), White Balance (Auto, Daylight, Cloudy, Tungsten, Fluorescent), Flash/Torch, dan kunci orientasi Portrait/Landscape.
- **Video Tanpa Batas**: Resolusi 480p, 720p HD, hingga 1080p Full HD; target 15/24/30/60 FPS; pengaturan bitrate/kualitas stream.
- **Audio & Mikrofon**: Transmisi mikrofon real-time via port `4748` dengan opsi sample rate (16 kHz / 44.1 kHz / 48 kHz), Gain Boost, dan Hardware Noise Suppression.
- **Koneksi Wi-Fi & USB**: Mendukung Wi-Fi/LAN lokal maupun koneksi kabel USB (ADB port forwarding & USB Tethering).
- **Kompatibel dengan OBS Studio, Zoom, Google Meet, Microsoft Teams, & Discord**.

## Cara Build APK
### Opsi A: Menggunakan Android Studio
1. Ekstrak seluruh folder proyek ini.
2. Buka **Android Studio** -> **Open** -> pilih folder `DroidCamAlternative`.
3. Tunggu proses *Gradle Sync* selesai.
4. Pilih menu **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. File `.apk` siap instal akan muncul di `app/build/outputs/apk/debug/app-debug.apk`.

### Opsi B: Build Otomatis via GitHub Actions (Tanpa Android Studio)
1. Upload seluruh isi proyek ini (termasuk folder `.github/workflows/build-apk.yml`) ke repository GitHub Anda.
2. Buka tab **Actions** pada repository GitHub.
3. Jalankan workflow **Build DroidCam Alternative APK**.
4. Unduh file `DroidCamAlternative-debug-apk` atau `DroidCamAlternative-release-apk` dari bagian **Artifacts**.

## Cara Menghubungkan ke Komputer (Windows / Linux / macOS)
1. **Langsung di OBS Studio (Tanpa Aplikasi Tambahan)**:
   - Buka aplikasi **DroidCam Alternative** di HP dan tekan **START WEBCAM**.
   - Di OBS Studio, tambahkan **Browser Source** atau **Media Source** (nonaktifkan *Local File*), lalu masukkan URL: `http://<IP_HP_ANDA>:4747/video`.
   - Aktifkan **Start Virtual Camera** di OBS Studio agar otomatis terbaca di **Zoom**, **Google Meet**, dan **Discord**.
2. **Menggunakan Aplikasi Pendamping PC (`desktop-client/droidcam_alt_pc.py`)**:
   - Instal OBS Studio (untuk driver virtual webcam) dan jalankan:
     ```bash
     cd desktop-client
     pip install -r requirements.txt
     python droidcam_alt_pc.py
     ```
   - Pilih mode **Wi-Fi** (masukkan IP HP) atau **USB** (aktifkan *USB Debugging* di HP), lalu klik **Start Virtual Webcam**.
