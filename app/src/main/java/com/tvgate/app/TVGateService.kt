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
 */
class TVGateService : Service() {

    private val TAG = "TVGateService"
    private var process: Process? = null
    private val running = AtomicBoolean(false)

    companion object {
        const val NOTIFY_ID = 1001
        const val ACTION_ERROR = "com.tvgate.app.ERROR"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

    override fun onDestroy() {
        super.onDestroy()
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        running.set(false)
    }

    private fun buildNotification(): Notification {
        val channelId = "tvgate_foreground"
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId, "TVGate 服务", NotificationManager.IMPORTANCE_LOW
            )
            mgr.createNotificationChannel(ch)
        }
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, channelId)
            .setContentTitle("TVGate 运行中")
            .setContentText("本地转发服务已启动 (127.0.0.1:8888)")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .build()
    }
}
