package com.hpu.selfcammonitor.utils

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class MJPEGStreamer {

    companion object {
        const val BOUNDARY = "MYBOUNDARY"
        private const val TAG = "MJPEGStreamer"

        fun yuv420888ToNv21(image: ImageProxy): ByteArray? {
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

        /**
         * 旋转 NV21 数据。只支持 0/90/180/270 度。
         * 返回 Pair(旋转后的 nv21, 新的宽度)，新高度可通过长度反推。
         */
        fun rotateNv21(nv21: ByteArray, width: Int, height: Int, rotation: Int): Pair<ByteArray, Int> {
            return when (rotation % 360) {
                90 -> rotateNv2190(nv21, width, height) to height
                180 -> rotateNv21180(nv21, width, height) to width
                270 -> rotateNv21270(nv21, width, height) to height
                else -> nv21 to width
            }
        }

        // NV21 顺时针旋转 90 度
        private fun rotateNv2190(src: ByteArray, width: Int, height: Int): ByteArray {
            val dst = ByteArray(src.size)
            val ySize = width * height
            // 旋转 Y
            var dstIdx = 0
            for (x in 0 until width) {
                for (y in height - 1 downTo 0) {
                    dst[dstIdx++] = src[y * width + x]
                }
            }
            // 旋转 VU (chroma)
            val srcChromaStart = ySize
            var dstChromaIdx = ySize
            val cw = width / 2
            val ch = height / 2
            for (x in 0 until cw) {
                for (y in ch - 1 downTo 0) {
                    val srcIdx = srcChromaStart + y * cw * 2 + x * 2
                    dst[dstChromaIdx++] = src[srcIdx]       // V
                    dst[dstChromaIdx++] = src[srcIdx + 1]   // U
                }
            }
            return dst
        }

        // NV21 旋转 180 度
        private fun rotateNv21180(src: ByteArray, width: Int, height: Int): ByteArray {
            val dst = ByteArray(src.size)
            val ySize = width * height
            // 旋转 Y
            var dstIdx = 0
            for (y in height - 1 downTo 0) {
                for (x in width - 1 downTo 0) {
                    dst[dstIdx++] = src[y * width + x]
                }
            }
            // 旋转 VU
            var srcIdx = ySize
            var dstChromaIdx = dst.size
            val cw = width / 2
            val ch = height / 2
            val chromaSize = cw * ch * 2
            srcIdx = ySize + chromaSize
            while (dstChromaIdx > ySize) {
                dst[--dstChromaIdx] = src[--srcIdx] // U
                dst[--dstChromaIdx] = src[--srcIdx] // V
            }
            return dst
        }

        // NV21 顺时针旋转 270 度（= 逆时针 90 度）
        private fun rotateNv21270(src: ByteArray, width: Int, height: Int): ByteArray {
            val dst = ByteArray(src.size)
            val ySize = width * height
            // 旋转 Y
            var dstIdx = 0
            for (x in width - 1 downTo 0) {
                for (y in 0 until height) {
                    dst[dstIdx++] = src[y * width + x]
                }
            }
            // 旋转 VU
            var dstChromaIdx = ySize
            val cw = width / 2
            val ch = height / 2
            for (x in cw - 1 downTo 0) {
                for (y in 0 until ch) {
                    val srcIdx = ySize + y * cw * 2 + x * 2
                    dst[dstChromaIdx++] = src[srcIdx]
                    dst[dstChromaIdx++] = src[srcIdx + 1]
                }
            }
            return dst
        }
    }

    // 每个客户端独立的输出流 + 线程
    private data class ClientInfo(
        val outputStream: OutputStream,
        val pendingFrame: AtomicReference<ByteArray?> = AtomicReference(null)
    )

    private val clients = ConcurrentHashMap<OutputStream, ClientInfo>()
    private val pushExecutor: ExecutorService = Executors.newCachedThreadPool()

    // 最新一帧 JPEG（供 /snapshot 端点使用）
    private val latestJpeg = AtomicReference<ByteArray?>(null)

    // 最近一次推帧时间戳（供 /status 端点判断画面是否活跃）
    @Volatile
    private var lastFrameTime: Long = 0

    fun addClient(outputStream: OutputStream) {
        val info = ClientInfo(outputStream)
        clients[outputStream] = info
        // 为每个客户端启动一个推送线程
        pushExecutor.submit { clientPushLoop(info) }
        Log.d(TAG, "addClient done, total=${clients.size}")
    }

    fun removeClient(outputStream: OutputStream) {
        clients.remove(outputStream)
        Log.d(TAG, "Client disconnected, total: ${clients.size}")
        try { outputStream.close() } catch (_: Exception) {}
    }

    /** 获取最新一帧 JPEG 数据（供 /snapshot 端点） */
    fun getLatestJpeg(): ByteArray? = latestJpeg.get()

    /** 当前 MJPEG 客户端数量 */
    fun getClientCount(): Int = clients.size

    /** 最近一帧距今的毫秒数；从未推过帧时返回大值 */
    fun getLastFrameAge(): Long {
        val t = lastFrameTime
        return if (t == 0L) 999999999L else System.currentTimeMillis() - t
    }

    /**
     * 每个客户端独立的推送循环。
     * 使用 AtomicReference 存最新帧：新帧来了直接覆盖旧帧，丢弃慢帧。
     */
    private fun clientPushLoop(info: ClientInfo) {
        try {
            while (clients.containsValue(info)) {
                val frameBytes = info.pendingFrame.getAndSet(null)
                if (frameBytes != null) {
                    try {
                        info.outputStream.write(frameBytes)
                        info.outputStream.flush()
                    } catch (e: Exception) {
                        Log.w(TAG, "Client write failed, removing")
                        removeClient(info.outputStream)
                        return
                    }
                } else {
                    // 没有新帧，短暂等待
                    Thread.sleep(5)
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            try { info.outputStream.close() } catch (_: Exception) {}
        }
    }

    /**
     * 将一帧推送给所有客户端。
     * 只更新每个客户端的 pendingFrame，不阻塞。
     */
    fun pushFrame(jpegData: ByteArray) {
        if (jpegData.size < 1000) {
            Log.d(TAG, "Skipping small frame (size=${jpegData.size})")
            return
        }

        // 存储最新帧，供 /snapshot 端点使用（即使没有 MJPEG 客户端也存）
        latestJpeg.set(jpegData)
        lastFrameTime = System.currentTimeMillis()

        if (clients.isEmpty()) return

        val frameHeader = "\r\n--$BOUNDARY\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${jpegData.size}\r\n\r\n"
        val headerBytes = frameHeader.toByteArray()
        val frameBytes = ByteArray(headerBytes.size + jpegData.size)
        System.arraycopy(headerBytes, 0, frameBytes, 0, headerBytes.size)
        System.arraycopy(jpegData, 0, frameBytes, headerBytes.size, jpegData.size)

        // 只更新引用，不阻塞写
        clients.values.forEach { info ->
            info.pendingFrame.set(frameBytes)
        }
    }

    /**
     * 将 ImageProxy 转为 JPEG，并根据旋转角度校正方向。
     * 优化方案：在 NV21 层面旋转，只做一次 JPEG 编码。
     */
    fun imageToJpeg(image: ImageProxy, quality: Int = 60): ByteArray? {
        val width = image.width
        val height = image.height

        // 1. YUV -> NV21（本方法同时负责把 ImageProxy 转为可脱离生命周期的字节数组）
        val nv21 = yuv420888ToNv21(image) ?: return null
        val rotation = image.imageInfo.rotationDegrees

        return nv21ToJpeg(nv21, width, height, rotation, quality)
    }

    /**
     * 对已提取的 NV21 字节数组做旋转 + JPEG 编码。
     * 输入与 ImageProxy 生命周期无关，可放到独立线程执行，避免阻塞相机分析线程。
     */
    fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int, rotation: Int, quality: Int = 60): ByteArray? {
        // 在 NV21 层面旋转（纯字节操作，无编解码）
        val (rotatedNv21, newWidth) = if (rotation != 0) {
            rotateNv21(nv21, width, height, rotation)
        } else {
            nv21 to width
        }
        val newHeight = if (rotation == 90 || rotation == 270) width else height

        // 只做一次 JPEG 编码
        val yuvImage = YuvImage(rotatedNv21, ImageFormat.NV21, newWidth, newHeight, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, newWidth, newHeight), quality, out)
        return out.toByteArray()
    }
}
