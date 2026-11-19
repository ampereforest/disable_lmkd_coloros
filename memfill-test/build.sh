#!/usr/bin/env bash
set -euo pipefail
# Reproducible CLI build script for MemFillTest (arm64-v8a Android RAM allocation benchmark app)
# Required environment variables (or defaults):
#   ANDROID_SDK_ROOT  - Android SDK path containing platforms/android-35 and build-tools/35.0.0+
#   ANDROID_NDK_ROOT  - Android NDK path containing toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang
#   KEYSTORE          - Optional PKCS12/JKS keystore for apksigner (generates a throwaway debug keystore if omitted)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/build"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/lib/arm64-v8a"

SDK="${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}"
NDK="${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT}"
BT="$(ls -d "$SDK/build-tools"/* | sort -V | tail -n 1)"
ANDROID_JAR="$(ls -d "$SDK/platforms"/android-* | sort -V | tail -n 1)/android.jar"
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"

"$CLANG" -shared -fPIC -O2 -Wall -Wl,-z,max-page-size=16384 \
    "$SCRIPT_DIR/jni/memfill_jni.c" -llog -o "$BUILD_DIR/lib/arm64-v8a/libmemfill.so"

javac --release 11 -cp "$ANDROID_JAR" -d "$BUILD_DIR/classes" \
    "$SCRIPT_DIR/src/com/dada/memfill/MainActivity.java"

"$BT/d8" --min-api 29 --lib "$ANDROID_JAR" --output "$BUILD_DIR/dex" \
    "$BUILD_DIR/classes/com/dada/memfill/"*.class

"$BT/aapt2" link -o "$BUILD_DIR/base.apk" -I "$ANDROID_JAR" --manifest "$SCRIPT_DIR/AndroidManifest.xml"
cp "$BUILD_DIR/base.apk" "$BUILD_DIR/unaligned.apk"
(cd "$BUILD_DIR/dex" && zip -q -0 "$BUILD_DIR/unaligned.apk" classes.dex)
(cd "$BUILD_DIR" && zip -q -0 "$BUILD_DIR/unaligned.apk" lib/arm64-v8a/libmemfill.so)

"$BT/zipalign" -P 16 -f 4 "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk"

if [ -z "${KEYSTORE:-}" ]; then
    KEYSTORE="$BUILD_DIR/debug.jks"
    keytool -genkeypair -v -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=MemFillTest"
    "$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
        --out "$BUILD_DIR/MemFillTest.apk" "$BUILD_DIR/aligned.apk"
else
    "$BT/apksigner" sign --ks "$KEYSTORE" --out "$BUILD_DIR/MemFillTest.apk" "$BUILD_DIR/aligned.apk"
fi
echo "Built $BUILD_DIR/MemFillTest.apk"
