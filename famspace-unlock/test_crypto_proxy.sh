#!/usr/bin/env bash
# The round trip of the API crypto, against the answers that were captured
# from the real server:
#
#   encrypted answer -> decrypt -> rewrite the wallet -> encrypt -> decrypt
#
# This is the test that proves the wallet rewrite actually reaches the app:
# the second decryption has to show coins=999999999.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d /tmp/opencode/pipe.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

echo "== compile"
javac -nowarn -d "$WORK/classes" \
  "$HERE/helper/src/com/famspace/unlock/ApiCrypto.java" \
  "$HERE/helper/src/com/famspace/unlock/Json.java" \
  "$HERE/helper/src/com/famspace/unlock/Rules.java" \
  "$HERE/helper/test/TestPipe.java" 2>&1 | grep -v "bootstrap class path" || true

echo "== run"
java -cp "$WORK/classes" TestPipe
