"""
mitmproxy addon for Famspace.

Same rewrite rules as proxy.py, but applied to live TLS traffic.  Use this
when you prefer a MITM setup over the patched-APK redirect:

  1. install mitmproxy's CA on the phone.  Dart's HttpClient on Android only
     reads /system/etc/security/cacerts, so a *user* CA is not enough - on a
     rooted phone use a Magisk module that moves certificates into the system
     store (the app itself queries for Magisk/SuperSU, so rooted devices are
     expected).
  2. run:  mitmdump -s mitm_addon.py --listen-host 0.0.0.0 --listen-port 8080
  3. point the phone's Wi-Fi proxy at this machine and open Famspace.

Start with:  mitmdump -s mitm_addon.py -v
"""

import json
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from proxy import rewrite_obj, PUBLIC_URL  # noqa: E402

import proxy as _proxy


def response(flow):
    ctype = flow.response.headers.get("content-type", "")
    if "json" not in ctype.lower():
        return
    try:
        doc = json.loads(flow.response.text)
    except Exception:  # noqa: BLE001
        return
    newdoc, changed = _proxy.rewrite_obj(doc)
    if changed:
        flow.response.text = json.dumps(newdoc)
        flow.response.headers["content-type"] = "application/json"
        print("[famspace] rewritten %s %s" % (flow.request.method, flow.request.path))
