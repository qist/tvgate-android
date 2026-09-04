# 更新说明（2026-09-04）

本次更新：直播接口启动自动打开 + CI 构建产物改为 artifact + 构建脚本补齐 Web 前端构建。

## 一、直播接口启动自动打开

TVGate 的「直播接口」即 H5 播放器模块（`config.yaml` 中 `player.enabled: true`）。

- **[ConfigParser.kt](app/src/main/java/com/tvgate/app/ConfigParser.kt)**：新增解析 `player.enabled` 字段；新增 `buildPlayerUrl()` 生成播放器独立入口 `http://127.0.0.1:{port}/pp`（回环地址，不受网络切换影响；`/pp` 为 TVGate 内置独立播放页，不暴露隐藏的 web.path）。
- **[MainActivity.kt](app/src/main/java/com/tvgate/app/MainActivity.kt)**：服务就绪后读取配置，直播开启即自动打开直播页。
  - 过渡动画：等播放页加载完成再揭示（信息卡片淡出、播放页微缩放淡入），避免露白屏
  - 沉浸式全屏：隐藏状态栏/导航栏，WebView 黑底
  - 自动起播：`mediaPlaybackRequiresUserGesture = false`
  - 支持 H5 播放器的网页全屏按钮（HTML5 fullscreen 回调）
  - 返回键：先退出直播页回到信息卡片，本次会话不再自动弹出；首次进入有 Toast 提示
- **[strings.xml](app/src/main/res/values/strings.xml)**：新增提示文案。

## 二、消除手机/电视上的白色元素

- **[themes.xml](app/src/main/res/values/themes.xml)**：`windowBackground` / 状态栏 / 导航栏统一为深色 `#0D1117`，消除浅色模式下的白条与启动白闪。
- **[MainActivity.kt](app/src/main/java/com/tvgate/app/MainActivity.kt)**：
  - WebView 底色固定黑色，且每次导航时重新压黑（部分机型会重置底色）
  - 通过 WebView document-start 脚本预置播放器深色主题（`tvgate-player-theme = dark`，
    注意 H5 侧为裸字符串比较，不能带 JSON 引号）；仅当用户未手动选过主题时生效
  - 进入沉浸式前把系统栏压黑兜底

## 三、CI：不再创建 Release，仅上传 artifact

- **[build.yml](.github/workflows/build.yml)**：
  - 移除 `softprops/action-gh-release` 步骤，产物以 workflow artifact 供下载
  - 权限降为 `contents: read`（artifact 不需要写权限）
  - 新增 `actions/setup-node@v7`（Node 20，与上游 tvgate release.yml 一致），用于构建 TVGate Web 前端
  - ⚠️ 注意：无 Release 后，App 在线更新（依赖 `releases/latest`）将检测不到新版本，检查会静默跳过

## 四、构建脚本补齐 Web 前端构建

- **[build-android.sh](build-android.sh)**：编译 Go 之前自动构建 TVGate Web 前端。
  - 原因：`web/dist` 不进 git（`.gitignore` 只保留 `.gitkeep`），`go:embed all:dist` 嵌入的是
    磁盘产物；跳过此步二进制里只有占位页（管理后台与 H5 直播播放器均不可用）
  - 增量构建：仅当 `ui/` 源码比 `web/dist/.built` 标记新或产物缺失时才执行
    `npm install` + `npm run build`（vite 直接输出到 `web/dist`）
  - npm 缺失时直接报错退出（静默占位页不可接受）

## 五、本地构建环境要求

- Go 1.25+ / Android SDK + NDK / **Node.js 20+（含 npm）**
- 首次构建会执行一次 `npm install`（约 1-2 分钟），之后有缓存增量跳过
