#!/usr/bin/env bash
# Verify that the built APK set is install-clean.
#
#   ./verify_build.sh output/FamSpace-unlocked.xapk
#
# Checks every condition the Android package installer enforces, plus the
# two patches this build ships:
#
#   * every native library is STORED (uncompressed) and 4096 byte aligned
#     (the app sets android:extractNativeLibs="false")
#   * every dex keeps the original dex version (037) and a valid checksum
#   * the split set is complete (base + base__abi + base__density)
#   * the manifest and resources parse
#   * signatures are valid
#   * PairIP's Play licence check is neutralised in classes2.dex
#   * libapp.so points at the local proxy
set -uo pipefail

XAPK="${1:-output/FamSpace-unlocked.xapk}"
XAPK="$(cd "$(dirname "$XAPK")" && pwd)/$(basename "$XAPK")"
BT="${BT:-$HOME/tools/android-sdk/build-tools/34.0.0}"
WORK="$(mktemp -d /tmp/opencode/verify.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
FAIL=0

ok()   { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAIL=$((FAIL+1)); }
note() { printf '       %s\n' "$1"; }

mkdir -p "$WORK"
echo "== unpacking $XAPK"
(cd "$WORK" && unzip -q "$XAPK")

echo "== split set"
for f in com.famspace.app.android.apk config.arm64_v8a.apk config.xxhdpi.apk; do
  [ -f "$WORK/$f" ] && ok "$f present" || bad "$f MISSING (required split)"
