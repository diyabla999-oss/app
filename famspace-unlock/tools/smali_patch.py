#!/usr/bin/env python3
"""
Neutralise PairIP's Play licence check in a decoded (apktool) smali tree.

PairIP (com.pairip.licensecheck) is the app's Application class:

    <application android:name="com.pairip.application.Application">
        -> attachBaseContext() -> LicenseClient.checkLicense(context)

A build that was not installed by Google Play fails that check, and PairIP
then shows its error dialog / opens the Play Store paywall and kills the app.
The in-app variant additionally starts the local rewrite proxy from
attachBaseContext() instead, so the proxy is listening before the Flutter
engine starts.

Edits:
  com/pairip/application/Application.smali
      attachBaseContext: replace the checkLicense call with
      ProxyHolder.ensureStarted()
  com/pairip/licensecheck/LicenseClient.smali
      checkLicense: return immediately
      scheduleAppShutdown: return immediately
  com/pairip/licensecheck/LicenseResponseHelper.smali
      validateResponse: return immediately
  com/pairip/licensecheck/LicenseActivity.smali
      onStart: return immediately (no error dialog, no paywall)

Usage:
    python3 smali_patch.py <apktool-decoded-dir>
"""

import os
import re
import sys

RETURN_AFTER = {
    "smali_classes2/com/pairip/licensecheck/LicenseClient.smali": [
        ("checkLicense(Landroid/content/Context;)V", "return-void"),
        ("scheduleAppShutdown()V", "return-void"),
    ],
    "smali_classes2/com/pairip/licensecheck/LicenseResponseHelper.smali": [
        ("validateResponse(Landroid/os/Bundle;Ljava/lang/String;)V", "return-void"),
    ],
    "smali_classes2/com/pairip/licensecheck/LicenseActivity.smali": [
        ("onStart()V", "return-void"),
    ],
}


def patch_method(path, sig, instr):
    s = open(path, encoding="utf-8").read()
    m = re.search(r"(\.method[^\n]*" + re.escape(sig) + r"[^\n]*\n)(.*?)(\n\s*\.end method)", s, re.S)
    if not m:
        return "missing"
    body = m.group(2)
    if "# patched by build_inapp" in body:
        return "already"
    # insert right after the .locals/.registers line (before any instruction)
    lines = body.split("\n")
    idx = 0
    for i, l in enumerate(lines):
        st = l.strip()
        if st == "" or st.startswith(".locals") or st.startswith(".registers"):
            idx = i + 1
            continue
        break
    lines[idx:idx] = ["    # patched by build_inapp: " + instr, "    " + instr]
    s = s[:m.start(2)] + "\n".join(lines) + s[m.end(2):]
    open(path, "w", encoding="utf-8").write(s)
    return "patched"


def patch_fingerprint(root, fingerprint):
    """Make the app report the ORIGINAL (Play) signing certificate fingerprint.

    Ll7/a.a(PackageManager) returns the SHA-256 hex of the app's own signing
    certificate; the backend validates it ("Invalid sign"), so a re-signed
    build is rejected on every request - including login.  Reporting the
    original fingerprint makes the request identical to the official build.
    """
    path = os.path.join(root, "smali_classes5/l7/a.smali")
    if not os.path.exists(path):
        return "missing"
    s = open(path, encoding="utf-8").read()
    if "# patched by build_inapp: report the original certificate fingerprint" in s:
        return "already"
    m = re.search(r"(\.method public final a\(Landroid/content/pm/PackageManager;\)Ljava/lang/String;\n)(.*?)(\n\s*\.end method)", s, re.S)
    if not m:
        return "missing"
    body = ("    .locals 1\n\n"
            "    # patched by build_inapp: report the original certificate fingerprint\n"
            "    const-string v0, \"" + fingerprint + "\"\n\n"
            "    return-object v0")
    s = s[:m.start(2)] + body + s[m.end(2):]
    open(path, "w", encoding="utf-8").write(s)
    return "patched"


def patch_application(root):
    path = os.path.join(root, "smali_classes2/com/pairip/application/Application.smali")
    s = open(path, encoding="utf-8").read()
    old = "    invoke-static {p1}, Lcom/pairip/licensecheck/LicenseClient;->checkLicense(Landroid/content/Context;)V"
    new = ("    # patched by build_inapp: licence check disabled, local rewrite proxy started here\n"
           "    invoke-static {p1}, Lcom/famspace/unlock/ProxyHolder;->ensureStarted(Landroid/content/Context;)V")
    if new.strip() in s:
        return "already"
    if old not in s:
        return "missing"
    s = s.replace(old, new)
    open(path, "w", encoding="utf-8").write(s)
    return "patched"


def main():
    root = sys.argv[1]
    fingerprint = sys.argv[2] if len(sys.argv) > 2 else None
    if fingerprint:
        print("   certificate fingerprint   -> %s" % patch_fingerprint(root, fingerprint))
    print("   Application.attachBaseContext -> %s" % patch_application(root))
    for rel, methods in RETURN_AFTER.items():
        path = os.path.join(root, rel)
        if not os.path.exists(path):
            print("   ! missing %s" % rel)
            continue
        for sig, instr in methods:
            print("   %-34s -> %s" % (sig.split("(")[0], patch_method(path, sig, instr)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
