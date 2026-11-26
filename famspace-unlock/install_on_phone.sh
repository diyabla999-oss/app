#!/usr/bin/env bash
# Install the patched Famspace build + the on-device rewrite proxy onto a
# phone connected over adb.
#
#   ./install_on_phone.sh            # uses adb from PATH or ~/tools/platform-tools
#
# What it does:
#   1. removes the original app (its signature differs from ours)
#   2. removes the "Famspace Unlock" helper if it is installed - the current
#      build carries the proxy inside the app, and the helper would fight it
#      for 127.0.0.1:8888
#   3. installs all 19 Famspace APKs as one split set
#
# Pass --with-helper to keep/install the standalone helper instead (only
# useful for the older build where the proxy lived in the helper app).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/output"
ADB="${ADB:-}"
if [ -z "$ADB" ]; then
  if command -v adb >/dev/null; then ADB=adb; else ADB="$HOME/tools/platform-tools/adb"; fi
fi
[ -x "$ADB" ] || command -v "$ADB" >/dev/null || { echo "adb not found ($ADB)" >&2; exit 1; }

"$ADB" devices | tail -n +2 | grep -q "device$" || {
  echo "no device attached - plug the phone in with USB debugging on" >&2
  "$ADB" devices -l; exit 1; }

echo "== uninstalling the original (different signature)"
"$ADB" uninstall com.famspace.app.android || true

WITH_HELPER=0
for arg in "$@"; do [ "$arg" = "--with-helper" ] && WITH_HELPER=1; done

if [ "$WITH_HELPER" = "1" ]; then
  echo "== installing the local proxy helper"
  "$ADB" install -r "$OUT/FamspaceUnlock.apk"
  echo "== starting the helper"
  "$ADB" shell am start -n com.famspace.unlock/.MainActivity >/dev/null
  sleep 2
else
  echo "== removing the helper (the proxy now lives inside the app)"
  "$ADB" uninstall com.famspace.unlock >/dev/null 2>&1 && echo "   removed" || echo "   not installed"
fi

echo "== installing the patched split set"
"$ADB" install-multiple -r "$OUT"/com.famspace.app.android.apk "$OUT"/config.*.apk

echo "== state"
"$ADB" shell pm list packages | grep -E "famspace" || true
echo
echo "Now open Famspace.  The proxy starts inside the app on 127.0.0.1:8888."
echo "If the install fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE, the Play"
echo "version is still installed - uninstall it and run this script again."
