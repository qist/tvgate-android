package com.tvgate.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 负责把 TVGate 服务端二进制准备好并交给 TVGateService 执行。
 *
 * Android 15 起强制“只能执行 APK 内自带的代码”，禁止从 app 私有数据目录
 * (files/code_cache) 执行外部二进制。因此首选方案是：内置二进制以 JNI 库
 * libtvgate.so 的形式打进 lib/<abi>/，运行时直接从系统提供的 nativeLibraryDir
 * 执行该 .so（执行自己 APK 内的 .so 一直被允许，且不受 15 私有目录限制影响）。
 *
 * 但存在例外：x86_64 模拟器上装 arm64 包时，nativeLibraryDir 里的 .so 是 arm64 的，
 * 模拟器不允许直接 execve 转译的 arm64 .so（转译只对 System.loadLibrary 生效）。
 * 此时该 .so 文件虽然“存在且 canExecute() 为真”，exec 却会失败。故这里不再由
 * install() 二选一，而是返回“有序候选列表”，交由 TVGateService 逐个尝试 exec，
 * 某个能真正拉起进程即采用，失败则顺延下一个候选（code_cache 拷贝方案在模拟器
 * 上没有 noexec 限制，可成功）。
 *
 * 注意：二进制在 APK 内只打包一份（lib/<abi>/libtvgate.so）。回退路径不再依赖
 * assets，而是直接把 nativeLibraryDir 里的 .so 拷贝到可写目录再 exec，避免体积翻倍。
 */
object BinaryInstaller {

    private const val TAG = "BinaryInstaller"
    const val BINARY_NAME = "tvgate"
    const val SO_NAME = "libtvgate.so"

    /**
     * 返回“尝试执行”的候选二进制有序列表：
     *   1. nativeLibraryDir/libtvgate.so        （真机 Android 15 首选，最稳）
     *   2. 拷贝 native lib 到 code_cache 的文件  （模拟器 / 老 ROM 回退）
     *   3. 拷贝 native lib 到 files 的文件       （再回退）
     * TVGateService 会依次 exec，命中即用。
     */
    fun installCandidates(context: Context): List<File> {
        val list = mutableListOf<File>()

        // 1) 首选：直接 exec APK 内的 libtvgate.so
        nativeLibFile(context)?.let { so ->
            if (so.exists() && so.canExecute()) list += so
        }

        // 2) 回退：把 native lib 拷贝到可写目录后 exec
        //    （模拟器上 native lib 文件本身可被拷贝；code_cache 无 noexec 限制，可成功）
        val srcSo = nativeLibFile(context)
        if (srcSo != null && srcSo.exists()) {
            val outDirs = mutableListOf<File>()
            try { outDirs += context.codeCacheDir } catch (_: Exception) {}
            try { outDirs += context.filesDir } catch (_: Exception) {}
            for (outDir in outDirs) {
                try {
                    list += copyTo(context, srcSo, outDir)
                } catch (e: Exception) {
                    Log.w(TAG, "prepare fallback for ${outDir.absolutePath} failed: ${e.message}")
                }
            }
        }

        if (list.isEmpty()) {
            throw IOException("no usable binary candidate (nativeLib unavailable)")
        }
        Log.i(TAG, "candidates: ${list.joinToString { it.absolutePath }}")
        return list
    }

    /** 兼容旧调用：返回首个候选（多数场景等同于直接用 native lib） */
    @Deprecated("use installCandidates + caller-side exec fallback")
    fun install(context: Context): File = installCandidates(context).first()

    /** APK 内打包的 JNI 库 libtvgate.so 的真实路径（由系统解压到 nativeLibraryDir） */
    private fun nativeLibFile(context: Context): File? {
        return try {
            val dir = File(context.applicationInfo.nativeLibraryDir)
            val f = File(dir, SO_NAME)
            if (f.exists()) f else null
        } catch (_: Exception) {
            null
        }
    }

    /** 把 native lib 拷贝到可写目录并赋可执行权限，返回目标文件 */
    private fun copyTo(context: Context, src: File, outDir: File): File {
        if (!outDir.exists()) {
            if (!outDir.mkdirs()) throw IOException("cannot mkdir ${outDir.absolutePath}")
        }
        val outFile = File(outDir, BINARY_NAME)

        // 已存在且大小一致则跳过拷贝（提升二次启动速度）
        if (outFile.exists() && outFile.canExecute() && outFile.length() == src.length()) {
            Log.i(TAG, "binary already prepared: ${outFile.absolutePath}")
            return outFile
        }

        src.inputStream().use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }

        if (!outFile.setExecutable(true, false)) {
            throw IOException("failed to chmod +x ${outFile.absolutePath}")
        }
        Log.i(TAG, "binary prepared: ${outFile.absolutePath} (${outFile.length()} bytes)")
        return outFile
    }
}
