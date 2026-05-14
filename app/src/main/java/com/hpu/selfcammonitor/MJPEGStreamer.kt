package com.hpu.selfcammonitor

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

class MJPEGStreamer {

    companion object {
        const val BOUNDARY = "MYBOUNDARY"            // 不含前导 '--'
        private const val TAG = "MJPEGStreamer"

        fun yuv420888ToNv21(image: androidx.camera.core.ImageProxy): ByteArray? {
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

            // Y 分量逐行拷贝
            val yRowStride = yPlane.rowStride
            for (row in 0 until height) {
                if (yBuf.remaining() < width) break
                yBuf.get(nv21, pos, width)
                pos += width
                if (yRowStride > width) {
                    yBuf.position(yBuf.position() + (yRowStride - width))
                }
            }

            // UV 分量交错为 VUVU...
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
                    nv21[pos++] = v  // V
                    nv21[pos++] = u  // U
                    uPos += uPixelStride
                    vPos += vPixelStride
                }
            }
            return nv21
        }
    }
    private var firstFrameLogged = false

    private val clients = ConcurrentLinkedQueue<OutputStream>()

    // 添加客户端（不再发送任何数据，只记录流）
    fun addClient(outputStream: OutputStream) {
        // 不再写初始边界，纯注册
        clients.add(outputStream)
        Log.d(TAG, "addClient done, total=${clients.size}")
    }

    fun removeClient(outputStream: OutputStream) {
        clients.remove(outputStream)
        Log.d(TAG, "Client disconnected, total: ${clients.size}")
        try { outputStream.close() } catch (_: Exception) {}
    }

    // 在 MJPEGStreamer 中添加这个方法
    fun startTestStream() {
        Thread {
            val bmp = android.graphics.Bitmap.createBitmap(320, 240, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.RED)
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
            bmp.recycle()
            val testFrame = out.toByteArray()

            while (true) {
                if (clients.isEmpty()) {
                    Thread.sleep(500)
                    continue
                }
                pushFrame(testFrame)
                Thread.sleep(200) // 每秒5帧
            }
        }.start()
    }

    // 推送一帧（纯 MJPEG 帧体）
    fun pushFrame(jpegData: ByteArray) {
        Log.d(TAG, "pushFrame called, clients=${clients.size}, jpegSize=${jpegData.size}")
        // 只打印第一次调用的帧头
        if (!firstFrameLogged) {
            firstFrameLogged = true
            Log.d(TAG, "pushFrame first call, size=${jpegData.size}, " +
                    "hex start: ${jpegData.take(20).joinToString { "%02X".format(it) }}")
        }
        if (clients.isEmpty()) return

        if (jpegData.size < 1000) {
            Log.d(TAG, "Skipping small frame (size=${jpegData.size})")
            return
        }

        // 构建 MJPEG 帧：边界 + Content-Type + Content-Length + 数据
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
//                Log.d(TAG, "Wrote ${frameBytes.size} bytes to client")
            } catch (e: Exception) {
                Log.w(TAG, "客户端断开连接，移除")
                iterator.remove()
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    // YUV -> JPEG
    fun imageToJpeg(image: androidx.camera.core.ImageProxy, quality: Int = 70): ByteArray? {
        val nv21 = yuv420888ToNv21(image) ?: return null
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
        return out.toByteArray()
    }

    // YUV420_888 -> NV21（之前的安全版本）


}