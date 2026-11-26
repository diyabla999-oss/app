#!/usr/bin/env python3
"""
Famspace (com.famspace.app.android) - redirect the app's service hosts to a
local unlock proxy.

The Flutter/Dart AOT build keeps its service host names as plain ASCII string
literals inside the read-only heap of libapp.so.  Dart string literals are
stored back-to-back in the snapshot's string-data area with the length kept in
a separate table, so replacing the bytes in place with an equally long string
is safe: no header, length or hash field has to be touched.

Two host constants are patched:

    "https://famspace.disoms.net"   (27 bytes)  -> service/API host
    "https://www.famspace.us"        (23 bytes)  -> service/API host

("https://wt.famspace.us" is the Chatwoot support widget and is left alone,
as is the embedded debug host "http://190.92.202.202:9999".)

Usage:
    python3 patch_libapp.py --ip 192.168.1.50 --port 8888 \
        --in libapp.so --out libapp.patched.so
"""

import argparse
import shutil
import sys

TARGETS = [
    # (literal in the binary, expected length)
    (b"https://famspace.disoms.net", 27),
    (b"https://www.famspace.us", 23),
    (b"http://190.92.202.202:9999", 26),   # debug/test API host
]


def build_replacement(ip: str, port: int, width: int) -> bytes:
    """Build an exactly `width` byte replacement for the given slot.

    The slot keeps its original length, so the replacement is padded with a
    path prefix.  The proxy strips everything in front of "/api/v1", so the
    padding content does not matter.
    """
    base = "http://%s:%d" % (ip, port)
    pad = width - len(base)
    if pad < 0:
        raise SystemExit(
            "host %r is too long for a %d byte slot (need %d more bytes free); "
            "use a shorter LAN address or port" % (base, width, -pad)
        )
    # Neutral padding: it must not look like a path the app itself uses
    # ("/api/v1"), otherwise the app's own path would be swallowed by the
    # proxy's prefix stripping.
    if pad == 0:
        filler = ""
    else:
        filler = "/" + "x" * (pad - 1)
    repl = (base + filler).encode()
    assert len(repl) == width, (len(repl), width)
    return repl


def patch(data: bytes, ip: str, port: int, verbose: bool = True) -> bytes:
    for literal, width in TARGETS:
        repl = build_replacement(ip, port, width)
        count = data.count(literal)
        if count == 0:
            print("  ! %-32s not found - skipped" % literal.decode())
            continue
        # Only replace occurrences that are not the prefix of a longer literal.
        offset = 0
        patched = 0
        while True:
            idx = data.find(literal, offset)
            if idx < 0:
                break
            end = idx + len(literal)
            nxt = data[end:end + 1]
            if nxt == b"/":
                offset = end          # part of a longer URL, keep it
                continue
            data = data[:idx] + repl + data[end:]
            offset = idx + len(repl)
            patched += 1
            if verbose:
                print("  + %-32s @ 0x%x -> %s" % (literal.decode(), idx, repl.decode()))
        print("  = %-32s patched %d occurrence(s) (of %d found)"
              % (literal.decode(), patched, count))
    return data


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="infile", required=True)
    ap.add_argument("--out", dest="outfile", required=True)
    ap.add_argument("--ip", required=True, help="LAN IP of the unlock proxy")
    ap.add_argument("--port", type=int, default=8888)
    args = ap.parse_args()

    with open(args.infile, "rb") as fh:
        data = fh.read()
    print("patching %s (%d bytes)" % (args.infile, len(data)))
    out = patch(data, args.ip, args.port)
    with open(args.outfile, "wb") as fh:
        fh.write(out)
    print("wrote %s (%d bytes)" % (args.outfile, len(out)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
