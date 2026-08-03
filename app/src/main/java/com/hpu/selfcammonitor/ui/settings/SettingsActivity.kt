package com.hpu.selfcammonitor.ui.settings

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.hpu.selfcammonitor.R
import com.hpu.selfcammonitor.service.CameraService

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var spResolution: AutoCompleteTextView
    private lateinit var spCameraFacing: AutoCompleteTextView
    private lateinit var seekBarFps: SeekBar
    private lateinit var tvFpsValue: TextView
    private lateinit var seekBarSensitivity: SeekBar
    private lateinit var tvSensitivityValue: TextView
    private lateinit var switchBoot: SwitchMaterial
    private lateinit var etAlertUrl: EditText
    private lateinit var etAlertQuiet: EditText
    private lateinit var etStartTime: EditText
    private lateinit var etEndTime: EditText
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText

    private lateinit var spinnerMotionDuration: AutoCompleteTextView
    private lateinit var spinnerContinuousDuration: AutoCompleteTextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences("camera_prefs", MODE_PRIVATE)

        spCameraFacing = findViewById(R.id.spinnerCameraFacing)
        val facingLabels = resources.getStringArray(R.array.camera_facing_labels)
        spCameraFacing.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, facingLabels)
        )
        spCameraFacing.setText(facingLabels[prefs.getInt("camera_facing", 0)], false)

        spResolution = findViewById(R.id.spinnerResolution)
        // 动态加载支持的分辨率（按所选镜头朝向枚举）
        loadSupportedResolutions(prefs.getInt("camera_facing", 0))
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
        spinnerMotionDuration = findViewById(R.id.spinner_motion_duration)
        spinnerMotionDuration.setAdapter(
            ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item,
                resources.getStringArray(R.array.motion_duration_labels)
            )
        )
        spinnerContinuousDuration = findViewById(R.id.spinner_continuous_duration)
        spinnerContinuousDuration.setAdapter(
            ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item,
                resources.getStringArray(R.array.continuous_duration_labels)
            )
        )

        loadSettings()
        setupListeners()

        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings() }

        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            finish()   // 返回上一页（主界面）
        }

        findViewById<ImageView>(R.id.btnHelpSensitivity).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("检测灵敏度说明")
                .setMessage("动作识别灵敏度数值越低越敏感。\n\n数值越小，越轻微的画面变化就会触发运动检测；数值越大，需要更明显的画面变化才会触发。")
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun loadSettings() {
        var fps = prefs.getInt("fps", 16)
        if (fps > 30) fps = 30
        seekBarFps.progress = fps
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

        // 加载运动录像时长（秒）
        val motionSec = prefs.getInt("motion_clip_sec", CameraService.Companion.DEFAULT_MOTION_CLIP_SEC)
        val motionValues = resources.getStringArray(R.array.motion_duration_values)
        val motionLabels = resources.getStringArray(R.array.motion_duration_labels)
        val motionIndex = motionValues.indexOf(motionSec.toString())
        if (motionIndex >= 0) spinnerMotionDuration.setText(motionLabels[motionIndex], false)

        // 加载连续录像分段时长（秒）
        val continuousSec = prefs.getInt("continuous_segment_sec", CameraService.Companion.DEFAULT_CONTINUOUS_SEGMENT_SEC)
        val continuousValues = resources.getStringArray(R.array.continuous_duration_values)
        val continuousLabels = resources.getStringArray(R.array.continuous_duration_labels)
        val continuousIndex = continuousValues.indexOf(continuousSec.toString())
        if (continuousIndex >= 0) spinnerContinuousDuration.setText(continuousLabels[continuousIndex], false)
    }

    private fun loadSupportedResolutions(cameraFacing: Int) {
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        val resolutionItems = mutableListOf<String>()

        try {
            val targetFacing = if (cameraFacing == 1) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                facing == targetFacing
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

        spResolution.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resolutionItems)
        )

        val savedRes = prefs.getString("resolution", "640x480") ?: "640x480"
        val index = resolutionItems.indexOf(savedRes).coerceAtLeast(0)
        spResolution.setText(resolutionItems[index], false)
    }

    private fun setupListeners() {
        // 切换镜头时立即按新朝向重新枚举分辨率列表
        spCameraFacing.setOnItemClickListener { _, _, position, _ ->
            loadSupportedResolutions(position)
        }

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

        spinnerMotionDuration.setOnItemClickListener { _, _, position, _ ->
            val values = resources.getStringArray(R.array.motion_duration_values)
            val seconds = values[position].toInt()
            prefs.edit().putInt("motion_clip_sec", seconds).apply()
            sendReloadBroadcast()   // 实时通知服务生效
        }

        spinnerContinuousDuration.setOnItemClickListener { _, _, position, _ ->
            val values = resources.getStringArray(R.array.continuous_duration_values)
            val seconds = values[position].toInt()
            prefs.edit().putInt("continuous_segment_sec", seconds).apply()
            sendReloadBroadcast()
        }

        // 监控时间段：点击弹出时间选择器，不允许手动输入
        etStartTime.setOnClickListener { showTimePicker(etStartTime, "选择监控开始时间") }
        etEndTime.setOnClickListener { showTimePicker(etEndTime, "选择监控结束时间") }
    }

    /**
     * 弹出紧凑的滚轮时间选择器（小时 + 分钟），选中后以 HH:mm 格式回填目标输入框。
     * 相比系统时钟样式弹框更小巧，圆角与按钮颜色跟随应用 Material3 主题。
     */
    private fun showTimePicker(target: EditText, title: String) {
        val parts = target.text.toString().split(":")
        val initHour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 0
        val initMinute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0

        val density = resources.displayMetrics.density
        val hourPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 23
            value = initHour
            wrapSelectorWheel = true
            setFormatter { "%02d".format(it) }
        }
        val minutePicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 59
            value = initMinute
            wrapSelectorWheel = true
            setFormatter { "%02d".format(it) }
        }
        val colon = TextView(this).apply {
            text = ":"
            textSize = 22f
            setTextColor(getColor(R.color.text_primary))
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val vp = (12 * density).toInt()
            setPadding(0, vp, 0, vp)
            addView(hourPicker)
            addView(colon)
            addView(minutePicker)
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                target.setText("%02d:%02d".format(hourPicker.value, minutePicker.value))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sendReloadBroadcast() {
        val intent = Intent("com.hpu.selfcammonitor.RELOAD_CONFIG")
        sendBroadcast(intent)
    }

    private fun saveSettings() {
        // 下拉框取值：读取显示文本并反查对应索引
        val resolution = spResolution.text.toString()
        val facingLabels = resources.getStringArray(R.array.camera_facing_labels)
        val cameraFacing = facingLabels.indexOf(spCameraFacing.text.toString()).coerceAtLeast(0)
        val motionLabels = resources.getStringArray(R.array.motion_duration_labels)
        val motionValues = resources.getStringArray(R.array.motion_duration_values)
        val motionSec = motionValues[motionLabels.indexOf(spinnerMotionDuration.text.toString()).coerceAtLeast(0)].toInt()
        val continuousLabels = resources.getStringArray(R.array.continuous_duration_labels)
        val continuousValues = resources.getStringArray(R.array.continuous_duration_values)
        val continuousSec = continuousValues[continuousLabels.indexOf(spinnerContinuousDuration.text.toString()).coerceAtLeast(0)].toInt()

        prefs.edit()
            .putString("resolution", resolution)   // 保存纯字符串
            .putInt("camera_facing", cameraFacing)  // 镜头：0=后置，1=前置
            .putInt("fps", seekBarFps.progress)
            .putInt("sensitivity", seekBarSensitivity.progress)
            .putBoolean("boot_start", switchBoot.isChecked)
            .putString("alert_url", etAlertUrl.text.toString().trim())
            .putInt("alert_quiet", etAlertQuiet.text.toString().toIntOrNull() ?: 30)
            .putString("monitor_start", etStartTime.text.toString().trim())
            .putString("monitor_end", etEndTime.text.toString().trim())
            .putString("http_user", etUsername.text.toString().trim())
            .putString("http_pass", etPassword.text.toString().trim())
            .putInt("motion_clip_sec", motionSec)
            .putInt("continuous_segment_sec", continuousSec)
            .apply()

        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        // 发送广播通知服务重新加载
        sendBroadcast(Intent("com.hpu.selfcammonitor.RELOAD_CONFIG"))
        finish()
    }
}