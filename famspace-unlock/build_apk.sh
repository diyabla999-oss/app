#!/usr/bin/env bash
# Rebuild the Famspace XAPK with the patched libapp.so and re-sign every APK.
#
#   ./build_apk.sh <original.xapk> <patched.libapp.so> <outdir> [ip] [port]
#
# The XAPK is a zip of APK splits (base + config.*).  Only the arm64 split
# carries libapp.so, but *every* split has to be re-signed with the same key,
# otherwise Android refuses to install the set.
set -euo pipefail

XAPK="${1:?usage: build_apk.sh <in.xapk> <patched libapp.so> <outdir> [ip] [port]}"
LIBAPP="${2:?missing patched libapp.so}"
OUTDIR="${3:?missing outdir}"
IP="${4:-}"
PORT="${5:-8888}"

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d /tmp/opencode/famspace-build.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUTDIR"
OUTDIR="$(cd "$OUTDIR" && pwd)"

TOOLS="${TOOLS:-$HOME/tools}"
SIGNER="${SIGNER:-$TOOLS/uber-apk-signer-1.3.0.jar}"

command -v java >/dev/null || { echo "java is required" >&2; exit 1; }
[ -f "$SIGNER" ] || { echo "missing apksigner bundle: $SIGNER" >&2; exit 1; }

echo "== unpacking $XAPK"
mkdir -p "$WORK/apks"
cd "$WORK/apks"
unzip -q "$XAPK"

echo "== injecting patched libapp.so"
SPLIT="$(ls config.arm64_v8a.apk)"
rm -rf "$WORK/split"
mkdir -p "$WORK/split"
cd "$WORK/split"
unzip -q "$WORK/apks/$SPLIT" "lib/arm64-v8a/libapp.so"
cp "$LIBAPP" "lib/arm64-v8a/libapp.so"
# -0 = STORE, no compression.  The app sets android:extractNativeLibs="false",
# so every native library has to stay uncompressed (and page aligned) or the
# package installer rejects the APK with INSTALL_FAILED_INVALID.
zip -q -X -0 "$WORK/apks/$SPLIT" "lib/arm64-v8a/libapp.so"
echo "   patched $SPLIT (libapp.so stored uncompressed)"

echo "== re-signing all APKs"
SIGNED="$WORK/signed"
mkdir -p "$SIGNED"
KS="${KS:-$TOOLS/famspace-unlock.jks}"
if [ ! -f "$KS" ]; then
  echo "   creating signing keystore $KS"
  keytool -genkeypair -keystore "$KS" -alias famspace -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass famspace -keypass famspace \
    -dname "CN=Famspace Unlock, OU=Local, O=Local, C=US" >/dev/null 2>&1
fi
cd "$WORK/apks"
java -jar "$SIGNER" --allowResign \
  --ks "$KS" --ksAlias famspace --ksPass famspace --ksKeyPass famspace \
  -a "$WORK/apks" -o "$SIGNED" >/dev/null
for apk in "$SIGNED"/*.apk; do echo "   signed $(basename "$apk")"; done

echo "== writing $OUTDIR"
mkdir -p "$OUTDIR"
# The xapk manifest references the *original* file names, so the signed APKs
# are copied back under those names inside the xapk.
XPACK="$WORK/xapk"
mkdir -p "$XPACK"
cd "$WORK/apks"
for apk in *.apk; do
  signed="$SIGNED/${apk%.apk}-aligned-signed.apk"
  [ -f "$signed" ] || { echo "missing signed $apk" >&2; exit 1; }
  cp "$signed" "$XPACK/$apk"
done
cp manifest.json "$XPACK"/ 2>/dev/null || true
cp icon.png "$XPACK"/ 2>/dev/null || true

# total_size has to describe THIS archive, not the one the xapk came from
python3 - "$XPACK" <<'EOF'
import json, os, sys
root = sys.argv[1]
mp = os.path.join(root, "manifest.json")
if os.path.exists(mp):
    m = json.load(open(mp))
    total = sum(os.path.getsize(os.path.join(root, f))
                for f in os.listdir(root) if f.endswith(".apk"))
    if m.get("total_size") != total:
        print("   manifest total_size %s -> %d" % (m.get("total_size"), total))
        m["total_size"] = total
        json.dump(m, open(mp, "w"), separators=(",", ":"))
EOF

XAPK_OUT="$OUTDIR/$(basename "${XAPK%.xapk}")-unlocked.xapk"
rm -f "$XAPK_OUT"
cd "$XPACK"
zip -q -X "$XAPK_OUT" manifest.json icon.png *.apk
cp "$XPACK"/*.apk "$OUTDIR"/ 2>/dev/null || true
cp manifest.json icon.png "$OUTDIR"/ 2>/dev/null || true
echo "== $XAPK_OUT"
ls -la "$OUTDIR" | head -8

# remember the address the APK was patched with, so the proxy can be started
# with the matching --public value.
cat > "$OUTDIR/build.env" <<EOF
IP=$IP
PORT=$PORT
PUBLIC_URL=http://$IP:$PORT
EOF
echo "== proxy start command:"
echo "   python3 $(dirname "$0")/proxy.py --port $PORT --public http://$IP:$PORT -v"
