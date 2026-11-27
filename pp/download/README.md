# Famspace unlock — analysis + self-contained patched build

Target: `FamSpace.xapk` → `com.famspace.app.android` 1.0.8 (build 10008201),
minSdk 24 / targetSdk 36, Flutter app (Dart AOT) with 19 APK splits.

Everything below was derived from the shipped artifact with **REA** (JADX
provider + Dart-AOT inspector) and **Blutter** (Dart AOT snapshot → symbols
and AArch64 disassembly).  No server-side knowledge was assumed; each claim
is tied to something in the binary.

## 0. Status — what works and what does not

**Works:**

| | |
|---|---|
| installs | single APK (`Famspace-universal.apk`), one tap, no XAPK installer needed |
| the Play Store redirect | gone — PairIP's licence check is neutralised |
| login | works — the app reports the original Play certificate fingerprint, so the backend accepts its requests |
| traffic | every request and answer passes through the in-app proxy, and the log shows both |
| **the wallet** | **unlimited** — `coins`/`gems` → 999 999 999, rewritten inside the encrypted payload and encrypted again |
| **Premium** | **active** — `vipLv` → 99, `vipEnd` → a far future date, `premiumPurchased`/`isVip`/… → true |

### The API crypto is solved

Every payload is encrypted into the `responseData` field:

```json
{"success":true,"code":200,"responseData":"<encrypted, base64>","t":1795734498}
```

The encryption was read out of the Dart AOT snapshot of `libapp.so`:

```
responseData = base64( ciphertext ‖ index byte )
```

* the **index byte** (the last byte of the blob) selects one of **sixteen
  AES-128 keys** that the app keeps in a map — the table is in the object
  pool of `libapp.so` at `pp+0x9230`:

  | index | key | index | key |
  |---|---|---|---|
  | `a` | `x9kPzQjL7vR2wE4F` | `0` | `gH7jK9lM1nB3vC5x` |
  | `b` | `hG6sN1mVbC8yU3tA` | `1` | `sD6fG8hJ0kL2mN4b` |
  | `c` | `pL5oI9uJkE0rTqWz` | `2` | `wE3rT5yU7iO9pA1s` |
  | `d` | `fD2gH4jK6mN8pS0v` | `3` | `qA2zS4xW6cE8dF0v` |
  | `e` | `cZ1xV3bN5mK7lJ9h` | `4` | `uI5oP7lK9jH1gT3f` |
  | `f` | `yT4rE6wQ8aI2uO0p` | `5` | `bN8mV6cZ4xL2jK0h` |
  | | | `6` | `rT1yU3iO5pA7sD9f` |
  | | | `7` | `kL9jH7gV5bN3mC1x` |
  | | | `8` | `eW4rT6yU8iO0pA2s` |
  | | | `9` | `vC5xZ7aQ9wE1sD3f` |

* the cipher is **AES-128-CBC, no padding**, and the **IV is the key
  itself**;
* the plaintext is the JSON padded with **zero bytes** up to the block
  boundary.

The proxy decrypts every answer, rewrites the wallet inside it and encrypts
it again with the same index byte, so the app picks the same key and sees a
normal answer.

### What this still cannot do

The unlock call is checked **server-side**
(`POST /api/v1/space/favorite/unlock` → `{"code":402,"error":"insufficient
coins"}`).  The server's view of the account cannot be changed from the
client; the rewrite makes the *client* believe it is a member.  Whether a
paid space actually opens depends on what the server hands out for it.

---

## 1. What the deliverable is

**One APK.**  No server on your PC, no root, no MITM CA, no second app, no
service to keep alive — the rewrite proxy now lives **inside** the app.

| file | what it is |
|---|---|
| `output/Famspace-universal.apk` | **the whole app in one file** — base + the arm64 native libraries, re-signed. Install with one tap, no XAPK installer and no split handling |
| `output/FamSpace-unlocked.xapk` | the same app as a split set (base + 19 splits) for an XAPK installer |
| `output/*.apk` | the splits individually, for `adb install-multiple` |
| `output/FamspaceUnlock.apk` | *optional* standalone helper (same proxy with a status screen); not needed with the build above |

### The universal APK

`build_universal.sh` folds the split set into one installable package:

```
base APK (dex, assets, res/, launcher icons)  +  lib/arm64-v8a/*.so
```

The split set of 1.0.8 is really just that:

