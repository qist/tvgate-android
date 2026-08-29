package com.tvgate.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 开机自启：设备重启完成（BOOT_COMPLETED）后自动启动 TVGate 前台服务，
 * 使 IPTV 代理在无需手动打开 App 的情况下持续可用。
 *
 * 注意：Android 限制被用户"强制停止"过的 App 不会收到开机广播，
 * 需重新打开一次 App 后才能恢复开机自启。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "BOOT_COMPLETED received, starting TVGateService")

        val serviceIntent = Intent(context, TVGateService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "TVGateService start requested")
        } catch (e: Exception) {
            Log.e(TAG, "failed to start TVGateService on boot: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
