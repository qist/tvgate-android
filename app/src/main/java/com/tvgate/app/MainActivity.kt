package com.tvgate.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private lateinit var btnRestart: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ===== 直播播放页（H5 播放器 /pp 独立入口）=====
    private var playerVisible = false          // 播放页是否正在展示
    private var playerPendingReveal = false    // 已开始加载、等待页面就绪后做过渡动画
    private var playerDismissedByUser = false  // 用户按返回退出后，本次会话不再自动打开
    private var playerOpenHintShown = false    // “按返回键回到主页”提示只弹一次
    private var fullscreenView: View? = null   // HTML5 全屏视频容器

    // 当前配置（首次启动时 config.yaml 可能还不存在，用默认值）
    private var config: TVGateConfig = TVGateConfig()

    // 屏幕分辨率等级
    private enum class ScreenTier {
        PHONE,      // 手机（宽度 < 600dp）
        TABLET,     // 平板（600dp ≤ 宽度 < 960dp）
        TV_720,     // 电视 720p
        TV_1080,    // 电视 1080p
        TV_4K       // 电视 4K
    }
    private var screenTier = ScreenTier.PHONE
    private var densityDpi = 320
    private var screenWidthPx = 1080
    private var screenHeightPx = 1920

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

    // 接收内核重启完成通知
    private val restartReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handler.post {
                btnRestart.isEnabled = true
                btnRestart.text = getString(R.string.btn_restart)
                Toast.makeText(this@MainActivity, R.string.restart_success, Toast.LENGTH_SHORT).show()
                // 刷新信息卡片
                config = ConfigParser.load(this@MainActivity)
                updateInfoCard()
                // 重新轮询服务就绪
                waitForServerReady()
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
        btnRestart = findViewById(R.id.btnRestart)

        // 检测屏幕分辨率，自适应 UI 元素大小
        detectScreenTier()
        applyUiScaling()

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
        // 注册重启完成广播
        registerReceiver(
            restartReceiver,
            android.content.IntentFilter(TVGateService.ACTION_RESTARTED),
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

        // 注册网络变化监听，网络切换时刷新 IP 和二维码
        registerNetworkCallback()

        // 在线更新检测：无网络时自动跳过，不影响其他功能
        checkForUpdate()
    }

    /**
     * 检测屏幕分辨率等级。
     *
     * TV 设备常见分辨率：
     *   - 720p  (1280×720)
     *   - 1080p (1920×1080)
     *   - 4K    (3840×2160)
     *
     * 根据 uiMode 判断是否为 TV，再根据像素高度判断分辨率等级。
     */
    private fun detectScreenTier() {
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        densityDpi = dm.densityDpi
        screenWidthPx = dm.widthPixels
        screenHeightPx = dm.heightPixels

        val config = resources.configuration
        val isTvMode = (config.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
                Configuration.UI_MODE_TYPE_TELEVISION

        // 将像素宽度换算为 dp（消除 DPI 影响）
        val widthDp = screenWidthPx.toFloat() / dm.density

        // 判断是否为 TV / 大屏设备：
        // 很多电视盒子和 TV（如海信）不声明 UI_MODE_TYPE_TELEVISION，
        // 也不报告 SCREENLAYOUT_SIZE_XLARGE，甚至声称有触摸屏。
        // 因此最可靠的方法是直接看像素分辨率：
        //   - 屏幕短边 >= 720px 且长边 >= 1280px → 至少是 720p 级别的大屏
        //   - 屏幕短边 >= 1080px → 至少是 1080p 级别
        //   - 屏幕短边 >= 2000px → 4K 级别
        // 同时检查 uiMode 作为辅助判断。
        val shortSide = minOf(screenWidthPx, screenHeightPx)
        val isTv = isTvMode || shortSide >= 720

        screenTier = when {
            // 按像素短边判断分辨率等级（适用于 TV 和大屏）
            isTv && shortSide >= 2000 -> ScreenTier.TV_4K
            isTv && shortSide >= 1000 -> ScreenTier.TV_1080
            isTv && shortSide >= 700 -> ScreenTier.TV_720
            // 非 TV 小屏：按 dp 宽度区分手机/平板
            widthDp >= 600 -> ScreenTier.TABLET
            else -> ScreenTier.PHONE
        }

        android.util.Log.i("TVGate", "Screen: ${screenWidthPx}x${screenHeightPx} " +
                "dpi=$densityDpi density=${dm.density} tier=$screenTier isTv=$isTv " +
                "isTvMode=$isTvMode shortSide=$shortSide")
    }

    /**
     * 根据屏幕分辨率等级，动态缩放 UI 元素。
     *
     * - 手机：默认尺寸（布局 XML 中的值）
     * - 平板：略放大
     * - TV 720p：默认尺寸，确保紧凑
     * - TV 1080p：适度放大二维码和文字
     * - TV 4K：大幅放大，确保远距离可见
     */
    private fun applyUiScaling() {
        // 缩放因子：Android 的 dp 已自动处理 DPI 缩放，
        // 所以只需要根据设备类型给出额外缩放即可
        val tvScale = when (screenTier) {
            ScreenTier.PHONE -> 1.0f
            ScreenTier.TABLET -> 1.0f
            ScreenTier.TV_720 -> 0.9f   // 720p 屏幕空间小，略缩小
            ScreenTier.TV_1080 -> 1.0f   // 1080p 是基准
            ScreenTier.TV_4K -> 1.2f    // 4K TV 字体略放大，保证远距离可读
        }
        val scale = tvScale.coerceIn(0.7f, 1.5f)

        // 二维码大小（dp 转 px）
        val qrDp = (140 * scale).toInt().coerceIn(100, 300)
        val qrPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, qrDp.toFloat(), resources.displayMetrics
        ).toInt()
        qrCodeImage.layoutParams.apply {
            width = qrPx
            height = qrPx
        }
        qrCodeImage.requestLayout()

        // Logo 大小
        val logoDp = (48 * scale).toInt().coerceIn(36, 96)
        val logoPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, logoDp.toFloat(), resources.displayMetrics
        ).toInt()
        splashLogo.layoutParams.apply {
            width = logoPx
            height = logoPx
        }
        splashLogo.requestLayout()

        // 文字大小缩放
        val appNameSp = (20 * scale).coerceIn(14f, 36f)
        findViewById<TextView>(R.id.splashAppName).textSize = appNameSp

        val statusSp = (12 * scale).coerceIn(10f, 20f)
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, statusSp)

        // IP 地址文字：限制最大值避免遮挡
        val ipAddrSp = (14 * scale).coerceIn(11f, 18f)
        ipAddressText.setTextSize(TypedValue.COMPLEX_UNIT_SP, ipAddrSp)

        // 账号/密码/端口值：限制最大值避免三列互相遮挡
        val valueSp = (13 * scale).coerceIn(10f, 15f)
        accountValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, valueSp)
        passwordValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, valueSp)
        portValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, valueSp)
        // 确保长文本不溢出列宽
        accountValue.maxWidth = (screenWidthPx / 4).coerceAtLeast(80)
        passwordValue.maxWidth = (screenWidthPx / 4).coerceAtLeast(80)
        portValue.maxWidth = (screenWidthPx / 4).coerceAtLeast(80)
        for (tv in listOf(accountValue, passwordValue, portValue)) {
            tv.maxLines = 1
            tv.ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val hintSp = (10 * scale).coerceIn(9f, 16f)
        findViewById<TextView>(R.id.ipCopyHint).setTextSize(TypedValue.COMPLEX_UNIT_SP, hintSp)

        // 重启按钮文字
        val btnSp = (13 * scale).coerceIn(11f, 20f)
        btnRestart.setTextSize(TypedValue.COMPLEX_UNIT_SP, btnSp)

        // 重启按钮 padding 和最小尺寸
        val padHPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, (16 * scale), resources.displayMetrics
        ).toInt()
        val padVPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, (5 * scale), resources.displayMetrics
        ).toInt()
        btnRestart.setPadding(padHPx, padVPx, padHPx, padVPx + 2)

        val minW = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, (110 * scale), resources.displayMetrics
        ).toInt()
        val minH = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, (32 * scale), resources.displayMetrics
        ).toInt()
        btnRestart.minWidth = minW
        btnRestart.minHeight = minH
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

        // 重启按钮点击事件
        btnRestart.setOnClickListener {
            triggerRestart()
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

    // ==================== 直播播放页（启动自动打开） ====================

    /**
     * 服务就绪后按配置决定是否自动打开直播接口。
     * 仅在 config.yaml 中 player.enabled: true 时打开；
     * 用户按返回键退出后，本次会话不再自动打开。
     */
    private fun maybeOpenLivePlayer() {
        if (!config.playerEnabled || playerVisible || playerDismissedByUser) return
        openLivePlayer()
    }

    /**
     * 打开直播播放页：先在后台加载 /pp，页面就绪后做优雅过渡。
     */
    private fun openLivePlayer() {
        playerVisible = true
        playerPendingReveal = true
        webView.loadUrl(config.buildPlayerUrl())
        enterImmersiveMode()

        if (!playerOpenHintShown) {
            playerOpenHintShown = true
            Toast.makeText(this, R.string.player_back_hint, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 播放页加载完成的过渡动画：
     * 信息卡片淡出下移，播放页自下方微微放大淡入，形成顺滑的“揭幕”效果。
     */
    private fun revealPlayerPage() {
        if (!playerVisible || !playerPendingReveal) return
        playerPendingReveal = false

        webView.visibility = View.VISIBLE
        webView.alpha = 0f
        webView.scaleX = 0.96f
        webView.scaleY = 0.96f
        webView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(80)
            .setDuration(450)
            .withEndAction {
                webView.scaleX = 1f
                webView.scaleY = 1f
            }
            .start()

        splashContainer.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction { splashContainer.visibility = View.GONE }
            .start()
    }

    /**
     * 退出直播播放页，回到信息卡片。反向过渡。
     */
    private fun closeLivePlayer() {
        playerVisible = false
        playerDismissedByUser = true
        playerPendingReveal = false
        exitImmersiveMode()

        webView.animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction {
                webView.visibility = View.GONE
                // 停止视频解码与网络拉流
                webView.loadUrl("about:blank")
                webView.alpha = 1f

                splashContainer.alpha = 0f
                splashContainer.visibility = View.VISIBLE
                splashContainer.animate()
                    .alpha(1f)
                    .setDuration(300)
                    .start()
            }
            .start()
    }

    /**
     * 沉浸式全屏：隐藏状态栏/导航栏，播放页视觉更干净。
     */
    private fun enterImmersiveMode() {
        // 兜底压黑系统栏：即使隐藏失败也不露白条
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.BLACK
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.BLACK
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.hide(android.view.WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }

    private fun exitImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
            window.insetsController?.show(android.view.WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(errorReceiver)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(restartReceiver)
        } catch (_: Exception) {
        }
        // 注销网络监听
        try {
            networkCallback?.let {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(it)
            }
            networkCallback = null
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun setupWebView() {
        webView.setBackgroundColor(Color.BLACK)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            // 直播页启动后自动起播，无需用户手势
            mediaPlaybackRequiresUserGesture = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
        }
        // 文档启动脚本：播放器默认主题 auto（系统浅色模式时会渲染白色顶栏），
        // 在页面任何 JS 执行前预置为深色。注意 player.html 早期脚本与
        // usePersistedEnum 都按裸字符串比较（'dark'，不带 JSON 引号），
        // 带引号会判不出、且 React 挂载后会把非法值删除。
        // 仅当用户未手动选过主题时生效，用户在播放器里选的主题仍被尊重。
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                "try{if(localStorage.getItem('tvgate-player-theme')===null){" +
                    "localStorage.setItem('tvgate-player-theme','dark')" +
                "}}catch(e){}",
                setOf("*")
            )
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean = false

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // 部分机型导航后会重置 WebView 底色，重新压黑避免白闪
                view?.setBackgroundColor(Color.BLACK)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // 播放页就绪后再做过渡动画，避免露出白屏/加载中
                revealPlayerPage()
            }
        }
        // 支持 H5 播放器的网页全屏按钮（HTML5 fullscreen API）
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                fullscreenView = view
                webView.visibility = View.GONE
                (window.decorView as ViewGroup).addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }

            override fun onHideCustomView() {
                fullscreenView?.let {
                    (window.decorView as ViewGroup).removeView(it)
                }
                fullscreenView = null
                if (playerVisible) webView.visibility = View.VISIBLE
            }
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

                        // 直播接口开启时，启动即打开直播页
                        maybeOpenLivePlayer()
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
     * 注册网络变化监听。
     * 网络切换时（WiFi → 4G/5G 或反过来），IP 地址会变化，
     * 需要刷新界面上的 IP、二维码和通知。
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return

        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) {
                    handler.post { updateInfoCard() }
                }

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: android.net.LinkProperties
                ) {
                    handler.post { updateInfoCard() }
                }
            }
            cm.registerNetworkCallback(
                android.net.NetworkRequest.Builder().build(),
                callback
            )
            networkCallback = callback
        } catch (_: Exception) {
        }
    }

    /**
     * 触发内核重启。
     */
    private fun triggerRestart() {
        btnRestart.isEnabled = false
        btnRestart.text = getString(R.string.restart_in_progress)
        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.restart_in_progress)
        statusText.setTextColor(
            ContextCompat.getColor(this, R.color.splash_status_normal)
        )
        progressBar.visibility = View.VISIBLE

        val restartIntent = Intent(this, TVGateService::class.java).apply {
            action = TVGateService.ACTION_RESTART
        }
        try {
            startService(restartIntent)
        } catch (e: Exception) {
            btnRestart.isEnabled = true
            btnRestart.text = getString(R.string.btn_restart)
            statusText.text = getString(R.string.restart_failed) + ": ${e.localizedMessage}"
            statusText.setTextColor(
                ContextCompat.getColor(this, R.color.splash_status_error)
            )
            progressBar.visibility = View.GONE
        }
    }

    /**
     * 遥控器按键处理：
     * - DPAD 上下/左右：导航焦点（系统默认处理）
     * - OK/ENTER：复制地址到剪贴板（聚焦信息卡片时）或触发重启（聚焦重启按钮时）
     * - BACK：移到后台（不退出）
     * - MENU：同 OK
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                // 如果重启按钮聚焦，触发重启
                if (btnRestart.isFocused && btnRestart.isEnabled) {
                    triggerRestart()
                    return true
                }
                // 否则：复制地址
                if (ipCard.visibility == View.VISIBLE) {
                    val ip = NetworkUtils.getLocalIpAddress(this)
                    val url = if (ip != null) config.buildWebUrl(ip) else config.buildLocalUrl()
                    copyToClipboard(url)
                    return true
                }
            }
            KeyEvent.KEYCODE_MENU -> {
                // 菜单键：同 OK
                if (btnRestart.isFocused && btnRestart.isEnabled) {
                    triggerRestart()
                    return true
                }
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
        // 直播播放页展示时：返回键先退出播放页，回到信息卡片
        if (playerVisible) {
            closeLivePlayer()
            return
        }
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            moveTaskToBack(true)
        }
    }

    // ==================== 在线 APK 更新 ====================

    /**
     * 静默检查更新：对比 GitHub Latest 版本与本地版本。
     * - 网络不通：AppUpdater 内部跳过验证（本地版本优于远程，直接忽略）。
     * - 有新版：弹出更新对话框。
     */
    private fun checkForUpdate() {
        AppUpdater.checkUpdate(this) { info ->
            if (info == null) return@checkUpdate
            showUpdateDialog(info)
        }
    }

    private fun showUpdateDialog(info: AppUpdater.UpdateInfo) {
        val local = AppUpdater.localVersion(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_dialog_title)
            .setMessage(getString(R.string.update_dialog_message, info.version, local))
            .setPositiveButton(R.string.update_positive) { _, _ ->
                startUpdateDownload(info)
            }
            .setNegativeButton(R.string.update_negative, null)
            .setCancelable(true)
            .show()
    }

    /**
     * 后台下载新版 APK 并展示下载进度，完成后交给系统安装器。
     */
    private fun startUpdateDownload(info: AppUpdater.UpdateInfo) {
        // 用程序化布局实现“进度文字 + 横向进度条”的下载对话框
        val paddingDp = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics
        ).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingDp, paddingDp, paddingDp, 0)
        }
        val progressLabel = TextView(this).apply {
            text = getString(R.string.update_downloading, 0)
            textSize = 13f
        }
        val progressBar = ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
        }
        column.addView(progressLabel)
        column.addView(progressBar)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_dialog_title)
            .setView(column)
            .setCancelable(false)
            .create()
        dialog.show()

        Thread {
            val file = AppUpdater.downloadApk(this, info.apkUrl, info.apkName) { percent ->
                runOnUiThread {
                    progressLabel.text = getString(R.string.update_downloading, percent)
                    progressBar.progress = percent
                }
            }
            runOnUiThread {
                dialog.dismiss()
                if (file != null) {
                    installApk(file)
                } else {
                    Toast.makeText(
                        this, R.string.update_download_failed, Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    /**
     * 通过系统安装器安装新版 APK（FileProvider 授予临时读取权限）。
     */
    private fun installApk(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                this, R.string.update_install_src_forbidden, Toast.LENGTH_LONG
            ).show()
            // 引导用户开启“允许安装未知来源”
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (_: Exception) {
            }
            return
        }

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.update_install_src_forbidden, Toast.LENGTH_LONG).show()
        }
    }
}
