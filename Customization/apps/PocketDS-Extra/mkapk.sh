#!/bin/bash
# Builds PocketDSParts.apk (PocketDS Extra) in seconds, without a Soong run:
# javac + aapt2 + d8 + zipalign + apksigner, signed with the AOSP test platform key.
# Needs the ROM tree built once (out/soong jars and framework-res.apk).
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../../../src"
cd "$SRC"
export JAVA_HOME=$PWD/prebuilts/jdk/jdk21/linux-x86 PATH=$PWD/prebuilts/jdk/jdk21/linux-x86/bin:$PWD/out/host/linux-x86/bin:$PATH
W="$HERE/build"; rm -rf "$W"; mkdir -p "$W/cls"
P=device/ayaneo/pocket_ds/parts
CP="$(cat "$HERE/tools/classpath.txt")"
javac -nowarn -source 17 -target 17 -d "$W/cls" -cp "$CP" $P/src/org/lineageos/pocketds/parts/*.java "$HERE/tools/stub/android/provider/MediaStore.java" 2>&1 | grep -v "^Note\|warning" || true
rm -rf "$W/cls/android"
FW=out/soong/.intermediates/frameworks/base/core/res/framework-res/android_common/framework-res.apk
aapt2 link -o "$W/base.apk" --manifest $P/AndroidManifest.xml -A $P/assets -I $FW --min-sdk-version 37 --target-sdk-version 37 --version-code 37 --version-name 17
LIBS=(); for j in $(echo "$CP" | tr ':' '\n' | grep -v '^$'); do LIBS+=(--lib "$j"); done
d8 --release --min-api 36 "${LIBS[@]}" --output "$W" $(find "$W/cls" -name '*.class') 2>&1 | grep -v -i '^warning\|^  ' || true
cp "$W/base.apk" "$W/unsigned.apk"; (cd "$W" && zip -q -j unsigned.apk classes.dex)
zipalign -f -p 4 "$W/unsigned.apk" "$W/aligned.apk"
K=build/make/target/product/security
apksigner sign --key $K/platform.pk8 --cert $K/platform.x509.pem --out "$HERE/PocketDSParts.apk" "$W/aligned.apk"
apksigner verify --print-certs "$HERE/PocketDSParts.apk" | head -2
ls -la "$HERE/PocketDSParts.apk"
