#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIBS_DIR="$ROOT_DIR/app/libs"
mkdir -p "$LIBS_DIR"

CRONET_URL="https://github.com/greatfire/envoy/releases/download/108.0.5359.195/cronet-108.0.5359.195.aar"
CRONET_DEST="$LIBS_DIR/cronet-108.0.5359.195.aar"

IEP_URL="https://github.com/greatfire/IEnvoyProxy/releases/download/e3.5.0/IEnvoyProxy.aar"
IEP_DEST="$LIBS_DIR/IEnvoyProxy.aar"

download_aar() {
    local url="$1"
    local dest="$2"
    local min_bytes="$3"

    if [[ -f "$dest" ]]; then
        local sz
        sz=$(wc -c < "$dest" | tr -d ' ')
        if [[ "$sz" -ge "$min_bytes" ]] && unzip -tq "$dest" >/dev/null 2>&1; then
            echo "[fetch-greatfire-libs] Cached valid $(basename "$dest") ($sz bytes)"
            return 0
        fi
        echo "[fetch-greatfire-libs] Existing $(basename "$dest") invalid/incomplete, re-downloading..."
        rm -f "$dest"
    fi

    echo "[fetch-greatfire-libs] Downloading $(basename "$dest") from $url ..."
    curl -fL --retry 5 --retry-delay 3 --connect-timeout 30 -o "${dest}.tmp" "$url"
    unzip -tq "${dest}.tmp" >/dev/null
    mv "${dest}.tmp" "$dest"
    echo "[fetch-greatfire-libs] Downloaded and verified $(basename "$dest")"
}

download_aar "$CRONET_URL" "$CRONET_DEST" 10000000
download_aar "$IEP_URL" "$IEP_DEST" 30000000

echo "[fetch-greatfire-libs] All GreatFire Android libraries ready in $LIBS_DIR"
