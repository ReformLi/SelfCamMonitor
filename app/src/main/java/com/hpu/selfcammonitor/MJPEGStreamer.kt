package com.hpu.selfcammonitor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue

class MJPEGStreamer {

    companion object {
        const val BOUNDARY = "MYBOUNDARY"
        private const val TAG = "MJPEGStreamer"

        fun yuv420888ToNv21(image: androidx.camera.core.ImageProxy): ByteArray? {
            // ... 保持原有正确实现（不变）...
            val width = image.width
            val height = image.height
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val yBuf = yPlane.buffer
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer
            yBuf.rewind(); uBuf.rewind(); vBuf.rewind()

            val nv21 = ByteArray(width * height * 3 / 2)
            var pos = 0

            val yRowStride = yPlane.rowStride
            for (row in 0 until height) {
                if (yBuf.remaining() < width) break
                yBuf.get(nv21, pos, width)
                pos += width
                if (yRowStride > width) {
                    yBuf.position(yBuf.position() + (yRowStride - width))
                }
            }

            val chromaWidth = width / 2
            val chromaHeight = height / 2
            val uRowStride = uPlane.rowStride
            val vRowStride = vPlane.rowStride
            val uPixelStride = uPlane.pixelStride
            val vPixelStride = vPlane.pixelStride

            for (row in 0 until chromaHeight) {
                var uPos = row * uRowStride
                var vPos = row * vRowStride
                for (col in 0 until chromaWidth) {
                    if (uPos >= uBuf.limit() || vPos >= vBuf.limit()) break
                    uBuf.position(uPos)
                    vBuf.position(vPos)
                    val u = uBuf.get()
                    val v = vBuf.get()
                    nv21[pos++] = v
                    nv21[pos++] = u
                    uPos += uPixelStride
                    vPos += vPixelStride
                }
            }
            return nv21
        }
    }

    private val clients = ConcurrentLinkedQueue<OutputStream>()

    fun addClient(outputStream: OutputStream) {
        clients.add(outputStream)
        Log.d(TAG, "addClient done, total=${clients.size}")
    }

    fun removeClient(outputStream: OutputStream) {
        clients.remove(outputStream)
        Log.d(TAG, "Client disconnected, total: ${clients.size}")
        try { outputStream.close() } catch (_: Exception) {}
    }

    fun pushFrame(jpegData: ByteArray) {
        if (clients.isEmpty()) return
        if (jpegData.size < 1000) {
            Log.d(TAG, "Skipping small frame (size=${jpegData.size})")
            return
        }

        val frameHeader = "\r\n--$BOUNDARY\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${jpegData.size}\r\n\r\n"
        val headerBytes = frameHeader.toByteArray()
        val frameBytes = ByteArray(headerBytes.size + jpegData.size)
        System.arraycopy(headerBytes, 0, frameBytes, 0, headerBytes.size)
        System.arraycopy(jpegData, 0, frameBytes, headerBytes.size, jpegData.size)

        val iterator = clients.iterator()
        while (iterator.hasNext()) {
            val client = iterator.next()
            try {
                client.write(frameBytes)
                client.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Client disconnected, removing")
                iterator.remove()
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * 将 ImageProxy 转为 JPEG，并根据旋转角度校正方向。
     * 方案：先编码成 JPEG，再旋转 JPEG（避免 NV21 旋转的颜色问题）
     */
    fun imageToJpeg(image: androidx.camera.core.ImageProxy, quality: Int = 70): ByteArray? {
        val rotation = image.imageInfo.rotationDegrees
        Log.v(TAG, "imageToJpeg rotation=$rotation")

        // 1. YUV -> JPEG（不旋转）
        val nv21 = yuv420888ToNv21(image) ?: return null
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
        var jpegData = out.toByteArray()

        // 2. 如果需要旋转，解码、旋转、再编码
        if (rotation != 0) {
            val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
            val matrix = Matrix()
            matrix.postRotate(rotation.toFloat())
            val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            bitmap.recycle()

            val rotatedOut = ByteArrayOutputStream()
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, rotatedOut)
            rotatedBitmap.recycle()
            jpegData = rotatedOut.toByteArray()
        }

        return jpegData
    }
}