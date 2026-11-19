#!/usr/bin/env python3
"""
DroidCam Alternative — PC Companion Virtual Webcam & Remote Controller
Connects to com.aripin.webcam over Wi-Fi or USB (ADB), receives the low-latency MJPEG stream,
and outputs directly to OBS Virtual Camera / Windows Virtual Webcam for Zoom, Meet, Teams, & OBS.
"""

import subprocess
import threading
import time
import urllib.request
import tkinter as tk
from tkinter import ttk, messagebox

import cv2
import numpy as np

try:
    import pyvirtualcam
    HAS_VIRTUAL_CAM = True
except ImportError:
    HAS_VIRTUAL_CAM = False


class DroidCamPcClient:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title("DroidCam Alternative — PC Virtual Webcam Bridge")
        self.root.geometry("460x360")
        self.root.resizable(False, False)

        self.running = False
        self.worker_thread = None

        self.mode_var = tk.StringVar(value="wifi")
        self.ip_var = tk.StringVar(value="192.168.1.10")
        self.port_var = tk.StringVar(value="4747")
        self.preview_var = tk.BooleanVar(value=True)
        self.status_var = tk.StringVar(value="Status: Idle (Siap Menghubungkan)")

        self._build_ui()

    def _build_ui(self):
        pad = {"padx": 14, "pady": 6}

        title = ttk.Label(self.root, text="DroidCam Alternative PC Client", font=("Segoe UI", 13, "bold"))
        title.pack(pady=(12, 4))

        mode_frame = ttk.LabelFrame(self.root, text="Metode Koneksi")
        mode_frame.pack(fill="x", **pad)

        ttk.Radiobutton(
            mode_frame, text="Wi-Fi / LAN", variable=self.mode_var, value="wifi", command=self._on_mode_change
        ).pack(side="left", padx=12, pady=6)
        ttk.Radiobutton(
            mode_frame, text="USB (ADB Auto-Forward)", variable=self.mode_var, value="usb", command=self._on_mode_change
        ).pack(side="left", padx=12, pady=6)

        net_frame = ttk.LabelFrame(self.root, text="Alamat Smartphone")
        net_frame.pack(fill="x", **pad)

        ttk.Label(net_frame, text="IP Address:").grid(row=0, column=0, padx=8, pady=8, sticky="w")
        self.ip_entry = ttk.Entry(net_frame, textvariable=self.ip_var, width=22)
        self.ip_entry.grid(row=0, column=1, padx=6, pady=8)

        ttk.Label(net_frame, text="Port:").grid(row=0, column=2, padx=4, pady=8)
        ttk.Entry(net_frame, textvariable=self.port_var, width=8).grid(row=0, column=3, padx=6, pady=8)

        ttk.Checkbutton(
            self.root, text="Tampilkan Jendela Preview Lokal", variable=self.preview_var
        ).pack(anchor="w", padx=16, pady=4)

        self.btn_connect = ttk.Button(self.root, text="Start Virtual Webcam", command=self.toggle_connection)
        self.btn_connect.pack(fill="x", padx=16, pady=8)

        remote_frame = ttk.Frame(self.root)
        remote_frame.pack(fill="x", padx=16, pady=4)
        ttk.Button(remote_frame, text="Switch Camera", command=lambda: self.send_remote_cmd("switch_camera")).pack(side="left", expand=True, fill="x", padx=2)
        ttk.Button(remote_frame, text="Toggle Flash", command=lambda: self.send_remote_cmd("toggle_torch")).pack(side="left", expand=True, fill="x", padx=2)
        ttk.Button(remote_frame, text="Auto Focus", command=lambda: self.send_remote_cmd("autofocus")).pack(side="left", expand=True, fill="x", padx=2)

        ttk.Label(self.root, textvariable=self.status_var, foreground="#0a66c2").pack(pady=(10, 6))

    def _on_mode_change(self):
        if self.mode_var.get() == "usb":
            self.ip_var.set("127.0.0.1")
            self.ip_entry.configure(state="disabled")
        else:
            self.ip_entry.configure(state="normal")

    def setup_adb_usb(self) -> bool:
        try:
            subprocess.run(["adb", "forward", "tcp:4747", "tcp:4747"], check=True, capture_output=True)
            subprocess.run(["adb", "forward", "tcp:4748", "tcp:4748"], check=True, capture_output=True)
            return True
        except Exception as exc:
            messagebox.showerror(
                "ADB USB Error",
                f"Gagal menjalankan adb forward. Pastikan USB Debugging aktif dan ADB terinstal.\nDetail: {exc}"
            )
            return False

    def send_remote_cmd(self, action: str):
        url = f"http://{self.ip_var.get().strip()}:{self.port_var.get().strip()}/cmd?action={action}"
        threading.Thread(target=lambda: self._safe_get(url), daemon=True).start()

    @staticmethod
    def _safe_get(url: str):
        try:
            urllib.request.urlopen(url, timeout=2)
        except Exception:
            pass

    def toggle_connection(self):
        if self.running:
            self.running = False
            self.btn_connect.configure(text="Start Virtual Webcam")
            self.status_var.set("Status: Terputus")
            return

        if self.mode_var.get() == "usb":
            if not self.setup_adb_usb():
                return

        self.running = True
        self.btn_connect.configure(text="Stop Virtual Webcam")
        self.worker_thread = threading.Thread(target=self._stream_loop, daemon=True)
        self.worker_thread.start()

    def _stream_loop(self):
        ip = self.ip_var.get().strip()
        port = self.port_var.get().strip()
        stream_url = f"http://{ip}:{port}/video"
        vcam = None

        while self.running:
            try:
                self.status_var.set(f"Menghubungkan ke {stream_url} ...")
                req = urllib.request.urlopen(stream_url, timeout=5)
                byte_buffer = bytearray()

                while self.running:
                    chunk = req.read(16384)
                    if not chunk:
                        raise ConnectionError("Stream terputus dari perangkat")
                    byte_buffer.extend(chunk)

                    start_idx = byte_buffer.find(b"\xff\xd8")
                    end_idx = byte_buffer.find(b"\xff\xd9")

                    if start_idx != -1 and end_idx != -1 and end_idx > start_idx:
                        jpg_bytes = bytes(byte_buffer[start_idx : end_idx + 2])
                        del byte_buffer[: end_idx + 2]

                        frame_bgr = cv2.imdecode(np.frombuffer(jpg_bytes, dtype=np.uint8), cv2.IMREAD_COLOR)
                        if frame_bgr is None:
                            continue

                        h, w, _ = frame_bgr.shape
                        if vcam is None and HAS_VIRTUAL_CAM:
                            vcam = pyvirtualcam.Camera(width=w, height=h, fps=30, fmt=pyvirtualcam.PixelFormat.BGR)
                            self.status_var.set(f"LIVE • Virtual Cam: {vcam.device} ({w}x{h})")
                        elif vcam is None:
                            self.status_var.set(f"LIVE Preview ({w}x{h}) — instal pyvirtualcam/OBS untuk driver webcam")

                        if vcam is not None:
                            if vcam.width != w or vcam.height != h:
                                vcam.close()
                                vcam = pyvirtualcam.Camera(width=w, height=h, fps=30, fmt=pyvirtualcam.PixelFormat.BGR)
                            vcam.send(frame_bgr)
                            vcam.sleep_until_next_frame()

                        if self.preview_var.get():
                            cv2.imshow("DroidCam Alternative - PC Preview (Tekan Q untuk sembunyikan)", frame_bgr)
                            if cv2.waitKey(1) & 0xFF == ord("q"):
                                self.preview_var.set(False)
                                cv2.destroyAllWindows()

            except Exception as exc:
                if not self.running:
                    break
                self.status_var.set(f"Koneksi terputus, auto-reconnect dalam 2 detik... ({exc})")
                time.sleep(2.0)

        if vcam is not None:
            vcam.close()
        cv2.destroyAllWindows()


if __name__ == "__main__":
    root = tk.Tk()
    app = DroidCamPcClient(root)
    root.mainloop()
