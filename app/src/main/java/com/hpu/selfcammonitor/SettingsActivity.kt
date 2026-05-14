package com.hpu.selfcammonitor

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

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

        prefs = getSharedPreferences("camera_prefs", MODE_PRIVATE)

        spResolution = findViewById(R.id.spinnerResolution)
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
        val res = prefs.getString("resolution", "640x480")!!
        val values = resources.getStringArray(R.array.resolution_values).toList()
        spResolution.setSelection(values.indexOf(res).coerceAtLeast(0))

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
        val resolutionIndex = spResolution.selectedItemPosition
        val resolution = resources.getStringArray(R.array.resolution_values)[resolutionIndex]
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
}