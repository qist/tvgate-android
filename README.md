# TVGate Android App

把 TVGate 服务端（Go 编写）内置到 Android App 中，App 启动后在设备本地
启动转发服务，并展示局域网访问信息（IP 地址、端口、账号密码、二维码），
方便手机扫码或输入地址访问 Web 管理界面。

专为**机顶盒、电视盒子、Android TV** 设计，支持遥控器操作。

> **服务端上游项目**：[qist/tvgate](https://github.com/qist/tvgate) —— App
> 不内置二进制源码，构建时自动 clone 该仓库源码交叉编译进 APK（见
> [构建](#构建)与 [build-android.sh](build-android.sh)）。

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
- 前台服务使用 `specialUse` 类型（Android 15 起禁止从开机广播启动
  `dataSync` 类型，`specialUse` 无类型时限、允许开机自启）

### 开机自启

设备重启后自动启动 TVGate 服务，无需手动打开 App：

- 注册 `BOOT_COMPLETED` 广播接收器 `BootReceiver`
- 重启后自动以 `startForegroundService` 拉起前台服务
- 适用机顶盒/电视盒子"开机即用"场景

> **注意**：部分 ROM（如某些模拟器/盒子固件）有"自启动管理"策略，
> 默认拦截第三方应用的开机广播。若重启后未自动启动，请在系统设置中
> 允许 TVGate 自启动；被"强制停止"过的应用也需先手动打开一次。

### 局域网信息展示

启动界面一目了然地显示：

- **局域网 IP 地址和访问端口**（自动检测设备 IP）
- **登录账号 / 密码**（从 `config.yaml` 动态读取）
- **访问二维码**（手机扫码即可打开，ZXing 生成）
- 点击信息卡片可复制访问地址到剪贴板

### 直播接口自动打开

TVGate 的「直播接口」即 H5 播放器模块。当 `config.yaml` 中 `player.enabled: true` 且
`player.android_autoplay` 未设为 `false` 时，App 在服务就绪后自动打开直播页，
机顶盒开机即进入看电视状态：

- 使用 TVGate 内置独立播放页 `/pp`（`http://127.0.0.1:{port}/pp`，回环访问不受网络切换影响）
- 等播放页加载完成再做过渡动画（信息卡片淡出、播放页淡入），不露白屏
- 沉浸式全屏（隐藏状态栏/导航栏）+ 启动即自动起播，无需点击
- 支持 H5 播放器的网页全屏按钮；深色界面，浅色系统下也不会出现白色顶栏
- **返回键**退出直播页回到信息卡片，且本次会话不再自动弹出（首次进入有提示）

### 动态配置

App 从 `config.yaml` 读取以下配置并实时更新界面：

| 配置项 | 说明 | 默认值 |
|---|---|---|
| `server.port` | 服务监听端口 | `8888` |
| `web.username` | Web 管理界面账号 | `admin` |
| `web.password` | Web 管理界面密码 | `admin` |
| `web.path` | Web 管理界面路径 | `/web/` |
| `player.enabled` | 直播接口（H5 播放器），开启后启动自动打开直播页 | `false` |
| `player.android_autoplay` | 安卓启动是否进入播放页标记位；`false` 时启动停留在信息界面不打开直播页（可在 Web 后台「播放器」页开关，服务端不控制行为） | 未配置（进入） |
| `dns.servers` | DNS 服务器列表 | 不配置，默认走系统/本地 DNS |

首次启动时 `config.yaml` 可能不存在，TVGate 二进制启动后会自动生成默认配置，
App 会检测配置文件出现后自动重新读取并更新界面。

### 遥控器支持

专为机顶盒 / 电视盒子优化：

| 按键 | 功能 |
|---|---|
| **方向键** | 切换焦点（导航信息卡片、重启按钮） |
| **OK / 确认键** | 聚焦信息卡片时复制地址，聚焦重启按钮时重启内核 |
| **菜单键** | 同 OK 键 |
| **返回键** | 直播页展示时先退出直播页回信息卡片；否则退到后台运行（不退出 App） |

界面元素支持焦点高亮，遥控器选中时显示蓝色边框。

### 手动重启内核

界面上提供「重启内核」按钮，适用于以下场景：

- 修改 `config.yaml` 后需要让配置生效
- 服务端异常（如 DNS 失效、连接超时）需要快速恢复
- 网络环境变化后服务未自动恢复

点击按钮或遥控器 OK 键即可重启 TVGate 内核进程，无需关闭 App：

1. 按钮变为「正在重启内核…」并禁用
2. Service 停止当前进程并重新启动
3. 重启完成后界面自动刷新，状态提示「内核已重启」
4. App 重新轮询服务就绪状态

> 重启仅影响内核进程，前台服务和通知不受影响。

### 在线 APK 更新

App 启动时自动检查 GitHub 最新 Release 版本，与本地安装版本对比：

- 调用 `GET /repos/{owner}/{repo}/releases/latest` 获取最新发布版与 APK 资源
- **本地版本更低** → 弹出「发现新版本」对话框，用户确认后下载并交给系统安装器安装
- 按当前设备 ABI（arm64 / arm / x86_64）自动匹配对应 APK 资源
- **网络不可用时自动跳过**更新检查，不影响本地转发服务与正常使用
- 升级为覆盖安装：包名与签名一致时，已授权权限与应用数据自动保留

实现见 `AppUpdater.kt`（网络检测、拉取、版本对比、按 ABI 找资源、下载）与
`MainActivity.kt`（检查、弹窗、下载进度、FileProvider 安装）。

## 构建

本仓库包含完整构建链：`build-android.sh` 交叉编译 Go 服务端为 `.so`，
`build-android-split.sh` 分架构打包并签名 APK。

### 前置依赖

- Go 1.25+
- Android NDK（`$ANDROID_NDK_HOME` 或 `$ANDROID_HOME/ndk/*`）
- Android SDK（build-tools 含 `zipalign` / `apksigner`）
- Node.js 20+（含 npm，构建 TVGate Web 前端；`web/dist` 不进 git，缺失时
  二进制内只有占位页）
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

**体积优化**：`build-android.sh` 已叠加裁剪参数 `-trimpath`
`-gcflags=all=-l`（关闭 Go 函数内联，减少代码膨胀）`-ldflags="-s -w"`
（剥离符号与调试信息），单架构二进制可省约 1.6MB。APK 约 12MB/架构。

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
CI 会自动完成「clone 服务端源码 → 构建 Web 前端 → 交叉编译 → 分架构打包 →
签名 → 上传 artifact」。

### 需要配置的 Secrets（仓库 Settings → Secrets and variables → Actions）

| Secret | 内容 |
|---|---|
| `TVGATE_KS_BASE64` | `base64 -w0 release.keystore` 的输出（密钥文件本身不入库） |
| `TVGATE_KS_PASS` | 密钥密码 |

密钥别名 CI 默认 `tvgate`（workflow 中 `env.TVGATE_KS_ALIAS` 可改）。
构建产物以 `tvgate-apks-<版本>` **artifact** 形式存留（Actions 页面下载），
同时自动创建同名 **GitHub Release**：APK 命名 `TVGate-<版本>-<abi>.apk`
上传到对应版本 Release，App 在线更新（依赖 `releases/latest`）可检测到
新版本并在线升级。

> 若 `qist/tvgate` 为私有仓库，请把 workflow 里的 clone 地址改为带凭据的
> URL（凭据存为另一个 Secret）。

### 手动触发 / 覆盖版本号

`Actions → Build APK → Run workflow` 时可填 `version` 覆盖 APK 版本号
（默认用 tvgate 源码 `config/version`），产物以该版本号命名 artifact 与
Release 标签。

## 工作原理

1. `MainActivity` 启动前台服务 `TVGateService`。
2. `TVGateService` 通过 `BinaryInstaller` 从 `nativeLibraryDir` 取出
   `libtvgate.so` 拷贝到应用私有 `files/` 目录并 `chmod +x`。
3. 用 `Runtime.exec` 启动 `tvgate -config <filesDir>/config.yaml`。
4. `MainActivity` 轮询本地服务端口就绪后，更新界面显示局域网访问信息。
5. `ConfigParser` 从 `config.yaml` 读取端口、账号、密码、路径、直播开关、安卓自动播放标记。
6. `NetworkUtils` 检测设备局域网 IP 地址。
7. 使用 ZXing 生成访问地址二维码显示在界面上。
8. `player.enabled: true` 且 `player.android_autoplay` 未设为 `false` 时，
   服务就绪后自动经 WebView 打开 `/pp` 直播页。

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

### PHP 脚本目录（docroot）

PHP 模块默认 `docroot: www`（相对路径），以**配置文件所在目录**为基准，
即解析为 `files/` 目录下的 `www/`。App 启动时会自动确保
`files/www` 目录存在，可直接在 Web 管理界面的「代码」页上传/编辑
`.php` 脚本（支持重命名、批量替换、查找替换等）。脚本内的相对路径
文件操作（如 `file_put_contents('json/xxx.json')`）也以脚本目录为基准。

### 仓库同步（sync，v3.0.8+）

内嵌的 TVGate 服务端支持**仓库同步**：把 GitHub / GitLab 仓库内容**单向**
同步到设备本地 `docroot/tvbox`（即 `files/www/tvbox`），一处维护、多端
自动拉取，无需 git。适合同步 TVBox 订阅配置 / 直播源 / 爬虫插件等混合内容。

- **配置**：编辑 `config.yaml` 的 `sync` 段（支持多仓库），或登录 Web 管理界面
  `http://<IP>:8888/web/sync-editor` 可视化增删仓库
- **访问**：同步到 `files/www/tvbox`，通过 `http://<IP>:8888/php/tvbox/<文件>`
  直接访问（如 `0707.json`、`listx.m3u`、`jar/spider.jar`），可作为 TVBox 订阅地址
- **多仓库**：`sync` 为条目列表，每项独立同步循环、独立 manifest；条目需使用互不相同的 `local_path`
- **protect 保护清单**：设备私有文件（如 `tv.txt`）加入后**永不覆盖、永不删除**
- **整仓归档**：公开仓库走 codeload 直连，不占 GitHub API 未认证 60 次/小时限额；
  首次同步或 API 限流时自动降级整仓归档，避免大仓库逐文件拉取触发 429/403
- **令牌安全**：Web 编辑器保存后令牌以 `********` 显示、**不回显**，填新值才覆盖

```yaml
sync:
  - name: tvbox               # 标识（用于日志区分多仓库，可空）
    enabled: true             # 是否启用
    type: github              # github | gitlab | gitee
    host: ""                  # 自建实例地址（自建 GitLab https://git.内网 或 Gitee https://gitee.com），留空 = 平台默认
    repo: qist/tvbox          # 仓库 owner/repo
    branch: master            # 同步分支
    token: ""                 # PAT（GitHub: ghp_xxx；GitLab: glpat_xxx；Gitee: 私人令牌），公开仓库可留空
    interval: 60s             # 轮询间隔（最小 10s）
    repo_path: .              # 仓库内源子目录（"." = 仓库根）
    local_path: tvbox         # 本地目标：以 php docroot 为锚点；"tvbox" = docroot/tvbox
    only_php: false           # 是否只同步 PHP 文件（混合内容默认 false 全量）
    backup: true              # 覆盖/删除前备份为 .bak.<时间戳>
    delete: false             # 远端已删除的文件，本地是否也删除
    protect: []               # 本地保护清单（相对 local_path，支持目录前缀）：永不覆盖、永不删除
    timeout: 15s              # 单次 API/下载请求超时
```

> 详细设计见 [tvgate 服务端 doc/SYNC.md](https://github.com/qist/tvgate/blob/main/doc/SYNC.md)。

### DNS 配置

#### 默认行为：走系统/本地 DNS（无需注入）

App **默认不做任何 DNS 注入**，域名解析交给 tvgate 服务端的 DNS 兜底链：

- 服务端移除了 `PreferGo`，且安卓包为 **CGO 链接**（`libc.so`），系统解析会经
  libc `getaddrinfo` → Android **netd** → 设备当前活动网络的本地 DNS
  （路由器 DHCP 下发），因此公网与内网域名都能解析，m3u8 等远程资源正常返回。
- 兜底顺序统一为：**手动配置的 `dns.servers` → 系统/本地 DNS → 内置公共 DNS
  （`223.5.5.5` / `119.29.29.29`）**。配置的 DNS 只在它真正成功时生效。

> 较旧版本曾通过 App 首次启动/网络切换时把设备 DNS 自动注入 `config.yaml`
> 并重启进程来解决早期纯 Go 解析失败的问题。当前版本已**关闭该自动注入**
> （`TVGateService.kt` 中相关代码整块注释保留），不再需要——默认即走本地 DNS。
> 若后续遇到回归，可恢复注释后再评估是否彻底移除。

#### 手动配置 DNS（可选，优先级最高）

默认走系统/本地 DNS 已够用；若需要固定使用指定 DNS（如公共 DNS、自定义 DNS
或 DoH/DoT 服务器），可手动编辑 `config.yaml`，在 `dns` 段指定服务器列表。
配置后该列表**强制优先**使用，仅当其失败时才回落系统/本地 DNS：

```yaml
server:
  port: 8888

web:
  username: admin
  password: admin
  path: /web/

dns:
  timeout: 5s
  max_conns: 10
  servers:
    - 223.5.5.5       # 阿里 DNS
    - 119.29.29.29     # 腾讯 DNS
    # 也可添加自定义 DNS
    # - 8.8.8.8        # Google DNS
```

| 字段 | 说明 | 默认值 |
|---|---|---|
| `dns.timeout` | 单次 DNS 查询超时时间 | `5s` |
| `dns.max_conns` | DNS 连接池大小 | `10` |
| `dns.servers` | DNS 服务器列表（IPv4 地址） | 不配置时走系统/本地 DNS |

## 项目结构

```
app/src/main/
├── java/com/tvgate/app/
│   ├── MainActivity.kt       # 启动界面、遥控器处理、信息展示、重启内核、在线更新
│   ├── TVGateService.kt      # 前台服务，常驻运行服务端二进制、手动重启（DNS 自动注入已注释停用）
│   ├── BootReceiver.kt       # 开机自启广播接收器（BOOT_COMPLETED）
│   ├── BinaryInstaller.kt    # 从 jniLibs 提取并安装二进制
│   ├── ConfigParser.kt       # 解析 config.yaml 配置文件
│   ├── AppUpdater.kt        # 在线 APK 更新：网络检测、GitHub 版本对比、按 ABI 下载
│   └── NetworkUtils.kt      # 获取设备局域网 IP 地址、DNS 服务器
├── res/
│   ├── layout/activity_main.xml   # 启动界面布局
│   ├── drawable/                   # 图标、卡片背景、焦点样式
│   ├── values/                     # 颜色、字符串、主题
│   └── anim/                        # 启动动画
└── AndroidManifest.xml
```
