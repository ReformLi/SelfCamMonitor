package com.hpu.selfcammonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.hpu.selfcammonitor.service.CameraService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("camera_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("boot_start", false)) {
            // 启动服务
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                // 启动 CameraService
                val serviceIntent = Intent(context, CameraService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    // Android 14+ 限制从后台（含开机广播）启动 camera/microphone 类型前台服务，
                    // 会抛异常；此处兜底防止崩溃，需用户手动打开 App 启动监控
                    Log.e("BootReceiver", "开机自启失败：系统限制后台启动摄像头前台服务", e)
                }
            }
        }
    }
}