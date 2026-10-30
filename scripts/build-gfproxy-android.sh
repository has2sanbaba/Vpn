#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="$ROOT_DIR/native/gfproxy"
JNI_DIR="$ROOT_DIR/app/src/main/jniLibs"

# Auto-detect Android NDK installation that includes API 21 Clang wrappers (NDK r25/r26/r27)
has_api21_clang() {
    local ndk="$1"
    [[ -n "$ndk" && -d "$ndk/toolchains/llvm/prebuilt" ]] || return 1
    local host_tag
    host_tag="$(ls -1 "$ndk/toolchains/llvm/prebuilt" 2>/dev/null | head -n 1 || true)"
    [[ -n "$host_tag" && -x "$ndk/toolchains/llvm/prebuilt/$host_tag/bin/aarch64-linux-android21-clang" ]]
}

find_ndk() {
    for candidate in \
        "${ANDROID_NDK_HOME:-}" \
        "${ANDROID_NDK_ROOT:-}" \
        "${NDK_HOME:-}" \
        "${ANDROID_NDK_LATEST_HOME:-}"; do
        if has_api21_clang "$candidate"; then
            echo "$candidate"
            return 0
        fi
    done

    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "/usr/local/lib/android/sdk"; do
        if [[ -n "$sdk" && -d "$sdk/ndk" ]]; then
            local candidate
            for candidate in $(ls -1d "$sdk"/ndk/* 2>/dev/null | sort -Vr); do
                if has_api21_clang "$candidate"; then
                    echo "$candidate"
                    return 0
                fi
            done
        fi
    done
    return 1
}

NDK_DIR="$(find_ndk || true)"
if [[ -z "$NDK_DIR" ]]; then
    echo "ERROR: Android NDK with API 21 clang wrappers not found. Set ANDROID_NDK_HOME." >&2
    exit 1
fi

HOST_TAG="$(ls -1 "$NDK_DIR/toolchains/llvm/prebuilt" | head -n 1)"
TOOLCHAIN_BIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG/bin"
LLVM_NM="$TOOLCHAIN_BIN/llvm-nm"
LLVM_READELF="$TOOLCHAIN_BIN/llvm-readelf"

echo "[build-gfproxy] Using Android NDK: $NDK_DIR ($HOST_TAG)"

rm -rf "$JNI_DIR"
mkdir -p "$JNI_DIR"

(
    cd "$SRC_DIR"
    GOTOOLCHAIN=auto go mod tidy
)

build_for_abi() {
    local abi="$1"
    local goarch="$2"
    local goarm="$3"
    local cc_bin="$4"

    local out_dir="$JNI_DIR/$abi"
    mkdir -p "$out_dir"
    local cc_path="$TOOLCHAIN_BIN/$cc_bin"
    local cxx_path="${cc_path}++"
    local gf_out="$out_dir/libgfproxy.so"

    echo "[build-gfproxy] Building standard Android JNI C-shared library libgfproxy.so ($abi, minSdk=21, 16KB page-aligned)..."
    (
        cd "$SRC_DIR"
        env \
            GOTOOLCHAIN=auto \
            CGO_ENABLED=1 \
            GOOS=android \
            GOARCH="$goarch" \
            GOARM="$goarm" \
            GO386=sse2 \
            CC="$cc_path" \
            CXX="$cxx_path" \
            go build \
                -buildmode=c-shared \
                -trimpath \
                -ldflags="-s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384" \
                -o "$gf_out" \
                .
    )

    # Remove generated C header so only pure .so shared libraries are packaged into APK
    rm -f "$out_dir/libgfproxy.h"
    chmod 0644 "$gf_out"

    if [[ -x "$LLVM_NM" ]]; then
        "$LLVM_NM" -D "$gf_out" | grep -Fq "Java_org_greatfire_browser_vpn_NativeProxyBridge_nativeStart" || {
            echo "ERROR: JNI symbol missing in $gf_out" >&2
            exit 1
        }
    fi
    if [[ -x "$LLVM_READELF" ]]; then
        if "$LLVM_READELF" -d "$gf_out" | grep -Fq "TEXTREL"; then
            echo "ERROR: Forbidden DT_TEXTREL found in $gf_out" >&2
            exit 1
        fi
    fi

    ls -lh "$gf_out"
}

# Build all 4 Android CPU architectures starting at Android 5.0 (API 21) -> Android 16+ (API 35+)
build_for_abi "armeabi-v7a" "arm"   "7" "armv7a-linux-androideabi21-clang"
build_for_abi "arm64-v8a"   "arm64" ""  "aarch64-linux-android21-clang"
build_for_abi "x86"         "386"   ""  "i686-linux-android21-clang"
build_for_abi "x86_64"      "amd64" ""  "x86_64-linux-android21-clang"

echo "[build-gfproxy] Successfully built standard JNI libgfproxy.so for all 4 Android ABIs!"
