# TVGate Android App

把 TVGate 服务端（Go 编写）内置到 Android App 中，App 启动后在手机本地
启动转发服务（监听 `127.0.0.1:8888`），并用 WebView 打开 Web 管理界面。

## ⚠️ 关于 Android 4.0 的重要说明

**本 App 无法安装到 Android 4.0（API 15）或 4.4（API 19）设备。**

原因（物理限制，非配置问题）：

1. Go 官方 `GOOS=android` 最低仅支持 `minSdk=21`（Android 5.0）。
   在 4.x 上缺少必需的 Bionic libc 符号与运行时，二进制无法执行。
2. 现代 Android 构建工具链（AGP 8.x / Kotlin 1.9）同样要求 `minSdk>=21`。

如果要兼容 Android 4.0，唯一可行方案是「Java WebView 壳 + 服务端部署在
外部服务器上」，而不是 App 内跑服务端——这与「App 内跑服务端」互斥。

**支持的 Android 版本：5.0（API 21）及以上。**

## 支持的 CPU 架构

- `arm64-v8a`（64 位 ARM，主流手机）
- `armeabi-v7a`（32 位 ARM，老手机）
- `x86_64`（模拟器 / 少数平板）

## 构建步骤

### 1. 交叉编译 Go 服务端二进制

在仓库根目录执行（需 Go 1.21+）：

```bash
# 编译全部架构（推荐）
./build-android.sh all

# 或仅编译某一种
./build-android.sh arm64-v8a
```

脚本会把二进制放入 `app/src/main/assets/<abi>/tvgate.bin`。

> 若 TVGate 使用了 cgo（例如某些 DNS 库），需要把 `CC` 指向 Android NDK
> 的 clang，并设置 `CGO_ENABLED=1`。当前脚本默认 `CGO_ENABLED=0`（纯 Go）。

### 2. 生成 Gradle Wrapper（首次）

`gradle-wrapper.jar` 是二进制，未纳入版本库。在 `android/` 目录执行：

```bash
gradle wrapper --gradle-version 8.6
```

（需本机已安装 Gradle；或用 Android Studio 打开工程时自动生成。）

### 3. 用 Android Studio 打开 `android/` 目录并构建

`Build → Build Bundle(s) / APK(s) → Build APK(s)`

产物位于 `android/app/build/outputs/apk/`。

## 工作原理

1. `MainActivity` 启动前台服务 `TVGateService`。
2. `TVGateService` 通过 `BinaryInstaller` 把 assets 里的二进制
   拷贝到应用私有 `files/` 目录并 `chmod +x`。
3. 用 `Runtime.exec` 启动 `tvgate -addr 0.0.0.0:8888 -data <filesDir>`。
4. `MainActivity` 轮询 `127.0.0.1:8888` 就绪后，用 WebView 加载管理界面。

## 配置 TVGate

二进制沿用 TVGate 的命令行参数（`-addr`、`-data` 等）和同目录配置文件。
如需持久化配置，把配置文件放进 `Context.getFilesDir()`（即上面的 `-data` 目录）。
