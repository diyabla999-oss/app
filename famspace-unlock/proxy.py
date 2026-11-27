#!/usr/bin/env python3
"""
Famspace unlock proxy.

The patched APK points Famspace's service hosts at this proxy.  The proxy
forwards every request to the real Famspace service (keeping the app's
auth/device/signature headers untouched, so the server still accepts them) and
rewrites the JSON responses so the app behaves as if the account had

  * unlimited gems and coins,
  * an active Premium/VIP subscription,
  * unlimited downloads and ad-free playback,
  * every space item already unlocked.

It also rewrites the cloud-config answer so "baseUrl" keeps pointing at the
proxy instead of the real host.

Nothing is modified in the request direction by default: the app signs its
requests, so changing them would invalidate the signature.  Use
--forge-balance to additionally claim a huge balance on unlock requests
(only useful if the server trusts client reported balances).

Usage:
    python3 proxy.py --port 8888 --public http://192.168.1.50:8888
"""

import argparse
import gzip
import io
import json
import os
import re
import ssl
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Hosts the patched app used before, tried in this order.  The first answer
# that is not a 404 wins, which keeps working if Famspace moves a route
# between the two.
UPSTREAM_HOSTS = [u.strip() for u in os.environ.get(
    "FAMSPACE_UPSTREAM",
    "https://famspace.disoms.net,https://www.famspace.us").split(",") if u.strip()]

LOGFILE = None
VERBOSE = False
PUBLIC_URL = ""
FORGE_BALANCE = False

# --------------------------------------------------------------------------
# rewrite rules
# --------------------------------------------------------------------------

BIG_BALANCE = 999_999_999

# key -> value forced in any JSON object
FORCE_VALUES = {
    # currency
    "gems": BIG_BALANCE,
    "coins": BIG_BALANCE,
    "balance": BIG_BALANCE,
    "addedGems": BIG_BALANCE,
    "coinCount": BIG_BALANCE,
    "coinBalance": BIG_BALANCE,
    "gemBalance": BIG_BALANCE,
    "userBalance": BIG_BALANCE,
    "rewardCoins": BIG_BALANCE,
    "favoriteCoins": BIG_BALANCE,
    "baseCoins": BIG_BALANCE,
    # subscription / premium
    "vipLv": 99,
    "vipLevel": 99,
    "premiumPurchased": True,
    "isVip": True,
    "isVIP": True,
    "vip": True,
    "premium": True,
    "isPremium": True,
    "isPro": True,
    "pro": True,
    "subscriptionActive": True,
    "isSubscribed": True,
    "subscribed": True,
    "adFree": True,
    "noAd": True,
    "isAdFree": True,
    "unlimitedDownloads": True,
    "incognitoEnabled": True,
    "incognito": True,
    # access flags
    "canView": True,
    "canDownload": True,
    "canPlay": True,
    "canShare": True,
    "isFavorite": True,
    "alreadyUnlocked": True,
    "unlocked": True,
    "isUnlocked": True,
    "unlockType": "",
    "unlockStatus": "unlocked",
    "locked": False,
    "isLocked": False,
    "needUnlock": False,
    "needCoins": False,
    "restricted": False,
    "viewingRestricted": False,
    "downloadRestricted": False,
    "memberOnly": False,
    "membersOnly": False,
    # limits -> effectively unlimited
    "expireDay": 36500,
    "expiresIn": 36500,
    "dailyLimit": 100000,
    "adWatchLimit": 100000,
    "unlockLimit": 100000,
    "freeUnlockLimit": 100000,
    "todayLimit": 100000,
    "maxCount": 100000,
}

# costs become free (only unlock-cost names, so IAP product prices stay intact)
ZERO_COST = {
    "cost", "coinCost", "gemCost", "unlockCost", "videoCost",
    "needCoin", "needGem", "needCoins", "coinPrice", "gemPrice",
}

# expiry fields -> far future
FUTURE = {
    "vipEnd": "2099-12-31T23:59:59Z",
    "vipExpire": "2099-12-31T23:59:59Z",
    "expireTime": "2099-12-31T23:59:59Z",
    "expiredTime": "2099-12-31T23:59:59Z",
    "expiresAt": "2099-12-31T23:59:59Z",
    "endTime": "2099-12-31T23:59:59Z",
}

KEY_RE = re.compile(
    r"(gem|coin|balance|credit|wallet|vip|premium|pro\b|subscri|member|"
    r"unlock|locked|limit|expire|cost|price|pay|fee|quota|left|remain)",
    re.IGNORECASE,
)


def rewrite_value(key, value):
    """Force a single JSON value."""
    if key == "baseUrl" and PUBLIC_URL:
        # Keep API requests on the local proxy. Media URLs (playUrl) are not
        # rewritten; their original CDN host must remain reachable.
        return PUBLIC_URL, True
    if key in FORCE_VALUES:
        return FORCE_VALUES[key], True
    if key in ZERO_COST:
        return 0, True
    if key in FUTURE:
        return FUTURE[key], True
    return value, False


