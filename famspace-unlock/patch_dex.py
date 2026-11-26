#!/usr/bin/env python3
"""
Patch a DEX file in place - no disassembly, no reassembly.

Why: reassembling with smali rewrites the whole dex (and downgrades it from
dex 037 to dex 035), which the package installer can reject.  The patches
needed here are tiny, so instead of rebuilding the dex we overwrite the first
instruction of the target methods with "return-void" + nops.  The method size
does not change, so every offset in the dex stays valid, and only the dex
checksum (adler32) and signature (sha1) have to be refreshed.

Usage:
    python3 patch_dex.py <classes2.dex in> <classes2.dex out>
"""

import hashlib
import struct
import sys
import zlib

# class descriptor -> {method name: number of code units to overwrite}
TARGETS = {
    "Lcom/pairip/licensecheck/LicenseClient;": {
        "checkLicense": 2,
        "scheduleAppShutdown": 2,
    },
    "Lcom/pairip/licensecheck/LicenseResponseHelper;": {
        "validateResponse": 2,
    },
    "Lcom/pairip/licensecheck/LicenseActivity;": {
        "onStart": 3,
    },
}

RETURN_VOID = 0x0e   # 1 code unit
NOP = 0x0000          # 1 code unit


def uleb(data, off):
    result = 0
    shift = 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, off
        shift += 7


class Dex:
    def __init__(self, data):
        self.d = data
        (self.string_ids_size, self.string_ids_off) = struct.unpack_from("<II", data, 56)
        (self.type_ids_size, self.type_ids_off) = struct.unpack_from("<II", data, 64)
        (self.method_ids_size, self.method_ids_off) = struct.unpack_from("<II", data, 88)
        (self.class_defs_size, self.class_defs_off) = struct.unpack_from("<II", data, 96)

    def string(self, idx):
        off = struct.unpack_from("<I", self.d, self.string_ids_off + 4 * idx)[0]
        _, off = uleb(self.d, off)          # utf16 length
        end = self.d.index(b"\x00", off)
        return self.d[off:end].decode("utf-8", "replace")

    def type_descriptor(self, idx):
        str_idx = struct.unpack_from("<I", self.d, self.type_ids_off + 4 * idx)[0]
        return self.string(str_idx)

    def method_name(self, idx):
        name_idx = struct.unpack_from("<I", self.d, self.method_ids_off + 8 * idx + 4)[0]
        return self.string(name_idx)

    def class_data(self, class_data_off):
        """-> list of (method_idx, access_flags, code_off)"""
        off = class_data_off
        static_fields, off = uleb(self.d, off)
        instance_fields, off = uleb(self.d, off)
        direct_methods, off = uleb(self.d, off)
        virtual_methods, off = uleb(self.d, off)
        for _ in range(static_fields + instance_fields):
            _, off = uleb(self.d, off)
            _, off = uleb(self.d, off)
        methods = []
        for count in (direct_methods, virtual_methods):
            idx = 0
            for _ in range(count):
                diff, off = uleb(self.d, off)
                _, off = uleb(self.d, off)
                code_off, off = uleb(self.d, off)
                idx = diff if idx == 0 and diff == 0 else idx + diff
                methods.append((idx, code_off))
        return methods

    def find_code_off(self, descriptor, method_name):
        for i in range(self.class_defs_size):
            base = self.class_defs_off + 32 * i
            class_idx = struct.unpack_from("<I", self.d, base)[0]
            if self.type_descriptor(class_idx) != descriptor:
                continue
            class_data_off = struct.unpack_from("<I", self.d, base + 24)[0]
            if class_data_off == 0:
                return None
            for idx, code_off in self.class_data(class_data_off):
                if code_off and self.method_name(idx) == method_name:
                    return code_off
        return None


def refresh_checksums(data):
    data = bytearray(data)
    data[12:32] = hashlib.sha1(bytes(data[32:])).digest()
    struct.pack_into("<I", data, 8, zlib.adler32(bytes(data[12:])) & 0xFFFFFFFF)
    return bytes(data)


def main():
    src, dst = sys.argv[1], sys.argv[2]
    data = open(src, "rb").read()
    dex = Dex(data)
    print("dex version %s, %d bytes, %d classes" %
          (data[:8].decode("latin1").strip("\x00"), len(data), dex.class_defs_size))
    patched = 0
    for descriptor, methods in TARGETS.items():
        for name, units in methods.items():
            code_off = dex.find_code_off(descriptor, name)
            if code_off is None:
                print("  ! %s.%s not found" % (descriptor, name))
                continue
            insns = code_off + 16          # code_item header is 16 bytes
            buf = bytearray(data)
            # first instruction: return-void + nops, keeping the byte count
            buf[insns:insns + 2 * units] = struct.pack("<H", RETURN_VOID) + \
                b"\x00\x00" * (units - 1)
            data = bytes(buf)
            dex = Dex(data)
            print("  + %-46s code_off=0x%x  units=%d -> return-void" %
                  (descriptor.split("/")[-1].rstrip(";") + "." + name, code_off, units))
            patched += 1
    if not patched:
        print("nothing patched")
        return 1
    data = refresh_checksums(data)
    open(dst, "wb").write(data)
    print("wrote %s (%d bytes)" % (dst, len(data)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