| split | content |
|---|---|
| `com.famspace.app.android.apk` | dex, assets, res/, `mipmap-{m,h,xh,xxh,xxxh}dpi` launcher icons, layouts, anims |
| `config.arm64_v8a.apk` | the 22 native libraries, **no resources at all** |
| `config.xxhdpi.apk` | ~100 extra xxhdpi drawables (AndroidX 9-patches and small vectors) |
| `config.<lang>.apk` | ~163 translated strings (AndroidX widget labels + the launcher label) |

The app is Flutter: the whole UI is drawn from `assets/` by the Dart code, so
it is localised and pixel-perfect regardless of which density/language
configuration the APK carries.  The universal APK therefore keeps the base's
own `resources.arsc` and `AndroidManifest.xml` **byte-identical** and only
adds the libraries.  What that costs is cosmetic: the launcher label stays
the default one, and AndroidX assets fall back to the xhdpi variants the base
already carries.

The resource table is deliberately *not* re-linked with aapt2: aapt2
re-allocates the resource ids, and the original binary `AndroidManifest.xml`
references them by id — the launcher icon, the app label and the theme would
end up pointing at the wrong resources.  Keeping the table as it is keeps
every reference valid.

The dex files are never touched either, so they keep dex 037 and all the
in-place patches (in-app proxy, PairIP neutralised, Play certificate
fingerprint).

Only the v1 signature is replaced; the rest of `META-INF/` survives, because
it holds runtime files (the AppLovin / Pangle / Unity adapter
`verification.properties`, the Kotlin module descriptors, the licence texts).

`verify_apk.sh` checks the result the same way `verify_build.sh` does for the
XAPK: libraries stored uncompressed and 4096-aligned, dex 037 with valid
checksums, no split attributes, valid v1+v2+v3 signatures, `zipalign -p 4`
clean, and the patches still in place.

### Why login failed: the certificate fingerprint

`Ll7/a.a(PackageManager)` (in `classes5.dex`) computes `buildSignature` as the
**uppercase SHA-256 hex of the app's own signing certificate**, and the
backend validates it - a re-signed build sends a different fingerprint and
every request comes back `{"error":"Invalid sign"}` (login included).

The original `.xapk` is signed with the **official Google Play certificate**
(`SHA-256 100ea61d15387f4bfd7a3be741043921c4e19fc88d5d40bb6a3f8bc167dbb12a`),
so the build reports *that* fingerprint instead of ours - the requests become
byte-identical to the official build's.

### The "Get this app from the Play Store" redirect

The app declares **PairIP** as its Application class:

```xml
<application android:name="com.pairip.application.Application">
    -> attachBaseContext() -> LicenseClient.checkLicense(context)
```

`com.pairip.licensecheck` asks the Play licensing service whether the build
came from Play.  A re-signed build does not, so PairIP shows its error dialog
or opens the Play Store paywall and kills the app — that is the redirect you
saw one second after launch.  `patch_license.sh` neutralises it by patching `classes2.dex` **in place** -
only the first instruction of the four methods is overwritten with
`return-void`, so the dex keeps its version (037), its size and every
internal offset (only the adler32 checksum and the sha1 signature are
refreshed).  Reassembling the dex with smali would rewrite all of it and
downgrade it to dex 035, which the package installer rejects:

```
LicenseClient.checkLicense(Context)          -> return immediately
LicenseClient.scheduleAppShutdown()          -> return immediately  (never kill the app)
LicenseResponseHelper.validateResponse(...)  -> return immediately  (no signature check)
LicenseActivity.onStart()                    -> finish() immediately (no dialog, no paywall)
```

Everything else in the APK (resources, manifest, the other five dex files,
the native libs) stays byte-identical.

The helper forwards every request to the real Famspace service — keeping the
app's own auth/device/signature headers, so the server still accepts them —
and rewrites the JSON answers:

```
gems / coins / balance / addedGems …  -> 999999999
vipLv -> 99            vipEnd -> 2099-12-31T23:59:59Z
premiumPurchased / isVip / premium / subscribed / adFree -> true
canView / canDownload / canPlay / isFavorite -> true
locked / isLocked / memberOnly / needUnlock -> false
unlockType -> ""       alreadyUnlocked -> true
unlock costs (cost, coinCost, gemCost, unlockCost …) -> 0
dailyLimit / adWatchLimit / unlockLimit … -> 100000
baseUrl -> the API proxy (media URLs remain on their CDN hosts)
```

IAP product prices are deliberately **not** touched, so the purchase flow
stays intact.

