#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [ ! -d "$SDK" ]; then
    # Fallback to local user tools path if set
    SDK="$HOME/Documents/moova/.tools/android-sdk"
fi

if [ ! -d "$SDK" ]; then
    echo "[-] No Android SDK found. Set ANDROID_HOME first."
    exit 1
fi
if ! command -v javac >/dev/null 2>&1; then
    echo "[-] javac not found. Install a JDK (17 recommended) first."
    exit 1
fi
JAVAC_VER=$(javac -version 2>&1 | grep -oE '[0-9]+' | head -n 1)
if [ "$JAVAC_VER" -gt 17 ] 2>/dev/null; then
    echo "[!] javac $JAVAC_VER > 17: class-file drift may break d8. Prefer JDK 17."
fi

BUILD_TOOLS="$SDK/build-tools/35.0.0"
if [ ! -x "$BUILD_TOOLS/aapt2" ]; then
    # Fall back to newest installed build-tools
    BUILD_TOOLS=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -n 1)
fi
PLATFORM="$SDK/platforms/android-36/android.jar"
if [ ! -f "$PLATFORM" ]; then
    # Pick any available android platform jar
    PLATFORM=$(find "$SDK/platforms" -name "android.jar" | sort -V | tail -n 1)
fi

# Auto-generate throwaway debug keystore on fresh clones (it's gitignored)
if [ ! -f debug.keystore ]; then
    echo "[*] Generating fresh debug.keystore..."
    keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10950 \
        -dname "CN=Blue Debug" 2>/dev/null || { echo "[-] keytool failed"; exit 1; }
fi

echo "[*] Using Android SDK: $SDK"
echo "[*] Build-tools: $BUILD_TOOLS"
echo "[*] Platform JAR: $PLATFORM"

# Clean build artifacts
rm -rf obj classes.dex base.apk aligned.apk compiled_res.zip src/com/bluebridge/app/R.java
mkdir -p obj

# Compile resources
"$BUILD_TOOLS/aapt2" compile --dir res -o compiled_res.zip
"$BUILD_TOOLS/aapt2" link -I "$PLATFORM" --manifest AndroidManifest.xml --java src compiled_res.zip -o base.apk

# Compile Java source
javac -cp "$PLATFORM" -d obj src/com/bluebridge/app/*.java

# Dex
"$BUILD_TOOLS/d8" --lib "$PLATFORM" --output . obj/com/bluebridge/app/*.class

# Package & align
zip -u base.apk classes.dex
"$BUILD_TOOLS/zipalign" -f 4 base.apk aligned.apk

# Sign
OUT_APK="${1:-$DIR/blue.apk}"
"$BUILD_TOOLS/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out "$OUT_APK" aligned.apk

echo "[✓] Built and signed APK: $OUT_APK"
