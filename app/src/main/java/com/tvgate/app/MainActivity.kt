package com.tvgate.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())
    // TVGate 默认在 8888 提供 Web 管理界面，路径为 /web/
    private val serverUrl = "http://127.0.0.1:8888/web/"

    // 接收服务端进程的错误信息（如不支持的 ABI、二进制执行失败）
    private val errorReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val msg = intent?.getStringExtra("msg") ?: "未知错误"
            handler.post {
                statusText.visibility = android.view.View.VISIBLE
                statusText.text = "TVGate 启动失败：$msg"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)

        setupWebView()

        // 注册错误广播（不导出，避免外部应用发送伪造错误）
        val receiverFlag = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
            android.content.Context.RECEIVER_NOT_EXPORTED
        else
            0
        registerReceiver(
            errorReceiver,
            android.content.IntentFilter(TVGateService.ACTION_ERROR),
            receiverFlag
        )

        // 启动前台服务跑服务端
        val serviceIntent = Intent(this, TVGateService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            // 前台服务被系统拒绝（如 Android 14 权限缺失）时降级为普通服务
            try {
                startService(serviceIntent)
            } catch (ignored: Exception) {
                statusText.visibility = android.view.View.VISIBLE
                statusText.text = "无法启动服务：${e.localizedMessage}"
            }
        }

        // 轮询直到服务端就绪，再加载 Web 管理界面
        waitForServerThenLoad()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(errorReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            // TVGate Web 界面用 fetch/XHR，需要这些
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean = false

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = android.view.View.GONE
            }
        }
    }

    private fun waitForServerThenLoad() {
        statusText.visibility = android.view.View.VISIBLE
        statusText.text = getString(R.string.starting)
        Thread {
            repeat(80) { i ->
                if (isServerUp()) {
                    handler.post {
                        statusText.visibility = android.view.View.GONE
                        webView.loadUrl(serverUrl)
                    }
                    return@Thread
                }
                Thread.sleep(500)
            }
            // 超时后仍尝试加载一次：部分 ROM 下端口探测线程可能被阻塞，
            // 但服务其实已经起来
            handler.post {
                webView.loadUrl(serverUrl)
                android.os.Handler(Looper.getMainLooper()).postDelayed({
                    if (!isServerUp()) {
                        statusText.visibility = android.view.View.VISIBLE
                        statusText.text = "无法连接本地服务(127.0.0.1:8888)，请确认设备为 Android 5.0+ 的 arm 架构，且已授予必要权限。"
                    }
                }, 3000)
            }
        }.start()
    }

    private fun isServerUp(): Boolean {
        return try {
            val conn = URL(serverUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 300
            conn.readTimeout = 300
            conn.requestMethod = "GET"
            conn.responseCode in 200..499
        } catch (_: Exception) {
            false
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
