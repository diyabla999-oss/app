#!/usr/bin/env bash
# End-to-end test of the encrypted-payload path:
#
#   config/app hands out the AES key in the clear
#   me/profile answers with an ENCRYPTED payload
#   -> the proxy learns the key, decrypts, rewrites the wallet and encrypts
#      it again, so the client sees gems=999999999
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d /tmp/opencode/cryptotest.XXXXXX)"
trap 'rm -rf "$WORK"; pkill -f "[F]akeApi" 2>/dev/null || true; pkill -f "[R]unProxy2" 2>/dev/null || true' EXIT

JSON_JAR="${JSON_JAR:-$HOME/tools/json-20231013.jar}"

cat > "$WORK/RunProxy2.java" <<'EOF'
import com.famspace.unlock.LocalProxy;

public class RunProxy2 {
    public static void main(String[] a) throws Exception {
        LocalProxy p = new LocalProxy(new String[]{"http://127.0.0.1:9998"}, 8899, "http://127.0.0.1:8899");
        p.start();
        Thread.sleep(120000);
    }
}
EOF

echo "== compile"
javac -nowarn -d "$WORK/classes" -cp "$JSON_JAR" \
  "$HERE/helper/src/com/famspace/unlock/LocalProxy.java" \
  "$HERE/helper/src/com/famspace/unlock/Rules.java" \
  "$HERE/helper/src/com/famspace/unlock/Crypto.java" \
  /tmp/opencode/FakeApi.java "$WORK/RunProxy2.java" /tmp/opencode/Verify.java 2>&1 | grep -v "deprecat" || true

echo "== run"
java -cp "$WORK/classes:$JSON_JAR" FakeApi >/dev/null 2>&1 &
sleep 1
java -cp "$WORK/classes:$JSON_JAR" RunProxy2 >/dev/null 2>&1 &
sleep 2

echo "== 1) the config answer hands out the key"
curl -s -m 10 http://127.0.0.1:8899/api/v1/config/app | head -c 200; echo

echo "== 2) the profile answer is encrypted - the proxy must rewrite it"
OUT="$(curl -s -m 10 http://127.0.0.1:8899/api/v1/me/profile)"
echo "   $OUT" | head -c 200; echo

printf '%s' "$OUT" > "$WORK/answer.json"
java -cp "$WORK/classes" Verify "$WORK/answer.json" > "$WORK/plain.json"
cat "$WORK/plain.json"; echo
python3 - "$WORK/plain.json" <<'EOF'
import json, sys
plain = json.load(open(sys.argv[1]))
d = plain["data"]
checks = {"gems": 999999999, "coins": 999999999, "vipLv": 99, "canView": True,
          "email": "a@b.c"}
bad = {k: (d.get(k), v) for k, v in checks.items() if d.get(k) != v}
if bad:
    print("FAIL", bad)
    sys.exit(1)
print("PASS - the encrypted payload was decrypted, the wallet rewritten and re-encrypted")
EOF

echo "== 3) the proxy log"
curl -s -m 10 http://127.0.0.1:8899/__famunlock/log | grep -E "learned|decrypted|re-encrypted|rewrote" | head -10
echo "OK"
