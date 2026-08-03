package com.hpu.selfcammonitor.utils

import androidx.camera.core.ImageProxy
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

    /**
     * 核心检测：直接从 ImageProxy 的 Y 平面（亮度平面）降采样出 64x48 缩略图，
     * 与上一帧做逐像素亮度差比较，差异像素占比超过 1% 判定为运动。
     *
     * 优化说明：NV21/YUV_420_888 的 Y 平面本身就是亮度分量，因此：
     * - 无需 YUV→NV21 全帧拷贝（只读 Y 平面的采样点）
     * - 无需全分辨率 JPEG 压缩/解码和 Bitmap 缩放
     * 单帧开销从"整帧编解码"降为约 3072 次字节读取，不再阻塞分析线程。
     */
    fun detectMotion(image: ImageProxy): Boolean {
        val width = image.width
        val height = image.height
        if (width < SCALE_WIDTH || height < SCALE_HEIGHT) return false

        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride

        // 最近邻采样，与旧实现 createScaledBitmap(filter=false) 的行为一致
        val luma = ByteArray(SCALE_WIDTH * SCALE_HEIGHT)
        for (sy in 0 until SCALE_HEIGHT) {
            val srcRowOffset = (sy * height / SCALE_HEIGHT) * yRowStride
            val dstRowOffset = sy * SCALE_WIDTH
            for (sx in 0 until SCALE_WIDTH) {
                val srcCol = sx * width / SCALE_WIDTH
                luma[dstRowOffset + sx] = yBuf.get(srcRowOffset + srcCol)
            }
        }

        val prev = previousLuma
        previousLuma = luma
        if (prev == null) return false

        // 计算差异比例（按无符号字节求差，避免有符号字节在 128 边界处的差值错误）
        var diffCount = 0
        val threshold = ((100 - sensitivity) * 2.55).toInt().coerceAtLeast(5) // 将灵敏度映射为差异阈值
        for (i in luma.indices) {
            val diff = abs((luma[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF))
            if (diff > threshold) {
                diffCount++
            }
        }
        val diffRatio = diffCount.toFloat() / luma.size

        // 阈值：超过1%的像素差异视为运动（可调）
        return diffRatio > 0.01f
    }
}
