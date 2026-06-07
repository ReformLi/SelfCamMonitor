package com.hpu.selfcammonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.hpu.selfcammonitor.service.CameraService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("camera_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("boot_start", false)) {
            // 启动服务
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                // 启动 CameraService
                val serviceIntent = Intent(context, CameraService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}