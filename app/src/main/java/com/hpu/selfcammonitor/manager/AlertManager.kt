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
        // 与 CameraService 同 TAG：报警链路日志在按服务名过滤时能看到完整链路
        private const val TAG = "CameraService"
    }

    private val client = OkHttpClient()
    private var alertUrl: String? = null
    private var lastAlertTime = 0L
    private var quietPeriodMs = 30_000L
    // 状态类日志只打一次（跳过/无效 URL）：连续运动时逐帧触发，避免每帧刷屏
    private var loggedQuietSkip = false
    private var loggedInvalidUrl = false

    fun setAlertUrl(url: String?) {
        alertUrl = url
        loggedInvalidUrl = false
        Log.i(TAG, "报警 URL 配置: ${url ?: "未配置"}")
    }

    fun setQuietPeriod(ms: Long) {
        quietPeriodMs = ms
        Log.i(TAG, "报警静默期: ${ms / 1000}秒")
    }

    fun sendMotionAlert() {
        val url = alertUrl
        // 检查 URL 是否有效
        if (url.isNullOrBlank() || !url.startsWith("http://") && !url.startsWith("https://")) {
            if (!loggedInvalidUrl) {
                Log.w(TAG, "报警 URL 无效（'$url'），跳过发送；请在设置中配置 http(s):// 开头的完整地址")
                loggedInvalidUrl = true
            }
            return
        }

        val now = System.currentTimeMillis()
        val elapsed = now - lastAlertTime
        if (elapsed < quietPeriodMs) {
            if (!loggedQuietSkip) {
                Log.d(TAG, "静默期内跳过报警（剩余 ${quietPeriodMs - elapsed}ms），URL: $url")
                loggedQuietSkip = true
            }
            return
        }
        loggedQuietSkip = false

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

        Log.i(TAG, "发送报警 POST → $url")
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // 发送失败不占用静默期：网络/服务端故障恢复后，下一次运动事件可立即报警。
                // 静默期仅在成功送达时启动
                Log.w(TAG, "报警请求失败（$url）: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                if (code in 200..299) {
                    lastAlertTime = now
                    Log.i(TAG, "报警送达成功: $url → $code")
                } else {
                    // 已发出但接收方返回错误：多为 URL 路径与接收方路由不匹配（如 404）
                    Log.w(TAG, "报警被接收方拒绝: $url → HTTP $code（检查接收方是否处理该路径）")
                }
                response.close()
            }
        })
    }
}