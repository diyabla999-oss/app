#!/usr/bin/env bash
# Verify that a single universal APK is install-clean and still patched.
#
#   ./verify_apk.sh output/Famspace-universal.apk
#
# Same conditions the package installer enforces, plus the patches this build
# ships.  (verify_build.sh does the same for the XAPK split set.)
set -uo pipefail

APK="${1:-output/Famspace-universal.apk}"
APK="$(cd "$(dirname "$APK")" && pwd)/$(basename "$APK")"
BT="${BT:-$HOME/tools/android-sdk/build-tools/34.0.0}"
WORK="$(mktemp -d /tmp/opencode/verifyapk.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
FAIL=0

ok()   { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAIL=$((FAIL+1)); }

echo "== unpacking $APK"
mkdir -p "$WORK"
(cd "$WORK" && unzip -q "$APK")

echo "== native libraries (extractNativeLibs=false -> must be stored + aligned)"
res=$(python3 - "$APK" <<'EOF'
import sys, zipfile, struct
z = zipfile.ZipFile(sys.argv[1])
bad, total, abi = [], 0, set()
for i in z.infolist():
    if not i.filename.endswith(".so"):
        continue
    total += 1
    abi.add(i.filename.split("/")[1])
    z.fp.seek(i.header_offset)
    hdr = z.fp.read(30)
    nlen, elen = struct.unpack_from("<HH", hdr, 26)
    data_off = i.header_offset + 30 + nlen + elen
    if i.compress_type != 0:
        bad.append("%s is compressed (method %d)" % (i.filename, i.compress_type))
    elif data_off % 4096:
        bad.append("%s data offset %d not 4096-aligned" % (i.filename, data_off))
print("TOTAL_SO=%d" % total)
print("ABIS=%s" % ",".join(sorted(abi)))
print("BAD=%s" % ("\n".join(bad) if bad else "none"))
EOF
)
total=$(echo "$res" | sed -n 's/^TOTAL_SO=//p')
abis=$(echo "$res" | sed -n 's/^ABIS=//p')
badlist=$(echo "$res" | sed -n 's/^BAD=//p')
echo "       $total libs, abi: $abis"
if [ "$badlist" = "none" ]; then ok "$total native libs: stored uncompressed + 4096 aligned"; else
  bad "native library layout:"; echo "$badlist" | sed 's/^/       /'
fi

echo "== dex files"
res=$(python3 - "$APK" <<'EOF'
import sys, zipfile, struct, hashlib, zlib
out, n = [], 0
z = zipfile.ZipFile(sys.argv[1])
for i in z.infolist():
    if i.filename.endswith(".dex") and "/" not in i.filename:
        n += 1
        d = z.read(i)
        ver = d[:8].decode("latin1").strip("\x00")
        ck = struct.unpack_from("<I", d, 8)[0]
        okck = (zlib.adler32(d[12:]) & 0xffffffff) == ck
        oksig = hashlib.sha1(d[32:]).digest() == d[12:32]
        if not ver.endswith("037") or not okck or not oksig:
            out.append("%s version=%s checksum=%s signature=%s" %
                       (i.filename, ver, okck, oksig))
print("COUNT=%d" % n)
print("BAD=%s" % ("\n".join(out) if out else "none"))
EOF
)
echo "$res" | sed -n 's/^COUNT=/       dex files: /p'
badlist=$(echo "$res" | sed -n 's/^BAD=//p')
if [ "$badlist" = "none" ]; then ok "all dex files are dex 037 with valid checksum+signature"; else
  bad "dex problems:"; echo "$badlist" | sed 's/^/       /'
fi

echo "== manifest / resources"
if "$BT/aapt2" dump badging "$APK" > "$WORK/badging.txt" 2>&1; then
  ok "parses"
  grep -E "^package:" "$WORK/badging.txt" | sed -E "s/.*name='([^']*)'.*versionCode='([^']*)'.*/\1  versionCode \2/" | sed 's/^/       /'
  grep -qE "split=" "$WORK/badging.txt" && bad "still declares itself as a split" || ok "no split attributes - it is a standalone APK"
else
  bad "does not parse"
fi

echo "== native abi declared"
if "$BT/aapt2" dump badging "$APK" 2>/dev/null | grep -q "native-code: 'arm64-v8a'"; then
  ok "declares arm64-v8a"
else
  bad "no native-code declared - the libraries would not be installed"
fi

echo "== signature"
if "$BT/apksigner" verify --verbose "$APK" 2>&1 | grep -q "Verifies"; then
  ok "v1+v2+v3 signatures valid"
  "$BT/apksigner" verify --print-certs "$APK" 2>/dev/null | grep -i "SHA-256 digest" | head -1 | sed 's/^/       /'
else
  bad "signature verification failed"
fi

echo "== alignment"
if "$BT/zipalign" -c -p -v 4 "$APK" >/dev/null 2>&1; then ok "zipalign -p 4 clean"; else bad "zipalign reports problems"; fi

echo "== in-app proxy"
res=$(python3 - "$APK" <<'EOF'
import sys, zipfile, struct
d = zipfile.ZipFile(sys.argv[1]).read("classes2.dex")
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
EOF
)
if [ "$res" = "MISSING=none" ]; then ok "proxy classes are inside classes2.dex"; else bad "proxy classes missing: $res"; fi

echo "== certificate fingerprint"
if python3 -c "
import sys, zipfile
d = zipfile.ZipFile('$APK').read('classes5.dex')
fp = b'100EA61D15387F4BFD7A3BE741043921C4E19FC88D5D40BB6A3F8BC167DBB12A'
sys.exit(0 if fp in d else 1)"; then
  ok "app reports the original Play certificate fingerprint"
else
  bad "the original certificate fingerprint is not in classes5.dex"
fi

echo "== patches"
ver=$(python3 -c "import zipfile; print(zipfile.ZipFile('$APK').read('classes2.dex')[:8].decode('latin1').replace(chr(10),' ').replace(chr(0),'').strip())")
case "$ver" in
  "dex 037") ok "classes2.dex is still dex 037" ;;
  *) bad "classes2.dex is '$ver' (expected dex 037)" ;;
esac
if python3 -c "import sys, zipfile; sys.exit(0 if b'127.0.0.1:888' in zipfile.ZipFile('$APK').read('lib/arm64-v8a/libapp.so') else 1)"; then
  ok "libapp.so points at the local proxy"; else bad "libapp.so host patch missing"; fi
if python3 - "$APK" <<'EOF'
import sys, struct, zipfile
d = zipfile.ZipFile(sys.argv[1]).read("classes2.dex")
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
def td(idx): return s(struct.unpack_from("<I", d, to + 4*idx)[0])
def mn(idx): return s(struct.unpack_from("<I", d, mo + 8*idx + 4)[0])
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

echo
if [ "$FAIL" -eq 0 ]; then echo "ALL CHECKS PASSED"; else echo "$FAIL CHECK(S) FAILED"; fi
exit $FAIL
