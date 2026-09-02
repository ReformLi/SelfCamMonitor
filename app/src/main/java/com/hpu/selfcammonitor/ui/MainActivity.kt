package com.hpu.selfcammonitor.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.text.Spannable
import android.view.View
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.hpu.selfcammonitor.service.CameraService
import com.hpu.selfcammonitor.utils.FileSizeFormatter
import com.hpu.selfcammonitor.R
import com.hpu.selfcammonitor.ui.recordings.RecordingsActivity
import com.hpu.selfcammonitor.ui.settings.SettingsActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var tvIpAddress: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvCameraFacing: TextView
    private lateinit var tvResolution: TextView
    private lateinit var switchMjpeg: SwitchMaterial
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStorage: TextView
    private lateinit var tvFps: TextView
    private lateinit var btnViewRecordings: Button
    private lateinit var btnPreview: Button

    private lateinit var switchContinuous: SwitchCompat

    private lateinit var switchMotion: SwitchCompat

    // 记录上次返回键按下的时间
    private var lastBackPressedTime = 0L

    private val prefs by lazy { getSharedPreferences("camera_prefs", MODE_PRIVATE) }

    // 权限请求启动器（处理多个权限）
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
            if (cameraGranted) {
                // 通知权限：Android 13+ 未授予时前台服务通知不显示，提示但不阻断启动
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    permissions[Manifest.permission.POST_NOTIFICATIONS] == false) {
                    Toast.makeText(this, "建议授予通知权限，否则通知栏看不到监控状态", Toast.LENGTH_LONG).show()
                }
                // 录音权限非强制：未授予时录像不含音频
                if (permissions[Manifest.permission.RECORD_AUDIO] == false) {
                    Toast.makeText(this, "未授予录音权限，录像将不含音频", Toast.LENGTH_SHORT).show()
                }
                startCameraService()
            } else {
                Toast.makeText(this, "需要相机权限才能运行", Toast.LENGTH_SHORT).show()
            }
        }

    // 接收服务状态广播
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.hpu.selfcammonitor.SERVICE_STATUS") {
                val ip = intent.getStringExtra("ip") ?: "未知"
                val running = intent.getBooleanExtra("running", false)
                val facing = intent.getIntExtra("camera_facing", 0)
                val resolution = intent.getStringExtra("resolution") ?: "--"
                tvIpAddress.text = "IP 地址: $ip"
                tvCameraFacing.text = "摄像头: ${if (facing == 1) "前置" else "后置"}"
                tvResolution.text = "分辨率: $resolution"
                updateUI(running)
            }
        }
    }

    // 接收帧率更新广播
    private val fpsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.hpu.selfcammonitor.FPS_UPDATE") {
                val fps = intent.getIntExtra("fps", 0)
                tvFps.text = "帧率: $fps fps"
            }
        }
    }

    // 录像失败（如磁盘写满）标记：置红"录像存储"显示；可用空间恢复后自动解除
    private var storageFull = false
    private val storageFullThresholdBytes = 64L * 1024 * 1024

    // 接收录像失败广播（磁盘满等），将存储显示标红
    private val recordErrorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.hpu.selfcammonitor.RECORD_ERROR") {
                storageFull = true
                updateStorageInfo()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 绑定视图
        tvIpAddress = findViewById(R.id.tvIpAddress)
        tvStatus = findViewById(R.id.tvStatus)
        tvCameraFacing = findViewById(R.id.tvCameraFacing)
        tvResolution = findViewById(R.id.tvResolution)
        switchMjpeg = findViewById(R.id.switchMjpeg)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStorage = findViewById(R.id.tvStorage)
        tvFps = findViewById(R.id.tvFps)
        btnViewRecordings = findViewById(R.id.btnViewRecordings)
        btnPreview = findViewById(R.id.btnPreview)

        // 本机查看监控画面：进程内共享编码帧，不走 HTTP/认证
        // 按钮仅在"服务运行 + MJPEG 推流开启"时可见（见 updateUI / switchMjpeg 监听），无需点击校验
        btnPreview.setOnClickListener {
            startActivity(Intent(this, PreviewActivity::class.java))
        }

        switchContinuous = findViewById(R.id.switch_continuous)
        switchMotion = findViewById(R.id.switch_motion)


        // 恢复开关状态
        switchMjpeg.isChecked = prefs.getBoolean("mjpeg_enabled", true)

        // 启动按钮
        btnStart.setOnClickListener {
            if (hasRequiredPermissions()) {
                requestIgnoreBatteryOptimizations()
                startCameraService()
            } else {
                requestRequiredPermissions()
            }
        }

        // 停止按钮
        btnStop.setOnClickListener {
            stopCameraService()
        }

        // 开关监听：保存设置并通知服务
        switchMjpeg.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("mjpeg_enabled", isChecked).apply()
            val intent = Intent("com.hpu.selfcammonitor.RELOAD_CONFIG")
            intent.setPackage(packageName)  // 显式广播：防系统过滤隐式广播
            sendBroadcast(intent)
            // 查看画面按钮依赖推流产帧：开关切换时即时联动可见性
            btnPreview.visibility =
                if (isChecked && CameraService.isRunning) View.VISIBLE else View.GONE
        }

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 加载保存的录像模式
        val savedMode = prefs.getInt("record_mode", CameraService.Companion.MODE_PREVIEW_ONLY)
        when (savedMode) {
            CameraService.Companion.MODE_CONTINUOUS -> {
                switchContinuous.isChecked = true
                switchMotion.isChecked = false
            }
            CameraService.Companion.MODE_MOTION_TRIGGERED -> {
                switchContinuous.isChecked = false
                switchMotion.isChecked = true
            }
            else -> {
                switchContinuous.isChecked = false
                switchMotion.isChecked = false
            }
        }

        // 设置互斥监听
        switchContinuous.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (switchMotion.isChecked) switchMotion.isChecked = false
                saveAndNotifyMode(CameraService.Companion.MODE_CONTINUOUS)
            } else {
                // 如果两个都关闭，则为预览模式
                if (!switchMotion.isChecked) {
                    saveAndNotifyMode(CameraService.Companion.MODE_PREVIEW_ONLY)
                }
            }
        }

        switchMotion.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (switchContinuous.isChecked) switchContinuous.isChecked = false
                saveAndNotifyMode(CameraService.Companion.MODE_MOTION_TRIGGERED)
            } else {
                if (!switchContinuous.isChecked) {
                    saveAndNotifyMode(CameraService.Companion.MODE_PREVIEW_ONLY)
                }
            }
        }

        // 查看录像按钮
        btnViewRecordings.setOnClickListener {
            startActivity(Intent(this, RecordingsActivity::class.java))
        }

        // 注册服务状态广播（RECEIVER_NOT_EXPORTED：仅接收本应用内广播）
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter("com.hpu.selfcammonitor.SERVICE_STATUS"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // 注册帧率更新广播
        ContextCompat.registerReceiver(
            this,
            fpsReceiver,
            IntentFilter("com.hpu.selfcammonitor.FPS_UPDATE"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // 注册录像失败广播（磁盘满等，用于存储显示标红）
        ContextCompat.registerReceiver(
            this,
            recordErrorReceiver,
            IntentFilter("com.hpu.selfcammonitor.RECORD_ERROR"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // 初始UI状态
        updateUI(isServiceRunning())
        updateStorageInfo()

        // 使用 OnBackPressedDispatcher 处理返回键（兼容 Android 13+）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastBackPressedTime < 2000) {
                    // 2秒内连续按两次，将应用退到后台（相当于回到桌面）
                    moveTaskToBack(true)
                } else {
                    // 第一次按下，显示提示
                    Toast.makeText(this@MainActivity, "再按一次返回桌面", Toast.LENGTH_SHORT).show()
                    lastBackPressedTime = currentTime
                }
            }
        })
    }

    // 辅助方法：保存模式并发送广播
    private fun saveAndNotifyMode(mode: Int) {
        prefs.edit().putInt("record_mode", mode).apply()
        val intent = Intent("com.hpu.selfcammonitor.RELOAD_CONFIG")
        intent.setPackage(packageName)  // 显式广播：防系统过滤隐式广播
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
        unregisterReceiver(fpsReceiver)
        unregisterReceiver(recordErrorReceiver)
    }

    // ---------- 权限相关 ----------
    // 只把相机权限作为启动的必要条件：录音未授权时录像降级为无声，
    // 若两者都强制，用户永久拒绝录音后将无法启动监控
    private fun hasRequiredPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        // Android 13+ 需运行时请求通知权限，否则前台服务通知不显示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    // ---------- 电池优化 ----------
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "请手动在设置中忽略电池优化", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---------- 服务控制 ----------
    private fun startCameraService() {
        val intent = Intent(this, CameraService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "服务已启动", Toast.LENGTH_SHORT).show()
        updateUI(true)
    }

    private fun stopCameraService() {
        val intent = Intent(this, CameraService::class.java)
        stopService(intent)
        updateUI(false)
    }

    private fun isServiceRunning(): Boolean = CameraService.isRunning

    // ---------- UI 更新 ----------
    private fun updateUI(running: Boolean) {
        if (running) {
            tvStatus.text = "服务运行中"
            tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            tvStatus.text = "服务已停止"
            tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            tvFps.text = "帧率: -- fps"
            tvCameraFacing.text = "摄像头: --"
            tvResolution.text = "分辨率: --"
        }

        // 按钮状态控制：运行时启动按钮置灰，停止按钮可用；停止时相反
        btnStart.isEnabled = !running
        btnStop.isEnabled = running

        // 查看画面按钮：仅服务运行且 MJPEG 推流开启时显示（预览依赖编码循环产帧）
        btnPreview.visibility =
            if (running && prefs.getBoolean("mjpeg_enabled", true)) View.VISIBLE else View.GONE

        // 设置按钮背景色和文字颜色
        updateButtonAppearance(running)

        updateStorageInfo()
    }

    private fun updateButtonAppearance(running: Boolean) {
        if (running) {
            // 服务运行时：启动按钮置灰，停止按钮正常
            btnStart.setBackgroundColor(getColor(android.R.color.darker_gray))
            btnStart.setTextColor(getColor(android.R.color.white))
            btnStop.setBackgroundColor(getColor(android.R.color.holo_red_dark))
            btnStop.setTextColor(getColor(android.R.color.white))
        } else {
            // 服务停止时：启动按钮正常，停止按钮置灰
            btnStart.setBackgroundColor(getColor(android.R.color.holo_green_dark))
            btnStart.setTextColor(getColor(android.R.color.white))
            btnStop.setBackgroundColor(getColor(android.R.color.darker_gray))
            btnStop.setTextColor(getColor(android.R.color.white))
        }
    }

    override fun onResume() {
        super.onResume()
        updateStorageInfo()
    }

    private fun updateStorageInfo() {
        // 递归统计放到后台线程：长期运行后录像文件数千个，主线程 walk 会卡 UI
        val dir = File(getExternalFilesDir(null), "Recordings")
        Thread {
            val totalSize = if (dir.exists()) {
                dir.walkTopDown()
                    .filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }
                    .sumOf { it.length() }
            } else 0L
            // 自愈检查：可用空间恢复到阈值以上时解除"已满"标记（用户删除录像/清理空间后）
            val freeBytes = getExternalFilesDir(null)?.let {
                try { StatFs(it.absolutePath).availableBytes } catch (_: Exception) { Long.MAX_VALUE }
            } ?: Long.MAX_VALUE
            if (freeBytes > storageFullThresholdBytes) storageFull = false
            runOnUiThread {
                if (storageFull) {
                    // 数字+计量单位+（已满）红色，"录像存储: "标签保持原色
                    val sizeStr = FileSizeFormatter.format(totalSize)
                    val text = "录像存储: $sizeStr（已满）"
                    val span = SpannableString(text)
                    span.setSpan(
                        ForegroundColorSpan(getColor(android.R.color.holo_red_dark)),
                        "录像存储: ".length, text.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    tvStorage.text = span
                } else {
                    tvStorage.text = "录像存储: ${FileSizeFormatter.format(totalSize)}"
                }
            }
        }.start()
    }

}