def rewrite_obj(obj, depth=0):
    """Recursively rewrite a decoded JSON document. Returns (obj, changed)."""
    if depth > 12:
        return obj, False
    changed = False
    if isinstance(obj, dict):
        for k in list(obj.keys()):
            v = obj[k]
            if isinstance(v, (dict, list)):
                nv, ch = rewrite_obj(v, depth + 1)
                if ch:
                    obj[k] = nv
                    changed = True
                continue
            nv, forced = rewrite_value(k, v)
            if forced:
                obj[k] = nv
                changed = True
            elif isinstance(v, str) and KEY_RE.search(k):
                # leave strings alone unless they look like a date/expiry
                pass
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            if isinstance(v, (dict, list)):
                nv, ch = rewrite_obj(v, depth + 1)
                if ch:
                    obj[i] = nv
                    changed = True
    return obj, changed


def normalise_path(path: str) -> str:
    """Strip the padding prefix the patched host string carries."""
    for marker in ("/api/v1", "/ap/api/v1"):
        idx = path.find(marker)
        if idx >= 0:
            return path[idx:]
    # "/ap" padding only
    if path.startswith("/ap/"):
        return path[3:]
    return path


def log(*args):
    msg = " ".join(str(a) for a in args)
    line = "[%s] %s" % (time.strftime("%H:%M:%S"), msg)
    print(line, flush=True)
    if LOGFILE:
        with open(LOGFILE, "a") as fh:
            fh.write(line + "\n")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "FamspaceUnlock/1.0"

    def log_message(self, fmt, *a):  # silence default logging
        pass

    # ---------------------------------------------------------------- utils
    def _forward(self, method, body=None):
        path = normalise_path(self.path)
        headers = {}
        for k, v in self.headers.items():
            lk = k.lower()
            if lk in ("host", "content-length", "connection", "accept-encoding",
                      "proxy-connection"):
                continue
            headers[k] = v
        headers["Host"] = urllib.parse.urlsplit(
            [u for u in UPSTREAM_HOSTS if u][0]).netloc
        headers["Accept-Encoding"] = "identity"
        headers["X-Famspace-Unlock"] = "1"

        last = None
        # API routes live on the service host, media (/v1/...) on the S3 style
        # host, everything else (web pages, terms) on the website host.
        if path.startswith("/api/") or path.startswith("/v1/"):
            candidates = list(UPSTREAM_HOSTS)
        else:
            candidates = ["https://www.famspace.us"] + list(UPSTREAM_HOSTS)
        for base in candidates:
            url = base + path
            req = urllib.request.Request(url, data=body, method=method,
                                         headers=headers)
            try:
                with urllib.request.urlopen(req, timeout=45,
                                            context=ssl.create_default_context()) as resp:
                    return resp.status, dict(resp.headers), resp.read()
            except urllib.error.HTTPError as e:
                if e.code == 404 and base != candidates[-1]:
                    last = e
                    continue
                return e.code, dict(e.headers), e.read()
            except Exception as e:  # noqa: BLE001
                last = e
                if base != candidates[-1]:
                    continue
                raise
        if last is not None:
            raise last

    def _send(self, status, headers, payload):
        self.send_response(status)
        for k, v in headers.items():
            lk = k.lower()
            if lk in ("content-length", "content-encoding", "transfer-encoding",
                      "connection", "server", "date"):
                continue
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.end_headers()
        if payload:
            self.wfile.write(payload)

    def _handle(self, method):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        if VERBOSE:
            log("-> %s %s" % (method, self.path))
        try:
            status, headers, payload = self._forward(method, body)
        except Exception as e:  # noqa: BLE001
            log("!! upstream error: %r" % (e,))
            self._send(502, {"Content-Type": "text/plain"},
                       b"upstream error: " + str(e).encode())
            return

        ctype = headers.get("Content-Type", "")
        if "json" in ctype.lower() and payload:
            try:
                doc = json.loads(payload.decode("utf-8"))
            except Exception:  # noqa: BLE001
                doc = None
            if doc is not None:
                newdoc, changed = rewrite_obj(doc)
                if changed:
                    payload = json.dumps(newdoc).encode("utf-8")
                    headers["Content-Type"] = "application/json"
                if VERBOSE and changed:
                    log("   [rewritten] %s" % self.path)
        self._send(status, headers, payload)

    # -------------------------------------------------------------- verbs
    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")

    def do_PUT(self):
        self._handle("PUT")

    def do_DELETE(self):
        self._handle("DELETE")

    def do_PATCH(self):
        self._handle("PATCH")


def main():
    global VERBOSE, LOGFILE, PUBLIC_URL
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8888)
    ap.add_argument("--public", default="", help="URL the app should use, e.g. http://192.168.1.50:8888")
    ap.add_argument("--log", default="proxy.log")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    VERBOSE = args.verbose
    LOGFILE = args.log
    PUBLIC_URL = args.public or ("http://127.0.0.1:%d" % args.port)

    srv = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    srv.daemon_threads = True
    log("Famspace unlock proxy listening on 0.0.0.0:%d" % args.port)
    log("public url   : %s" % PUBLIC_URL)
    log("upstream     : %s" % ", ".join(UPSTREAM_HOSTS))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        log("bye")


if __name__ == "__main__":
    main()
