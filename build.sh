#!/bin/bash
# Builds build/MiniWin.apk using only the Android SDK (no Gradle).
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
TOOLS="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"

rm -rf build
mkdir -p build/classes build/gen build/dex

"$TOOLS/aapt2" compile --dir res -o build/res.zip
"$TOOLS/aapt2" link -o build/base.apk -I "$ANDROID_JAR" --manifest AndroidManifest.xml \
    --java build/gen build/res.zip

javac -nowarn -Xlint:none --release 8 -classpath "$ANDROID_JAR" -d build/classes \
    $(find src build/gen -name '*.java')
"$TOOLS/d8" --lib "$ANDROID_JAR" --min-api 26 --output build/dex $(find build/classes -name '*.class')

cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -j ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 build/unsigned.apk build/aligned.apk

if [ ! -f miniwin.keystore ]; then
    keytool -genkeypair -keystore miniwin.keystore -storepass miniwin -keypass miniwin -alias miniwin \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=MiniWin" 2>/dev/null
fi
"$TOOLS/apksigner" sign --ks miniwin.keystore --ks-pass pass:miniwin --key-pass pass:miniwin \
    --out build/MiniWin.apk build/aligned.apk

echo "Built $(pwd)/build/MiniWin.apk"
