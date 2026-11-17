#!/usr/bin/env bash
#
# 分架构构建 TVGate APK：arm64-v8a / armeabi-v7a / x86_64 各自产出独立 APK，
# 不把多架构二进制打进同一个包。
#
# 实现方式：
#   TVGate 二进制作为 JNI 库 libtvgate.so 放在 jniLibs/<abi>/，Gradle 按
#   abiFilters 自动只打包对应架构的 .so 进 lib/<abi>/，运行时由 BinaryInstaller
#   从 nativeLibraryDir 直接 exec（Android 15 兼容）。二进制在 APK 内仅此一份，
#   不再打包 assets，避免体积翻倍。
#
# 签名：密钥文件不入库，密码/别名从环境变量或 .env 读取，本地与 GitHub Actions 统一：
#   TVGATE_KS_FILE   密钥文件路径（默认 $ROOT/release.keystore）
#   TVGATE_KS_ALIAS  密钥别名（默认 tvgate）
#   TVGATE_KS_PASS   密钥密码（必填）
# 本地可写一个 .env（git 忽略）免去每次 export；CI 由 GitHub Secrets 注入。
#
# 产物（release.keystore 签名，可直接安装）：
#   TVGate-<version>-arm64.apk
#   TVGate-<version>-arm.apk
#   TVGate-<version>-x86_64.apk
# 版本号从 TVGate 源码的 config/version 文件读取，与 tvgate 仓库 tag 一致。
#
# 依赖：Go 1.25+ / Android NDK / Android SDK（build-tools 含 zipalign / apksigner）
#       / TVGate 服务端源码（见 build-android.sh）
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

# ---- 加载本地 .env（可选） ----
if [ -f "$ROOT/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT/.env"
  set +a
  echo "Loaded $ROOT/.env"
fi

# 确保 TVGATE_SRC 能传递给 build-android.sh
export TVGATE_SRC="${TVGATE_SRC:-$(cd "$ROOT/../tvgate" 2>/dev/null && pwd)}"

# ---- 读取 TVGate 版本号（与 tvgate 仓库 tag 一致） ----
# 若外部已通过环境变量提供 TVGATE_VERSION（例如手动触发 CI 覆盖版本做更新测试），
# 则以环境变量为准；否则从 tvgate 源码 config/version 读取。
if [ -n "${TVGATE_VERSION:-}" ]; then
  echo "Override TVGate version: $TVGATE_VERSION"
else
  VERSION_FILE="$TVGATE_SRC/config/version"
  if [ ! -f "$VERSION_FILE" ]; then
    echo "ERROR: 找不到版本文件 $VERSION_FILE" >&2
    exit 1
  fi
  TVGATE_VERSION="$(tr -d '[:space:]' < "$VERSION_FILE")"
  echo "TVGate version: $TVGATE_VERSION"
fi
export TVGATE_VERSION

ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
export ANDROID_HOME
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort | tail -1)"

JNILIBS="$ROOT/app/src/main/jniLibs"
APK_OUT="$ROOT/app/build/outputs/apk/release"
KS="${TVGATE_KS_FILE:-$ROOT/release.keystore}"
KS_ALIAS="${TVGATE_KS_ALIAS:-tvgate}"
KS_PASS="${TVGATE_KS_PASS:?请设置环境变量 TVGATE_KS_PASS（密钥密码），或写入 .env}"

# ---- 若密钥文件不存在，则尝试从环境变量生成 ----
# CI 不入库密钥文件，可由 GitHub Secrets 注入以下任一变量：
#   TVGATE_KS_BASE64  keystore 的 base64 内容（推荐）
#   TVGATE_KS_B64     同上（备选命名）
# 本地亦可：echo "TVGATE_KS_BASE64=$(base64 -w0 release.keystore)" >> .env
if [ ! -f "$KS" ]; then
  if [ -n "${TVGATE_KS_BASE64:-}" ]; then
    echo "release.keystore 不存在，从环境变量 TVGATE_KS_BASE64 生成…"
    printf '%s' "$TVGATE_KS_BASE64" | base64 -d > "$KS"
  elif [ -n "${TVGATE_KS_B64:-}" ]; then
    echo "release.keystore 不存在，从环境变量 TVGATE_KS_B64 生成…"
    printf '%s' "$TVGATE_KS_B64" | base64 -d > "$KS"
  else
    echo "ERROR: 找不到签名密钥 $KS，且未提供 TVGATE_KS_BASE64 / TVGATE_KS_B64（请放置密钥文件或设置环境变量）" >&2
    exit 1
  fi
  chmod 600 "$KS"
fi
[ -f "$KS" ] || { echo "ERROR: 找不到签名密钥 $KS" >&2; exit 1; }

ABIS=("arm64-v8a" "armeabi-v7a" "x86_64")

# 清理旧 assets（二进制不再打包进 assets，避免体积翻倍与冗余）
rm -rf "$ROOT/app/src/main/assets" 2>/dev/null || true

# 先把所有架构二进制都编译好（避免每次 assemble 前重复编译）
echo "===== step 1/3: build all binaries ====="
./build-android.sh all

build_apk() {
  local abi="$1"
  local short
  case "$abi" in
    arm64-v8a) short=arm64 ;;
    armeabi-v7a) short=arm ;;
    x86_64) short=x86_64 ;;
  esac

  echo
  echo "===== building APK for $abi ====="

  # 清空 jniLibs 目录，仅保留当前 abi 的二进制（二进制只打包进 lib/<abi>/）
  rm -rf "$JNILIBS"/* 2>/dev/null || true
  mkdir -p "$JNILIBS/$abi"
  cp "$ROOT/build-artifacts/$abi/libtvgate.so" "$JNILIBS/$abi/libtvgate.so"

  # 重新打包（Gradle 按 abiFilters 只把对应 .so 打进 lib/）
  ./gradlew assembleRelease --no-daemon

  local unsigned="$APK_OUT/app-release-unsigned.apk"
  local aligned="/tmp/tvgate-$short-aligned.apk"
  local final="$ROOT/TVGate-${TVGATE_VERSION}-$short.apk"

  rm -f "$aligned" "$final"
  zipalign -p 4 "$unsigned" "$aligned"
  apksigner sign --ks "$KS" --ks-key-alias "$KS_ALIAS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --out "$final" "$aligned"
  apksigner verify "$final"
  echo "  -> $final ($(du -h "$final" | cut -f1))"
}

# 把脚本编译出的二进制集中到 build-artifacts，方便分发
mkdir -p "$ROOT/build-artifacts"
for abi in "${ABIS[@]}"; do
  mkdir -p "$ROOT/build-artifacts/$abi"
  cp "$JNILIBS/$abi/libtvgate.so" "$ROOT/build-artifacts/$abi/libtvgate.so" 2>/dev/null || true
done

echo
echo "===== step 2/3: build split APKs ====="
for abi in "${ABIS[@]}"; do
  build_apk "$abi"
done

# 还原 jniLibs 为完整三架构（方便后续 assemble 仍为 universal，可选）
echo
echo "===== step 3/3: restore full libs ====="
rm -rf "$JNILIBS"/* 2>/dev/null || true
for abi in "${ABIS[@]}"; do
  mkdir -p "$JNILIBS/$abi"
  cp "$ROOT/build-artifacts/$abi/libtvgate.so" "$JNILIBS/$abi/libtvgate.so"
done

echo "Done. 独立 APK 已生成（版本 $TVGATE_VERSION）："
ls -la "$ROOT"/TVGate-${TVGATE_VERSION}-*.apk
