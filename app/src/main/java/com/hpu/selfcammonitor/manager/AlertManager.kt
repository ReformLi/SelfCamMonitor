package com.hpu.selfcammonitor.manager

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class AlertManager {
    companion object {
        private const val TAG = "AlertManager"
    }

    private val client = OkHttpClient()
    private var alertUrl: String? = null
    private var lastAlertTime = 0L
    private var quietPeriodMs = 30_000L

    fun setAlertUrl(url: String?) {
        alertUrl = url
    }

    fun setQuietPeriod(ms: Long) {
        quietPeriodMs = ms
    }

    fun sendMotionAlert() {
        val url = alertUrl
        // 新增：检查 URL 是否有效
        if (url.isNullOrBlank() || !url.startsWith("http://") && !url.startsWith("https://")) {
            Log.w(TAG, "无效的报警 URL，跳过发送: $url")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastAlertTime < quietPeriodMs) {
            Log.d(TAG, "在静默期，跳过")
            return
        }
        lastAlertTime = now

        val json = JSONObject().apply {
            put("event", "motion_detected")
            put("timestamp", now)
            put("message", "摄像头检测到运动")
        }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "报警请求失败: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                Log.d(TAG, "报警已发送，状态码: ${response.code}")
                response.close()
            }
        })
    }
}