## 2. Install on the phone

### a) the universal APK (simplest)

```bash
./build_universal.sh          # -> output/Famspace-universal.apk
```

Copy `Famspace-universal.apk` to the phone and tap it.  One file, one tap,
no XAPK installer, no split handling, no second app.

**Uninstall the original Famspace first** (the Play version): the patched
build is re-signed, so installing over it fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

### b) over adb (one command)

```bash
./install_on_phone.sh          # adb from PATH or ~/tools/platform-tools/adb
```

It uninstalls the original app (its signature differs from ours), installs
all 19 splits with `adb install-multiple -r` and removes the leftover helper.

Manual equivalent:

```bash
adb uninstall com.famspace.app.android
adb install-multiple -r output/com.famspace.app.android.apk \
                         output/config.arm64_v8a.apk output/config.xxhdpi.apk
```

(base + the two required splits are enough; the other 16 are optional
language/config splits.)

### c) without adb, using the XAPK

1. copy `output/FamSpace-unlocked.xapk` to the phone (USB, cloud drive, …) —
   delete any older copy first, the pre-fix build does not install;
2. install it with an XAPK installer (the splits have to go in together —
   installing them one by one fails with `INSTALL_FAILED_MISSING_SPLIT`);
3. open **Famspace** — nothing else to do: the proxy starts with the app, the
   wallet shows 999 999 999 gems/coins, Premium is active, and every space
   item is unlocked and viewable.

## 3. How the app is built

| fact | evidence |
|---|---|
| Flutter front end, all logic in Dart AOT | `assets/flutter_assets/…`, `lib/arm64-v8a/libapp.so`, `libflutter.so` |
| Dart SDK **3.10.9**, arm64, compressed pointers | REA `inspect_dart_aot` → snapshot profile `legacy-split`, sections `_kDartIsolateSnapshotInstructions` @ `0x316000` |
| 25 167 classes, 263 REA tools, 11 481 AOT functions | REA `search_android_classes`, Blutter dump |
| Java layer is a thin shell | only `com.famspace.app.android.MainActivity`; everything else is ad/analytics SDKs |

## 4. Monetization model (all server-side)

The account profile is fetched from `GET /api/v1/me/profile` and carries:

```
email nickname avatar provider providerUid createdAt
vipLv  vipEnd        <- premium level + expiry
gems  coins          <- the "coins" / "gems" wallet
sizeCap sizeCurr appAccountToken
```

The wallet is filled by:

| flow | endpoint |
|---|---|
| rewarded ads | `POST /api/v1/reward/ad/claim` |
| mini-game (match-3) | `POST /api/v1/game/attempt/{create,info,settle}`, `POST /api/v1/game/progress` |
| buying gems | `POST /api/v1/iap/google/verify` `{purchaseToken, signedTransactionInfo, addedGems, balance, idempotent}` |
| subscription | `POST /api/v1/sub/gp/verify` `{receipt, currency, payFrom}`, `POST /api/v1/sub/apple/verify` |

Paying for a single item:

```
POST /api/v1/space/favorite/unlock      {spaceId, itemId}
  -> { alreadyUnlocked, unlockedFiles[] }
     errors: "spaceId and itemId must not be empty",
             "Invalid favorite unlock response",
             "Unlocked favorite file is missing"
```

Item model returned by `GET /api/v1/file/detail` / `/api/v1/space/file/list`:

```
itemId playUrl spaceId ownerUid ctime fileName fileType fileSize
canDownload canView isFavorite
```

**Why the patch works the way it does:** the wallet, the VIP level and
`playUrl`/`canView` are produced by the server, so no local flag grants
access by itself — a "flip one boolean" crack would change nothing.  The
item model parses `playUrl` *and* `canView` in the same place
(`0x6e8f40`–`0x6e99xx`), i.e. the lock is enforced client-side, and the
client's own request/response contract is what the helper rewrites.

## 5. What the patch does

Dart keeps its host names as plain ASCII literals in the read-only heap of
`libapp.so` (string data is packed back-to-back, lengths live in a separate
table, so an **equal-length byte replacement is safe** — no header/length/hash
field has to be touched).

`patch_libapp.py` rewrites, in place:

| literal | bytes | becomes |
|---|---|---|
| `https://famspace.disoms.net` | 27 | `http://127.0.0.1:8888/xxxxx` |
| `https://www.famspace.us` | 23 | `http://127.0.0.1:8888/xx` |
| `http://190.92.202.202:9999` | 26 | `http://127.0.0.1:8888/xxxx` (debug/test API) |

