#!/usr/bin/env bash
# Build the on-device helper app (the rewrite proxy that runs inside the phone).
#
#   ./build_helper.sh
#
# Needs: ~/tools/android-sdk/{android-35/android.jar, build-tools/34.0.0}
# Output: output/FamspaceUnlock.apk
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK:-$HOME/tools/android-sdk}"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/android-35/android.jar"
OUT="$HERE/output"
WORK="$(mktemp -d /tmp/opencode/helper.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

for f in "$AJ" "$BT/aapt2" "$BT/d8" "$BT/apksigner" "$BT/zipalign"; do
  [ -e "$f" ] || { echo "missing $f" >&2; exit 1; }
done

mkdir -p "$WORK/classes" "$OUT"

echo "== javac"
javac -nowarn -source 8 -target 8 -classpath "$AJ" -d "$WORK/classes" \
  "$HERE"/helper/src/com/famspace/unlock/*.java 2>&1 | grep -v "bootstrap class path"
[ -n "$(find "$WORK/classes" -name '*.class')" ] || { echo "javac produced no classes" >&2; exit 1; }

echo "== d8"
mkdir -p "$WORK/dex"
"$BT/d8" --lib "$AJ" --min-api 24 --output "$WORK/dex" \
  $(find "$WORK/classes" -name '*.class') >/dev/null

echo "== aapt2 link"
"$BT/aapt2" link -o "$WORK/helper.apk" \
  --manifest "$HERE/helper/AndroidManifest.xml" \
  -I "$AJ" \
  --min-sdk-version 24 --target-sdk-version 35 \
  --java "$WORK/gen" 2>&1 | tail -3

echo "== adding dex"
(cd "$WORK/dex" && zip -q -X "$WORK/helper.apk" classes.dex)

echo "== zipalign + sign"
KS="${KS:-$HOME/tools/famspace-unlock.jks}"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias famspace -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass famspace -keypass famspace \
    -dname "CN=Famspace Unlock, OU=Local, O=Local, C=US" >/dev/null 2>&1
fi
"$BT/zipalign" -p -f 4 "$WORK/helper.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-key-alias famspace --ks-pass pass:famspace \
  --key-pass pass:famspace --v1-signing-enabled true --v2-signing-enabled true \
  --v3-signing-enabled true --out "$OUT/FamspaceUnlock.apk" "$WORK/aligned.apk"

"$BT/apksigner" verify --print-certs "$OUT/FamspaceUnlock.apk" | head -6
ls -la "$OUT/FamspaceUnlock.apk"
