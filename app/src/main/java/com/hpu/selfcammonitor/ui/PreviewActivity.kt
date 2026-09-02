package com.hpu.selfcammonitor.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hpu.selfcammonitor.R
import com.hpu.selfcammonitor.service.CameraService
import com.hpu.selfcammonitor.utils.MJPEGStreamer
import java.util.concurrent.atomic.AtomicReference

/**
 * 本地预览页：监控手机本机查看摄像头画面。
 *
 * 实现方式（方案 C）：进程内直接订阅服务编码循环产出的 JPEG 帧，
 * 不走 HTTP、不做认证、不加相机用例（零额外相机/帧率成本）。
 * 解码限速 10fps——"看一眼画面"足够，避免占用主线程。
 */
class PreviewActivity : AppCompatActivity() {

    companion object {
        private const val MIN_DECODE_INTERVAL_MS = 100L  // 10fps 上限
        private const val NO_FRAME_TIMEOUT_MS = 2500L    // 超时提示阈值
    }

    private lateinit var ivPreview: ImageView
    private lateinit var tvHint: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val latestFrame = AtomicReference<ByteArray?>(null)
    @Volatile
    private var decodeScheduled = false
    @Volatile
    private var lastFrameArrival = 0L
    private var lastDecodeAt = 0L

    // 编码线程回调：只存最新帧 + 调度一次解码，天然丢帧（旧帧被覆盖）
    private val frameListener = object : MJPEGStreamer.FrameListener {
        override fun onFrame(jpeg: ByteArray) {
            latestFrame.set(jpeg)
            lastFrameArrival = System.currentTimeMillis()
            scheduleDecode()
        }
    }

    private val noFrameCheck = Runnable {
        if (System.currentTimeMillis() - lastFrameArrival > NO_FRAME_TIMEOUT_MS) {
            tvHint.text = "无画面：请确认监控运行中，且已开启 MJPEG 推流"
            tvHint.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)
        title = "实时画面"
        // 查看监控时保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ivPreview = findViewById(R.id.ivPreview)
        tvHint = findViewById(R.id.tvPreviewHint)
    }

    override fun onStart() {
        super.onStart()
        val service = CameraService.instance
        if (service == null) {
            tvHint.text = "监控服务未运行"
            tvHint.visibility = View.VISIBLE
            return
        }
        service.addPreviewListener(frameListener)
        tvHint.text = "连接中…"
        tvHint.visibility = View.VISIBLE
        mainHandler.postDelayed(noFrameCheck, NO_FRAME_TIMEOUT_MS)
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(noFrameCheck)
        CameraService.instance?.removePreviewListener(frameListener)
    }

    private fun scheduleDecode() {
        if (decodeScheduled) return
        decodeScheduled = true
        mainHandler.post {
            decodeScheduled = false
            val now = SystemClock.uptimeMillis()
            val elapsed = now - lastDecodeAt
            if (elapsed < MIN_DECODE_INTERVAL_MS) {
                mainHandler.postDelayed({ doDecode() }, MIN_DECODE_INTERVAL_MS - elapsed)
            } else {
                doDecode()
            }
        }
    }

    private fun doDecode() {
        val bytes = latestFrame.getAndSet(null) ?: return
        lastDecodeAt = SystemClock.uptimeMillis()
        try {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            ivPreview.setImageBitmap(bmp)
            tvHint.visibility = View.GONE  // 首帧到达，隐藏提示
        } catch (_: Exception) {
        }
    }
}
