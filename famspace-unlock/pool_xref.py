#!/usr/bin/env python3
"""
Find code that loads a given object-pool slot (Dart AOT, arm64).

In Dart AOT the object pool is addressed through the PP register (x27):
    add  xN, x27, #<slot_hi>, lsl #12
    ldr  xN, [xN, #<slot_lo>]
so a pool slot can be located by scanning the code for the matching LDR and
walking back to its ADD.

Usage:
    python3 pool_xref.py libapp.so 0x19f48 [0x1cab8 ...]
"""
import struct
import sys

PP = 27
ADD_IMM64 = 0x91400000          # add Xd, Xn, #imm12, LSL #12
LDR_IMM64 = 0xF9400000          # ldr Xt, [Xn, #imm12]


def decode(data, off):
    w = struct.unpack_from("<I", data, off)[0]
    if (w & 0xFFC00000) == ADD_IMM64:
        rd = w & 0x1F
        rm = (w >> 5) & 0x1F
        imm = (w >> 10) & 0xFFF
        return ("add", rd, rm, imm)
    if (w & 0xFFC00000) == LDR_IMM64:
        rt = w & 0x1F
        rn = (w >> 5) & 0x1F
        imm = (w >> 10) & 0xFFF
        return ("ldr", rt, rn, imm)
    return None


def find_xrefs(data, slot, text_start, text_end):
    """Return [(ldr_addr, add_addr, reg)] for loads of pool slot.

    Dart's object pool is addressed in bytes; a pool entry is 8 bytes, and the
    64-bit LDR scales its immediate by 8, so the encoded immediates are:
        add  xN, x27, #slot >> 12, lsl #12
        ldr  xN, [xN, #((slot & 0xfff) >> 3)]
    """
    hi = (slot >> 12) & 0xFFF
    lo = (slot & 0xFFF) >> 3
    hits = []
    for off in range(text_start, text_end, 4):
        w = struct.unpack_from("<I", data, off)[0]
        if (w & 0xFFC00000) != LDR_IMM64:
            continue
        if ((w >> 10) & 0xFFF) != lo:
            continue
        rn = (w >> 5) & 0x1F
        rt = w & 0x1F
        # walk back to find "add xN, x27, #hi, lsl #12"
        for back in range(1, 9):
            aoff = off - back * 4
            if aoff < text_start:
                break
            d = decode(data, aoff)
            if d and d[0] == "add" and d[1] == rn and d[2] == PP and d[3] == hi:
                hits.append((off, aoff, rt))
                break
    return hits


def main():
    path = sys.argv[1]
    slots = [int(a, 0) for a in sys.argv[2:]]
    data = open(path, "rb").read()
    # .text of libapp.so: the Dart code lives in the second LOAD segment
    # (the isolate snapshot instructions).  Scan everything executable.
    text_start = 0x300000
    text_end = len(data) - 4
    for slot in slots:
        hits = find_xrefs(data, slot, text_start, text_end)
        print("pp+0x%x : %d xref(s)" % (slot, len(hits)))
        for ldr, add, reg in hits:
            print("   ldr @0x%x  (add @0x%x, x%d)" % (ldr, add, reg))


if __name__ == "__main__":
    main()