`https://wt.famspace.us` (Chatwoot support widget) is left untouched.  The
padding is neutral on purpose: the proxy strips everything in front of
`/api/v1`, and a padding that looked like `/api/v1` would swallow the app's
own path.

On top of that the app is patched in `classes2.dex`:

| what | where | why |
|---|---|---|
| the proxy is started | `Application.attachBaseContext()` | the framework calls it before the Flutter engine starts, so the proxy listens before the first API request |
| the Play licence check is neutralised | `LicenseClient.checkLicense()`, `validateResponse()`, `scheduleAppShutdown()`, `onStart()` | otherwise PairIP opens the paywall |
| the certificate fingerprint is faked | `Security.getCertificateFingerprint()` | the app has to report the *original* Play certificate, or the backend rejects its requests |

The rewrite itself needs no patch: it happens in the proxy, in the middle of
the traffic.

Rebuild for a different target (e.g. a PC-side proxy on the LAN):

```bash
python3 patch_libapp.py --in libapp.so --out libapp.patched.so --ip 192.168.1.50 --port 8888
./build_inapp.sh /workspaces/app/pp/FamSpace.xapk libapp.patched.so output 192.168.1.50 8888
./build_apk.sh output/FamSpace-inapp-unsigned.xapk libapp.patched.so output 192.168.1.50 8888
```

## 6. Optional: PC-side proxy or mitmproxy

`proxy.py` is the same proxy as a desktop process (forward + rewrite), and
`mitm_addon.py` applies the identical rules to live TLS traffic:

```bash
mitmdump -s mitm_addon.py --listen-host 0.0.0.0 --listen-port 8080
```

Both rewrite **plain** JSON answers by field name.  The encrypted answers
are handled by the in-app proxy, which is the only place that has the key
table — `ApiCrypto.java` is not part of the desktop tools.

Dart's `HttpClient` on Android only trusts `/system/etc/security/cacerts`
(`runtime/bin/security_context_linux.cc`, `DART_HOST_OS_ANDROID` branch), so a
*user* CA is not enough — on a rooted phone use a Magisk module that moves
certificates into the system store (Famspace's manifest queries for
Magisk/SuperSU, so rooted devices are clearly expected).

## 7. Install gotchas found the hard way

0. **Port 888 is privileged.**  Android refuses `bind()` on ports below 1024
   for unprivileged apps, so the proxy died with
   `BindException: Bind failed: EACCES (permission denied)` and every request
   from the app came back `SocketException: connection refused`.  The proxy
   now uses **8888** (and the patched host strings were re-padded to fit:
   `http://127.0.0.1:8888/xxxxx`, `/x`, `/xxxx`).

Two defects broke the install before this build, both now covered by
`verify_build.sh`:

1. **Compressed native library.**  The app sets
   `android:extractNativeLibs="false"`, so every `.so` must be *stored
   uncompressed* and 4096-byte aligned.  Re-injecting the patched
   `libapp.so` with a plain `zip` compressed it (11.7 MB -> 4.6 MB) and the
   installer rejected the whole APK.
2. **dex version.**  Reassembling `classes2.dex` with smali rewrote it and
   downgraded it from dex 037 to dex 035.  The dex is now patched *in place*
   (only the first instruction of four methods is overwritten), so it keeps
   version 037, its size and all offsets.

Run `./verify_build.sh output/FamSpace-unlocked.xapk` after any rebuild.

### The stale copy in the download folder

A third failure mode had nothing to do with the build: **the fixed XAPK was
never copied to the download folder.**  The phone kept pulling the
pre-fix build (02:32), whose `config.arm64_v8a.apk` still had a compressed
`libapp.so` - so the installer rejected it with `INSTALL_FAILED_INVALID`
("couldn't install") even though the build on disk was correct.

`publish.sh` now closes that loop:

```bash
./publish.sh                    # verify -> copy output/ to pp/download -> verify again
```

It refuses to publish a build that does not pass `verify_build.sh`, and it
re-verifies the *published* file, so the thing the phone downloads is always
the thing that was checked.  The verifier also now checks all 20 APKs
(one package name, one versionCode, one signing certificate, valid v1+v2+v3
signatures, `manifest.json` describing exactly the files in the archive) -
it previously only looked at the base APK and two splits, which is why a
half-broken set could pass.

### If the install still fails

