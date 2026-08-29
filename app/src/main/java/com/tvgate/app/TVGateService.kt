package com.tvgate.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val restarting = AtomicBoolean(false)
    // 标记进程是被主动杀掉的（重启/DNS注入），runServer 线程不应报错
    private val killedByUs = AtomicBoolean(false)

    companion object {
        const val NOTIFY_ID = 1001
        const val ACTION_ERROR = "com.tvgate.app.ERROR"
        const val ACTION_STOP = "com.tvgate.app.STOP"
        const val ACTION_RESTART = "com.tvgate.app.RESTART"
        const val ACTION_RESTARTED = "com.tvgate.app.RESTARTED"
        private const val CHANNEL_ID = "tvgate_foreground"
        private const val AUTO_DNS_MARKER = "# --- Android 自动注入 DNS 配置 ---"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 处理通知栏"停止"按钮
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "received stop action from notification")
            stopSelfWithNotification()
            return START_NOT_STICKY
        }

        // 处理手动重启内核
        if (intent?.action == ACTION_RESTART) {
            Log.i(TAG, "received restart action")
            restartServer()
            return START_STICKY
        }

        try {
            startForeground(NOTIFY_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed (will run in background)", e)
        }
        if (running.compareAndSet(false, true)) {
            Thread { runServer() }.start()
        }

        // 注册网络变化监听，网络切换时自动更新 DNS
        registerNetworkCallback()

        return START_STICKY
    }

    private fun runServer() {
        try {
            val configPath = File(filesDir, "config.yaml").absolutePath

            // 兜底：确保 PHP docroot 目录（config.yaml 默认相对路径 www）
            // 存在。相对路径 www 会按配置文件所在目录解析，即 filesDir/www，
            // 属 App 私有可写目录，提前建好可避免目录缺失/不可写问题。
            try {
                File(filesDir, "www").mkdirs()
            } catch (e: Exception) {
                Log.w(TAG, "create docroot dir failed: ${e.message}")
            }

            // 第一次启动：让二进制生成默认 config.yaml（如果不存在）
            val configFile = File(filesDir, "config.yaml")
            val needDnsInject = !configFile.exists() || !hasDnsConfig(configFile)

            // 启动二进制
            launchProcess(configPath)

            // 如果需要注入 DNS，等待 config.yaml 出现后注入，然后重启
            if (needDnsInject) {
                ensureDnsAndRestart(configPath)
            }

            // 等待当前进程退出
            val proc = process
            if (proc != null) {
                val code = proc.waitFor()
                Log.i(TAG, "tvgate exited with code $code")
                // 只有非主动杀掉且退出码非0时才报错
                if (code != 0 && !killedByUs.get()) {
                    sendError("TVGate 进程退出，退出码=$code。请以 -config 确认配置，并查看 logcat 中 TVGateService 的日志。")
                }
                killedByUs.set(false)  // 重置标记
            }
        } catch (e: Exception) {
            Log.e(TAG, "run server failed", e)
            sendError(e.localizedMessage ?: e.toString())
        } finally {
            running.set(false)
        }
    }

    /**
     * 启动 TVGate 二进制进程。
     */
    private fun launchProcess(configPath: String): Boolean {
        val candidates = BinaryInstaller.installCandidates(this)
        var lastErr: String? = null
        for (bin in candidates) {
            val cmd = arrayOf(bin.absolutePath, "-config", configPath)
            Log.i(TAG, "try launch: ${cmd.joinToString(" ")}")
            try {
                val p = Runtime.getRuntime().exec(cmd, null, filesDir)
                // 短暂探测：若进程立即退出，视为该候选不可用
                if (p.waitFor(800, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    val code = p.exitValue()
                    val err = p.errorStream.bufferedReader().readText().take(300)
                    Log.w(TAG, "candidate exited immediately code=$code err=$err")
                    lastErr = "候选 ${bin.name} 启动即退出(退出码=$code): $err"
                    continue
                }
                // 进程存活
                process = p
                Log.i(TAG, "launched via ${bin.absolutePath}")
                consume(p.inputStream, TAG)
                consume(p.errorStream, "$TAG-err")
                return true
            } catch (execE: Exception) {
                Log.e(TAG, "exec failed for ${bin.absolutePath}", execE)
                lastErr = execE.localizedMessage ?: execE.toString()
            }
        }
        sendError(lastErr ?: "所有二进制候选均无法启动")
        return false
    }

    /**
     * 注册网络变化监听。
     * 当网络切换时（WiFi → 4G/5G 或反过来），自动检测 DNS 是否变化，
     * 如果是自动注入的 DNS 则更新并重启进程。
     * 用户手动配置的 DNS 不会被覆盖。
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return  // 已注册

        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) {
                    // 网络能力变化（如 WiFi → 蜂窝），可能伴随 DNS 变化
                    Log.i(TAG, "network capabilities changed, checking DNS...")
                    handleNetworkChange()
                }

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: android.net.LinkProperties
                ) {
                    // LinkProperties 变化（包括 DNS 服务器变化）
                    Log.i(TAG, "link properties changed, checking DNS...")
                    handleNetworkChange()
                }
            }
            cm.registerNetworkCallback(
                android.net.NetworkRequest.Builder().build(),
                callback
            )
            networkCallback = callback
            Log.i(TAG, "network callback registered")
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback failed", e)
        }
    }

    /**
     * 网络变化时的处理：检测 DNS 是否变化，更新 config.yaml 并重启进程。
     * 仅对自动注入的 DNS 生效，用户手动配置的 DNS 不会被覆盖。
     */
    private fun handleNetworkChange() {
        val configFile = File(filesDir, "config.yaml")
        if (!configFile.exists()) {
            Log.d(TAG, "config.yaml not found, skipping DNS update")
            return
        }

        // 用户手动配置了 DNS → 不覆盖
        if (hasDnsConfig(configFile) && !isAutoInjectedDns(configFile)) {
            Log.i(TAG, "DNS is manually configured by user, skipping auto-update")
            return
        }

        // 获取当前网络的新 DNS
        val newDns = NetworkUtils.getDnsServers(this)
        if (newDns.isEmpty()) {
            Log.w(TAG, "no DNS servers found after network change, skipping")
            return
        }

        // 比较新旧 DNS 是否有变化
        val currentDns = extractDnsServers(configFile)
        if (currentDns.isNotEmpty() && currentDns.containsAll(newDns) && newDns.containsAll(currentDns)) {
            Log.d(TAG, "DNS servers unchanged ($newDns), skipping restart")
            return
        }

        Log.i(TAG, "DNS changed: $currentDns -> $newDns, updating config and restarting")

        // 防止重复重启
        if (!restarting.compareAndSet(false, true)) {
            Log.d(TAG, "restart already in progress, skipping")
            return
        }

        Thread {
            try {
                // 注入新的 DNS 配置
                injectDnsConfig(configFile, newDns)

                // 停止当前进程
                killedByUs.set(true)
                process?.destroy()
                process?.waitFor()
                Thread.sleep(100)

                // 重新启动
                val configPath = configFile.absolutePath
                launchProcess(configPath)
                // 启动新的监控线程
                running.set(true)
                Thread { runServerMonitor() }.start()
                Log.i(TAG, "tvgate restarted with updated DNS: $newDns")
            } catch (e: Exception) {
                Log.e(TAG, "handleNetworkChange failed", e)
            } finally {
                restarting.set(false)
            }
        }.start()
    }

    /**
     * 检查 config.yaml 是否已有 dns.servers 配置。
     */
    private fun hasDnsConfig(configFile: File): Boolean {
        return try {
            val content = configFile.readText()
            val hasDnsSection = content.contains(Regex("(?m)^dns:"))
            val hasDnsServers = content.contains(Regex("(?m)^\\s+-\\s+\\d+\\.\\d+\\.\\d+\\.\\d+"))
            hasDnsSection && hasDnsServers
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 检查 config.yaml 中的 DNS 是否为 App 自动注入的（而非用户手动配置）。
     * 通过自动注入时写入的标记注释来区分。
     */
    private fun isAutoInjectedDns(configFile: File): Boolean {
        return try {
            configFile.readText().contains(AUTO_DNS_MARKER)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 从 config.yaml 中提取当前配置的 DNS 服务器列表。
     */
    private fun extractDnsServers(configFile: File): List<String> {
        return try {
            val content = configFile.readText()
            val dnsSection = Regex("(?ms)^dns:.*?(?=^\\S|\\Z)").find(content)?.value ?: return emptyList()
            Regex("(?m)^\\s+-\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)").findAll(dnsSection)
                .map { it.groupValues[1] }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Android 上 Go 的 net.Resolver(PreferGo=true) 会尝试连 [::1]:53，
     * 但 Android 没有本地 DNS 服务，导致域名解析失败。
     * 如果 config.yaml 中没有 dns.servers，自动获取设备 DNS 并注入，
     * 然后重启进程让配置生效。
     */
    private fun ensureDnsAndRestart(configPath: String) {
        val configFile = File(filesDir, "config.yaml")

        // 等待 config.yaml 出现（首次启动时二进制会自动生成）
        var waited = 0
        while (!configFile.exists() && waited < 5000) {
            Thread.sleep(200)
            waited += 200
        }
        if (!configFile.exists()) {
            Log.w(TAG, "config.yaml still not found after waiting, skipping DNS injection")
            return
        }

        // 再次检查是否已有 DNS 配置（可能二进制生成的默认配置已包含）
        if (hasDnsConfig(configFile)) {
            Log.i(TAG, "config.yaml already has DNS servers configured, skipping")
            return
        }

        // 获取设备 DNS 服务器
        val dnsServers = NetworkUtils.getDnsServers(this)
        if (dnsServers.isEmpty()) {
            Log.w(TAG, "no DNS servers found on device, cannot inject")
            return
        }

        // 注入 DNS 配置
        Log.i(TAG, "injecting DNS servers into config.yaml: $dnsServers")
        injectDnsConfig(configFile, dnsServers)

        // 重启进程让配置生效
        Log.i(TAG, "restarting tvgate to apply DNS config")
        try {
            killedByUs.set(true)
            process?.destroy()
            process?.waitFor()
        } catch (_: Exception) {
        }

        // 重新启动
        launchProcess(configPath)
    }

    /**
     * 将 DNS 配置注入 config.yaml。
     * 如果已有 dns: 段，替换整个段；否则追加到文件末尾。
     */
    private fun injectDnsConfig(configFile: File, dnsServers: List<String>) {
        try {
            val content = configFile.readText()
            val dnsYaml = buildString {
                appendLine("\n# --- Android 自动注入 DNS 配置 ---")
                appendLine("dns:")
                appendLine("  timeout: 5s")
                appendLine("  max_conns: 10")
                appendLine("  servers:")
                for (dns in dnsServers) {
                    appendLine("    - $dns")
                }
            }

            val newContent = if (content.contains(Regex("(?m)^dns:"))) {
                // 已有 dns: 段，替换整个段（匹配从 dns: 到下一个顶层字段或文件结尾）
                val dnsBlock = Regex("(?ms)^dns:.*?(?=^\\S|\\Z)").find(content)
                if (dnsBlock != null) {
                    content.replaceRange(dnsBlock.range, dnsYaml)
                } else {
                    content + dnsYaml
                }
            } else {
                content + dnsYaml
            }

            configFile.writeText(newContent)
            Log.i(TAG, "DNS config injected successfully")
        } catch (e: Exception) {
            Log.e(TAG, "injectDnsConfig failed", e)
        }
    }

    /**
     * 手动重启 TVGate 内核进程。
     * 停止当前进程后重新启动，不影响前台服务本身。
     */
    private fun restartServer() {
        if (!restarting.compareAndSet(false, true)) {
            Log.w(TAG, "restart already in progress")
            return
        }
        Thread {
            try {
                Log.i(TAG, "manual restart: stopping current process")
                killedByUs.set(true)
                process?.destroy()
                process?.waitFor()

                // 等待 runServer 线程的 waitFor 返回并重置标记
                Thread.sleep(100)

                val configPath = File(filesDir, "config.yaml").absolutePath
                launchProcess(configPath)

                // 启动一个新的 runServer 线程来监控新进程
                running.set(true)
                Thread { runServerMonitor() }.start()

                Log.i(TAG, "manual restart: process relaunched")

                // 通知 Activity 重启完成
                sendBroadcast(Intent(ACTION_RESTARTED).setPackage(packageName))
            } catch (e: Exception) {
                Log.e(TAG, "manual restart failed", e)
                sendError("重启失败: ${e.localizedMessage}")
            } finally {
                restarting.set(false)
            }
        }.start()
    }

    /**
     * 监控当前进程退出（restartServer 调用后使用）。
     * 与 runServer 类似但不负责首次启动/DNS注入逻辑。
     */
    private fun runServerMonitor() {
        try {
            val proc = process
            if (proc != null) {
                val code = proc.waitFor()
                Log.i(TAG, "tvgate exited with code $code")
                if (code != 0 && !killedByUs.get()) {
                    sendError("TVGate 进程退出，退出码=$code。请以 -config 确认配置，并查看 logcat 中 TVGateService 的日志。")
                }
                killedByUs.set(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "runServerMonitor failed", e)
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
            try {
                stream.bufferedReader().useLines { lines ->
                    lines.forEach { Log.d(tag, it) }
                }
            } catch (_: java.io.InterruptedIOException) {
                // process.destroy() 会中断正在读取的流，这是正常的
            } catch (e: Exception) {
                Log.w(tag, "stream consume ended: ${e.message}")
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
        // 注销网络监听
        try {
            networkCallback?.let {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(it)
            }
            networkCallback = null
        } catch (_: Exception) {
        }
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
