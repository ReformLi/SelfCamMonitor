package com.hpu.selfcammonitor

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaFormat
import android.util.Log


class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var spResolution: Spinner
    private lateinit var seekBarFps: SeekBar
    private lateinit var tvFpsValue: TextView
    private lateinit var seekBarSensitivity: SeekBar
    private lateinit var tvSensitivityValue: TextView
    private lateinit var switchBoot: Switch
    private lateinit var etAlertUrl: EditText
    private lateinit var etAlertQuiet: EditText
    private lateinit var etStartTime: EditText
    private lateinit var etEndTime: EditText
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 适配全面屏状态栏
        setupStatusBarPadding()

        prefs = getSharedPreferences("camera_prefs", MODE_PRIVATE)

        spResolution = findViewById(R.id.spinnerResolution)
        // 动态加载支持的分辨率
        loadSupportedResolutions()
        seekBarFps = findViewById(R.id.seekBarFps)
        tvFpsValue = findViewById(R.id.tvFpsValue)
        seekBarSensitivity = findViewById(R.id.seekBarSensitivity)
        tvSensitivityValue = findViewById(R.id.tvSensitivityValue)
        switchBoot = findViewById(R.id.switchBootStart)
        etAlertUrl = findViewById(R.id.etAlertUrl)
        etAlertQuiet = findViewById(R.id.etAlertQuiet)
        etStartTime = findViewById(R.id.etStartTime)
        etEndTime = findViewById(R.id.etEndTime)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)

        loadSettings()
        setupListeners()

        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings() }

        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            finish()   // 返回上一页（主界面）
        }
    }

    private fun loadSettings() {
        seekBarFps.progress = prefs.getInt("fps", 10)
        tvFpsValue.text = "${seekBarFps.progress} fps"

        seekBarSensitivity.progress = prefs.getInt("sensitivity", 50)
        tvSensitivityValue.text = seekBarSensitivity.progress.toString()

        switchBoot.isChecked = prefs.getBoolean("boot_start", false)
        etAlertUrl.setText(prefs.getString("alert_url", ""))
        etAlertQuiet.setText(prefs.getInt("alert_quiet", 30).toString())
        etStartTime.setText(prefs.getString("monitor_start", ""))
        etEndTime.setText(prefs.getString("monitor_end", ""))
        etUsername.setText(prefs.getString("http_user", "admin"))
        etPassword.setText(prefs.getString("http_pass", ""))
    }

    private fun loadSupportedResolutions() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val resolutionItems = mutableListOf<String>()

        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList[0]

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = configMap?.getOutputSizes(ImageFormat.YUV_420_888)

            outputSizes?.forEach { size ->
                val aspect = size.width.toFloat() / size.height.toFloat()
                // 只保留主流横屏比例，且宽度在 320~1920 之间
                if (size.width in 320..1920 && size.height >= 240 &&
                    aspect >= 1.33f && aspect <= 1.78f) {
                    val label = "${size.width}x${size.height}"
                    if (label !in resolutionItems) {
                        resolutionItems.add(label)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Settings", "获取摄像头分辨率失败", e)
        }

        // 兜底安全列表
        if (resolutionItems.isEmpty()) {
            resolutionItems.addAll(listOf("320x240", "640x480", "1280x720", "1920x1080"))
        }

        spResolution.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, resolutionItems
        )

        val savedRes = prefs.getString("resolution", "640x480") ?: "640x480"
        val index = resolutionItems.indexOf(savedRes).coerceAtLeast(0)
        spResolution.setSelection(index)
    }

    private fun setupListeners() {
        seekBarFps.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvFpsValue.text = "$progress fps"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        seekBarSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvSensitivityValue.text = progress.toString()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun saveSettings() {
        // 获取下标，从 values 数组取纯尺寸
//        val resolutionIndex = spResolution.selectedItemPosition
//        val resolution = resources.getStringArray(R.array.resolution_values)[resolutionIndex]
        val resolution = spResolution.selectedItem.toString()
        prefs.edit()
            .putString("resolution", resolution)   // 保存纯字符串
            .putInt("fps", seekBarFps.progress)
            .putInt("sensitivity", seekBarSensitivity.progress)
            .putBoolean("boot_start", switchBoot.isChecked)
            .putString("alert_url", etAlertUrl.text.toString().trim())
            .putInt("alert_quiet", etAlertQuiet.text.toString().toIntOrNull() ?: 30)
            .putString("monitor_start", etStartTime.text.toString().trim())
            .putString("monitor_end", etEndTime.text.toString().trim())
            .putString("http_user", etUsername.text.toString().trim())
            .putString("http_pass", etPassword.text.toString().trim())
            .apply()

        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        // 发送广播通知服务重新加载
        sendBroadcast(Intent("com.hpu.selfcammonitor.RELOAD_CONFIG"))
        finish()
    }

    private fun setupStatusBarPadding() {
        // 获取状态栏高度并设置padding
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            val statusBarHeight = resources.getDimensionPixelSize(resourceId)
            findViewById<View>(android.R.id.content).setPadding(0, statusBarHeight, 0, 0)
        }
    }
}