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
#
# The check lives in classes2.dex.  The dex is patched *in place* (only the
# first instruction of four methods is overwritten with "return-void"), so the
# dex keeps its version (037), its size and every internal offset - only the
# adler32 checksum and the sha1 signature are refreshed:
#
#   LicenseClient.checkLicense(Context)          -> return immediately
#   LicenseClient.scheduleAppShutdown()          -> return immediately
#   LicenseResponseHelper.validateResponse(...)  -> return immediately
#   LicenseActivity.onStart()                    -> return immediately
#
# Everything else in the APK (resources, manifest, the other five dex files,
# the native libs) stays byte-identical.
set -euo pipefail

XAPK="${1:?usage: patch_license.sh <in.xapk> <outdir>}"
OUT="${2:?missing outdir}"
HERE="$(cd "$(dirname "$0")" && pwd)"

WORK="$(mktemp -d /tmp/opencode/license.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

echo "== unpacking $XAPK"
mkdir -p "$WORK/apks"
(cd "$WORK/apks" && unzip -q "$XAPK")

echo "== extracting classes2.dex from the base APK"
mkdir -p "$WORK/dex"
(cd "$WORK/dex" && unzip -q -o "$WORK/apks/com.famspace.app.android.apk" classes2.dex)

echo "== patching the dex in place"
python3 "$HERE/patch_dex.py" "$WORK/dex/classes2.dex" "$WORK/dex/classes2.patched.dex"

echo "== swapping the patched dex back into the untouched base APK"
cp "$WORK/apks/com.famspace.app.android.apk" "$WORK/base_patched.apk"
(cd "$WORK/dex" && cp classes2.patched.dex classes2.dex && zip -q -X "$WORK/base_patched.apk" classes2.dex)
cp "$WORK/base_patched.apk" "$WORK/apks/com.famspace.app.android.apk"

echo "== repacking the (still unsigned) xapk"
(cd "$WORK/apks" && zip -q -X "$OUT/FamSpace-patched-unsigned.xapk" manifest.json icon.png *.apk)
echo "   $OUT/FamSpace-patched-unsigned.xapk"
echo "   now run: ./build_apk.sh $OUT/FamSpace-patched-unsigned.xapk <libapp.so> $OUT <ip> <port>"
