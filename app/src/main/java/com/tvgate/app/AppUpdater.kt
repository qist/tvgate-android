package com.tvgate.app

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 在线 APK 更新组件。
 *
 * 流程：
 *   1. 检查当前设备是否有可用网络（无网络则跳过，不打扰用户）。
 *   2. 通过 GitHub Releases API（releases/latest）获取最新发布版本号。
 *   3. 与本地安装版本号对比：本地版本更低 → 提示下载并更新。
 *   4. 按当前设备 ABI 从发布资产中找到对应 APK，下载到 filesDir/updates/ 并交给系统安装器。
 *
 * 版本号来源：APK 的 versionName 即 TVGate 版本（由 TVGATE_VERSION 注入），
 * 与 tvgate 仓库 tag 一致，因此可直接与 GitHub 发布版本号比较。
 */
object AppUpdater {
    // GitHub Releases 最新版 API。发布资产命名：TVGate-{version}-{arm64|arm|x86_64}.apk
    private const val GITHUB_REPO = "qist/tvgate-android"
    private const val LATEST_API = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    // 设备 ABI → 发布资产中的短名
    private val ABI_SHORT = mapOf(
        "arm64-v8a" to "arm64",
        "armeabi-v7a" to "arm",
        "x86_64" to "x86_64"
    )

    /** 远程更新信息 */
    data class UpdateInfo(
        val version: String,
        val apkName: String,
        val apkUrl: String
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 检查是否有可用的互联网连接。
     * 无网络时返回 false，调用方应据此跳过更新验证。
     */
    fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 获取本地已安装版本号（versionName）。
     */
    fun localVersion(context: Context): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            (info.versionName ?: BuildConfig.VERSION_NAME).removePrefix("v")
        } catch (_: PackageManager.NameNotFoundException) {
            BuildConfig.VERSION_NAME.removePrefix("v")
        }
    }

    /**
     * 获取当前设备的 ABI 短名（arm64 / arm / x86_64），不支持的架构返回 null。
     */
    fun abiShortName(): String? {
        val abis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS
        } else {
            listOf(Build.CPU_ABI).toTypedArray()
        }
        for (abi in abis) {
            ABI_SHORT[abi]?.let { return it }
        }
        return null
    }

    /**
     * 在后台线程检查更新，结果通过 [onResult] 回调到主线程。
     *
     * - 无网络：直接回调 null（跳过验证，不提示用户）。
     * - 有更新：回调 [UpdateInfo]。
     * - 无需更新 / 检查失败：回调 null。
     */
    fun checkUpdate(context: Context, onResult: (UpdateInfo?) -> Unit) {
        Thread {
            var info: UpdateInfo? = null
            try {
                if (!isNetworkAvailable(context)) {
                    // 网络不通，跳过验证
                    info = null
                } else {
                    val remote = fetchLatest()
                    if (remote != null && isNewer(context, remote.version)) {
                        // 当前架构发行版没有对应 APK 时也要跳过
                        val short = abiShortName()
                        if (short != null) {
                            // 允许资源名带或不带 "v" 前缀（如 TVGate-3.11.0-arm64.apk / TVGate-v3.11.0-arm64.apk）
                            val apkName = findAssetName(remote.json, remote.version, short)
                            if (apkName != null) {
                                val assetUrl = findAssetUrl(remote.json, apkName)
                                if (assetUrl != null) {
                                    info = UpdateInfo(remote.version, apkName, assetUrl)
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                info = null
            }
            mainHandler.post { onResult(info) }
        }.start()
    }

    private data class Latest(val version: String, val json: JSONObject)

    /** 拉取 GitHub 最新发布（阻塞调用，需在后台线程执行）。 */
    private fun fetchLatest(): Latest? {
        val conn = URL(LATEST_API).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode !in 200..299) return null

            val text = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(text)
            val tag = json.optString("tag_name").removePrefix("v")
            if (tag.isBlank()) return null
            Latest(tag, json)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 在发布资产中查找与版本、ABI 匹配的 APK 文件名。
     * 兼容资源名带 / 不带 "v" 前缀，找到则返回实际资源名，未找到返回 null。
     */
    private fun findAssetName(json: JSONObject, version: String, abiShort: String): String? {
        val candidates = setOf(
            "TVGate-$version-$abiShort.apk",
            "TVGate-v$version-$abiShort.apk"
        )
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val name = assets.getJSONObject(i).optString("name")
            if (name in candidates) return name
        }
        return null
    }

    /** 在发布资产中按文件名查找其下载地址（browser_download_url）。 */
    private fun findAssetUrl(json: JSONObject, apkName: String): String? {
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            val browserUrl = asset.optString("browser_download_url")
            if (name == apkName && browserUrl.isNotBlank()) {
                return browserUrl
            }
        }
        return null
    }

    /**
     * 判断远程版本是否比本地版本更新。
     */
    private fun isNewer(context: Context, remote: String): Boolean {
        return compareVersions(localVersion(context), remote) < 0
    }

    /**
     * 语义化版本比较（可接受 v 前缀、多段数字）。返回负数表示 version1 < version2。
     */
    fun compareVersions(v1: String, v2: String): Int {
        val p1 = parseVersion(v1)
        val p2 = parseVersion(v2)
        val n = maxOf(p1.size, p2.size)
        for (i in 0 until n) {
            val a = p1.getOrNull(i) ?: 0
            val b = p2.getOrNull(i) ?: 0
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    private fun parseVersion(v: String): List<Int> {
        return v.removePrefix("v")
            .split(".", "-", "+")
            .mapNotNull { it.toIntOrNull() }
    }

    /**
     * 下载 APK 到 filesDir/updates/<apkName>。阻塞调用，需在后台线程执行。
     * [onProgress] 回调下载百分比到主线程，成功返回下载文件。
     */
    fun downloadApk(
        context: Context,
        url: String,
        apkName: String,
        onProgress: (Int) -> Unit
    ): File? {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        val dest = File(dir, apkName)

        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            // GitHub 内部会临时 302 到 CDN/Presigned URL，判断最终 200
            val code = conn.responseCode
            if (code !in 200..299) return null

            val total = conn.contentLength.toLong()
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var downloaded = 0L
                    var lastReport = -1
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val percent = ((downloaded * 100) / total).toInt()
                            if (percent != lastReport) {
                                lastReport = percent
                                val p = percent
                                mainHandler.post { onProgress(p) }
                            }
                        }
                    }
                }
            }
            dest
        } catch (_: Exception) {
            dest.delete()
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}