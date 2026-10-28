#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="$ROOT_DIR/native/gfproxy"
JNI_DIR="$ROOT_DIR/app/src/main/jniLibs"
CACHE_SRC_DIR="${RUNNER_TEMP:-/tmp}/vpn-native-sources"
mkdir -p "$CACHE_SRC_DIR"

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

echo "[build-native] Using Android NDK: $NDK_DIR ($HOST_TAG)"

# Clone pinned upstream anti-censorship engines if not already cached
WARP_PLUS_DIR="$CACHE_SRC_DIR/warp-plus-v1.2.6"
if [[ ! -f "$WARP_PLUS_DIR/go.mod" ]]; then
    echo "[build-native] Fetching bepass-org/warp-plus v1.2.6..."
    rm -rf "$WARP_PLUS_DIR"
    git clone --depth 1 --branch v1.2.6 https://github.com/bepass-org/warp-plus.git "$WARP_PLUS_DIR"
    sed -i.bak '/^toolchain /d' "$WARP_PLUS_DIR/go.mod" && rm -f "$WARP_PLUS_DIR/go.mod.bak"
fi

SING_BOX_DIR="$CACHE_SRC_DIR/sing-box-v1.14.4"
if [[ ! -f "$SING_BOX_DIR/go.mod" ]]; then
    echo "[build-native] Fetching SagerNet/sing-box v1.14.4..."
    rm -rf "$SING_BOX_DIR"
    git clone --depth 1 --branch v1.14.4 https://github.com/SagerNet/sing-box.git "$SING_BOX_DIR"
fi

SING_TAGS="with_quic,with_wireguard,with_utls,badlinkname,tfogo_checklinkname0"

build_all_for_abi() {
    local abi="$1"
    local goarch="$2"
    local goarm="$3"
    local cc_bin="$4"

    local out_dir="$JNI_DIR/$abi"
    mkdir -p "$out_dir"
    local cc_path="$TOOLCHAIN_BIN/$cc_bin"
    local cxx_path="${cc_path}++"

    echo "================================================================="
    echo "[build-native] Target ABI: $abi (GOARCH=$goarch, minSdk=21, 16KB page-aligned)"
    echo "================================================================="

    # 1. Local Anti-DPI & Unified HTTP/SOCKS5/Kill-Switch Gateway: libgfproxy.so
    local gf_out="$out_dir/libgfproxy.so"
    echo "[build-native] [1/3] Building libgfproxy.so ($abi)..."
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
                -buildmode=pie \
                -trimpath \
                -ldflags="-s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384" \
                -o "$gf_out" \
                .
    )
    chmod 0755 "$gf_out"

    # 2. Zero-Config Clean-IP WARP / WARP-in-WARP (Gool) / Psiphon-over-WARP (CFON): libwarpplus.so
    # Uses Go 1.24 toolchain so Psiphon-Labs/quic-go + -checklinkname=0 compile cleanly across all 4 ABIs.
    local wp_out="$out_dir/libwarpplus.so"
    echo "[build-native] [2/3] Building libwarpplus.so ($abi)..."
    (
        cd "$WARP_PLUS_DIR"
        env \
            GOTOOLCHAIN=go1.24.4 \
            CGO_ENABLED=1 \
            GOOS=android \
            GOARCH="$goarch" \
            GOARM="$goarm" \
            GO386=sse2 \
            CC="$cc_path" \
            CXX="$cxx_path" \
            go build \
                -buildmode=pie \
                -trimpath \
                -ldflags="-s -w -buildid= -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384" \
                -o "$wp_out" \
                ./cmd/warp-plus
    )
    chmod 0755 "$wp_out"

    # 3. Multi-Protocol Anti-Censorship Core (VLESS Reality/WS/gRPC, VMess, Trojan, Hysteria2, TUIC, SS): libsingbox.so
    local sb_out="$out_dir/libsingbox.so"
    echo "[build-native] [3/3] Building libsingbox.so ($abi)..."
    (
        cd "$SING_BOX_DIR"
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
                -buildmode=pie \
                -trimpath \
                -tags "$SING_TAGS" \
                -ldflags="-X github.com/sagernet/sing-box/constant.Version=1.14.4 -s -w -buildid= -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384" \
                -o "$sb_out" \
                ./cmd/sing-box
    )
    chmod 0755 "$sb_out"

    ls -lh "$gf_out" "$wp_out" "$sb_out"
}

# Build all 4 Android CPU architectures starting at Android 5.0 (API 21)!
build_all_for_abi "armeabi-v7a" "arm"   "7" "armv7a-linux-androideabi21-clang"
build_all_for_abi "arm64-v8a"   "arm64" ""  "aarch64-linux-android21-clang"
build_all_for_abi "x86"         "386"   ""  "i686-linux-android21-clang"
build_all_for_abi "x86_64"      "amd64" ""  "x86_64-linux-android21-clang"

echo "[build-native] Successfully built libgfproxy.so, libwarpplus.so, and libsingbox.so for all 4 Android ABIs!"
