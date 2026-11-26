#!/usr/bin/env bash
# Desktop test for the on-device proxy (LocalProxy + Rules).
#
#   ./test_local_proxy.sh
#
# Starts a fake upstream on 127.0.0.1:9999, runs LocalProxy against it and
# checks that the answers come back rewritten.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d /tmp/opencode/proxytest.XXXXXX)"
trap 'rm -rf "$WORK"; pkill -f "FakeUpstream" 2>/dev/null || true' EXIT

JSON_JAR="${JSON_JAR:-$HOME/tools/json-20231013.jar}"
if [ ! -f "$JSON_JAR" ]; then
  mkdir -p "$HOME/tools"
  curl -sL -o "$JSON_JAR" https://repo1.maven.org/maven2/org/json/json/20231013/json-20231013.jar
fi

cat > "$WORK/FakeUpstream.java" <<'EOF'
import com.sun.net.httpserver.*;
import java.io.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class FakeUpstream {
    public static void main(String[] a) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 9999), 0);
        s.createContext("/", new HttpHandler() {
            public void handle(HttpExchange e) throws IOException {
                String body = "{\"code\":0,\"data\":{\"gems\":12,\"coins\":3,\"vipLv\":0,"
                        + "\"vipEnd\":\"2026-01-01T00:00:00Z\",\"canView\":false,"
                        + "\"canDownload\":false,\"isLocked\":true,\"unlockType\":\"coins\","
                        + "\"alreadyUnlocked\":false,\"dailyLimit\":3,\"expireDay\":1,"
                        + "\"premiumPurchased\":false,\"baseUrl\":\"https://famspace.disoms.net\","
                        + "\"playUrl\":\"https://x/y.m3u8\",\"price\":150}}";
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().add("Content-Type", "application/json");
                e.sendResponseHeaders(200, b.length);
                e.getResponseBody().write(b);
                e.close();
            }
        });
        s.start();
        Thread.sleep(60000);
    }
}
EOF

cat > "$WORK/RunProxy.java" <<'EOF'
import com.famspace.unlock.LocalProxy;

public class RunProxy {
    public static void main(String[] a) throws Exception {
        LocalProxy p = new LocalProxy(new String[]{"http://127.0.0.1:9999"}, 8888, "http://127.0.0.1:8888");
        p.start();
        Thread.sleep(60000);
    }
}
EOF

echo "== compile"
javac -nowarn -d "$WORK/classes" -cp "$JSON_JAR" \
  "$HERE/helper/src/com/famspace/unlock/LocalProxy.java" \
  "$HERE/helper/src/com/famspace/unlock/Rules.java" \
  "$WORK/FakeUpstream.java" "$WORK/RunProxy.java"

echo "== run"
java -cp "$WORK/classes:$JSON_JAR" FakeUpstream &
sleep 1
java -cp "$WORK/classes:$JSON_JAR" RunProxy &
sleep 2

echo "== request"
BODY="$(curl -s -m 10 http://127.0.0.1:8888/api/v1/me/profile)"
echo "$BODY"

python3 - "$BODY" <<'EOF'
import json, sys
d = json.loads(sys.argv[1])["data"]
checks = {
    "gems": 999999999, "coins": 999999999, "vipLv": 99, "canView": True,
    "canDownload": True, "isLocked": False, "unlockType": "", "alreadyUnlocked": True,
    "dailyLimit": 100000, "expireDay": 36500, "premiumPurchased": True,
    "baseUrl": "http://127.0.0.1:8888", "playUrl": "https://x/y.m3u8", "price": 150,
}
bad = {k: (d.get(k), v) for k, v in checks.items() if d.get(k) != v}
if bad:
    print("FAIL", bad)
    sys.exit(1)
print("PASS - all", len(checks), "fields rewritten as expected")
EOF

echo "== padded path"
curl -s -m 10 http://127.0.0.1:8888/xxx/api/v1/me/profile | python3 -c "import sys,json; d=json.load(sys.stdin)['data']; print('gems',d['gems'],'vipLv',d['vipLv'],'baseUrl',d['baseUrl'])"
echo "OK"
