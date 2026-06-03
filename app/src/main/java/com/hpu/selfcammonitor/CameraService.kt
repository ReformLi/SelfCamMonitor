package com.hpu.selfcammonitor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
    private var continuousRecording = false // 暂未启用

    // 运动触发短视频录制（已验证可行）
//    private lateinit var videoCapture: VideoCapture<Recorder>
//    private var recording: Recording? = null
//    private val isRecording = AtomicBoolean(false)

    // 目录
    private lateinit var recordDir: File

    private val handler = Handler(Looper.getMainLooper())

    private var lastIgnoredLogTime = 0L

    private val clipDurationMs = 10_000L

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

    companion object {
        const val CHANNEL_ID = "camera_service_channel"
        const val NOTIFICATION_ID = 1
        const val TAG = "CameraService"
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
            loadSettings()
            Log.d(TAG, "配置已更新: mjpeg=$mjpegEnabled, motion=$motionDetectionEnabled")
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        super.onDestroy()

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
            .setSmallIcon(android.R.drawable.ic_menu_camera)
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
                .setTargetResolution(android.util.Size(targetWidth, targetHeight))
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
//                    Log.d(TAG, "帧已分析，时间: ${System.currentTimeMillis()}")
                    // 运动检测
                    if (motionDetectionEnabled) {
                        val motion = motionDetector.detectMotion(imageProxy)
                        if (motion) {
                            Log.d(TAG, "检测到运动")
                            alertManager.sendMotionAlert()
                            startClipRecording()   // 启动短视频录制
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
            } catch (e: Exception) {
                Log.e(TAG, "相机绑定失败", e)
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun generateMotionFileName(): String {
        val timestamp = System.currentTimeMillis()
        val date = Date(timestamp)

        // 紧凑格式：YYMMDDHHmmss（12位） + 毫秒后3位
        val formatter = SimpleDateFormat("yyMMddHHmmss", Locale.getDefault())
        val dateTimePart = formatter.format(date)
        // 获取时间戳最后3位并补零（如 012）
        val lastThreeDigits = (timestamp % 1000).toString().padStart(3, '0')

        return "motion_${dateTimePart}_${lastThreeDigits}.mp4"
    }

    private fun isWithinTimeWindow(): Boolean {
        val start = monitorStart ?: return true  // 为空时无限制，全天
        val end = monitorEnd ?: return true  // 为空时无限制，全天
        if (start == end) return true  // 前后时间相等时 无限制，全天
        val now = java.util.Calendar.getInstance()
        val currentMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)

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
        return MJPEGStreamer.yuv420888ToNv21(image) // 若已为 public
    }

    private fun startClipRecording() {
        if (isRecording) return
        val fileName = generateMotionFileName()
        val file = File(recordDir, fileName)
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
        }, clipDurationMs)
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