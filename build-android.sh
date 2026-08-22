#!/usr/bin/env bash
#
# 交叉编译 TVGate 服务端（Go）为 Android .so，放入 app/src/main/jniLibs/<abi>/。
#
# 说明：TVGate 通过 //go:embed 已把 web 前端(templates/static)、config.yaml、
# favicon、version 等全部编译进二进制，因此单个可执行文件即包含整个服务端，
# APK 只需内置该二进制即可在手机本地完整运行（含 Web 管理界面）。
#
# 支持架构：
#   - arm64-v8a   (64 位 ARM，主流设备)
#   - armeabi-v7a (32 位 ARM，老设备)
#   - x86_64      (64 位 Intel/AMD，模拟器 / 部分 Chromebox)
# armv7 / x86_64 需要 quic-go 的 cgo，因此用 Android NDK 的 clang 作为 C 交叉编译器。
#
# 用法：
#   ./build-android.sh [abi]
#   abi 可选: arm64-v8a(默认) armeabi-v7a x86_64 all
#
# 依赖：
#   - Go 1.25+
#   - Android NDK（$ANDROID_NDK_HOME 或 $ANDROID_HOME/ndk/*）
#   - TVGate 服务端源码目录（$TVGATE_SRC，默认 ../tvgate 即主仓库）
#     源码可单独 clone：git clone https://github.com/qist/tvgate.git
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TVGATE_SRC="${TVGATE_SRC:-$(cd "$ROOT/../tvgate" 2>/dev/null && pwd)}"
JNILIBS="$ROOT/app/src/main/jniLibs"

# ---- 校验 TVGate 源码 ----
if [ ! -f "$TVGATE_SRC/go.mod" ]; then
  echo "ERROR: 找不到 TVGate 源码（$TVGATE_SRC）" >&2
  echo "       请先 clone 一份：git clone https://github.com/qist/tvgate.git" >&2
  echo "       或设置 TVGATE_SRC 指向 tvgate 源码目录" >&2
  exit 1
fi

# ---- 定位 NDK ----
if [ -n "${ANDROID_NDK_HOME:-}" ]; then
  NDK="$ANDROID_NDK_HOME"
elif [ -n "${ANDROID_HOME:-}" ] && compgen -G "$ANDROID_HOME/ndk/*" >/dev/null; then
  NDK="$(ls -d "$ANDROID_HOME"/ndk/* | sort | tail -1)"
else
  echo "ERROR: 找不到 Android NDK，请设置 ANDROID_NDK_HOME 或 ANDROID_HOME" >&2
  exit 1
fi
echo "Using NDK: $NDK"
CLANG_DIR="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

ABI="${1:-all}"

build_one() {
  local abi="$1"
  local goarch goarm cc
  case "$abi" in
    arm64-v8a)
      goarch=arm64; goarm=; cc=aarch64-linux-android21-clang ;;
    armeabi-v7a)
      goarch=arm; goarm=7; cc=armv7a-linux-androideabi21-clang ;;
    x86_64)
      goarch=amd64; goarm=; cc=x86_64-linux-android21-clang ;;
    *)
      echo "unknown abi: $abi" >&2; exit 1 ;;
  esac

  local out="$JNILIBS/$abi/libtvgate.so"
  mkdir -p "$(dirname "$out")"

  echo "==> building TVGate for $abi (GOARCH=$goarch GOARM=${goarm:-none} CC=$cc) from $TVGATE_SRC"
  local envs=(GOOS=android GOARCH="$goarch" CGO_ENABLED=1 CC="$CLANG_DIR/$cc")
  if [ -n "$goarm" ]; then envs+=(GOARM="$goarm"); fi

  ( cd "$TVGATE_SRC" && env "${envs[@]}" \
      go build -trimpath -ldflags="-s -w" -o "$out" . )
  echo "    -> $out ($(du -h "$out" | cut -f1))"
}

if [ "$ABI" = "all" ]; then
  build_one arm64-v8a
  build_one armeabi-v7a
  build_one x86_64
else
  build_one "$ABI"
fi

echo "Done. 可用 ./build-android-split.sh 构建分架构 APK，或用 Android Studio 打开本目录构建。"
