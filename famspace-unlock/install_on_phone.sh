#!/usr/bin/env bash
# Install the patched Famspace build + the on-device rewrite proxy onto a
# phone connected over adb.
#
#   ./install_on_phone.sh            # uses adb from PATH or ~/tools/platform-tools
#
# What it does:
#   1. removes the original app (its signature differs from ours)
#   2. installs all 19 Famspace APKs as one split set
#   3. installs the "Famspace Unlock" helper (the local rewrite proxy)
#   4. starts the helper so the proxy is up
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

echo "== installing the patched split set"
"$ADB" install-multiple -r "$OUT"/com.famspace.app.android.apk "$OUT"/config.*.apk

echo "== installing the local proxy helper"
"$ADB" install -r "$OUT/FamspaceUnlock.apk"

echo "== starting the helper"
"$ADB" shell am start -n com.famspace.unlock/.MainActivity >/dev/null
sleep 2

echo "== state"
"$ADB" shell pm list packages | grep -E "famspace" || true
echo
echo "Now open Famspace.  Keep 'Famspace Unlock' running (its notification"
echo "keeps the proxy alive); the app talks to 127.0.0.1:888 on the phone."
