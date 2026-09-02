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
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

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
    private var motionAlertEnabled = false  // 运动报警开关（与检测参数共用，独立于录像模式）
    private var useFrontCamera = false   // 镜头选择：false=后置，true=前置（启动相机时生效）
    private var actualCameraFacing = 0   // 实际绑定的镜头朝向：0=后置，1=前置（回退场景下与配置可能不同）
    private var actualResolution = "--"  // 实际绑定的推流分辨率（回退策略下可能与设置值不同）

    // 运动触发短视频录制（已验证可行）
//    private lateinit var videoCapture: VideoCapture<Recorder>
//    private var recording: Recording? = null
//    private val isRecording = AtomicBoolean(false)

    // 目录
    private lateinit var recordDir: File

    private val handler = Handler(Looper.getMainLooper())

    private var lastIgnoredLogTime = 0L

    // 录制相关
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    // 运动录像进行中标志：启停均在主线程串行执行，volatile 保证跨线程可见
    @Volatile
    private var isRecording = false

    // 运动录像停止定时器句柄（可取消，防止误停后续新录像）
    private var clipStopRunnable: Runnable? = null

    // 运动录像代数计数：使迟到的旧会话 Finalize 事件/定时器不影响新会话状态
    private var clipGeneration = 0

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

    // 配置热重载广播是否已注册（防止重复 onStartCommand 导致重复注册）
    private var configReceiverRegistered = false

    // 相机是否已绑定（防止重复 onStartCommand 重新绑定，导致进行中的录像被中断）
    @Volatile
    private var cameraBound = false

    companion object {
        const val CHANNEL_ID = "camera_service_channel"
        const val NOTIFICATION_ID = 1
        const val TAG = "CameraService"

        // 服务运行状态标志（供界面查询，替代已弃用的 ActivityManager.getRunningServices）
        @Volatile
        var isRunning: Boolean = false
            private set

        // 服务实例引用：同进程界面（本地预览页）注册帧监听用，随服务生命周期维护
        @Volatile
        var instance: CameraService? = null
            private set

        // CameraService.kt  companion object 内添加
        const val MODE_CONTINUOUS = 0      // 连续录像
        const val MODE_MOTION_TRIGGERED = 1 // 运动触发录像
        const val MODE_PREVIEW_ONLY = 2    // 仅预览（不录像、不运动检测）

        // 默认值（DEFAULT_MODE 与 MainActivity 读取 record_mode 的默认值保持一致，
        // 避免首次安装无配置键时界面显示"仅预览"而服务实际跑运动检测）
        const val DEFAULT_MODE = MODE_PREVIEW_ONLY
        const val DEFAULT_MOTION_CLIP_SEC = 10      // 秒
        const val DEFAULT_CONTINUOUS_SEGMENT_SEC = 600 // 10分钟
    }

    // 待编码帧槽位：分析线程只做覆盖式投递（旧帧被新帧直接替换）。
    // 积压时旧帧在"编码前"就被丢弃——不浪费 CPU，也不让观看延迟无限增长
    private val pendingEncode = AtomicReference<EncodeTask?>(null)
    private val encodeLock = java.lang.Object()  // 需 wait/notifyAll，Any 不暴露这些方法
    @Volatile
    private var encodeLoopRunning = false

    // 一帧待编码数据（NV21 已脱离 ImageProxy 生命周期）
    private data class EncodeTask(
        val nv21: ByteArray,
        val width: Int,
        val height: Int,
        val rotation: Int
    )

    override fun onCreate() {
        super.onCreate()
        instance = this
        cameraExecutor = Executors.newSingleThreadExecutor()
        // 单线程编码循环：多线程并发编码无法保证完成顺序，会导致客户端帧乱序（画面回跳）；
        // 配合 pendingEncode 槽位，单线程 + 覆盖式投递天然保序且自带丢帧
        encodeExecutor = Executors.newSingleThreadExecutor()
        mjpegStreamer = MJPEGStreamer()
        streamServer = StreamServer(8080)
        streamServer.setMJPEGStreamer(mjpegStreamer)

        createNotificationChannel()
        acquireWakeLock()

        recordDir = File(getExternalFilesDir(null), "Recordings")
        if (!recordDir.exists()) recordDir.mkdirs()

        loadSettings()
        startEncodeLoop()
    }

    // 编码循环：持续取最新帧做旋转+JPEG 编码并推送。
    // 无帧时通过 wait 挂起（5ms 轮询改为事件唤醒），500ms 超时兜底防 notify 丢失
    private fun startEncodeLoop() {
        if (encodeLoopRunning) return
        encodeLoopRunning = true
        encodeExecutor.execute {
            while (encodeLoopRunning) {
                val task = pendingEncode.getAndSet(null)
                if (task == null) {
                    synchronized(encodeLock) {
                        // double-check：防 notify 发生在 getAndSet 与 wait 之间
                        if (pendingEncode.get() == null) encodeLock.wait(500)
                    }
                    continue
                }
                try {
                    val jpeg = mjpegStreamer.nv21ToJpeg(
                        task.nv21, task.width, task.height, task.rotation, 60
                    )
                    if (jpeg != null) {
                        mjpegStreamer.pushFrame(jpeg)
                        updateFps()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "编码失败", e)
                }
            }
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("camera_prefs", MODE_PRIVATE)
        mjpegEnabled = prefs.getBoolean("mjpeg_enabled", true)
        // 运动报警开关：默认关闭；仅在已配置报警 URL 时服务端才真正发送
        motionAlertEnabled = prefs.getBoolean("motion_alert_enabled", false)

        // HTTP 认证（空白视为未配置，认证关闭）
        streamServer.username = prefs.getString("http_user", null)?.takeIf { it.isNotBlank() }
        streamServer.password = prefs.getString("http_pass", null)?.takeIf { it.isNotBlank() }

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

        // 镜头选择（0=后置，1=前置）；与分辨率/帧率一样，启动相机时生效
        useFrontCamera = prefs.getInt("camera_facing", 0) == 1
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        loadSettings()
        super.onStartCommand(intent, flags, startId)

        // 注册配置热重载广播（RECEIVER_NOT_EXPORTED：仅接收本应用内广播，满足 Android 13+ 要求）。
        // 仅注册一次：onStartCommand 可能被重复触发（如服务已运行时再次 start）
        if (!configReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                configReceiver,
                IntentFilter("com.hpu.selfcammonitor.RELOAD_CONFIG"),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            configReceiverRegistered = true
        }

        // HTTP 服务器已在运行时跳过：重复 start 会因端口被占用抛异常，进而触发 stopSelf 导致服务自杀
        if (!streamServer.isAlive) {
            try {
                streamServer.start()
                Log.d(TAG, "HTTP server started on port 8080")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start HTTP server", e)
                sendStatusBroadcast(false)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val ipAddress = getLocalIpAddress()
        val notification = buildNotification(ipAddress)
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Android 14+ 从后台（如开机广播）启动 camera 类型前台服务受限时走到这里，
            // 兜底防止崩溃；正常从界面启动不受影响
            Log.e(TAG, "startForeground 失败（系统限制后台启动）", e)
            sendStatusBroadcast(false)
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true

        // 相机已绑定时跳过：重复 unbindAll/rebind 会中断正在进行中的录像
        if (!cameraBound) {
            startCamera()
        }

        sendStatusBroadcast()
        return START_STICKY
    }

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val oldMode = recordMode
            val oldMotionAlert = motionAlertEnabled
            val wasPreviewOnly = (oldMode == MODE_PREVIEW_ONLY)
            loadSettings() // 重新加载所有配置
            val isPreviewOnly = (recordMode == MODE_PREVIEW_ONLY)

            // 报警开关或模式切换后清空检测参考帧：
            // 长时间未跑检测（如连续模式未开报警）后重新启用，首帧与旧参考帧差分会产生假运动
            if (oldMotionAlert != motionAlertEnabled) {
                motionDetector.reset()
                Log.d(TAG, "运动报警开关切换: $oldMotionAlert -> $motionAlertEnabled")
            }

            if (oldMode != recordMode) {
                // 模式切换：先无条件停掉所有录像会话（不依赖旧模式判断），
                // 再按新模式初始化。旧实现先 loadSettings 再按模式判断，
                // 会导致旧运动录像停不掉、连续录像标志残留
                stopMotionRecording()
                stopContinuousRecording()

                if (wasPreviewOnly != isPreviewOnly) {
                    // 跨越仅预览边界，需要重新绑定相机用例
                    Log.d(TAG, "模式跨越了仅预览边界，重新绑定相机用例: old=$oldMode, new=$recordMode")
                    startCamera()   // 绑定成功后会按新模式自动启动连续录像
                    return
                }
                if (recordMode == MODE_CONTINUOUS) {
                    startContinuousRecording()
                }
            }
            Log.d(TAG, "配置已更新: mode=$recordMode, motionClip=${motionClipDurationMs}ms, continuousSegment=${continuousSegmentDurationMs}ms")
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    // 本地预览帧监听透传（监控手机本机查看画面，进程内共享已编码帧）
    fun addPreviewListener(listener: MJPEGStreamer.FrameListener) =
        mjpegStreamer.addFrameListener(listener)

    fun removePreviewListener(listener: MJPEGStreamer.FrameListener) =
        mjpegStreamer.removeFrameListener(listener)

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        instance = null
        sendStatusBroadcast(false)  // 通知主界面更新状态（原实现停止时无广播，界面一直显示"运行中"）
        stopContinuousRecording()
        stopMotionRecording()
        streamServer.stop()
        cameraProvider?.unbindAll()
        cameraBound = false
        cameraExecutor.shutdown()
        // 先置停再唤醒编码循环，避免 wait 中挂 500ms 超时
        encodeLoopRunning = false
        synchronized(encodeLock) { encodeLock.notifyAll() }
        encodeExecutor.shutdown()
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

    private fun buildNotification(ip: String, text: String = "访问: http://$ip:8080/video"): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("监控运行中")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * 录像异常提示：更新前台服务通知内容，避免磁盘写满等错误静默失败
     */
    private fun showRecordError(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(getLocalIpAddress(), text))
        notifyRecordError()
    }

    /**
     * 录像失败（如磁盘写满）时通知主界面，将"录像存储"标红显示
     */
    private fun notifyRecordError() {
        val intent = Intent("com.hpu.selfcammonitor.RECORD_ERROR")
        intent.setPackage(packageName)  // 显式广播：防系统过滤隐式广播
        sendBroadcast(intent)
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
                        // 非监控时段：不分析、不推流；同时暂停连续录像
                        // （保留 continuousRecording 标志，时段恢复后自动续录）
                        if (continuousRecording && currentSegmentRecording != null) {
                            handler.post { pauseContinuousRecording() }
                        }
                        imageProxy.close()
                        return@Analyzer  // 不在时间窗内，直接丢弃帧
                    }
// 只读诊断：统计分析线程实际收到的帧（时间窗内）
                    diagRawCount++

                    // 应用帧率限制（均匀间隔到达：到固定时间间隔才处理，其余帧毫秒级丢弃）。
                    // 无论是否推流都限速：关闭推流时若不限速，运动检测会跑满传感器帧率，
                    // 既浪费 CPU，也会使相邻帧差异变小、改变灵敏度语义
                    val now = System.currentTimeMillis()
                    if (nextEmitAt == 0L) nextEmitAt = now
                    if (now < nextEmitAt) {
                        imageProxy.close()
                        return@Analyzer
                    }
                    // 防止掉帧后疯狂追赶造成突发，同时保持固定节奏
                    nextEmitAt = maxOf(nextEmitAt + (1000L / targetFps), now)
                    val frameStartTs = now  // 用于统计整帧处理耗时

                    // 根据录像模式处理
                    when (recordMode) {
                        MODE_CONTINUOUS -> {
                            // 连续录像：录像会话因监控时间窗暂停或异常中断时自动恢复
                            maybeResumeContinuousRecording()
                            // 运动报警（独立开关）：检测管线与运动录像共享同一套参数，
                            // 检测到运动只报警，不触发录像（本来就在连续录）
                            if (motionAlertEnabled) {
                                val motion = motionDetector.detectMotion(imageProxy)
                                if (motion) {
                                    Log.d(TAG, "连续录像模式下检测到运动")
                                    alertManager.sendMotionAlert()
                                }
                            }
                        }
                        MODE_MOTION_TRIGGERED -> {
                            // 运动检测：触发录像；报警由独立开关控制（URL 已配置时才真正发送）
                            val motion = motionDetector.detectMotion(imageProxy)
                            if (motion) {
                                Log.d(TAG, "检测到运动")
                                if (motionAlertEnabled) alertManager.sendMotionAlert()
                                startClipRecording()  // 启动短视频录制
                            }
                        }
                        MODE_PREVIEW_ONLY -> {
                            // 不录像、不运动检测
//                            Log.d(TAG, "不录像、不运动检测")
                        }
                    }
                    // MJPEG 推流：只在分析线程做 NV21 拷贝，随后覆盖式投递到编码槽位。
                    // 编码（旋转+JPEG）由单线程编码循环异步完成：分析线程快速返回，
                    // CameraX 不用等编码；编码积压时旧帧在编码前就被丢弃，不产生延迟累积
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
                            val rotation = imageProxy.imageInfo.rotationDegrees
                            pendingEncode.set(
                                EncodeTask(nv21, imageProxy.width, imageProxy.height, rotation)
                            )
                            synchronized(encodeLock) { encodeLock.notifyAll() }
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

            // 根据配置选择镜头（0=后置，1=前置）；所选镜头不存在时回退后置
            val desiredSelector = if (useFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            val cameraSelector = try {
                if (cameraProvider?.hasCamera(desiredSelector) == true) {
                    desiredSelector
                } else {
                    if (useFrontCamera) Log.w(TAG, "前置摄像头不可用，回退到后置摄像头")
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
            } catch (e: Exception) {
                Log.w(TAG, "检查摄像头可用性失败，使用后置摄像头", e)
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            actualCameraFacing = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) 1 else 0

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
                    // 读取实际绑定的输出分辨率（回退策略下可能与设置值不同），同步给主界面
                    try {
                        val size = imageAnalysis.resolutionInfo?.resolution
                        if (size != null) {
                            val newRes = "${size.width}x${size.height}"
                            if (newRes != actualResolution) {
                                actualResolution = newRes
                                Log.d(TAG, "实际推流分辨率: $actualResolution")
                                sendStatusBroadcast()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "读取实际推流分辨率失败", e)
                    }
                }
                cameraBound = true
                Log.d(TAG, "相机绑定成功")

                // 绑定成功后根据模式启动连续录像
                if (recordMode == MODE_CONTINUOUS) {
                    startContinuousRecording()
                }
            } catch (e: Exception) {
                Log.e(TAG, "相机绑定失败", e)
                cameraBound = false
                sendStatusBroadcast(false)  // 通知主界面更新状态（原实现停止时无广播，界面一直显示"运行中"）
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
    // 原实现请求精确区间 Range(fps, fps)，个别机型不支持该区间会导致绑定失败，
    // 现改为从设备支持的 AE 帧率区间中选取最接近目标的；分辨率/帧率仍需重启监控才生效。
    private fun applyTargetFps(builder: ImageAnalysis.Builder, fps: Int) {
        if (fps <= 0) return
        try {
            val range = pickSupportedAeFpsRange(fps)
            Log.d(TAG, "请求 AE 目标帧率区间: $range (target=$fps)")
            Camera2Interop.Extender(builder)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    range
                )
        } catch (e: Exception) {
            Log.e(TAG, "设置相机目标帧率失败", e)
        }
    }

    // 从目标镜头支持的 AE 帧率区间中选取：优先精确匹配，其次包含 fps 的最小区间，
    // 再次上界不超过 fps 的最大区间，兜底返回精确区间（交由系统收敛）
    private fun pickSupportedAeFpsRange(fps: Int): Range<Int> {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val targetFacing = if (useFrontCamera) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == targetFacing
            } ?: return Range(fps, fps)
            val ranges = cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?: return Range(fps, fps)

            ranges.firstOrNull { it.lower == fps && it.upper == fps }?.let { return it }
            ranges.filter { fps >= it.lower && fps <= it.upper }
                .minByOrNull { it.upper - it.lower }?.let { return it }
            ranges.filter { it.upper <= fps }
                .maxByOrNull { it.upper }?.let { return it }
        } catch (e: Exception) {
            Log.w(TAG, "读取支持的 AE 帧率区间失败，使用精确区间兜底", e)
        }
        return Range(fps, fps)
    }

    // ---- 预览页 HUD 只读状态（同进程 UI 读取，无锁快照语义） ----

    /** 当前是否正在写录像文件（连续分段或运动短片） */
    val hudRecording: Boolean
        get() = isRecording || continuousRecording

    /** 录像模式中文标签 */
    val hudModeLabel: String
        get() = when (recordMode) {
            MODE_CONTINUOUS -> "连续录像"
            MODE_MOTION_TRIGGERED -> "运动触发"
            else -> "仅预览"
        }

    /** 当前是否处于监控时间窗内 */
    val hudWithinWindow: Boolean
        get() = isWithinTimeWindow()

    private fun isWithinTimeWindow(): Boolean {
        // 空白值（null 或空字符串）视为无限制，全天监控
        val start = monitorStart?.takeIf { it.isNotBlank() } ?: return true
        val end = monitorEnd?.takeIf { it.isNotBlank() } ?: return true
        if (start == end) return true  // 前后时间相等时 无限制，全天

        val startMinutes = parseTimeToMinutes(start) ?: return true  // 格式非法时不限制，避免崩溃
        val endMinutes = parseTimeToMinutes(end) ?: return true
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        return if (startMinutes <= endMinutes) {
            currentMinutes in startMinutes..endMinutes
        } else {
            // 跨天情况，如 22:00 - 06:00
            currentMinutes >= startMinutes || currentMinutes <= endMinutes
        }
    }

    // 将 "HH:mm" 解析为当日分钟数；格式非法（缺冒号、非数字、越界）返回 null
    private fun parseTimeToMinutes(time: String): Int? {
        val parts = time.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun startClipRecording() {
        // 统一切到主线程执行：录像的启停、停止定时器、Finalize 回调均在主线程串行，
        // 消除相机分析线程与主线程之间的状态竞态（原实现 Start 事件异步置位，
        // 运动连续触发时可能在事件到达前重复创建录制会话）
        handler.post {
            if (isRecording) return@post
            if (!isRunning) return@post
            val vc = videoCapture
            if (vc == null) {
                Log.w(TAG, "startClipRecording: videoCapture 为 null，无法开始录像")
                return@post
            }
            val dailyDir = getDailyRecordDir()
            val fileName = "motion_${System.currentTimeMillis()}.mp4"
            val file = File(dailyDir, fileName)
            val outputOptions = FileOutputOptions.Builder(file).build()
            // 代数计数：本段录像的"身份证"，迟到的旧会话事件凭此失效
            val gen = ++clipGeneration

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
                            Log.d(TAG, "运动录像开始（含音频）: ${file.name}")
                        }
                        is VideoRecordEvent.Finalize -> {
                            // 仅处理当前代的事件，防止迟到的旧会话 Finalize 误清新会话状态
                            if (gen == clipGeneration) {
                                isRecording = false
                                if (recordEvent.error != 0) {
                                    // 录制失败（如磁盘已满）：删除损坏的空文件，避免出现在录像列表
                                    if (!file.exists() || file.length() < 4096) {
                                        try { file.delete() } catch (_: Exception) {}
                                    }
                                    Log.e(TAG, "运动录像失败: ${file.name}, error=${recordEvent.error}")
                                    notifyRecordError()  // 通知主界面"录像存储"标红
                                } else {
                                    Log.d(TAG, "运动录像完成: ${file.name}")
                                }
                            }
                        }
                    }
                }
            recording = pending
            // 同步置位，闭紧「检查-置位」窗口，防止运动连续触发时重复创建录制会话
            isRecording = true

            // 取消旧的停止定时器（若前一段录像被提前停止），避免误停本次录像
            clipStopRunnable?.let { handler.removeCallbacks(it) }
            val stopRunnable = Runnable {
                if (gen == clipGeneration) {
                    recording?.stop()
                    recording = null
                }
            }
            clipStopRunnable = stopRunnable
            handler.postDelayed(stopRunnable, motionClipDurationMs)
        }
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
        if (currentSegmentRecording != null) return  // 已有分段在录，避免重复创建
        if (!isWithinTimeWindow()) {
            // 非监控时段不开新分段（continuousRecording 标志保留，时段恢复后自动续录）
            Log.d(TAG, "非监控时段，暂不开启连续录像分段")
            return
        }
        val vc = videoCapture
        if (vc == null) {
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
                        if (event.error != 0) {
                            // 写入失败（如磁盘已满）：删除损坏文件并停止录像循环，
                            // 避免无限失败重试占用 CPU（原实现无论成败都开下一段）
                            if (!file.exists() || file.length() < 4096) {
                                try { file.delete() } catch (_: Exception) {}
                            }
                            Log.e(TAG, "连续录像分段失败: ${file.name}, error=${event.error}")
                            continuousRecording = false
                            currentSegmentRecording = null
                            segmentRotateRunnable?.let { continuousSegmentHandler.removeCallbacks(it) }
                            segmentRotateRunnable = null
                            showRecordError("录像出错已停止（错误码 ${event.error}），请检查存储空间")
                        } else {
                            Log.d(TAG, "连续录像分段完成: ${file.name}")
                            // 分段结束后，若仍处于连续录像模式且在监控时段内，启动下一段
                            // （非监控时段由分析线程在时段恢复后自动续录）
                            if (recordMode == MODE_CONTINUOUS && continuousRecording && isWithinTimeWindow()) {
                                startNewContinuousSegment()
                            }
                        }
                    }
                }
            }
        currentSegmentRecording = pending

        // 清理旧分段定时器后重新计时，避免旧定时器误停新分段
        segmentRotateRunnable?.let { continuousSegmentHandler.removeCallbacks(it) }
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
     * 停止连续录像（清除标志、取消定时器、停止当前分段）
     */
    private fun stopContinuousRecording() {
        continuousRecording = false
        pauseContinuousRecording()
    }

    /**
     * 暂停连续录像：停止当前分段但保留 continuousRecording 标志，
     * 监控时间窗恢复后由分析线程自动续录（用于非监控时段）
     */
    private fun pauseContinuousRecording() {
        segmentRotateRunnable?.let { continuousSegmentHandler.removeCallbacks(it) }
        segmentRotateRunnable = null
        currentSegmentRecording?.stop()
        currentSegmentRecording = null
    }

    /**
     * 恢复连续录像（在相机分析线程调用）：
     * 监控时间窗恢复或分段意外缺失时，投递到主线程重新开启分段
     */
    private fun maybeResumeContinuousRecording() {
        if (!continuousRecording || currentSegmentRecording != null) return
        handler.post {
            if (recordMode == MODE_CONTINUOUS && continuousRecording && currentSegmentRecording == null) {
                startNewContinuousSegment()
            }
        }
    }

    /**
     * 无条件停止运动触发录像（不依赖当前 recordMode 判断）。
     * 模式切换时 loadSettings() 已把 recordMode 更新为新值，
     * 若按模式判断会漏停正在进行的录像（原 stopMotionRecordingIfNeeded 的缺陷）
     */
    private fun stopMotionRecording() {
        clipGeneration++  // 使迟到的 Finalize 事件与停止定时器失效
        clipStopRunnable?.let { handler.removeCallbacks(it) }
        clipStopRunnable = null
        recording?.stop()
        recording = null
        isRecording = false
    }

    private fun getLocalIpAddress(): String {
        // 优先返回 Wi-Fi 网卡（wlan*）的 IPv4：插着移动数据或 VPN 时，
        // 遍历到的第一个 IPv4 可能是蜂窝/虚拟网卡地址，浏览器无法访问
        var fallback: String? = null
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress ?: continue
                        if (networkInterface.name.startsWith("wlan")) {
                            return ip
                        }
                        fallback = fallback ?: ip
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get IP", e)
        }
        return fallback ?: "0.0.0.0"
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
            fpsIntent.setPackage(packageName)  // 显式广播：防系统过滤隐式广播
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

    private fun sendStatusBroadcast(running: Boolean = true) {
        val ip = getLocalIpAddress()
        val intent = Intent("com.hpu.selfcammonitor.SERVICE_STATUS")
        intent.setPackage(packageName)  // 显式广播：防系统过滤隐式广播
        intent.putExtra("ip", ip)
        intent.putExtra("running", running)
        intent.putExtra("mjpeg_enabled", mjpegEnabled)
        intent.putExtra("camera_facing", actualCameraFacing)  // 实际使用的镜头：0=后置，1=前置
        intent.putExtra("resolution", actualResolution)        // 实际推流分辨率，如 "640x480"
        sendBroadcast(intent)
    }
}