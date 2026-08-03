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
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var isRecording = false

    // 帧率限制（均匀间隔出帧）
    private var targetFps = 10
    private var nextEmitAt = 0L

    // 独立编码/推流线程池（避免阻塞相机分析线程，提升实际出帧量）
    private lateinit var encodeExecutor: ExecutorService

    // 实际帧率统计（在编码完成后计数，反映真正推出去的帧）
    private var frameCount = 0
    private var fpsWindowStart = 0L
    private var currentFps = 0

    // 只读诊断：区分「分析收到的原始帧」和「编码推送成功帧」
    private var diagRawCount = 0      // 分析线程收到的原始帧数（进如分析器起算，不含时间窗外）
    private var diagPushedCount = 0   // 实际编码并推送给客户端的帧数
    private var diagWindowStart = 0L

    // 只读诊断：分析线程内「NV21 拷贝 + 限速开销」耗时统计（min/avg/max，按秒汇总）
    private val diagCopyLock = Any()
    private var diagCopyCount = 0L
    private var diagCopySumMs = 0L
    private var diagCopyMinMs = Long.MAX_VALUE
    private var diagCopyMaxMs = 0L
    private var diagThreadName: String = "?"

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

        // 服务运行状态标志（供界面查询，替代已弃用的 ActivityManager.getRunningServices）
        @Volatile
        var isRunning: Boolean = false
            private set

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
        encodeExecutor = Executors.newFixedThreadPool(2)
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

        // 帧率控制（每秒平均策略）
        targetFps = prefs.getInt("fps", 16).coerceIn(1, 30)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        loadSettings()
        super.onStartCommand(intent, flags, startId)

        // 在 onStartCommand 中或 onCreate 中
        // 注册配置热重载广播（RECEIVER_NOT_EXPORTED：仅接收本应用内广播，满足 Android 13+ 要求）
        ContextCompat.registerReceiver(
            this,
            configReceiver,
            IntentFilter("com.hpu.selfcammonitor.RELOAD_CONFIG"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

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
        isRunning = true

        startCamera()

        sendStatusBroadcast()
        return START_STICKY
    }

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val oldMode = recordMode
            val wasPreviewOnly = (oldMode == MODE_PREVIEW_ONLY)
            loadSettings() // 重新加载所有配置
            val isPreviewOnly = (recordMode == MODE_PREVIEW_ONLY)

            // 如果在"仅预览"和"录像模式"之间切换，需要重新绑定相机用例
            if (wasPreviewOnly != isPreviewOnly) {
                Log.d(TAG, "模式跨越了仅预览边界，重新绑定相机用例: old=$oldMode, new=$recordMode")
                startCamera()
                return
            }

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
        isRunning = false
        stopContinuousRecording()
        stopMotionRecordingIfNeeded()
        streamServer.stop()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        encodeExecutor.shutdown()
        stopClipRecording()
        if (wakeLock.isHeld) wakeLock.release()
        try {
            unregisterReceiver(configReceiver)
        } catch (e: IllegalArgumentException) {
            // 接收器未注册（如 onStartCommand 未执行完），忽略
        }
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
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(targetWidth, targetHeight),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()
            val imageAnalysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(resolutionSelector)
            // 请求相机按 targetFps 出帧（Camera2 interop 设置 AE 目标帧率范围），
            // 否则 CameraX 默认按传感器可用帧率给，可能远低于用户设置值
            applyTargetFps(imageAnalysisBuilder, targetFps)
            val imageAnalysis = imageAnalysisBuilder.build()

            imageAnalysis.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { imageProxy ->
                try {
                    // 检查是否在允许的监控时间段内
                    if (!isWithinTimeWindow()) {
                        imageProxy.close()
                        return@Analyzer  // 不在时间窗内，直接丢弃帧
                    }
// 只读诊断：统计分析线程实际收到的帧（时间窗内）
                    diagRawCount++

                    // 应用帧率限制（均匀间隔到达：到固定时间间隔才处理，其余帧毫秒级丢弃）
                    val now = System.currentTimeMillis()
                    if (nextEmitAt == 0L) nextEmitAt = now
                    if (mjpegEnabled && now < nextEmitAt) {
                        imageProxy.close()
                        return@Analyzer
                    }
                    if (mjpegEnabled) {
                        // 防止掉帧后疯狂追赶造成突发，同时保持固定节奏
                        nextEmitAt = maxOf(nextEmitAt + (1000L / targetFps), now)
                    }
                    val frameStartTs = now  // 用于统计整帧处理耗时

                    // 根据录像模式处理
                    when (recordMode) {
                        MODE_CONTINUOUS -> {
                            // 连续录像：确保录像正在运行（服务启动时开始，模式切换时处理）
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
                            // 不录像、不运动检测
                        }
                    }
                    // MJPEG 推流：只在分析线程做廉价的 NV21 拷贝，旋转 + JPEG 编码交给独立线程池，
                    // 让分析线程快速返回，CameraX 不用等编码完成，从而让实际出帧更贴近目标帧率。
                    if (mjpegEnabled) {
                        val copyStart = System.nanoTime()
                        val nv21 = MJPEGStreamer.yuv420888ToNv21(imageProxy)
                        val copyCostMs = (System.nanoTime() - copyStart) / 1_000_000L
                        // 只读诊断：汇总该秒的拷贝耗时，分析线程单帧 CPU 成本
                        synchronized(diagCopyLock) {
                            diagThreadName = Thread.currentThread().name
                            diagCopyCount++
                            diagCopySumMs += copyCostMs
                            if (copyCostMs < diagCopyMinMs) diagCopyMinMs = copyCostMs
                            if (copyCostMs > diagCopyMaxMs) diagCopyMaxMs = copyCostMs
                        }
                        if (nv21 != null) {
                            val frameWidth = imageProxy.width
                            val frameHeight = imageProxy.height
                            val rotation = imageProxy.imageInfo.rotationDegrees
                            encodeExecutor.execute {
                                val jpeg = mjpegStreamer.nv21ToJpeg(nv21, frameWidth, frameHeight, rotation, 60)
                                if (jpeg != null) {
                                    mjpegStreamer.pushFrame(jpeg)
                                    updateFps()
                                }
                            }
                        }
                    }

                    val frameCost = System.currentTimeMillis() - frameStartTs
//                    Log.d(TAG, "帧处理完成: 耗时=${frameCost}ms, 实际帧率约=${1000 / frameCost.coerceAtLeast(1)}fps")

                } catch (e: Exception) {
                    Log.e(TAG, "帧分析错误", e)
                } finally {
                    imageProxy.close()
                }
            })

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()

                // 根据录像模式决定是否绑定 VideoCapture
                val useCases = mutableListOf<UseCase>(imageAnalysis)
                if (recordMode == MODE_CONTINUOUS || recordMode == MODE_MOTION_TRIGGERED) {
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.SD))
                        .build()
                    videoCapture = VideoCapture.withOutput(recorder)
                    useCases.add(videoCapture!!)
                    Log.d(TAG, "相机绑定：ImageAnalysis + VideoCapture")
                } else {
                    videoCapture = null
                    Log.d(TAG, "相机绑定：仅 ImageAnalysis（仅预览模式）")
                }

                cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    *useCases.toTypedArray()
                ).also { camera ->
                    // 只读诊断：打印传感器在该分辨率下的可达帧率上限
                    logCameraFpsCap(camera, targetWidth, targetHeight)
                }
                Log.d(TAG, "相机绑定成功")

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
//        // 紧凑格式：YYMMddHHmmss（12位） + 毫秒后3位
//        val formatter = SimpleDateFormat("yyyyMMddHHmm", Locale.getDefault())
//        val dateTimePart = formatter.format(date)
//        // 获取时间戳最后3位并补零（如 012）
//        val lastThreeDigits = (timestamp % 1000).toString().padStart(3, '0')
//
//        return "${videoName}_${dateTimePart}_${lastThreeDigits}.mp4"
//    }

    // 只读诊断：打印相机/传感器在当前分辨率下的可达帧率上限
    // （帧率上限 = 1e9ns / getOutputMinFrameDuration，即最短帧间隔对应的最大 fps）
    private fun logCameraFpsCap(camera: Camera?, width: Int, height: Int) {
        if (camera == null) return
        try {
            val cameraId = Camera2CameraInfo.from(camera.cameraInfo).cameraId
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map: StreamConfigurationMap? =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map == null) {
                Log.d(TAG, "FPS诊断: 无法读取 SCALER_STREAM_CONFIGURATION_MAP")
                return
            }
            val minDurNs = map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, Size(width, height))
            if (minDurNs != 0L) {
                val maxFps = 1_000_000_000L / minDurNs
                Log.d(TAG, "FPS诊断: 传感器可达上限 ${maxFps}fps @ ${width}x${height} (minFrameDuration=${minDurNs}ns)")
            } else {
                Log.d(TAG, "    FPS诊断: ${width}x${height} 不支持 YUV_420_888 输出，无法确定帧率上限")
            }
        } catch (e: Exception) {
            Log.e(TAG, "FPS诊断: 读取传感器帧率上限失败", e)
        }
    }

    // 通过 Camera2 interop 请求相机按目标帧率出帧（AE 目标帧率范围）。
    // 仅供参考，不保证精确达到；分辨率/帧率仍需重启监控才生效。
    private fun applyTargetFps(builder: ImageAnalysis.Builder, fps: Int) {
        if (fps <= 0) return
        try {
            Camera2Interop.Extender(builder)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(fps, fps)
                )
        } catch (e: Exception) {
            Log.e(TAG, "设置相机目标帧率失败", e)
        }
    }

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
        val vc = videoCapture ?: run {
            Log.w(TAG, "startClipRecording: videoCapture 为 null，无法开始录像")
            return
        }
        val dailyDir = getDailyRecordDir()
        val fileName = "motion_${System.currentTimeMillis()}.mp4"
        val file = File(dailyDir, fileName)
        val outputOptions = FileOutputOptions.Builder(file).build()

        val pending = vc.output
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
        val vc = videoCapture ?: run {
            Log.w(TAG, "startNewContinuousSegment: videoCapture 为 null，无法开始录像")
            return
        }

        val dailyDir = getDailyRecordDir()
        val fileName = "video_${System.currentTimeMillis()}.mp4"
        val file = File(dailyDir, fileName)
        val outputOptions = FileOutputOptions.Builder(file).build()

        val pending = vc.output
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

    // 更新实际帧率（在编码线程中调用，体现真实推出去的帧）
    @Synchronized
    private fun updateFps() {
        val now = System.currentTimeMillis()
        frameCount++
        diagPushedCount++
        if (fpsWindowStart == 0L) {
            fpsWindowStart = now
        } else if (now - fpsWindowStart >= 1000L) {
            currentFps = frameCount
            frameCount = 0
            fpsWindowStart = now
            val fpsIntent = Intent("com.hpu.selfcammonitor.FPS_UPDATE")
            fpsIntent.putExtra("fps", currentFps)
            sendBroadcast(fpsIntent)

            // 只读诊断:每秒打印「分析收到 RAW 帧数 / 实际推送 PUSHED 帧数」
            if (diagWindowStart == 0L) diagWindowStart = now
            if (now - diagWindowStart >= 1000L) {
                val copyAvg: Long
                val copyMin: Long
                val copyMax: Long
                val copyN: Long
                val thread: String
                synchronized(diagCopyLock) {
                    copyAvg = if (diagCopyCount > 0) diagCopySumMs / diagCopyCount else 0
                    copyMin = if (diagCopyCount > 0) diagCopyMinMs else 0
                    copyMax = diagCopyMaxMs
                    copyN = diagCopyCount
                    thread = diagThreadName
                    diagCopyCount = 0
                    diagCopySumMs = 0
                    diagCopyMinMs = Long.MAX_VALUE
                    diagCopyMaxMs = 0
                }
                Log.d(TAG, "FPS诊断: RAW=${diagRawCount}fps, PUSHED=${diagPushedCount}fps, 显示fps=$currentFps, targetFps=$targetFps, " +
                        "拷贝耗时(${thread}): n=$copyN, min=${copyMin}ms, avg=${copyAvg}ms, max=${copyMax}ms")
                diagRawCount = 0
                diagPushedCount = 0
                diagWindowStart = now
            }
        }
    }

    private fun sendStatusBroadcast() {
        val ip = getLocalIpAddress()
        val intent = Intent("com.hpu.selfcammonitor.SERVICE_STATUS")
        intent.putExtra("ip", ip)
        intent.putExtra("running", true)
        intent.putExtra("mjpeg_enabled", mjpegEnabled)
        intent.putExtra("motion_detection_enabled", motionDetectionEnabled)
        sendBroadcast(intent)
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