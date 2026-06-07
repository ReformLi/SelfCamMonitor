package com.hpu.selfcammonitor.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import kotlin.math.abs

class MotionDetector {

    companion object {
        private const val SCALE_WIDTH = 64
        private const val SCALE_HEIGHT = 48
        private const val DEFAULT_SENSITIVITY = 50 // 0-100
    }

    // 上一帧的亮度数据
    private var previousLuma: ByteArray? = null
    private var sensitivity: Int = DEFAULT_SENSITIVITY

    // 灵敏度设置（0-100，数值越小越敏感）
    fun setSensitivity(value: Int) {
        sensitivity = value.coerceIn(0, 100)
    }

    // 核心检测：传入 ImageProxy，返回是否检测到运动
    fun detectMotion(image: ImageProxy): Boolean {
        val nv21 = imageToNv21(image) ?: return false
        val bitmap = nv21ToBitmap(nv21, image.width, image.height)
        val scaled = Bitmap.createScaledBitmap(bitmap, SCALE_WIDTH, SCALE_HEIGHT, false)
        bitmap.recycle()

        val luma = ByteArray(SCALE_WIDTH * SCALE_HEIGHT)
        // 提取亮度（Y分量，但不需转YUV，直接从NV21取Y部分）
        for (y in 0 until SCALE_HEIGHT) {
            for (x in 0 until SCALE_WIDTH) {
                val pixel = scaled.getPixel(x, y)
                // RGB转亮度近似
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                luma[y * SCALE_WIDTH + x] = gray.toByte()
            }
        }
        scaled.recycle()

        val prev = previousLuma
        previousLuma = luma

        if (prev == null) return false

        // 计算差异比例
        var diffCount = 0
        val threshold = ((100 - sensitivity) * 2.55).toInt().coerceAtLeast(5) // 将灵敏度映射到差异阈值
        for (i in luma.indices) {
            if (abs(luma[i] - prev[i]) > threshold) {
                diffCount++
            }
        }
        val diffRatio = diffCount.toFloat() / luma.size

        // 阈值：超过1%的像素差异视为运动（可调）
        return diffRatio > 0.01f
    }

    // ImageProxy YUV -> NV21 (复用我们之前的转换)
    private fun imageToNv21(image: ImageProxy): ByteArray? {
        val width = image.width
        val height = image.height
        // 直接借用 MJPEGStreamer 的转换方法？避免重复，我们在这里实现一个简洁版
        // 为保持独立，这里写一个简单直接的转换（如果已有通用方法可调用）
        // 此处重用你已有的安全转换逻辑（和 MJPEGStreamer 相同即可）
        return yuv420888ToNv21(image)
    }

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray? {
        // 直接复用 MJPEGStreamer 中已验证安全可靠的转换代码（复制过来）
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

    private fun nv21ToBitmap(nv21: ByteArray, width: Int, height: Int): Bitmap {
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
        val jpegData = out.toByteArray()
        return BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
    }
}