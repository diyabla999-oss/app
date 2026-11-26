#!/usr/bin/env bash
# Build ONE installable universal APK from the XAPK split set.
#
#   ./build_universal.sh [xapk] [outdir]
#
# Why: an XAPK needs an XAPK installer, and all 19 splits have to be
# installed together (INSTALL_FAILED_MISSING_SPLIT).  A universal APK is a
# single file you can tap to install.
#
# What the split set actually contains (verified on the 1.0.8 build):
#
#   com.famspace.app.android.apk   base: dex, assets, res/, mipmap-{m,h,xh,xxh,
#                                 xxxh}dpi launcher icons, layouts, anims
#   config.arm64_v8a.apk          the 21 native libraries ONLY (no resources)
#   config.xxhdpi.apk             ~100 extra xxhdpi drawables (9-patches and
#                                 small vectors of AndroidX widgets)
#   config.<lang>.apk             ~163 translated strings (AndroidX widgets +
#                                 the launcher label)
#
# So the only thing the universal APK really needs from the splits is the
# native libraries.  The app is Flutter: its whole UI is drawn from
# assets/ by the Dart code, so it is localised and pixel-perfect regardless of
# which density/language configuration the APK carries.  What is lost is
# cosmetic: the launcher label stays the default one, and AndroidX assets fall
# back to the xhdpi variants the base already carries.
#
# The resource table is deliberately NOT re-linked: aapt2 would re-allocate the
# resource ids and the original binary AndroidManifest.xml (which references
# them) would point at the wrong resources.  Keeping the base's resources.arsc
# and manifest byte-identical keeps every reference valid.
#
# The dex files are never touched either, so they keep dex 037 and the
# in-place patches (in-app proxy, PairIP neutralised, certificate
# fingerprint).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
XAPK="${1:-$HERE/output/FamSpace-unlocked.xapk}"
OUT="${2:-$HERE/output}"
XAPK="$(cd "$(dirname "$XAPK")" && pwd)/$(basename "$XAPK")"

BT="${BT:-$HOME/tools/android-sdk/build-tools/34.0.0}"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"
KS="${KS:-$HOME/tools/famspace-unlock.jks}"
KS_ALIAS="${KS_ALIAS:-famspace}"
KS_PASS="${KS_PASS:-famspace}"

WORK="$(mktemp -d /tmp/opencode/uni.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"

echo "== unpacking $XAPK"
mkdir -p "$WORK/splits"
(cd "$WORK/splits" && unzip -q "$XAPK")

BASE="$WORK/splits/com.famspace.app.android.apk"
ABI="$WORK/splits/config.arm64_v8a.apk"
[ -f "$BASE" ] || { echo "base APK missing" >&2; exit 1; }
[ -f "$ABI" ] || { echo "config.arm64_v8a.apk missing" >&2; exit 1; }

echo "== copying the native libraries in (stored, uncompressed)"
# The app sets android:extractNativeLibs="false": every .so has to be stored
# uncompressed and 4096-byte aligned or the installer rejects the APK with
# INSTALL_FAILED_INVALID.
rm -rf "$WORK/libs" && mkdir -p "$WORK/libs"
(cd "$WORK/libs" && unzip -q "$ABI" 'lib/arm64-v8a/*')
LIBS=$(find "$WORK/libs" -name '*.so' | wc -l)
cp "$BASE" "$WORK/universal.apk"
# zip needs the file names, not a wildcard (it does not glob across
# directories without -r, and -r would drop the -0 store mode)
(cd "$WORK/libs" && find lib -name '*.so' -print0 | xargs -0 zip -q -X -0 "$WORK/universal.apk")
echo "   $LIBS libraries added"

echo "== dropping the old signature (only the signature files)"
# NOT "META-INF/*": that folder also holds runtime files - the AppLovin /
# Pangle / Unity adapter verification.properties, the Kotlin module
# descriptors and the licence texts.  Only the v1 signature goes.
cd "$WORK"
zip -q -d universal.apk 'META-INF/MANIFEST.MF' 'META-INF/*.SF' 'META-INF/*.RSA' \
                     'META-INF/*.DSA' 'META-INF/*.EC' 2>/dev/null || true
cd - >/dev/null

echo "== aligning (page-align the native libraries)"
"$ZIPALIGN" -p -f 4 "$WORK/universal.apk" "$WORK/aligned.apk"

echo "== signing"
"$APKSIGNER" sign \
  --ks "$KS" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$WORK/signed.apk" "$WORK/aligned.apk"

mkdir -p "$OUT"
cp "$WORK/signed.apk" "$OUT/Famspace-universal.apk"
echo "== $OUT/Famspace-universal.apk"
ls -la "$OUT/Famspace-universal.apk"
echo
echo "   verify it with: ./verify_apk.sh $OUT/Famspace-universal.apk"
