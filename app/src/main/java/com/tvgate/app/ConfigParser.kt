package com.tvgate.app

import android.content.Context
import java.io.File

/**
 * TVGate 配置文件解析器。
 *
 * 读取 filesDir/config.yaml，解析出端口、账号、密码、Web 路径。
 * 使用简单的 YAML 解析（不引入额外依赖），仅提取需要的字段。
 *
 * 配置文件格式示例：
 *   server:
 *     port: 8888
 *   web:
 *     username: admin
 *     password: admin
 *     path: /web/
 */
data class TVGateConfig(
    val port: Int = 8888,
    val username: String = "admin",
    val password: String = "admin",
    val webPath: String = "/web/"
) {
    /**
     * 构建完整的 Web 管理界面 URL，例如 http://192.168.1.100:8888/web/
     */
    fun buildWebUrl(ip: String): String {
        val path = if (webPath.endsWith("/")) webPath else "$webPath/"
        return "http://$ip:$port$path"
    }

    /**
     * 构建本地回环 URL，例如 http://127.0.0.1:8888/web/
     */
    fun buildLocalUrl(): String {
        val path = if (webPath.endsWith("/")) webPath else "$webPath/"
        return "http://127.0.0.1:$port$path"
    }
}

object ConfigParser {

    /**
     * 从 filesDir/config.yaml 读取配置。
     * 如果配置文件不存在或解析失败，返回默认值。
     */
    fun load(context: Context): TVGateConfig {
        val configFile = File(context.filesDir, "config.yaml")
        if (!configFile.exists()) {
            return TVGateConfig()
        }

        return try {
            val content = configFile.readText()
            parseYaml(content)
        } catch (_: Exception) {
            TVGateConfig()
        }
    }

    /**
     * 简单的 YAML 解析：逐行扫描，提取需要的字段。
     * 支持注释(#)去除、引号去除。
     */
    private fun parseYaml(content: String): TVGateConfig {
        var port = 8888
        var username = "admin"
        var password = "admin"
        var webPath = "/web/"

        var currentSection = ""

        for (rawLine in content.lines()) {
            val line = rawLine.trim()

            // 跳过空行和注释
            if (line.isEmpty() || line.startsWith("#")) continue

            // 检测 section（如 server: / web:）
            if (!line.startsWith("-") && !line.contains(":")) continue

            // 去掉行内注释（# 后面的内容），但要小心 # 在引号内的情况
            val cleanLine = stripComment(line)

            // 检测 section
            if (cleanLine.endsWith(":") && !cleanLine.startsWith("-")) {
                currentSection = cleanLine.dropLast(1).trim()
                continue
            }

            // 解析 key: value
            val colonIdx = cleanLine.indexOf(':')
            if (colonIdx < 0) continue

            val key = cleanLine.substring(0, colonIdx).trim()
            val value = cleanLine.substring(colonIdx + 1).trim().trimQuotes()

            when {
                currentSection == "server" && key == "port" -> {
                    port = value.toIntOrNull() ?: 8888
                }
                currentSection == "web" && key == "username" -> {
                    username = value
                }
                currentSection == "web" && key == "password" -> {
                    password = value
                }
                currentSection == "web" && key == "path" -> {
                    webPath = value
                }
            }
        }

        return TVGateConfig(port, username, password, webPath)
    }

    /**
     * 去掉行内注释：# 后面的内容（不在引号内的）
     */
    private fun stripComment(line: String): String {
        var inSingleQuote = false
        var inDoubleQuote = false
        for (i in line.indices) {
            val c = line[i]
            when (c) {
                '\'' -> if (!inDoubleQuote) inSingleQuote = !inSingleQuote
                '"' -> if (!inSingleQuote) inDoubleQuote = !inDoubleQuote
                '#' -> if (!inSingleQuote && !inDoubleQuote) {
                    return line.substring(0, i).trim()
                }
            }
        }
        return line
    }

    /**
     * 去掉值两端的引号（单引号或双引号）
     */
    private fun String.trimQuotes(): String {
        var s = this.trim()
        if (s.length >= 2) {
            if ((s.startsWith('"') && s.endsWith('"')) ||
                (s.startsWith('\'') && s.endsWith('\''))
            ) {
                s = s.substring(1, s.length - 1)
            }
        }
        return s
    }
}
