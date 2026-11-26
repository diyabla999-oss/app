#!/usr/bin/env bash
# Patch out PairIP's Play licence check and rebuild the app.
#
#   ./patch_license.sh <original.xapk> <outdir>
#
# PairIP (com.pairip.licensecheck) is the app's Application class:
#
#   <application android:name="com.pairip.application.Application">
#       -> attachBaseContext() -> LicenseClient.checkLicense(context)
#
# A build that did not come from Google Play fails that check, and PairIP then
# shows its error dialog / opens the Play Store paywall and kills the app.
# This script neutralises it by editing the smali of classes2.dex only:
#
#   LicenseClient.checkLicense(Context)          -> return immediately
#   LicenseClient.scheduleAppShutdown()          -> return immediately
#   LicenseResponseHelper.validateResponse(...)  -> return immediately
#   LicenseActivity.onStart()                    -> finish() immediately
#
# Everything else in the APK (resources, manifest, the other five dex files,
# the native libs) stays byte-identical.
set -euo pipefail

XAPK="${1:?usage: patch_license.sh <in.xapk> <outdir>}"
OUT="${2:?missing outdir}"
HERE="$(cd "$(dirname "$0")" && pwd)"

APKTOOL="${APKTOOL:-/tmp/opencode/apktool.jar}"
if [ ! -f "$APKTOOL" ]; then
  echo "apktool jar not found at $APKTOOL" >&2
  echo "download: https://github.com/iBotPeaches/Apktool/releases/download/v2.9.3/apktool_2.9.3.jar" >&2
  exit 1
fi

WORK="$(mktemp -d /tmp/opencode/license.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

echo "== unpacking $XAPK"
mkdir -p "$WORK/apks"
(cd "$WORK/apks" && unzip -q "$XAPK")

echo "== decoding the base APK (dex only)"
java -jar "$APKTOOL" d -f -r -o "$WORK/dec" "$WORK/apks/com.famspace.app.android.apk" >/dev/null

echo "== patching PairIP"
python3 "$HERE/patch_pairip.py" "$WORK/dec"

echo "== reassembling"
java -jar "$APKTOOL" b -f "$WORK/dec" -o "$WORK/rebuilt.apk" >/dev/null

echo "== swapping the patched classes2.dex into the untouched base APK"
mkdir -p "$WORK/dex"
(cd "$WORK/dex" && unzip -q -o "$WORK/rebuilt.apk" classes2.dex)
cp "$WORK/apks/com.famspace.app.android.apk" "$WORK/base_patched.apk"
(cd "$WORK/dex" && zip -q -X "$WORK/base_patched.apk" classes2.dex)
cp "$WORK/base_patched.apk" "$WORK/apks/com.famspace.app.android.apk"

echo "== repacking the (still unsigned) xapk"
(cd "$WORK/apks" && zip -q -X "$OUT/FamSpace-patched-unsigned.xapk" manifest.json icon.png *.apk)
echo "   $OUT/FamSpace-patched-unsigned.xapk"
echo "   now run: ./build_apk.sh $OUT/FamSpace-patched-unsigned.xapk <libapp.so> $OUT <ip> <port>"
