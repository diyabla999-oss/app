#!/usr/bin/env python3
"""
Annotate raw arm64 code of libapp.so the way Blutter does for the parts
Blutter did not dump (the API/model layer).

  * finds the enclosing Dart function (EnterFrame prologue)
  * resolves object-pool loads to the strings/objects listed in pp.txt
  * marks branch targets and call targets

Usage:
    python3 aot_dis.py <libapp.so> <address> [instructions]
    python3 aot_dis.py <libapp.so> --func <address> [max instructions]
"""
import bisect
import re
import struct
import sys

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN

PP = 27


def load_pp(path):
    """pool offset -> description"""
    out = {}
    try:
        for line in open(path, encoding="utf-8", errors="replace"):
            m = re.match(r"\[pp\+0x([0-9a-f]+)\] (.*)$", line.rstrip("\n"))
            if m:
                out[int(m.group(1), 16)] = m.group(2)
    except OSError:
        pass
    return out


def find_func_start(data, addr, max_back=0x4000):
    """Dart AOT prologue: stp x29, x30, [sp, #-N]! ; mov x29, sp"""
    lo = max(0, addr - max_back)
    pos = lo
    best = None
    while True:
        i = data.find(b"\xfd\x03\x00\x91", pos, addr)   # mov x29, sp
        if i < 0:
            break
        w = struct.unpack_from("<I", data, i - 4)[0]
        # stp x29, x30, [sp, #imm]!  -> 0xA9B... with pre-index
        if (w & 0xFFC00000) == 0xA9800000:
            best = i - 4
        pos = i + 4
    return best


def annotate(data, pp, start, count):
    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    insns = list(md.disasm(data[start:start + count * 4], start))
    pending = {}
    for k, i in enumerate(insns):
        note = ""
        if i.mnemonic == "add" and "x27" in i.op_str:
            m = re.search(r"#(0x[0-9a-f]+), lsl #12", i.op_str)
            if m:
                hi = int(m.group(1), 16)
                reg = i.op_str.split(",")[0].strip()
                pending[reg] = ("pp", hi)
        elif i.mnemonic == "ldr" and "x27" not in i.op_str:
            m = re.match(r"(\w+), \[(\w+)(?:, #0x([0-9a-f]+))?\]", i.op_str)
            if m and m.group(1) in pending and pending[m.group(1)][0] == "pp":
                hi = pending[m.group(1)][1]
                # capstone prints the already-scaled byte offset, which is
                # exactly what the pool slot is built from.
                lo = (int(m.group(3), 16) if m.group(3) else 0)
                slot = (hi << 12) | lo
                desc = pp.get(slot)
                if desc:
                    note = "  ; [pp+0x%x] %s" % (slot, desc)
        if i.mnemonic in ("b", "bl") and "#" in i.op_str:
            m = re.search(r"#(0x[0-9a-f]+)", i.op_str)
            if m:
                note = "  ; -> %s" % m.group(1)
        print("0x%x:  %-10s %-38s%s" % (i.address, i.bytes.hex(),
                                        i.mnemonic + " " + i.op_str, note))


def main():
    lib = sys.argv[1]
    data = open(lib, "rb").read()
    pp = load_pp("/tmp/opencode/blutter_out/pp.txt")
    if sys.argv[2] == "--func":
        addr = int(sys.argv[3], 0)
        count = int(sys.argv[4]) if len(sys.argv) > 4 else 120
        fs = find_func_start(data, addr)
        print("== function starts at 0x%x (asked 0x%x)" % (fs or 0, addr))
        annotate(data, pp, fs or addr, count)
        return
    addr = int(sys.argv[2], 0)
    count = int(sys.argv[3]) if len(sys.argv) > 3 else 40
    annotate(data, pp, addr, count)


if __name__ == "__main__":
    main()
