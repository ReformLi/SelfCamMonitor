package com.hpu.selfcammonitor.service

import android.Manifest
import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.hpu.selfcammonitor.manager.AlertManager
import com.hpu.selfcammonitor.utils.MJPEGStreamer
import com.hpu.selfcammonitor.utils.MotionDetector
import com.hpu.selfcammonitor.ui.MainActivity
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraService : LifecycleService() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var wakeLock: PowerManager.WakeLock
    private var cameraProvider: ProcessCameraProvider? = null

    private lateinit var mjpegStreamer: MJPEGStreamer
    private lateinit var streamServer: StreamServer

    private val motionDetector = MotionDetector()
    private val alertManager = AlertManager()

    // 配置项
    private var mjpegEnabled = true
    private var motionDetectionEnabled = true

    // 运动触发短视频录制（已验证可行）
//    private lateinit var videoCapture: VideoCapture<Recorder>
//    private var recording: Recording? = null
//    private val isRecording = AtomicBoolean(false)

    // 目录
    private lateinit var recordDir: File

    private val handler = Handler(Looper.getMainLooper())

    private var lastIgnoredLogTime = 0L

    // 录制相关
    private var mediaRecorder: MediaRecorder? = null
    private var recordingSurface: Surface? = null
    private var previewUseCase: Preview? = null  // 用于提供录制 Surface

    private lateinit var videoCapture: VideoCapture<Recorder>
    private var recording: Recording? = null
    private var isRecording = false

    private var lastFrameTime = 0L
    private var minFrameInterval = 0L // 由 fps 计算

    private val prefs by lazy { getSharedPreferences("camera_prefs", MODE_PRIVATE) }

    // 监控时间限制
    private var monitorStart: String? = null   // 如 "08:00"
    private var monitorEnd: String? = null     // 如 "20:00"

    // 录像模式
    private var recordMode = MODE_MOTION_TRIGGERED
    // 运动触发录像时长（毫秒）
    private var motionClipDurationMs = DEFAULT_MOTION_CLIP_SEC * 1000L
    // 连续录像分段时长（毫秒）
    private var continuousSegmentDurationMs = DEFAULT_CONTINUOUS_SEGMENT_SEC * 1000L

    // 连续录像相关
    private var continuousRecording = false          // 是否处于连续录像状态
    private var currentSegmentRecording: Recording? = null
    private val continuousSegmentHandler = Handler(Looper.getMainLooper())
    private var segmentRotateRunnable: Runnable? = null

    companion object {
        const val CHANNEL_ID = "camera_service_channel"
        const val NOTIFICATION_ID = 1
        const val TAG = "CameraService"

        // CameraService.kt  companion object 内添加
        const val MODE_CONTINUOUS = 0      // 连续录像
        const val MODE_MOTION_TRIGGERED = 1 // 运动触发录像
        const val MODE_PREVIEW_ONLY = 2    // 仅预览（不录像、不运动检测）

        // 默认值
        const val DEFAULT_MODE = MODE_MOTION_TRIGGERED
        const val DEFAULT_MOTION_CLIP_SEC = 10      // 秒
        const val DEFAULT_CONTINUOUS_SEGMENT_SEC = 600 // 10分钟
    }

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
        mjpegStreamer = MJPEGStreamer()
        streamServer = StreamServer(8080)
        streamServer.setMJPEGStreamer(mjpegStreamer)

        createNotificationChannel()
        acquireWakeLock()

        recordDir = File(getExternalFilesDir(null), "Recordings")
        if (!recordDir.exists()) recordDir.mkdirs()

        loadSettings()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("camera_prefs", MODE_PRIVATE)
        mjpegEnabled = prefs.getBoolean("mjpeg_enabled", true)
        motionDetectionEnabled = prefs.getBoolean("motion_detection_enabled", true)

        streamServer.username = prefs.getString("http_user", null)
        streamServer.password = prefs.getString("http_pass", null)

        // 同步给 StreamServer
        streamServer.isMjpegEnabled = mjpegEnabled

        // 读取动作灵敏度   设置灵敏度为 20（更敏感），轻微运动即触发录像；设为 80（较迟钝），需大幅度动作才触发。
        val sensitivity = prefs.getInt("sensitivity", 50)
        motionDetector.setSensitivity(sensitivity)

        // 监控时间限制
        monitorStart = prefs.getString("monitor_start", null)
        monitorEnd = prefs.getString("monitor_end", null)

        // 报警 URL 和静默期
        val alertQuiet = prefs.getInt("alert_quiet", 30)
        val rawUrl = prefs.getString("alert_url", null)
        alertManager.setAlertUrl(rawUrl?.takeIf { it.isNotBlank() && it.startsWith("http") })
        alertManager.setQuietPeriod(alertQuiet * 1000L) // 秒转毫秒

        // 新增：录像模式
        recordMode = prefs.getInt("record_mode", DEFAULT_MODE)
        // 运动触发录像时长（秒转毫秒）
        motionClipDurationMs = prefs.getInt("motion_clip_sec", DEFAULT_MOTION_CLIP_SEC) * 1000L
        // 连续录像分段时长（秒转毫秒）
        continuousSegmentDurationMs = prefs.getInt("continuous_segment_sec", DEFAULT_CONTINUOUS_SEGMENT_SEC) * 1000L
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        loadSettings()
        super.onStartCommand(intent, flags, startId)

        // 在 onStartCommand 中或 onCreate 中
        LocalBroadcastManager.getInstance(this).registerReceiver(configReceiver,
            IntentFilter("com.hpu.selfcammonitor.RELOAD_CONFIG"))

        try {
            streamServer.start()
            Log.d(TAG, "HTTP server started on port 8080")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server", e)
            stopSelf()
            return START_NOT_STICKY
        }

        val ipAddress = getLocalIpAddress()
        val notification = buildNotification(ipAddress)
        startForeground(NOTIFICATION_ID, notification)

        startCamera()

        sendStatusBroadcast()
        return START_STICKY
    }

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val oldMode = recordMode
            loadSettings() // 重新加载所有配置
            when (recordMode) {
                MODE_CONTINUOUS -> {
                    if (oldMode != MODE_CONTINUOUS) {
                        // 从其他模式切换到连续录像
                        stopMotionRecordingIfNeeded()   // 停止可能正在进行的运动录像
                        stopContinuousRecording()       // 停止旧连续录像（若有）
                        startContinuousRecording()      // 启动新连续录像
                    }
                }
                MODE_MOTION_TRIGGERED -> {
                    if (oldMode == MODE_CONTINUOUS) {
                        stopContinuousRecording()       // 停止连续录像
                    }
                    // 运动触发模式不需要立即录像，等待运动事件
                }
                MODE_PREVIEW_ONLY -> {
                    // 停止所有录像
                    stopContinuousRecording()
                    stopMotionRecordingIfNeeded()
                }
            }
            Log.d(TAG, "配置已更新: mode=$recordMode, motionClip=${motionClipDurationMs}ms, continuousSegment=${continuousSegmentDurationMs}ms")
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopContinuousRecording()
        stopMotionRecordingIfNeeded()
        streamServer.stop()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        stopClipRecording()
        if (wakeLock.isHeld) wakeLock.release()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(configReceiver)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "摄像头监控服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "用于保持摄像头后台运行"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(ip: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("监控运行中")
            .setContentText("访问: http://$ip:8080/video")
            .setSmallIcon(R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CameraService::WakeLock"
        )
        wakeLock.acquire()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // 读取设置的分辨率
            val resString = prefs.getString("resolution", "640x480") ?: "640x480"
            val parts = resString.split("x")
            val targetWidth = parts.getOrNull(0)?.toIntOrNull() ?: 640
            val targetHeight = parts.getOrNull(1)?.toIntOrNull() ?: 480

            // 1. 图像分析（MJPEG源 + 运动检测）
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(Size(targetWidth, targetHeight))
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { imageProxy ->
                try {
                    // 检查是否在允许的监控时间段内
                    if (!isWithinTimeWindow()) {
                        imageProxy.close()
                        return@Analyzer  // 不在时间窗内，直接丢弃帧
                    }
                    // 应用帧率限制
                    val now = System.currentTimeMillis()
                    if (now - lastFrameTime < minFrameInterval) {
                        imageProxy.close()   // 丢弃帧
                        return@Analyzer
                    }
                    lastFrameTime = now

                    // 根据录像模式处理
                    when (recordMode) {
                        MODE_CONTINUOUS -> {
                            // 连续录像：确保录像正在运行（服务启动时开始，模式切换时处理）
                            // 注意：连续录像的启动/停止在 onStartCommand 和模式切换时控制
                            // 这里不需要额外动作，只需保持推流（如果需要）
                        }
                        MODE_MOTION_TRIGGERED -> {
                            // 运动检测
                            val motion = motionDetector.detectMotion(imageProxy)
                            if (motion) {
                                Log.d(TAG, "检测到运动")
                                alertManager.sendMotionAlert()
                                startClipRecording()  // 启动短视频录制
                            }
                        }
                        MODE_PREVIEW_ONLY -> {
                            // 不录像、不运动检测，直接跳过运动检测和录制逻辑
                            // 注意：仍可保留 MJPEG 推流（如果用户需要预览）
                        }
                    }
                    // MJPEG 推流
                    if (mjpegEnabled) {
                        val jpegData = mjpegStreamer.imageToJpeg(imageProxy, 70)
                        if (jpegData != null) {
                            mjpegStreamer.pushFrame(jpegData)
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "帧分析错误", e)
                } finally {
                    imageProxy.close()
                }
            })

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.SD))
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    imageAnalysis,
                    videoCapture
                )
                Log.d(TAG, "相机绑定成功（含预览）")

                // 绑定成功后根据模式启动连续录像
                if (recordMode == MODE_CONTINUOUS) {
                    startContinuousRecording()
                }
            } catch (e: Exception) {
                Log.e(TAG, "相机绑定失败", e)
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

//    private fun generateMotionFileName(videoName : String): String {
//        val timestamp = System.currentTimeMillis()
//        val date = Date(timestamp)
//
//        // 紧凑格式：YYMMDDHHmmss（12位） + 毫秒后3位
//        val formatter = SimpleDateFormat("yyyyMMDDHHmm", Locale.getDefault())
//        val dateTimePart = formatter.format(date)
//        // 获取时间戳最后3位并补零（如 012）
//        val lastThreeDigits = (timestamp % 1000).toString().padStart(3, '0')
//
//        return "${videoName}_${dateTimePart}_${lastThreeDigits}.mp4"
//    }

    private fun isWithinTimeWindow(): Boolean {
        val start = monitorStart ?: return true  // 为空时无限制，全天
        val end = monitorEnd ?: return true  // 为空时无限制，全天
        if (start == end) return true  // 前后时间相等时 无限制，全天
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val startParts = start.split(":")
        val startMinutes = startParts[0].toInt() * 60 + startParts[1].toInt()
        val endParts = end.split(":")
        val endMinutes = endParts[0].toInt() * 60 + endParts[1].toInt()

        return if (startMinutes <= endMinutes) {
            currentMinutes in startMinutes..endMinutes
        } else {
            // 跨天情况，如 22:00 - 06:00
            currentMinutes >= startMinutes || currentMinutes <= endMinutes
        }
    }

    // 放在 CameraService 类内部
    private fun imageToNv21(image: ImageProxy): ByteArray? {
        // 复用安全转换逻辑（参考 MotionDetector 或 MJPEGStreamer 中的实现）
        return MJPEGStreamer.Companion.yuv420888ToNv21(image) // 若已为 public
    }

    private fun startClipRecording() {
        if (isRecording) return
        val dailyDir = getDailyRecordDir()
        val fileName = "motion_${System.currentTimeMillis()}.mp4"
        val file = File(dailyDir, fileName)
        val outputOptions = FileOutputOptions.Builder(file).build()

        val pending = videoCapture.output
            .prepareRecording(this, outputOptions)
            .apply {
                // 如果已授予录音权限，则启用音频
                if (ActivityCompat.checkSelfPermission(
                        this@CameraService,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        Log.d(TAG, "运动录像开始（含音频）: ${file.name}")
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        Log.d(TAG, "运动录像完成: ${file.name}")
                    }
                }
            }
        recording = pending

        handler.postDelayed({
            recording?.stop()
            recording = null
        }, motionClipDurationMs)
    }

    private fun stopClipRecording() {
        if (!isRecording) return
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {}
        releaseMediaRecorder()
        // 释放预览 Surface 提供
        previewUseCase?.setSurfaceProvider(cameraExecutor) { request ->
            request.willNotProvideSurface()
        }
        isRecording = false
        Log.d(TAG, "运动录像完成")
    }

    private fun releaseMediaRecorder() {
        try { mediaRecorder?.reset() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        recordingSurface = null
    }

    /**
     * 启动连续录像的第一个分段
     */
    private fun startContinuousRecording() {
        if (recordMode != MODE_CONTINUOUS) return
        if (continuousRecording) {
            Log.d(TAG, "连续录像已在运行中")
            return
        }
        continuousRecording = true
        startNewContinuousSegment()
    }
    //获取当前日期文件夹
    private fun getDailyRecordDir(): File {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val dailyDir = File(recordDir, dateStr)
        if (!dailyDir.exists()) dailyDir.mkdirs()
        return dailyDir
    }

    /**
     * 开始一个新的连续录像分段文件
     */
    private fun startNewContinuousSegment() {
        if (!continuousRecording) return

        val dailyDir = getDailyRecordDir()
        val fileName = "video_${System.currentTimeMillis()}.mp4"
        val file = File(dailyDir, fileName)
        val outputOptions = FileOutputOptions.Builder(file).build()

        val pending = videoCapture.output
            .prepareRecording(this, outputOptions)
            .apply {
                if (ActivityCompat.checkSelfPermission(this@CameraService, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        Log.d(TAG, "连续录像分段开始: ${file.name}")
                    }
                    is VideoRecordEvent.Finalize -> {
                        Log.d(TAG, "连续录像分段完成: ${file.name}, 原因: ${event.error}")
                        // 分段结束后，如果仍处于连续录像模式，启动下一段
                        if (recordMode == MODE_CONTINUOUS && continuousRecording) {
                            startNewContinuousSegment()
                        }
                    }
                }
            }
        currentSegmentRecording = pending

        // 设置定时器，到达分段时长后停止当前分段（Finalize 事件中会自动开启下一段）
        segmentRotateRunnable = Runnable {
            if (recordMode == MODE_CONTINUOUS && continuousRecording) {
                currentSegmentRecording?.stop()
                currentSegmentRecording = null
            }
        }
        continuousSegmentHandler.postDelayed(segmentRotateRunnable!!, continuousSegmentDurationMs)
    }

    /**
     * 停止连续录像（取消定时器，停止当前分段）
     */
    private fun stopContinuousRecording() {
        continuousRecording = false
        segmentRotateRunnable?.let { continuousSegmentHandler.removeCallbacks(it) }
        currentSegmentRecording?.stop()
        currentSegmentRecording = null
    }

    /**
     * 停止运动触发录像（如果正在录制）
     */
    private fun stopMotionRecordingIfNeeded() {
        if (recordMode == MODE_MOTION_TRIGGERED && isRecording) {
            recording?.stop()
            recording = null
            isRecording = false
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get IP", e)
        }
        return "0.0.0.0"
    }

    private fun sendStatusBroadcast() {
        val ip = getLocalIpAddress()
        val intent = Intent("com.hpu.selfcammonitor.SERVICE_STATUS")
        intent.putExtra("ip", ip)
        intent.putExtra("running", true)
        intent.putExtra("mjpeg_enabled", mjpegEnabled)
        intent.putExtra("motion_detection_enabled", motionDetectionEnabled)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // 将 ImageProxy 转换为指定尺寸的 NV21
    private fun imageToNv21Scaled(image: ImageProxy, targetWidth: Int, targetHeight: Int): ByteArray? {
        try {
            // 先获得原始 NV21
            val nv21 = imageToNv21(image) ?: return null
            // 使用 YuvImage 解码为 Bitmap，再缩放，再转回 NV21（保留一次 JPEG 转换但保证兼容）
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            if (!yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)) {
                Log.e(TAG, "YuvImage 压缩失败")
                return null
            }
            val jpegData = out.toByteArray()
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val originalBitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size, opts)
                ?: return null
            val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, true)
            originalBitmap.recycle()
            val result = bitmapToNv21(scaledBitmap)
            scaledBitmap.recycle()
            return result
        } catch (e: Exception) {
            Log.e(TAG, "缩放转换异常", e)
            return null
        }
    }

    // Bitmap 转 NV21
    private fun bitmapToNv21(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        val yuv = ByteArray(width * height * 3 / 2)
        var index = 0
        var uvIndex = width * height
        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = argb[index]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[index] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && index % 2 == 0) {
                    val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    yuv[uvIndex++] = v.toByte()
                    yuv[uvIndex++] = u.toByte()
                }
                index++
            }
        }
        return yuv
    }
}