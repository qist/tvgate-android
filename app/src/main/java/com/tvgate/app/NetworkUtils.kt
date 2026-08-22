package com.tvgate.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 网络工具类：获取当前设备的局域网 IPv4 地址。
 *
 * 优先使用 ConnectivityManager（API 23+），fallback 到 NetworkInterface 枚举。
 * 过滤掉回环地址(127.x)、链路本地(169.254.x)、虚拟接口(docker/vpn 等)。
 */
object NetworkUtils {

    /**
     * 获取本机局域网 IPv4 地址，未找到返回 null。
     */
    fun getLocalIpAddress(context: Context): String? {
        // 方式一：ConnectivityManager（API 23+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.let {
                    val network = it.activeNetwork
                    val caps = it.getNetworkCapabilities(network)
                    if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
                    ) {
                        val linkProps = it.getLinkProperties(network)
                        linkProps?.linkAddresses?.forEach { addr ->
                            val inet = addr.address
                            if (inet is Inet4Address && !inet.isLoopbackAddress()) {
                                val ip = inet.hostAddress
                                if (isValidLanIp(ip)) return ip
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 方式二：枚举所有 NetworkInterface
        return getIpFromInterfaces()
    }

    /**
     * 获取本机局域网 IPv4 地址列表（可能有多个网卡）。
     * 返回形如 ["192.168.1.100", "10.0.0.5"] 的列表。
     */
    fun getAllLocalIps(context: Context): List<String> {
        val result = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.let {
                    val network = it.activeNetwork
                    val caps = it.getNetworkCapabilities(network)
                    if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
                    ) {
                        val linkProps = it.getLinkProperties(network)
                        linkProps?.linkAddresses?.forEach { addr ->
                            val inet = addr.address
                            if (inet is Inet4Address && !inet.isLoopbackAddress()) {
                                val ip = inet.hostAddress
                                if (isValidLanIp(ip) && ip !in result) {
                                    result.add(ip)
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 补充：枚举 NetworkInterface 找到的额外 IP
        val ifaceIps = getIpListFromInterfaces()
        for (ip in ifaceIps) {
            if (ip !in result) result.add(ip)
        }

        return result
    }

    private fun getIpFromInterfaces(): String? {
        return getIpListFromInterfaces().firstOrNull()
    }

    private fun getIpListFromInterfaces(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (intf in interfaces) {
                // 跳过未启用、回环、虚拟接口
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.displayName?.lowercase() ?: ""
                // 过滤 docker/vpn/virtual 等虚拟接口
                if (name.contains("docker") || name.contains("vpn") ||
                    name.contains("virtual") || name.contains("rmnet")
                ) continue

                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress
                        if (isValidLanIp(ip)) {
                            result.add(ip)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    /**
     * 判断是否是有效的局域网 IP：
     * 排除 127.x(回环)、169.254.x(链路本地)、0.x
     */
    private fun isValidLanIp(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        if (ip.startsWith("127.")) return false
        if (ip.startsWith("169.254.")) return false
        if (ip.startsWith("0.")) return false
        return true
    }

    /**
     * 构建完整的 Web 访问 URL，例如 http://192.168.1.100:8888/web/
     */
    fun buildWebUrl(ip: String): String {
        return "http://$ip:8888/web/"
    }

    /**
     * 构建完整的访问地址（不含路径），例如 http://192.168.1.100:8888
     */
    fun buildBaseUrl(ip: String): String {
        return "http://$ip:8888"
    }
}
