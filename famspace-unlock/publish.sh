#!/usr/bin/env bash
# Publish the verified build to the phone-facing download folder.
#
#   ./publish.sh [outdir] [destdir]
#
# Why this exists: the build was fixed twice for install failures (compressed
# native library -> INSTALL_FAILED_INVALID, dex 035 -> rejected).  The old,
# broken XAPK was still sitting in the download folder and got installed on the
# phone, which is why the fix "did not work".  This script copies the current
# build over the download folder and re-verifies the published file, so the
# thing the phone downloads is always the thing that was checked.
#
# It refuses to publish if verify_build.sh fails.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-$HERE/output}"
DEST="${2:-/workspaces/app/pp/download}"

[ -f "$OUT/FamSpace-unlocked.xapk" ] || { echo "no build in $OUT" >&2; exit 1; }

echo "== verifying the build before publishing"
"$HERE/verify_build.sh" "$OUT/FamSpace-unlocked.xapk" | tail -3

echo "== publishing $OUT -> $DEST"
mkdir -p "$DEST"
rm -f "$DEST"/*.apk "$DEST"/*.xapk "$DEST"/manifest.json "$DEST"/icon.png
cp "$OUT"/*.apk "$DEST"/
cp "$OUT"/FamSpace-unlocked.xapk "$DEST"/
cp "$OUT"/manifest.json "$OUT"/icon.png "$DEST"/ 2>/dev/null || true
cp "$HERE/README.md" "$HERE/ANALYSIS.md" "$DEST"/

echo "== verifying the published copy"
"$HERE/verify_build.sh" "$DEST/FamSpace-unlocked.xapk" | tail -3

cd "$DEST"
echo "== published:"
ls -la *.xapk *.apk | awk '{printf "   %-32s %10.1f MB\n", $9, $5/1048576}'
md5sum *.xapk
echo
echo "   do not install the old copy in pp/ - it is the pre-fix build"