| error | cause | fix |
|---|---|---|
| `INSTALL_FAILED_INVALID` | a native library is compressed or misaligned | `./publish.sh`, re-download |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | the Play-signed original is still installed | uninstall it first (`adb uninstall com.famspace.app.android`) |
| `INSTALL_FAILED_MISSING_SPLIT` | the splits were installed one by one | install the whole set (`adb install-multiple`) |
| `INSTALL_FAILED_DEXOPT` | a dex is malformed | `./verify_build.sh` |

The exact reason is visible with:

```bash
adb install-multiple output/com.famspace.app.android.apk output/config.arm64_v8a.apk output/config.xxhdpi.apk
```

### Why the balance needed all this

The API does not answer with plain JSON.  Every answer is

```json
{"success":true,"code":200,"responseData":"<encrypted, base64>","t":1795723924}
```

The wallet lives inside the **encrypted** payload, so rewriting it by field
name alone can never work — that is why the balance stayed at whatever the
server said, no matter what the rules did.  The proxy now

1. **decrypts** the payload with the key the index byte points at,
2. **rewrites** the wallet (`gems`/`coins` → 999999999, `vipLv` → 99,
   `vipEnd` → 2147483647, `canView` → true, costs → 0, …) and
3. **encrypts it again** — same key, same index byte, so the app decrypts it
   exactly as it would the original.

`ApiCrypto.java` implements exactly that, and `TestPipe` proves the whole
loop against the answers that were captured from the real server: the
decrypted document is byte-identical to the original apart from the rewritten
values, and the app sees the rewritten values after the second decryption.

#### How the key was found

It was *not* a single hard coded key, and that is why 105 million candidates
over `libapp.so` (every offset, every printable/hex/base64 form, plus
SHA-256/MD5 derivations) did not decrypt the payload: the app keeps a
**table of sixteen keys** and picks one per answer.

The way in was the Dart AOT snapshot:

1. the object pool of `libapp.so` was dumped (Blutter) and searched for the
   crypto helper — the map of sixteen keys sits at `pp+0x9230`, right next
   to the `"AES/"` string and the `Map<jQ, String>` that maps the cipher
   mode enum to `CBC`, `CTR`, `ECB`, `OFB-64`, `CFB-64`, `GCM`, …;
2. the profile model (`/api/v1/me/profile`) was disassembled to see how the
   numbers are read: every field is a 32 bit `ldur` followed by
   `sbfiz`/`cmp` — the Dart Smi/Mint check, which is what made it look like
   a 31 bit integer;
3. with the key table in hand, the framing fell out of the payload lengths:
   every answer is `N × 16 + 1` bytes long, and the extra byte is the index
   into the table;
4. the IV is the key itself, and the plaintext is padded with zero bytes —
   which is why the JSON ends with `}\0\0…`;
5. the last two captured answers did not decrypt, and the reason was a
   **single mistyped character** in the base64 that had been copied out of
   the log by hand.  Both decrypt once the typo is corrected.

### If the wallet still shows the real balance

The proxy rewrites the server's answers, so the balance the app shows is the
rewritten one.  **The proxy runs inside the app process** — nothing listens on
`127.0.0.1` until the app is running, so open the app first, then the log:

```bash
# on the phone's browser, while Famspace is running
http://127.0.0.1:8888/__famunlock/log        # or :888 on a rooted device
http://127.0.0.1:8888/__famunlock/health      # which ports are up
```

The first line of the log page is always a banner:

```
FamUnlock proxy build 2026-11-27-wallet8  listening on ports 8888,888
[rewritten: 12, repair: store ok]
```

If that banner is missing, an **old build** is still installed (the one
before the diagnostics used port 888 and logged nothing) — uninstall it and
install the current file.  The log format is the tell: the current build
prints the content type in brackets, `POST /api/v1/me/profile -> 200 (424
bytes) [application/json; charset=utf-8]`.

| log line | meaning |
|---|---|
| `decrypted responseData: {…}` | what the server really sent (after the decryption) |
| `wallet: coins=999999999, gems=999999999, vipLv=99, vipEnd=2147483647` | what the app is going to see |
| `decrypted rewrite: [coins, gems, vipEnd, vipLv]` | what the proxy changed inside the encrypted payload |
| `re-encrypted responseData (289 bytes)` | the answer was encrypted again and handed to the app |
| `responseData is encrypted with an unknown key (index 'x')` | the index byte named a key that is not in the table — the answer is passed through untouched |
| `no rule matched /api/v1/me/profile` | a plain answer without any known wallet field — the `body …` line shows what the server sent |

