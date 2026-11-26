#!/usr/bin/env bash
# Build the self-contained variant: the rewrite proxy lives INSIDE the
# Famspace APK - no second app to install, no service, no notification.
#
#   ./build_inapp.sh <original.xapk> <patched libapp.so> <outdir> [ip] [port]
#
# 1. the proxy classes (LocalProxy / Rules / ProxyHolder / ProxyProvider) are
#    compiled to a dex and disassembled to smali
# 2. they are added to classes2.dex of the base APK, next to PairIP
# 3. the app's Application.attachBaseContext() is rewritten so that instead of
#    running the Play licence check it starts the proxy - the framework calls
#    attachBaseContext() before the Flutter engine starts, so the proxy is
#    listening on 127.0.0.1:888 before the first API request
# 4. the rebuilt classes2.dex is put back into the untouched base APK
# 5. the host strings in libapp.so point at the proxy
#
# Only classes2.dex changes; manifest, resources and the other five dex
# files stay byte-identical.
set -euo pipefail

XAPK="${1:?usage: build_inapp.sh <in.xapk> <patched libapp.so> <outdir> [ip] [port]}"
LIBAPP="${2:?missing patched libapp.so}"
OUT="${3:?missing outdir}"
IP="${4:-127.0.0.1}"
PORT="${5:-888}"

HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK:-$HOME/tools/android-sdk}"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/android-35/android.jar"
APKTOOL="${APKTOOL:-/tmp/opencode/apktool.jar}"
WORK="$(mktemp -d /tmp/opencode/inapp.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

echo "== compiling the proxy classes"
mkdir -p "$WORK/classes" "$WORK/dex"
javac -nowarn -source 8 -target 8 -classpath "$AJ" -d "$WORK/classes" \
  "$HERE"/helper/src/com/famspace/unlock/LocalProxy.java \
  "$HERE"/helper/src/com/famspace/unlock/Rules.java \
  "$HERE"/helper/src/com/famspace/unlock/ProxyHolder.java \
  "$HERE"/helper/src/com/famspace/unlock/ProxyProvider.java 2>&1 | grep -v "bootstrap class path" || true
"$BT/d8" --lib "$AJ" --min-api 24 --output "$WORK/dex" \
  "$WORK"/classes/com/famspace/unlock/*.class >/dev/null
echo "   proxy dex: $(stat -c%s "$WORK/dex/classes.dex") bytes"

echo "== disassembling it to smali"
mkdir -p "$WORK/tools"
javac -nowarn -cp "$APKTOOL" -d "$WORK/tools" "$HERE/tools/Disasm.java"
java -cp "$APKTOOL:$WORK/tools" Disasm "$WORK/dex/classes.dex" "$WORK/proxysmali"

echo "== unpacking $XAPK"
mkdir -p "$WORK/apks"
(cd "$WORK/apks" && unzip -q "$XAPK")

echo "== decoding the base APK (dex only)"
java -jar "$APKTOOL" d -f -r -o "$WORK/dec" "$WORK/apks/com.famspace.app.android.apk" >/dev/null 2>&1

echo "== adding the proxy classes to classes2.dex"
mkdir -p "$WORK/dec/smali_classes2/com/famspace/unlock"
cp "$WORK/proxysmali/com/famspace/unlock/"*.smali "$WORK/dec/smali_classes2/com/famspace/unlock/"
echo "   $(ls "$WORK/dec/smali_classes2/com/famspace/unlock/" | wc -l) smali files"

echo "== patching the PairIP licence check + starting the proxy + fingerprint"
FINGERPRINT="${FINGERPRINT:-100EA61D15387F4BFD7A3BE741043921C4E19FC88D5D40BB6A3F8BC167DBB12A}"
python3 "$HERE/tools/smali_patch.py" "$WORK/dec" "$FINGERPRINT"

echo "== rebuilding classes2.dex"
java -jar "$APKTOOL" b -f "$WORK/dec" -o "$WORK/rebuilt.apk" >/dev/null 2>&1
mkdir -p "$WORK/out"
(cd "$WORK/out" && unzip -q -o "$WORK/rebuilt.apk" classes2.dex classes5.dex)
python3 - "$WORK/out/classes2.dex" "$WORK/out/classes5.dex" <<'EOF'
import struct, sys, hashlib, zlib
for p in sys.argv[1:]:
    d = bytearray(open(p, "rb").read())
    name = p.rsplit("/", 1)[-1]
    print("   rebuilt %s: %s, %d bytes" % (name, bytes(d[:8]).decode("latin1").replace("\n", " ").replace("\x00", "").strip(), len(d)))
    # keep the original dex version (037) - smali emits 035
    d[4:8] = b"037\x00"
    d[12:32] = hashlib.sha1(bytes(d[32:])).digest()
    struct.pack_into("<I", d, 8, zlib.adler32(bytes(d[12:])) & 0xFFFFFFFF)
    open(p, "wb").write(bytes(d))
    print("      version set to dex 037, checksum + signature refreshed")
EOF

echo "== swapping the rebuilt dex files into the untouched base APK"
cp "$WORK/apks/com.famspace.app.android.apk" "$WORK/base.apk"
(cd "$WORK/out" && zip -q -X "$WORK/base.apk" classes2.dex classes5.dex)
cp "$WORK/base.apk" "$WORK/apks/com.famspace.app.android.apk"

echo "== injecting the patched libapp.so (stored, uncompressed)"
SPLIT="config.arm64_v8a.apk"
rm -rf "$WORK/split"; mkdir -p "$WORK/split"
(cd "$WORK/split" && unzip -q "$WORK/apks/$SPLIT" "lib/arm64-v8a/libapp.so")
cp "$LIBAPP" "$WORK/split/lib/arm64-v8a/libapp.so"
(cd "$WORK/split" && zip -q -X -0 "$WORK/apks/$SPLIT" "lib/arm64-v8a/libapp.so")

echo "== repacking the unsigned xapk"
(cd "$WORK/apks" && zip -q -X "$OUT/FamSpace-inapp-unsigned.xapk" manifest.json icon.png *.apk)
echo "   $OUT/FamSpace-inapp-unsigned.xapk"
echo "   now run: ./build_apk.sh $OUT/FamSpace-inapp-unsigned.xapk $LIBAPP $OUT $IP $PORT"
