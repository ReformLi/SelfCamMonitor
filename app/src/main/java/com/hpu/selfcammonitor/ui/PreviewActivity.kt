package com.hpu.selfcammonitor.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hpu.selfcammonitor.R
import com.hpu.selfcammonitor.service.CameraService
import com.hpu.selfcammonitor.utils.FileSizeFormatter
import com.hpu.selfcammonitor.utils.MJPEGStreamer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 本地预览页：监控手机本机查看摄像头画面。
 *
 * 实现方式（方案 C）：进程内直接订阅服务编码循环产出的 JPEG 帧，
 * 不走 HTTP、不做认证、不加相机用例（零额外相机/帧率成本）。
 * 解码限速 10fps——"看一眼画面"足够，避免占用主线程。
 *
 * HUD 信息层：REC/模式/实际帧率/存储剩余/时间码，全部来自现有状态，零新增采集链路。
 * 适配全面屏与非全面屏：不隐藏状态栏，HUD 通过 window insets 避开状态栏/刘海。
 */
class PreviewActivity : AppCompatActivity() {

    companion object {
        private const val MIN_DECODE_INTERVAL_MS = 100L  // 10fps 上限
        private const val NO_FRAME_TIMEOUT_MS = 2500L    // 超时提示阈值
        private const val STORAGE_REFRESH_MS = 10_000L   // 存储信息刷新间隔
    }

    private lateinit var ivPreview: ImageView
    private lateinit var tvRec: TextView
    private lateinit var tvMode: TextView
    private lateinit var tvClock: TextView
    private lateinit var tvFps: TextView
    private lateinit var tvStorage: TextView
    private lateinit var tvWindowBanner: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val latestFrame = AtomicReference<ByteArray?>(null)
    @Volatile
    private var decodeScheduled = false
    @Volatile
    private var lastFrameArrival = 0L
    private var lastDecodeAt = 0L

    // 帧率统计：监听回调线程计数，每秒结算一次显示
    private val frameCounter = AtomicInteger(0)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // 编码线程回调：只存最新帧 + 调度一次解码，天然丢帧（旧帧被覆盖）
    private val frameListener = object : MJPEGStreamer.FrameListener {
        override fun onFrame(jpeg: ByteArray) {
            latestFrame.set(jpeg)
            lastFrameArrival = System.currentTimeMillis()
            frameCounter.incrementAndGet()
            scheduleDecode()
        }
    }

    // 每秒结算：时钟 + 帧率 + REC/模式/时间窗状态
    private val hudTicker = object : Runnable {
        override fun run() {
            tvClock.text = timeFmt.format(Date())
            tvFps.text = "${frameCounter.getAndSet(0)} fps"

            val service = CameraService.instance
            if (service != null) {
                tvMode.text = service.hudModeLabel
                tvRec.visibility = if (service.hudRecording) View.VISIBLE else View.GONE
                // 每秒重置横幅文案：避免被无帧超时提示覆盖后不恢复
                if (service.hudWithinWindow) {
                    tvWindowBanner.visibility = View.GONE
                } else {
                    tvWindowBanner.text = "当前为非监控时段，画面可能暂停"
                    tvWindowBanner.visibility = View.VISIBLE
                }
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    // 存储剩余：低频刷新（后台线程 StatFs，不占主线程）
    private val storageTicker = object : Runnable {
        override fun run() {
            Thread {
                val free = try {
                    StatFs(getExternalFilesDir(null)?.absolutePath).availableBytes
                } catch (_: Exception) {
                    null
                }
                val text = free?.let { "剩余 ${FileSizeFormatter.format(it)}" } ?: "剩余 --"
                runOnUiThread { tvStorage.text = text }
            }.start()
            mainHandler.postDelayed(this, STORAGE_REFRESH_MS)
        }
    }

    private val noFrameCheck = Runnable {
        if (System.currentTimeMillis() - lastFrameArrival > NO_FRAME_TIMEOUT_MS) {
            tvWindowBanner.text = "无画面：请确认监控运行中，且已开启 MJPEG 推流"
            tvWindowBanner.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)
        title = "实时画面"
        // 查看监控时保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 状态栏沿用应用主题（primary_dark），与其他页面一致；
        // 内容自动布局在状态栏下方，全面屏/非全面屏均正常

        ivPreview = findViewById(R.id.ivPreview)
        tvRec = findViewById(R.id.tvRec)
        tvMode = findViewById(R.id.tvMode)
        tvClock = findViewById(R.id.tvClock)
        tvFps = findViewById(R.id.tvFps)
        tvStorage = findViewById(R.id.tvStorage)
        tvWindowBanner = findViewById(R.id.tvWindowBanner)
    }

    override fun onStart() {
        super.onStart()
        val service = CameraService.instance
        if (service == null) {
            tvWindowBanner.text = "监控服务未运行"
            tvWindowBanner.visibility = View.VISIBLE
            return
        }
        service.addPreviewListener(frameListener)
        mainHandler.post(hudTicker)
        mainHandler.post(storageTicker)
        mainHandler.postDelayed(noFrameCheck, NO_FRAME_TIMEOUT_MS)
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(hudTicker)
        mainHandler.removeCallbacks(storageTicker)
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
        } catch (_: Exception) {
        }
    }
}
