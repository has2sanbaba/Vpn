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
storePassword=${KEYSTORE_PASSWORD:-drbrowser2026}
keyAlias=${KEY_ALIAS:-drbrowser}
keyPassword=${KEY_PASSWORD:-drbrowser2026}
EOF
    echo "[ensure-keystore] Custom release keystore configured."
    exit 0
fi

if [[ ! -f "$KS_FILE" ]]; then
    echo "[ensure-keystore] Generating consistent V1+V2+V3 RSA-2048 release keystore for Dr Browser..."
    keytool -genkeypair \
        -v \
        -keystore "$KS_FILE" \
        -storetype PKCS12 \
        -alias drbrowser \
        -keyalg RSA \
        -keysize 2048 \
        -validity 18250 \
        -storepass drbrowser2026 \
        -keypass drbrowser2026 \
        -dname "CN=Dr Browser, OU=Mobile Engineering, O=has2sanbaba, L=Nuremberg, ST=Bavaria, C=DE"
fi

cat > "$PROPS_FILE" <<EOF
storeFile=$KS_FILE
storePassword=drbrowser2026
keyAlias=drbrowser
keyPassword=drbrowser2026
EOF

echo "[ensure-keystore] Release signing keystore ready at $KS_FILE"
