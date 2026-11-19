package com.aripin.webcam.streaming

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MjpegStreamServer(
    private val port: Int = 4747,
    private val onClientCountChanged: (Int) -> Unit,
    private val onRemoteCommand: (String) -> Unit
) {
    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    private val videoClients = CopyOnWriteArrayList<ClientSession>()
    private val frameSequence = AtomicLong(0L)
    @Volatile
    private var latestJpegFrame: ByteArray? = null

    @Volatile
    var telemetryJson: String = """{"status":"ready"}"""

    private class ClientSession(
        val socket: Socket,
        val output: BufferedOutputStream,
        var lastSentSequence: Long = -1L
    )

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
                        socket.soTimeout = 10000
                        handleIncomingConnection(socket)
                    } catch (_: Exception) {
                        if (!isRunning.get()) break
                    }
                }
            } catch (_: Exception) {
            }
        }, "MjpegServerAcceptThread").apply { start() }
    }

    fun pushFrame(jpegBytes: ByteArray) {
        if (!isRunning.get()) return
        latestJpegFrame = jpegBytes
        val seq = frameSequence.incrementAndGet()

        if (videoClients.isEmpty()) return

        val header = (
            "--frame\r\n" +
            "Content-Type: image/jpeg\r\n" +
            "Content-Length: ${jpegBytes.size}\r\n\r\n"
        ).toByteArray(Charsets.US_ASCII)
        val footer = "\r\n".toByteArray(Charsets.US_ASCII)

        for (session in videoClients) {
            if (session.lastSentSequence == seq) continue
            try {
                session.output.write(header)
                session.output.write(jpegBytes)
                session.output.write(footer)
                session.output.flush()
                session.lastSentSequence = seq
            } catch (_: Exception) {
                removeVideoClient(session)
            }
        }
    }

    private fun handleIncomingConnection(socket: Socket) {
        Thread({
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                val requestLine = reader.readLine() ?: run {
                    socket.close()
                    return@Thread
                }

                var line: String?
                while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                }

                val path = requestLine.split(" ").getOrElse(1) { "/" }
                val out = BufferedOutputStream(socket.getOutputStream(), 65536)

                when {
                    path.startsWith("/video") || path.startsWith("/mjpeg") -> {
                        socket.soTimeout = 0
                        val headers = (
                            "HTTP/1.1 200 OK\r\n" +
                            "Server: DroidCamAlternative/1.0\r\n" +
                            "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                            "Pragma: no-cache\r\n" +
                            "Expires: 0\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Connection: close\r\n" +
                            "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII)
                        out.write(headers)
                        out.flush()

                        val session = ClientSession(socket, out)
                        videoClients.add(session)
                        onClientCountChanged(videoClients.size)

                        latestJpegFrame?.let { frame ->
                            val frameHdr = (
                                "--frame\r\n" +
                                "Content-Type: image/jpeg\r\n" +
                                "Content-Length: ${frame.size}\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII)
                            out.write(frameHdr)
                            out.write(frame)
                            out.write("\r\n".toByteArray(Charsets.US_ASCII))
                            out.flush()
                        }
                    }

                    path.startsWith("/shot.jpg") -> {
                        val frame = latestJpegFrame
                        if (frame != null) {
                            val headers = (
                                "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: image/jpeg\r\n" +
                                "Content-Length: ${frame.size}\r\n" +
                                "Access-Control-Allow-Origin: *\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII)
                            out.write(headers)
                            out.write(frame)
                            out.flush()
                        } else {
                            sendTextResponse(out, 503, "text/plain", "Camera warming up")
                        }
                        socket.close()
                    }

                    path.startsWith("/status") -> {
                        sendTextResponse(out, 200, "application/json", telemetryJson)
                        socket.close()
                    }

                    path.startsWith("/cmd") -> {
                        val action = path.substringAfter("action=", "").substringBefore("&")
                        if (action.isNotEmpty()) {
                            onRemoteCommand(action)
                        }
                        sendTextResponse(out, 200, "application/json", """{"ok":true,"action":"$action"}""")
                        socket.close()
                    }

                    else -> {
                        sendTextResponse(out, 200, "text/html; charset=utf-8", buildBrowserDashboardHtml())
                        socket.close()
                    }
                }
            } catch (_: Exception) {
                try { socket.close() } catch (_: Exception) {}
            }
        }, "HttpClientHandler").start()
    }

    private fun removeVideoClient(session: ClientSession) {
        if (videoClients.remove(session)) {
            onClientCountChanged(videoClients.size)
        }
        try { session.socket.close() } catch (_: Exception) {}
    }

    private fun sendTextResponse(out: BufferedOutputStream, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val statusText = if (code == 200) "OK" else "Service Unavailable"
        val headers = (
            "HTTP/1.1 $code $statusText\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n"
        ).toByteArray(Charsets.UTF_8)
        out.write(headers)
        out.write(bytes)
        out.flush()
    }

    private fun buildBrowserDashboardHtml(): String = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="UTF-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1.0" />
          <title>DroidCam Alternative — Live Web Feed</title>
          <style>
            body { margin:0; background:#0f172a; color:#f8fafc; font-family:system-ui,sans-serif; display:flex; flex-direction:column; align-items:center; justify-content:center; min-height:100vh; }
            .card { background:#1e293b; padding:16px; border-radius:14px; box-shadow:0 12px 30px rgba(0,0,0,.45); max-width:94vw; text-align:center; }
            img { max-width:100%; max-height:75vh; border-radius:10px; background:#000; display:block; margin:0 auto 14px; }
            .controls { display:flex; gap:10px; justify-content:center; flex-wrap:wrap; }
            button { background:#3b82f6; color:#fff; border:none; padding:10px 16px; border-radius:8px; font-weight:600; cursor:pointer; }
            button:hover { background:#2563eb; }
            .meta { font-size:13px; color:#94a3b8; margin-top:10px; }
          </style>
        </head>
        <body>
          <div class="card">
            <h2 style="margin:0 0 12px;">DroidCam Alternative (com.aripin.webcam)</h2>
            <img id="stream" src="/video" alt="Live Camera Stream" />
            <div class="controls">
              <button onclick="sendCmd('switch_camera')">Switch Camera</button>
              <button onclick="sendCmd('toggle_torch')">Toggle Flash</button>
              <button onclick="sendCmd('autofocus')">Auto Focus</button>
              <button onclick="location.reload()">Reconnect Stream</button>
            </div>
            <div class="meta">Direct OBS / VLC Video URL: <code>http://&lt;PHONE_IP&gt;:4747/video</code> &bull; Audio URL: <code>http://&lt;PHONE_IP&gt;:4748/audio</code></div>
          </div>
          <script>
            function sendCmd(act) { fetch('/cmd?action=' + act).catch(()=>{}); }
          </script>
        </body>
        </html>
    """.trimIndent()

    fun getActiveClientCount(): Int = videoClients.size

    fun stop() {
        isRunning.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        for (session in videoClients) {
            try { session.socket.close() } catch (_: Exception) {}
        }
        videoClients.clear()
        onClientCountChanged(0)
        acceptThread?.interrupt()
    }
}
