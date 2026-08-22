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
#   TVGate-v1.0.0-arm64.apk
#   TVGate-v1.0.0-arm.apk
#   TVGate-v1.0.0-x86_64.apk
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

ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
export ANDROID_HOME
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort | tail -1)"

JNILIBS="$ROOT/app/src/main/jniLibs"
APK_OUT="$ROOT/app/build/outputs/apk/release"
KS="${TVGATE_KS_FILE:-$ROOT/release.keystore}"
KS_ALIAS="${TVGATE_KS_ALIAS:-tvgate}"
KS_PASS="${TVGATE_KS_PASS:?请设置环境变量 TVGATE_KS_PASS（密钥密码），或写入 .env}"
[ -f "$KS" ] || { echo "ERROR: 找不到签名密钥 $KS（请放到仓库根，或设置 TVGATE_KS_FILE）" >&2; exit 1; }

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
  local final="$ROOT/TVGate-v1.0.0-$short.apk"

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

echo "Done. 独立 APK 已生成："
ls -la "$ROOT"/TVGate-v1.0.0-*.apk
