# Famspace unlock — analysis + self-contained patched build

Target: `FamSpace.xapk` → `com.famspace.app.android` 1.0.8 (build 10008201),
minSdk 24 / targetSdk 36, Flutter app (Dart AOT) with 19 APK splits.

Everything below was derived from the shipped artifact with **REA** (JADX
provider + Dart-AOT inspector) and **Blutter** (Dart AOT snapshot → symbols
and AArch64 disassembly).  No server-side knowledge was assumed; each claim
is tied to something in the binary.

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
| `config.arm64_v8a.apk` | the 21 native libraries, **no resources at all** |
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
baseUrl -> the proxy itself (so the app keeps using it)
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

Rebuild for a different target (e.g. a PC-side proxy on the LAN):

```bash
python3 patch_libapp.py --in libapp.so --out libapp.patched.so --ip 192.168.1.50 --port 8888
./build_apk.sh /workspaces/app/pp/FamSpace.xapk libapp.patched.so output 192.168.1.50 8888
python3 proxy.py --port 8888 --public http://192.168.1.50:8888 -v
```

## 6. Optional: PC-side proxy or mitmproxy

`proxy.py` is the same proxy as a desktop process (forward + rewrite), and
`mitm_addon.py` applies the identical rules to live TLS traffic:

```bash
mitmdump -s mitm_addon.py --listen-host 0.0.0.0 --listen-port 8080
```

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

### If the wallet still shows the real balance

The proxy rewrites the server's answers, so the balance the app shows is the
rewritten one.  The log says exactly what happened:

```bash
# on the phone's browser
http://127.0.0.1:8888/__famunlock/log        # or :8888
http://127.0.0.1:8888/__famunlock/health      # which ports are up
```

| log line | meaning |
|---|---|
| `rewrote /api/v1/me/profile: [gems, coins, …]` | the rewrite fired; the following `body …` line shows the raw server answer, so the field names are visible |
| `no rule matched /api/v1/me/profile` | the answer arrived but carried no known wallet field — the `body …` line shows what the server actually sent |
| `not json …` / `body is not a JSON object …` | the answer was not plain JSON — the snippet shows what came back |

Every answer that mentions `coin`/`gem`/`vip`/`premium` is logged with its
body, so the wallet can be traced in any response, not only in the profile.

The rules cover `coins`, `gems`, `balance` and the usual variants
(`coin`, `availableCoins`, `totalGems`, `points`, …); costs (`coinsRequired`,
`coinCost`, …) become 0 while IAP prices are left alone.  If the log shows
`no rule matched` with a body that *does* contain the wallet under a name
that is not in the rules, that name goes into `Rules.FORCE` and the build is
one `./build_inapp.sh` away.


## 8. Limits of this build

* If the server withholds `playUrl` for an item you have not paid for, no
  client-side change can conjure one.  The helper's rewrite forces the
  wallet, the VIP state and the `canView`/lock flags, which covers the
  client-enforced lock; a server-enforced one would still stop playback.
* Requests are **not** modified: Famspace signs them, so altering a request
  body invalidates the signature and the server rejects it.  `balance` /
  `addedGems` in the IAP request are therefore only readable, not forgeable
  from here.
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
build_apk.sh       inject -> re-sign all splits -> repack the XAPK
build_helper.sh    build the on-device helper APK (aapt2 + d8 + apksigner)
helper/src/…       the helper app (LocalProxy, Rules, ProxyService, MainActivity)
test_local_proxy.sh  desktop test of the helper's proxy (14 fields verified)
proxy.py           desktop forwarding proxy + JSON rewriter
mitm_addon.py      same rules as a mitmproxy addon
pool_xref.py       find code that loads a given object-pool slot
aot_dis.py         annotate raw arm64 code the way Blutter does
install_on_phone.sh  adb install of everything
build_universal.sh  fold the split set into one installable APK
verify_apk.sh       the same checks as verify_build.sh, for the single APK
publish.sh          verify -> copy the build to the download folder -> verify
ANALYSIS.md        the full REA/Blutter findings
output/            FamSpace-unlocked.xapk, 19 signed APKs, FamspaceUnlock.apk
```
