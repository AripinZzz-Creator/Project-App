package com.aripin.webcam.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class MicrophoneStreamer(
    private val port: Int = 4748
) {
    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var captureThread: Thread? = null
    private val clients = CopyOnWriteArrayList<Socket>()

    @Volatile
    var audioEnabled: Boolean = true

    @Volatile
    var sampleRateHz: Int = 44100

    @Volatile
    var gainMultiplier: Float = 1.0f

    @Volatile
    var useNoiseSuppression: Boolean = true

    fun start() {
        if (isRunning.getAndSet(true)) return

        acceptThread = Thread({
            try {
                val server = ServerSocket(port).apply {
                    reuseAddress = true
                }
                serverSocket = server
                while (isRunning.get()) {
                    try {
                        val socket = server.accept()
                        socket.tcpNoDelay = true
                        handleClientHandshake(socket)
                    } catch (_: Exception) {
                        if (!isRunning.get()) break
                    }
                }
            } catch (_: Exception) {
            }
        }, "AudioServerAcceptThread").apply { start() }

        startCaptureLoop()
    }

    private fun handleClientHandshake(socket: Socket) {
        Thread({
            try {
                val input = socket.getInputStream()
                val headerBuf = ByteArray(1024)
                val read = input.read(headerBuf)
                val request = if (read > 0) String(headerBuf, 0, read) else ""

                val out = socket.getOutputStream()
                if (request.startsWith("GET ")) {
                    val responseHeader = (
                        "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: audio/wav\r\n" +
                        "Cache-Control: no-cache, no-store\r\n" +
                        "Connection: close\r\n" +
                        "X-Audio-Sample-Rate: $sampleRateHz\r\n" +
                        "X-Audio-Channels: 1\r\n" +
                        "X-Audio-Bits: 16\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8)
                    out.write(responseHeader)
                    out.write(buildStreamingWavHeader(sampleRateHz))
                    out.flush()
                }
                clients.add(socket)
            } catch (_: Exception) {
                try { socket.close() } catch (_: Exception) {}
            }
        }, "AudioClientHandshake").start()
    }

    @SuppressLint("MissingPermission")
    private fun startCaptureLoop() {
        captureThread = Thread({
            var activeRate = sampleRateHz
            var recorder: AudioRecord? = null
            var ns: NoiseSuppressor? = null
            var aec: AcousticEchoCanceler? = null

            fun initRecorder(rate: Int): AudioRecord? {
                val minBuf = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) return null
                val bufferSize = maxOf(minBuf * 2, 4096)
                val source = if (useNoiseSuppression) {
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                } else {
                    MediaRecorder.AudioSource.MIC
                }
                val rec = AudioRecord(
                    source,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec.release()
                    return null
                }
                if (useNoiseSuppression && NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
                }
                if (useNoiseSuppression && AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
                }
                rec.startRecording()
                return rec
            }

            try {
                recorder = initRecorder(activeRate)
                val pcmBuffer = ByteArray(2048)

                while (isRunning.get()) {
                    if (!audioEnabled || clients.isEmpty()) {
                        Thread.sleep(25)
                        continue
                    }

                    if (activeRate != sampleRateHz || recorder == null) {
                        try {
                            ns?.release()
                            aec?.release()
                            recorder?.stop()
                            recorder?.release()
                        } catch (_: Exception) {}
                        activeRate = sampleRateHz
                        recorder = initRecorder(activeRate)
                        if (recorder == null) {
                            Thread.sleep(200)
                            continue
                        }
                    }

                    val bytesRead = recorder?.read(pcmBuffer, 0, pcmBuffer.size) ?: -1
                    if (bytesRead > 0) {
                        val payload = if (gainMultiplier != 1.0f) {
                            applyGain16BitPcm(pcmBuffer, bytesRead, gainMultiplier)
                        } else {
                            pcmBuffer.copyOf(bytesRead)
                        }
                        broadcastPcm(payload)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    ns?.release()
                    aec?.release()
                    recorder?.stop()
                    recorder?.release()
                } catch (_: Exception) {}
            }
        }, "AudioCaptureLoop").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun applyGain16BitPcm(buffer: ByteArray, length: Int, gain: Float): ByteArray {
        val out = ByteArray(length)
        val inBuf = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val outBuf = ByteBuffer.wrap(out, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        while (inBuf.remaining() >= 2) {
            val sample = inBuf.short.toInt()
            val amplified = (sample * gain).roundToInt().coerceIn(-32768, 32767).toShort()
            outBuf.putShort(amplified)
        }
        return out
    }

    private fun broadcastPcm(data: ByteArray) {
        for (client in clients) {
            try {
                if (client.isClosed || !client.isConnected) {
                    clients.remove(client)
                    continue
                }
                val out: OutputStream = client.getOutputStream()
                out.write(data)
                out.flush()
            } catch (_: Exception) {
                clients.remove(client)
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    private fun buildStreamingWavHeader(sampleRate: Int): ByteArray {
        val byteRate = sampleRate * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(0x7FFFFFFF)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(0x7FFFFFFF)
        return header.array()
    }

    fun stop() {
        isRunning.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        for (client in clients) {
            try { client.close() } catch (_: Exception) {}
        }
        clients.clear()
        acceptThread?.interrupt()
        captureThread?.interrupt()
    }
}