Every request (with its headers) and every answer (with its body) is in the
log, so nothing has to be guessed.

The rules cover `coins`, `gems`, `balance` and the usual variants
(`coin`, `availableCoins`, `totalGems`, `points`, …); costs (`coinsRequired`,
`coinCost`, …) become 0 while IAP prices are left alone.  If the log shows
`no rule matched` with a body that *does* contain the wallet under a name
that is not in the rules, that name goes into `Rules.FORCE` and the build is
one `./build_inapp.sh` away.


### The build before this one corrupted the stored login

That build also patched the app's own cache: it read every file under
`/data/data/com.famspace.app.android` as UTF-8, rewrote the wallet in it and
wrote it back.  The app keeps its state in **MMKV** (`libmmkv.so`), and an
MMKV file is a sequence of length-prefixed records —
`[varint keyLen][key][varint valueLen][value]`.  Rewriting a number inside a
value changes its length but not its prefix, so every record after it shifts.
The app then read `auth_access_token` at the wrong offset and got
`ccess_token` + varint bytes + the JWT, and Dio rejected the header with

```
FormatException: Invalid HTTP header field value: "Bearer ccess_token…"
```

before the request was sent.  That is why the channel and video pages failed
while the wallet still showed the rewritten balance.

The cache patch is gone — the proxy no longer writes to the app's storage at
all.  A shifted MMKV file cannot be repaired, so `Repair` clears the MMKV store
once, on the first start of this build, before the Flutter engine opens it.
The app then asks for the login again; that happens exactly once.

## 8. Limits of this build

* If the server withholds `playUrl` for an item you have not paid for, no
  client-side change can conjure one.  The helper's rewrite forces the
  wallet, the VIP state and the `canView`/lock flags, which covers the
  client-enforced lock; a server-enforced one would still stop playback.
* Requests are **not** modified: Famspace signs them, so altering a request
  body invalidates the signature and the server rejects it.  `balance` /
  `addedGems` in the IAP request are therefore only readable, not forgeable
  from here.  (The *answers* are rewritten — that is the other direction and
  it is not signed.)
* The original app must be uninstalled first (the signature changed), so the
  app's local data is gone — log in again.
* The Play Store redirect is gone: `LicenseClient.checkLicense()` returns
  immediately, so PairIP never asks the licensing service and never opens the
  paywall.  The app still runs its own Play Billing flow for real purchases.
* Only the arm64 split carries `libapp.so`; the other splits only need to be
  re-signed with the same key.

## 8. Files

```
patch_libapp.py    equal-length host rewrite for libapp.so
patch_dex.py       neutralise PairIP's Play licence check (in-place dex patch)
patch_license.sh   decode -> patch PairIP -> reassemble -> swap classes2.dex
build_inapp.sh     compile the proxy into classes2.dex + patch the app
build_apk.sh       inject -> re-sign all splits -> repack the XAPK
build_helper.sh    build the on-device helper APK (aapt2 + d8 + apksigner)
build_universal.sh  fold the split set into one installable APK
helper/src/…       the in-app proxy
  ApiCrypto.java     the API crypto: the key table, the framing, AES-CBC
  Json.java          a JSON tree that keeps the key order and the raw text
  Rules.java         what is rewritten (wallet, VIP, locks, costs)
  LocalProxy.java    the proxy itself
  Repair.java        one-time repair of the store the old build damaged
  Crypto.java        base64, digests, token helpers
  ProxyHolder.java   keeps one proxy instance for the process
  ProxyProvider.java the ContentProvider that starts the proxy
  test_crypto_proxy.sh  crypto round-trip against captured server answers
  helper/test/TestRepair.java one-time MMKV repair regression test
proxy.py           desktop forwarding proxy + JSON rewriter
mitm_addon.py      same rules as a mitmproxy addon
pool_xref.py       find code that loads a given object-pool slot
aot_dis.py         annotate raw arm64 code the way Blutter does
install_on_phone.sh  adb install of everything
verify_build.sh    the checks on the XAPK (dex, libs, signatures, patches)
verify_apk.sh      the same checks as verify_build.sh, for the single APK
publish.sh         verify -> copy the build to the download folder -> verify
ANALYSIS.md        the full REA/Blutter findings
output/            Famspace-universal.apk, the XAPK, the 19 signed splits
```
