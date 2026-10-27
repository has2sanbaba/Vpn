#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KS_DIR="$ROOT_DIR/keystore"
KS_FILE="$KS_DIR/release.jks"
PROPS_FILE="$KS_DIR/keystore.properties"

mkdir -p "$KS_DIR"

if [[ -n "${KEYSTORE_BASE64:-}" ]]; then
    echo "[ensure-keystore] Decoding release keystore from KEYSTORE_BASE64 secret..."
    echo "$KEYSTORE_BASE64" | tr -d ' \n\r' | base64 -d > "$KS_FILE"
    cat > "$PROPS_FILE" <<EOF
storeFile=$KS_FILE
storePassword=${KEYSTORE_PASSWORD:-greatfire2026}
keyAlias=${KEY_ALIAS:-greatfire}
keyPassword=${KEY_PASSWORD:-greatfire2026}
EOF
    echo "[ensure-keystore] Custom release keystore configured."
    exit 0
fi

if [[ ! -f "$KS_FILE" ]]; then
    echo "[ensure-keystore] Generating default V1+V2+V3 compatible RSA-2048 signing keystore..."
    keytool -genkeypair \
        -v \
        -keystore "$KS_FILE" \
        -storetype PKCS12 \
        -alias greatfire \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -storepass greatfire2026 \
        -keypass greatfire2026 \
        -dname "CN=GreatFire Envoy Browser, OU=AntiCensorship, O=GreatFire, L=Global, S=Global, C=US"
fi

cat > "$PROPS_FILE" <<EOF
storeFile=$KS_FILE
storePassword=greatfire2026
keyAlias=greatfire
keyPassword=greatfire2026
EOF

echo "[ensure-keystore] Release signing keystore ready at $KS_FILE"
