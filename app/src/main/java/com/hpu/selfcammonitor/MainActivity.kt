package com.hpu.selfcammonitor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var tvIpAddress: TextView
    private lateinit var tvStatus: TextView
    private lateinit var switchMjpeg: Switch
    private lateinit var switchMotion: Switch
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStorage: TextView
    private lateinit var btnViewRecordings: Button

    private val prefs by lazy { getSharedPreferences("camera_prefs", MODE_PRIVATE) }

    // 权限请求启动器（处理多个权限）
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
            if (cameraGranted) {
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
                tvIpAddress.text = "IP 地址: $ip"
                updateUI(running)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 绑定视图
        tvIpAddress = findViewById(R.id.tvIpAddress)
        tvStatus = findViewById(R.id.tvStatus)
        switchMjpeg = findViewById(R.id.switchMjpeg)
        switchMotion = findViewById(R.id.switchMotion)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStorage = findViewById(R.id.tvStorage)
        btnViewRecordings = findViewById(R.id.btnViewRecordings)

        // 恢复开关状态
        switchMjpeg.isChecked = prefs.getBoolean("mjpeg_enabled", true)
        switchMotion.isChecked = prefs.getBoolean("motion_detection_enabled", true)

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
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        switchMotion.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("motion_detection_enabled", isChecked).apply()
            val intent = Intent("com.hpu.selfcammonitor.RELOAD_CONFIG")
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }

        // 查看录像按钮
        btnViewRecordings.setOnClickListener {
            startActivity(Intent(this, RecordingsActivity::class.java))
        }

        // 注册服务状态广播
        LocalBroadcastManager.getInstance(this).registerReceiver(
            statusReceiver,
            IntentFilter("com.hpu.selfcammonitor.SERVICE_STATUS")
        )

        // 初始UI状态
        updateUI(isServiceRunning())
        updateStorageInfo()
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
    }

    // ---------- 权限相关 ----------
    private fun hasRequiredPermissions(): Boolean {
        val cameraGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        // 录音权限非强制，但为获得有声视频建议请求
        val audioGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return cameraGranted && audioGranted
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    // ---------- 电池优化 ----------
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
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

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (CameraService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    // ---------- UI 更新 ----------
    private fun updateUI(running: Boolean) {
        if (running) {
            tvStatus.text = "服务运行中"
            tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            tvStatus.text = "服务已停止"
            tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
        }

        // 按钮状态控制：运行时启动按钮置灰，停止按钮可用；停止时相反
        btnStart.isEnabled = !running
        btnStop.isEnabled = running

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

    private fun updateStorageInfo() {
        val dir = File(getExternalFilesDir(null), "Recordings")
        val totalSize = if (dir.exists()) {
            dir.listFiles()?.sumOf { it.length() } ?: 0
        } else 0L
        val sizeMB = totalSize / (1024 * 1024)
        tvStorage.text = "录像存储: ${sizeMB} MB"
    }
}