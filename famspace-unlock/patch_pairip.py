#!/usr/bin/env python3
"""
Neutralise PairIP's Play licence check in a decoded APK (apktool smali tree).

PairIP (com.pairip.licensecheck) is wired in as the app's Application class:

    <application android:name="com.pairip.application.Application">
        -> attachBaseContext() -> LicenseClient.checkLicense(context)

A build that was not installed by Google Play fails that check, and PairIP
then shows its error dialog or opens the Play Store paywall and kills the app -
the "Get this app from the Play Store" redirect.

Patches applied (each one is an early return, nothing else changes):

  LicenseClient.checkLicense(Context)          -> do nothing (no check at all)
  LicenseClient.scheduleAppShutdown()          -> do nothing (never kill the app)
  LicenseResponseHelper.validateResponse(...)  -> do nothing (no signature check)
  LicenseActivity.onStart()                    -> finish() right away
                                                  (never show the dialog/paywall)

Usage:
    python3 patch_pairip.py <apktool-decoded-dir>
"""

import os
import re
import sys

PATCHES = [
    # (file, method signature fragment, instructions to insert)
    ("smali_classes2/com/pairip/licensecheck/LicenseClient.smali",
     "checkLicense(Landroid/content/Context;)V",
     ["    return-void"]),
    ("smali_classes2/com/pairip/licensecheck/LicenseClient.smali",
     "scheduleAppShutdown()V",
     ["    return-void"]),
    ("smali_classes2/com/pairip/licensecheck/LicenseResponseHelper.smali",
     "validateResponse(Landroid/os/Bundle;Ljava/lang/String;)V",
     ["    return-void"]),
    ("smali_classes2/com/pairip/licensecheck/LicenseActivity.smali",
     "onStart()V",
     ["    invoke-virtual {p0}, Landroid/app/Activity;->finish()V",
      "    return-void"]),
]


def find_method(lines, sig_fragment):
    """Return (start, end) line indices of the .method ... .end method block."""
    for i, l in enumerate(lines):
        if l.startswith(".method ") and sig_fragment in l:
            for j in range(i + 1, len(lines)):
                if lines[j].strip().startswith(".end method"):
                    return i, j
            return i, None
    return None, None


def insert_into_method(lines, start, end, instrs):
    """Insert instructions before the first real instruction of the method.

    .locals/.registers and complete .annotation blocks in front of the code
    have to be skipped, otherwise the instructions land inside an annotation.
    """
    i = start + 1
    while i < end:
        l = lines[i].strip()
        if l == "" or l.startswith(".locals") or l.startswith(".registers"):
            i += 1
            continue
        if l.startswith(".annotation"):
            while i < end and not lines[i].strip().startswith(".end annotation"):
                i += 1
            i += 1  # skip the .end annotation line as well
            continue
        break
    return lines[:i] + instrs + lines[i:]


def main():
    root = sys.argv[1]
    changed = 0
    for rel, sig, instrs in PATCHES:
        path = os.path.join(root, rel)
        if not os.path.exists(path):
            print("  ! missing %s" % rel)
            continue
        lines = open(path, encoding="utf-8").read().split("\n")
        start, end = find_method(lines, sig)
        if start is None:
            print("  ! method %s not found in %s" % (sig, os.path.basename(rel)))
            continue
        # don't patch twice
        body = "\n".join(lines[start:end])
        if instrs[-1].strip() in body and "patched by patch_pairip" in body:
            print("  = %s already patched" % sig)
            continue
        lines = insert_into_method(lines, start, end, instrs)
        # mark it
        lines.insert(start + 1, "    # patched by patch_pairip: bypass Play licence check")
        open(path, "w", encoding="utf-8").write("\n".join(lines))
        print("  + %-44s %s" % (sig, " ".join(i.strip() for i in instrs)))
        changed += 1
    print("patched %d method(s)" % changed)
    return 0 if changed else 1


if __name__ == "__main__":
    sys.exit(main())