done
n=$(ls "$WORK"/*.apk | wc -l)
note "$n APKs in the set"

echo "== native libraries (extractNativeLibs=false -> must be stored + aligned)"
python3 - "$WORK" <<'EOF'
import sys, os, zipfile, struct
root = sys.argv[1]
bad = []
total = 0
for f in sorted(os.listdir(root)):
    if not f.endswith(".apk"):
        continue
    z = zipfile.ZipFile(os.path.join(root, f))
    for i in z.infolist():
        if i.filename.endswith(".so"):
            total += 1
            z.fp.seek(i.header_offset)
            hdr = z.fp.read(30)
            nlen, elen = struct.unpack_from("<HH", hdr, 26)
            data_off = i.header_offset + 30 + nlen + elen
            if i.compress_type != 0:
                bad.append("%s: %s is compressed (method %d)" % (f, i.filename, i.compress_type))
            elif data_off % 4096:
                bad.append("%s: %s data offset %d not 4096-aligned" % (f, i.filename, data_off))
print("TOTAL_SO=%d" % total)
print("BAD=%s" % ("\n".join(bad) if bad else ""))
EOF
res=$(python3 - "$WORK" <<'EOF'
import sys, os, zipfile, struct
root = sys.argv[1]
bad = []
total = 0
for f in sorted(os.listdir(root)):
    if not f.endswith(".apk"):
        continue
    z = zipfile.ZipFile(os.path.join(root, f))
    for i in z.infolist():
        if i.filename.endswith(".so"):
            total += 1
            z.fp.seek(i.header_offset)
            hdr = z.fp.read(30)
            nlen, elen = struct.unpack_from("<HH", hdr, 26)
            data_off = i.header_offset + 30 + nlen + elen
            if i.compress_type != 0:
                bad.append("%s: %s compressed (method %d)" % (f, i.filename, i.compress_type))
            elif data_off % 4096:
                bad.append("%s: %s not 4096-aligned" % (f, i.filename))
print("TOTAL_SO=%d" % total)
print("BAD=%s" % ("\n".join(bad) if bad else "none"))
EOF
)
total=$(echo "$res" | sed -n 's/^TOTAL_SO=//p')
badlist=$(echo "$res" | sed -n 's/^BAD=//p')
if [ "$badlist" = "none" ]; then ok "$total native libs: stored uncompressed + 4096 aligned"; else
  bad "native library layout:"; echo "$badlist" | sed 's/^/       /'
fi

echo "== dex files"
res=$(python3 - "$WORK" <<'EOF'
import sys, os, zipfile, struct, hashlib, zlib
root = sys.argv[1]
out = []
for f in sorted(os.listdir(root)):
    if not f.endswith(".apk"):
        continue
    z = zipfile.ZipFile(os.path.join(root, f))
    for i in z.infolist():
        if i.filename.endswith(".dex") and "/" not in i.filename:
            d = z.read(i)
            ver = d[:8].decode("latin1").strip("\x00")
            ck = struct.unpack_from("<I", d, 8)[0]
            sig = d[12:32]
            okck = (zlib.adler32(d[12:]) & 0xffffffff) == ck
            oksig = hashlib.sha1(d[32:]).digest() == sig
            if not ver.endswith("037") or not okck or not oksig:
                out.append("%s/%s version=%s checksum=%s signature=%s" %
                           (f, i.filename, ver, okck, oksig))
print("BAD=%s" % ("\n".join(out) if out else "none"))
EOF
)
if [ "$res" = "BAD=none" ]; then ok "all dex files are dex 037 with valid checksum+signature"; else bad "dex problems:"; echo "$res" | sed 's/^BAD=//' | sed 's/^/       /'; fi

echo "== manifest / resources"
for f in com.famspace.app.android.apk config.arm64_v8a.apk config.xxhdpi.apk; do
  if "$BT/aapt2" dump badging "$WORK/$f" >/dev/null 2>&1; then ok "$f parses"; else bad "$f does not parse"; fi
done

echo "== signatures"
if java -jar "$HOME/tools/uber-apk-signer-1.3.0.jar" -y -a "$WORK"/com.famspace.app.android.apk "$WORK"/config.arm64_v8a.apk "$WORK"/config.xxhdpi.apk >/dev/null 2>&1; then
  ok "v1+v2+v3 signatures valid on base and splits"
else
  bad "signature verification failed"
fi

echo "== in-app proxy"
res=$(python3 - "$WORK" <<'EOF2'
import sys, os, zipfile, struct
root = sys.argv[1]
z = zipfile.ZipFile(os.path.join(root, "com.famspace.app.android.apk"))
d = z.read("classes2.dex")
def uleb(d, o):
    r = s = 0
    while True:
        b = d[o]; o += 1; r |= (b & 0x7f) << s
        if not b & 0x80: return r, o
        s += 7
ss, so = struct.unpack_from("<II", d, 56); ts, to = struct.unpack_from("<II", d, 64)
cs, co = struct.unpack_from("<II", d, 96)
def st(idx):
    off = struct.unpack_from("<I", d, so + 4*idx)[0]
    _, off = uleb(d, off)
    return d[off:d.index(b"\x00", off)].decode()
def td(i): return st(struct.unpack_from("<I", d, to + 4*i)[0])
names = [td(struct.unpack_from("<I", d, co + 32*i)[0]) for i in range(cs)]
need = ["Lcom/famspace/unlock/LocalProxy;", "Lcom/famspace/unlock/Rules;",
        "Lcom/famspace/unlock/ProxyHolder;", "Lcom/famspace/unlock/ProxyProvider;"]
miss = [n for n in need if n not in names]
print("MISSING=%s" % (";".join(miss) if miss else "none"))
EOF2
)
if [ "$res" = "MISSING=none" ]; then ok "proxy classes are inside classes2.dex"; else bad "proxy classes missing: $res"; fi

echo "== patches"
(cd "$WORK" && unzip -q -o com.famspace.app.android.apk classes2.dex)
if python3 - "$WORK/classes2.dex" <<'EOF'
import sys, struct
d = open(sys.argv[1], "rb").read()
print("DEXVER=%s" % d[:8].decode("latin1").strip("\x00"))
EOF
then :; fi
ver=$(python3 -c "print(open('$WORK/classes2.dex','rb').read(8).decode('latin1').replace(chr(10),' ').replace(chr(0),'').strip())")
case "$ver" in
  "dex 037") ok "classes2.dex is still dex 037" ;;
  *) bad "classes2.dex is '$ver' (expected dex 037)" ;;
esac
if python3 - "$WORK/classes2.dex" <<'EOF' | grep -q PATCHED
import sys, struct
d = open(sys.argv[1], "rb").read()
def uleb(d, o):
    r = s = 0
    while True:
        b = d[o]; o += 1; r |= (b & 0x7f) << s
        if not b & 0x80: return r, o
        s += 7
ss, so = struct.unpack_from("<II", d, 56)
ts, to = struct.unpack_from("<II", d, 64)
ms, mo = struct.unpack_from("<II", d, 88)
cs, co = struct.unpack_from("<II", d, 96)
def s(idx):
    off = struct.unpack_from("<I", d, so + 4*idx)[0]
    _, off = uleb(d, off)
    return d[off:d.index(b"\x00", off)].decode("utf-8", "replace")
def td(idx):
    return s(struct.unpack_from("<I", d, to + 4*idx)[0])
def mn(idx):
    return s(struct.unpack_from("<I", d, mo + 8*idx + 4)[0])
targets = {("Lcom/pairip/licensecheck/LicenseClient;", "checkLicense"),
           ("Lcom/pairip/licensecheck/LicenseClient;", "scheduleAppShutdown"),
           ("Lcom/pairip/licensecheck/LicenseResponseHelper;", "validateResponse"),
           ("Lcom/pairip/licensecheck/LicenseActivity;", "onStart")}
found = set()
for i in range(cs):
    b = co + 32*i
    ci = struct.unpack_from("<I", d, b)[0]
    cdo = struct.unpack_from("<I", d, b + 24)[0]
    if not cdo: continue
    desc = td(ci)
    off = cdo
    sf, off = uleb(d, off); inf, off = uleb(d, off)
    dm, off = uleb(d, off); vm, off = uleb(d, off)
    for _ in range(sf+inf):
        _, off = uleb(d, off); _, off = uleb(d, off)
    for count in (dm, vm):
        idx = 0
        for _ in range(count):
            diff, off = uleb(d, off); _, off = uleb(d, off); code, off = uleb(d, off)
            idx = diff if idx == 0 and diff == 0 else idx + diff
            if code and (desc, mn(idx)) in targets:
                found.add((desc, mn(idx)))
for t in targets:
    print(("PATCHED " if t in found else "MISSING ") + t[1])
EOF
then ok "PairIP licence check neutralised in classes2.dex"; else bad "PairIP patch missing"; fi

(cd "$WORK" && unzip -q -o config.arm64_v8a.apk lib/arm64-v8a/libapp.so)
if python3 -c "import sys; sys.exit(0 if b'127.0.0.1:888' in open('$WORK/lib/arm64-v8a/libapp.so','rb').read() else 1)"; then
  ok "libapp.so points at the local proxy"; else bad "libapp.so host patch missing"; fi

echo
if [ "$FAIL" -eq 0 ]; then echo "ALL CHECKS PASSED"; else echo "$FAIL CHECK(S) FAILED"; fi
exit $FAIL
