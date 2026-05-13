package com.hpu.selfcammonitor

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
        val url = alertUrl ?: return
        val now = System.currentTimeMillis()
        if (now - lastAlertTime < quietPeriodMs) {
            Log.d(TAG, "在静默期，跳过报警")
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
                Log.e(TAG, "报警请求失败", e)
            }

            override fun onResponse(call: Call, response: Response) {
                Log.d(TAG, "报警已发送，状态码: ${response.code}")
                response.close()
            }
        })
    }
}