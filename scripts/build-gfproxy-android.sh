#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="$ROOT_DIR/native/gfproxy"
JNI_DIR="$ROOT_DIR/app/src/main/jniLibs"

# Auto-detect Android NDK installation that includes API 21 Clang wrappers
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
    echo "ERROR: Android NDK not found. Set ANDROID_NDK_HOME or install NDK via sdkmanager." >&2
    exit 1
fi

HOST_TAG="$(ls -1 "$NDK_DIR/toolchains/llvm/prebuilt" | head -n 1)"
TOOLCHAIN_BIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG/bin"

echo "[build-gfproxy] Using NDK: $NDK_DIR ($HOST_TAG)"

build_abi() {
    local abi="$1"
    local goarch="$2"
    local goarm="$3"
    local cc_bin="$4"

    local out_dir="$JNI_DIR/$abi"
    mkdir -p "$out_dir"
    local out_file="$out_dir/libgfproxy.so"

    echo "[build-gfproxy] Building $abi (GOARCH=$goarch, API=21, 16KB page-aligned PIE)..."
    (
        cd "$SRC_DIR"
        env \
            CGO_ENABLED=1 \
            GOOS=android \
            GOARCH="$goarch" \
            GOARM="$goarm" \
            GO386=sse2 \
            CC="$TOOLCHAIN_BIN/$cc_bin" \
            go build \
                -buildmode=pie \
                -trimpath \
                -ldflags="-s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384" \
                -o "$out_file" \
                .
    )
    chmod 0755 "$out_file"
    ls -lh "$out_file"
}

# Build all 4 Android CPU architectures starting at Android 5.0 (API 21)!
build_abi "armeabi-v7a" "arm"   "7" "armv7a-linux-androideabi21-clang"
build_abi "arm64-v8a"   "arm64" ""  "aarch64-linux-android21-clang"
build_abi "x86"         "386"   ""  "i686-linux-android21-clang"
build_abi "x86_64"      "amd64" ""  "x86_64-linux-android21-clang"

echo "[build-gfproxy] Successfully built libgfproxy.so for all 4 Android ABIs!"
