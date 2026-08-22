package com.tvgate.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.animation.AnimationUtils
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var splashContainer: LinearLayout
    private lateinit var splashLogo: ImageView
    private lateinit var ipCard: LinearLayout
    private lateinit var ipAddressText: TextView
    private lateinit var qrCodeImage: ImageView
    private lateinit var accountValue: TextView
    private lateinit var passwordValue: TextView
    private lateinit var portValue: TextView
    private lateinit var remoteHintCard: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    // 当前配置（首次启动时 config.yaml 可能还不存在，用默认值）
    private var config: TVGateConfig = TVGateConfig()

    // 接收服务端进程的错误信息
    private val errorReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra("msg") ?: "未知错误"
            handler.post {
                statusText.visibility = View.VISIBLE
                statusText.text = "TVGate 启动失败：$msg"
                statusText.setTextColor(
                    ContextCompat.getColor(this@MainActivity, R.color.splash_status_error)
                )
                progressBar.visibility = View.GONE
            }
        }
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 尝试读取已有的 config.yaml（非首次启动时存在）
        config = ConfigParser.load(this)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        splashContainer = findViewById(R.id.splashContainer)
        splashLogo = findViewById(R.id.splashLogo)
        ipCard = findViewById(R.id.ipCard)
        ipAddressText = findViewById(R.id.ipAddressText)
        qrCodeImage = findViewById(R.id.qrCodeImage)
        accountValue = findViewById(R.id.accountValue)
        passwordValue = findViewById(R.id.passwordValue)
        portValue = findViewById(R.id.portValue)
        remoteHintCard = findViewById(R.id.remoteHintCard)

        setupWebView()
        playSplashAnimations()

        // 先用当前配置（可能是默认值）显示信息卡片
        updateInfoCard()

        // 遥控器/键盘：卡片显示后自动聚焦，方便方向键导航
        ipCard.postDelayed({
            if (ipCard.visibility == View.VISIBLE) {
                ipCard.requestFocus()
            }
        }, 1200)

        // Android 13+ 需要运行时申请通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 注册错误广播
        val receiverFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Context.RECEIVER_NOT_EXPORTED
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
            try {
                startService(serviceIntent)
            } catch (ignored: Exception) {
                statusText.visibility = View.VISIBLE
                statusText.text = "无法启动服务：${e.localizedMessage}"
            }
        }

        // 轮询等待服务端就绪
        // 在此期间会检测 config.yaml 是否出现，出现后重新读取配置并更新界面
        waitForServerReady()
    }

    /**
     * 根据当前 config 更新信息卡片（IP、端口、账号、密码、二维码）。
     */
    private fun updateInfoCard() {
        val ip = NetworkUtils.getLocalIpAddress(this)
        val fullUrl = if (ip != null) {
            config.buildWebUrl(ip)
        } else {
            config.buildLocalUrl()
        }

        ipAddressText.text = fullUrl
        accountValue.text = config.username
        passwordValue.text = config.password
        portValue.text = config.port.toString()

        // 生成二维码
        val qrBitmap = generateQrCode(fullUrl, 480)
        if (qrBitmap != null) {
            qrCodeImage.setImageBitmap(qrBitmap)
        }

        // 点击卡片复制地址
        ipCard.setOnClickListener {
            copyToClipboard(fullUrl)
        }

        // 卡片淡入显示
        if (ipCard.visibility != View.VISIBLE) {
            ipCard.alpha = 0f
            ipCard.visibility = View.VISIBLE
            ipCard.animate()
                .alpha(1f)
                .setStartDelay(500)
                .setDuration(500)
                .start()

            // 遥控器提示卡片同时淡入
            remoteHintCard.alpha = 0f
            remoteHintCard.visibility = View.VISIBLE
            remoteHintCard.animate()
                .alpha(1f)
                .setStartDelay(700)
                .setDuration(500)
                .start()
        }
    }

    private fun generateQrCode(content: String, sizePx: Int): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val bitMatrix = QRCodeWriter().encode(
                content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints
            )
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (_: Exception) {
            null
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TVGate URL", text))
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    private fun playSplashAnimations() {
        val logoAnim = AnimationUtils.loadAnimation(this, R.anim.splash_logo_enter)
        splashLogo.startAnimation(logoAnim)

        val textAnim = AnimationUtils.loadAnimation(this, R.anim.splash_text_enter)
        statusText.startAnimation(textAnim)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean = false
        }
    }

    /**
     * 轮询等待服务端就绪。
     *
     * 关键：首次启动时 config.yaml 还不存在，TVGate 二进制启动后才自动生成。
     * 所以在轮询过程中持续检测 config.yaml 是否出现，一旦出现就重新读取
     * 端口/账号/密码并更新界面。
     */
    private fun waitForServerReady() {
        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.starting)

        val configFile = File(filesDir, "config.yaml")
        var configLoaded = configFile.exists()

        Thread {
            repeat(80) {
                // 检测 config.yaml 是否新出现（首次启动场景）
                if (!configLoaded && configFile.exists()) {
                    configLoaded = true
                    val newConfig = ConfigParser.load(this)
                    handler.post {
                        config = newConfig
                        updateInfoCard()
                    }
                }

                if (isServerUp(config.buildLocalUrl())) {
                    handler.post {
                        // 服务就绪后再读一次配置（确保拿到最终值）
                        val finalConfig = ConfigParser.load(this)
                        config = finalConfig
                        updateInfoCard()

                        progressBar.visibility = View.GONE
                        statusText.text = "服务已就绪，可扫码或输入地址访问"
                    }
                    return@Thread
                }
                Thread.sleep(500)
            }
            // 超时
            handler.post {
                progressBar.visibility = View.GONE
                statusText.visibility = View.VISIBLE
                statusText.text = getString(R.string.connect_failed)
                statusText.setTextColor(
                    ContextCompat.getColor(this@MainActivity, R.color.splash_status_error)
                )
            }
        }.start()
    }

    private fun isServerUp(url: String): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 300
            conn.readTimeout = 300
            conn.requestMethod = "GET"
            conn.responseCode in 200..499
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 遥控器按键处理：
     * - DPAD 上下/左右：导航焦点（系统默认处理）
     * - OK/ENTER：复制地址到剪贴板
     * - BACK：移到后台（不退出）
     * - MENU：同 OK，复制地址
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                // 遥控器 OK 键：复制地址
                if (ipCard.visibility == View.VISIBLE) {
                    val ip = NetworkUtils.getLocalIpAddress(this)
                    val url = if (ip != null) config.buildWebUrl(ip) else config.buildLocalUrl()
                    copyToClipboard(url)
                    return true
                }
            }
            KeyEvent.KEYCODE_MENU -> {
                // 菜单键：同 OK，复制地址
                if (ipCard.visibility == View.VISIBLE) {
                    val ip = NetworkUtils.getLocalIpAddress(this)
                    val url = if (ip != null) config.buildWebUrl(ip) else config.buildLocalUrl()
                    copyToClipboard(url)
                    return true
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (webView.canGoBack()) {
                    webView.goBack()
                    return true
                }
                moveTaskToBack(true)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * 按返回键时不退出 App，而是移到后台。
     */
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            moveTaskToBack(true)
        }
    }
}
