# TVGate Android App

把 TVGate 服务端（Go 编写）内置到 Android App 中，App 启动后在设备本地
启动转发服务，并展示局域网访问信息（IP 地址、端口、账号密码、二维码），
方便手机扫码或输入地址访问 Web 管理界面。

专为**机顶盒、电视盒子、Android TV** 设计，支持遥控器操作。

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

- `arm64-v8a`（64 位 ARM，主流手机 / 机顶盒）
- `armeabi-v7a`（32 位 ARM，老设备）
- `x86_64`（模拟器 / 少数平板）

## 功能特性

### 后台运行

- App 启动后自动以前台服务（Foreground Service）方式运行 TVGate 服务端
- 通知栏显示持久通知，包含**局域网访问地址和端口**
- 通知提供「停止服务」和「打开」操作按钮
- 按返回键时 App 移到后台运行，不会退出

### 局域网信息展示

启动界面一目了然地显示：

- **局域网 IP 地址和访问端口**（自动检测设备 IP）
- **登录账号 / 密码**（从 `config.yaml` 动态读取）
- **访问二维码**（手机扫码即可打开，ZXing 生成）
- 点击信息卡片可复制访问地址到剪贴板

### 动态配置

App 从 `config.yaml` 读取以下配置并实时更新界面：

| 配置项 | 说明 | 默认值 |
|---|---|---|
| `server.port` | 服务监听端口 | `8888` |
| `web.username` | Web 管理界面账号 | `admin` |
| `web.password` | Web 管理界面密码 | `admin` |
| `web.path` | Web 管理界面路径 | `/web/` |

首次启动时 `config.yaml` 可能不存在，TVGate 二进制启动后会自动生成默认配置，
App 会检测配置文件出现后自动重新读取并更新界面。

### 遥控器支持

专为机顶盒 / 电视盒子优化：

| 按键 | 功能 |
|---|---|
| **方向键** | 切换焦点（导航信息卡片） |
| **OK / 确认键** | 复制访问地址到剪贴板 |
| **菜单键** | 同 OK 键 |
| **返回键** | 退到后台运行（不退出 App） |

界面元素支持焦点高亮，遥控器选中时显示蓝色边框。

## 构建

本仓库包含完整构建链：`build-android.sh` 交叉编译 Go 服务端为 `.so`，
`build-android-split.sh` 分架构打包并签名 APK。

### 前置依赖

- Go 1.25+
- Android NDK（`$ANDROID_NDK_HOME` 或 `$ANDROID_HOME/ndk/*`）
- Android SDK（build-tools 含 `zipalign` / `apksigner`）
- TVGate 服务端源码（`$TVGATE_SRC`，默认 `../tvgate`）

  ```bash
  git clone https://github.com/qist/tvgate.git ../tvgate
  ```

### 1. 交叉编译服务端二进制

```bash
# 编译全部架构（推荐）
./build-android.sh all

# 或仅编译某一种
./build-android.sh arm64-v8a
```

脚本把二进制放入 `app/src/main/jniLibs/<abi>/libtvgate.so`。

> armv7 / x86_64 需要 quic-go 的 cgo，脚本会自动用 Android NDK 的 clang
> 作为 C 交叉编译器（`CGO_ENABLED=1`）。

### 2. 构建并签名 APK

```bash
# 首次配置本地签名（.env 已 git 忽略，只需写一次）：
cat > .env <<'EOF'
TVGATE_KS_PASS=你的密钥密码
EOF

# 分架构产出 3 个可直接安装的 APK
./build-android-split.sh
```

签名参数（都可用环境变量覆盖）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `TVGATE_KS_FILE` | `./release.keystore` | 密钥文件路径（本地存放，不入库） |
| `TVGATE_KS_ALIAS` | `tvgate` | 密钥别名 |
| `TVGATE_KS_PASS` | 必填 | 密钥密码 |

产物：`TVGate-v{version}-{arm64,arm,x86_64}.apk`（仓库根目录）。

### 3. 只用 Android Studio / Gradle 构建（不重新编译 .so）

源码已内置编译好的三个架构二进制，直接：

```
Build → Build Bundle(s) / APK(s) → Build APK(s)
```

产物位于 `app/build/outputs/apk/`。

## GitHub Actions 自动构建

仓库已配置 `.github/workflows/build.yml`：push 到 `main` 或手动触发后，
CI 会自动完成「clone 服务端源码 → 交叉编译 → 分架构打包 → 签名 → 上传产物」。

### 需要配置的 Secrets（仓库 Settings → Secrets and variables → Actions）

| Secret | 内容 |
|---|---|
| `TVGATE_KS_BASE64` | `base64 -w0 release.keystore` 的输出（密钥文件本身不入库） |
| `TVGATE_KS_PASS` | 密钥密码 |

密钥别名 CI 默认 `tvgate`（workflow 中 `env.TVGATE_KS_ALIAS` 可改）。
构建完成后在 Actions 页面下载 `tvgate-apks` artifact。

> 若 `qist/tvgate` 为私有仓库，请把 workflow 里的 clone 地址改为带凭据的
> URL（凭据存为另一个 Secret）。

## 工作原理

1. `MainActivity` 启动前台服务 `TVGateService`。
2. `TVGateService` 通过 `BinaryInstaller` 从 `nativeLibraryDir` 取出
   `libtvgate.so` 拷贝到应用私有 `files/` 目录并 `chmod +x`。
3. 用 `Runtime.exec` 启动 `tvgate -config <filesDir>/config.yaml`。
4. `MainActivity` 轮询本地服务端口就绪后，更新界面显示局域网访问信息。
5. `ConfigParser` 从 `config.yaml` 读取端口、账号、密码、路径。
6. `NetworkUtils` 检测设备局域网 IP 地址。
7. 使用 ZXing 生成访问地址二维码显示在界面上。

## 配置 TVGate

二进制沿用 TVGate 的 `-config` 参数和同目录配置文件 `config.yaml`。
配置文件位于应用私有 `files/` 目录（即 `-data` 目录）。

### config.yaml 示例

```yaml
server:
  port: 8888

web:
  username: admin
  password: admin
  path: /web/
```

修改 `config.yaml` 后重启 App 即可生效。App 会自动读取最新配置并更新
界面显示的端口、账号、密码和二维码。

## 项目结构

```
app/src/main/
├── java/com/tvgate/app/
│   ├── MainActivity.kt       # 启动界面、遥控器处理、信息展示
│   ├── TVGateService.kt      # 前台服务，常驻运行服务端二进制
│   ├── BinaryInstaller.kt    # 从 jniLibs 提取并安装二进制
│   ├── ConfigParser.kt       # 解析 config.yaml 配置文件
│   └── NetworkUtils.kt      # 获取设备局域网 IP 地址
├── res/
│   ├── layout/activity_main.xml   # 启动界面布局
│   ├── drawable/                   # 图标、卡片背景、焦点样式
│   ├── values/                     # 颜色、字符串、主题
│   └── anim/                        # 启动动画
└── AndroidManifest.xml
```
