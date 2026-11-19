package com.aripin.webcam.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

object YuvFrameEncoder {

    fun encodeImageProxyToJpeg(
        image: ImageProxy,
        jpegQuality: Int,
        mirrorHorizontal: Boolean = false
    ): ByteArray? {
        return try {
            val nv21 = yuv420888ToNv21(image)
            val width = image.width
            val height = image.height
            val rotation = image.imageInfo.rotationDegrees

            val rawJpegOut = ByteArrayOutputStream(width * height / 4)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            yuvImage.compressToJpeg(Rect(0, 0, width, height), jpegQuality, rawJpegOut)
            val rawJpegBytes = rawJpegOut.toByteArray()

            if (rotation == 0 && !mirrorHorizontal) {
                return rawJpegBytes
            }

            val bitmap = BitmapFactory.decodeByteArray(rawJpegBytes, 0, rawJpegBytes.size)
                ?: return rawJpegBytes

            val matrix = Matrix()
            if (rotation != 0) {
                matrix.postRotate(rotation.toFloat())
            }
            if (mirrorHorizontal) {
                matrix.postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
            }

            val transformed = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false
            )
            if (transformed !== bitmap) {
                bitmap.recycle()
            }

            val finalOut = ByteArrayOutputStream(rawJpegBytes.size)
            transformed.compress(Bitmap.CompressFormat.JPEG, jpegQuality, finalOut)
            transformed.recycle()
            finalOut.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 4

        val nv21 = ByteArray(ySize + uvSize * 2)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer: ByteBuffer = yPlane.buffer
        val uBuffer: ByteBuffer = uPlane.buffer
        val vBuffer: ByteBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        var pos = 0
        if (yPixelStride == 1 && yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
            pos = ySize
        } else {
            val rowBuffer = ByteArray(yRowStride)
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                val bytesToRead = minOf(yRowStride, yBuffer.remaining())
                yBuffer.get(rowBuffer, 0, bytesToRead)
                for (col in 0 until width) {
                    nv21[pos++] = rowBuffer[col * yPixelStride]
                }
            }
        }

        val uvRowStride = vPlane.rowStride
        val uvPixelStride = vPlane.pixelStride
        val uvHeight = height / 2
        val uvWidth = width / 2

        val vRowBytes = ByteArray(uvRowStride)
        val uRowBytes = ByteArray(uPlane.rowStride)

        for (row in 0 until uvHeight) {
            val vRowStart = row * uvRowStride
            val uRowStart = row * uPlane.rowStride

            vBuffer.position(vRowStart)
            uBuffer.position(uRowStart)

            val vRead = minOf(uvRowStride, vBuffer.remaining())
            val uRead = minOf(uPlane.rowStride, uBuffer.remaining())

            vBuffer.get(vRowBytes, 0, vRead)
            uBuffer.get(uRowBytes, 0, uRead)

            for (col in 0 until uvWidth) {
                val idx = col * uvPixelStride
                nv21[pos++] = vRowBytes[idx]
                nv21[pos++] = uRowBytes[idx]
            }
        }

        return nv21
    }
}
