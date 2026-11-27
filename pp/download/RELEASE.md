Patched build of `com.famspace.app.android` (Famspace 1.0.8, versionCode 10008201).

**The API crypto is solved — the wallet and Premium are now rewritten inside
the encrypted payload.**

## 1. `Famspace-universal.apk` — one file, one tap (recommended)

```
md5    3445d593403deaf1e7cbf0f5c17a6bad
sha256 2ae5a2c92388878086ac7683b1a5c2dfdf1a6abf39a371d99b406ec9412de58a
```

84.5 MB. Copy it to the phone and tap it. No XAPK installer, no split
handling, no second app.

1. **Uninstall the original Famspace** (the Play version) first. The patched
   build is re-signed, so installing over the Play install fails with
   `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
2. Delete any older copy of the app from the phone.
3. Tap `Famspace-universal.apk`, then open **Famspace**.

The split set of 1.0.8 is only base + native libraries: the `config.arm64_v8a`
split holds the 21 `.so` files and no resources, `config.xxhdpi` adds ~100
xxhdpi drawables and the language splits add ~163 AndroidX strings. The app
is Flutter — its UI is drawn from `assets/` by the Dart code — so the
universal APK keeps the base's own resource table and manifest byte-identical
and only folds in the libraries.

## 2. `FamSpace-unlocked.xapk` — the same app as a split set

```
md5    8f6abdade130a54c1ce1430707bb39bf
sha256 3c9a6e60094ee98c0f3edf2902f2b8338c1cdc2d6cf2d8575f37c4faf221d1ed
```

85.4 MB, base + 19 splits. Install with an XAPK installer — **all splits have
to go in together**, one by one fails with `INSTALL_FAILED_MISSING_SPLIT`.

The archive is stored uncompressed and laid out exactly like the XAPK from
the Play Store (base APK first, `manifest.json` last). Some XAPK installers
read the APKs straight out of the archive and refuse a compressed one with
"couldn't install" — that was the reason for the earlier failures.
Over adb:

```bash
adb install-multiple -r com.famspace.app.android.apk config.arm64_v8a.apk config.xxhdpi.apk
```

## What the patch does

| | |
|---|---|
| PairIP Play licence check | neutralised — no "get it from the Play Store" redirect, no paywall, no shutdown |
| certificate fingerprint | the app reports the original Play certificate SHA-256, so the backend accepts its requests and login works |
| **API crypto** | every answer is decrypted, rewritten and encrypted again — the app never notices |
| JSON rewrite proxy | `coins`/`gems`/`balance`/`coin`/`availableCoins`/`totalGems`/`points`/… → 999999999, `vipLv` → 99, `vipEnd` → 2147483647 (2038-01-19), `premiumPurchased`/`isVip`/`premium`/`canView`/`canDownload`/`canPlay` → true, `locked`/`isLocked`/`memberOnly` → false, unlock costs (`coinsRequired`, `coinCost`, …) → 0, daily limits → 100000 |
| host strings in `libapp.so` | `https://famspace.disoms.net` and friends → `http://127.0.0.1:8888` (equal-length in-place rewrite of the Dart AOT string pool) |

The proxy is compiled into `classes2.dex` and started by the app's own
`Application.attachBaseContext()` — no server on your PC, no root, no MITM
CA, no service to keep alive, no second app.

IAP product prices are deliberately not rewritten, so the purchase flow stays
intact. Requests are not modified — Famspace signs them, and altering a
request body invalidates the signature.

## The API crypto

Every answer is

```json
{"success":true,"code":200,"responseData":"<encrypted, base64>","t":1795723924}
```

so the wallet lives inside an **encrypted** payload, and no rule that
rewrites a field name can reach it. That was why the balance stayed at
whatever the server said.

The encryption was read out of the Dart AOT snapshot of `libapp.so`:

```
responseData = base64( ciphertext || index byte )
```

* the **index byte** — the last byte of the blob — selects one of **sixteen
  AES-128 keys** that the app keeps in a map. The table is in the object pool
  at `pp+0x9230`, right next to the `"AES/"` string and the map that maps
  the cipher-mode enum to `CBC`, `CTR`, `ECB`, `OFB-64`, `CFB-64`, `GCM`, …;
* the cipher is **AES-128-CBC without padding** and the **IV is the key
  itself**;
* the plaintext is the JSON followed by **zero bytes** up to the block
  boundary.

The proxy decrypts every answer, rewrites the wallet inside it and encrypts
it again with the same index byte — so the app decrypts it exactly as it
would the original.

How it was found: the pool dump gave the sixteen keys; CBC makes the second
block independent of the IV (`P2 = D(C2) XOR C1`), which confirmed the key
from the tail of the JSON alone; the IV then fell out of the algebra
(`IV = D(C1) XOR P1`). Two captured answers still did not decrypt — the
reason was a **single mistyped character** in the base64 that had been
copied out of the log by hand.

`test_crypto_proxy.sh` proves the whole loop against the answers that were
captured from the real server: the second decryption shows
`coins=999999999`, `gems=999999999`, `vipLv=99`.

## Checking it on the phone

The proxy runs inside the app process, so open Famspace first, then read the
log from the phone's browser:

```
http://127.0.0.1:8888/__famunlock/log
```

The line to look for is

```
wallet: coins=999999999, gems=999999999, vipLv=99, vipEnd=2147483647
```

## What this still cannot do

The unlock call is checked **server-side**
(`POST /api/v1/space/favorite/unlock` → `{"code":402,"error":"insufficient
coins"}`). The server's view of the account cannot be changed from the
client — the rewrite makes the *client* believe it is a member. Whether a
paid space really opens depends on whether the server hands out the
`playUrl` for it.
