package com.tvgate.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前台服务：在后台常驻运行 TVGate 服务端二进制。
 * 用前台服务可避免系统后台限制把进程杀掉，保证转发持续可用。
 *
 * 用户可以通过通知栏的"停止"按钮来主动停止服务，
 * 也可以点击通知本身回到 App 界面。
 */
class TVGateService : Service() {

    private val TAG = "TVGateService"
    private var process: Process? = null
    private val running = AtomicBoolean(false)

    companion object {
        const val NOTIFY_ID = 1001
        const val ACTION_ERROR = "com.tvgate.app.ERROR"
        const val ACTION_STOP = "com.tvgate.app.STOP"
        private const val CHANNEL_ID = "tvgate_foreground"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 处理通知栏"停止"按钮
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "received stop action from notification")
            stopSelfWithNotification()
            return START_NOT_STICKY
        }

        try {
            startForeground(NOTIFY_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed (will run in background)", e)
        }
        if (running.compareAndSet(false, true)) {
            Thread { runServer() }.start()
        }
        return START_STICKY
    }

    private fun runServer() {
        try {
            // TVGate 仅接受 -config（默认 config.yaml）与 -version 两个参数。
            // 配置文件不存在时，进程会自动在工作目录生成默认 config.yaml（监听 8888）。
            val configPath = File(filesDir, "config.yaml").absolutePath

            val candidates = BinaryInstaller.installCandidates(this)
            var lastErr: String? = null
            for (bin in candidates) {
                val cmd = arrayOf(bin.absolutePath, "-config", configPath)
                Log.i(TAG, "try launch: ${cmd.joinToString(" ")}")
                try {
                    val p = Runtime.getRuntime().exec(cmd, null, filesDir)
                    // 短暂探测：若进程立即退出（如 exec 被拒/转译失败），视为该候选不可用
                    if (p.waitFor(800, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        val code = p.exitValue()
                        val err = p.errorStream.bufferedReader().readText().take(300)
                        Log.w(TAG, "candidate exited immediately code=$code err=$err")
                        lastErr = "候选 ${bin.name} 启动即退出(退出码=$code): $err"
                        continue
                    }
                    // 进程存活，说明 exec 成功，采用此候选
                    process = p
                    Log.i(TAG, "launched via ${bin.absolutePath}")
                    break
                } catch (execE: Exception) {
                    Log.e(TAG, "exec failed for ${bin.absolutePath}", execE)
                    lastErr = execE.localizedMessage ?: execE.toString()
                }
            }

            val proc = process
            if (proc == null) {
                sendError(lastErr ?: "所有二进制候选均无法启动")
                return
            }

            // 异步消费输出，避免管道写满导致阻塞
            consume(proc.inputStream, TAG)
            consume(proc.errorStream, "$TAG-err")
            val code = proc.waitFor()
            Log.i(TAG, "tvgate exited with code $code")
            if (code != 0) {
                sendError("TVGate 进程退出，退出码=$code。请以 -config 确认配置，并查看 logcat 中 TVGateService 的日志。")
            }
        } catch (e: Exception) {
            Log.e(TAG, "run server failed", e)
            sendError(e.localizedMessage ?: e.toString())
        } finally {
            running.set(false)
        }
    }

    private fun sendError(msg: String) {
        val i = Intent(ACTION_ERROR).apply { putExtra("msg", msg) }
        sendBroadcast(i)
    }

    private fun consume(stream: java.io.InputStream, tag: String) {
        Thread {
            stream.bufferedReader().useLines { lines ->
                lines.forEach { Log.d(tag, it) }
            }
        }.start()
    }

    /**
     * 用户从通知栏点击"停止"时调用：
     * 先销毁服务端进程，再取消通知，最后停止自身。
     */
    private fun stopSelfWithNotification() {
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        running.set(false)

        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.cancel(NOTIFY_ID)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        running.set(false)
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            mgr.createNotificationChannel(ch)
        }

        // 点击通知回到 App
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // "停止"操作按钮
        val stopIntent = Intent(this, TVGateService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_running_title))
            .setContentText(getNotificationText())
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentPi)
            .setOngoing(true)  // 不可滑动清除
            .setShowWhen(false)

        // 添加"停止"操作按钮（API 24+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.addAction(
                Notification.Action.Builder(
                    null, getString(R.string.notif_action_stop), stopPi
                ).build()
            )
        }

        // 添加"打开"操作按钮
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.addAction(
                Notification.Action.Builder(
                    null, getString(R.string.notif_action_open), contentPi
                ).build()
            )
        }

        return builder.build()
    }

    /**
     * 构建通知正文：显示局域网 IP 地址和端口（从 config.yaml 读取），方便其他设备访问。
     */
    private fun getNotificationText(): String {
        val config = ConfigParser.load(this)
        val ip = NetworkUtils.getLocalIpAddress(this)
        return if (ip != null) {
            "局域网访问: $ip:${config.port}"
        } else {
            getString(R.string.notif_running_text)
        }
    }
}
