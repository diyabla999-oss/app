# Famspace (com.famspace.app.android) — reverse engineering notes

Everything here comes from the shipped artifact only:
**REA** (JADX provider + Dart-AOT inspector) and **Blutter** (Dart AOT
snapshot reader → symbols, AArch64 disassembly, object pool).

---

## A. Artifact facts

```
package          com.famspace.app.android
version          1.0.8 (versionCode 10008201)
minSdk / target  24 / 36   (compileSdk 36, Android 16)
splits           base + config.{ar,arm64_v8a,de,en,es,fr,hi,in,it,ja,ko,my,pt,ru,th,tr,vi,xxhdpi,zh}
flavour          Flutter, Dart AOT, arm64 only
Dart SDK         3.10.9  (flags: product, compressed-pointers, dedup_instructions,
                           dwarf_stack_traces_mode, no-shared_data)
snapshot hash    1ce86630892e2dca9a8543fdb8ed8e22
libapp.so        11 731 888 bytes, sha256 78d61c87e9c1b7fee60aadf45fd07592e9aa09df1537ed2267afb08fe68b6cf6
classes          25 167   (JADX), 11 481 AOT functions (Blutter)
```

REA snapshot layout (from `inspect_dart_aot`):

```
_kDartVmSnapshotInstructions      off 3145728  size  92480
_kDartIsolateSnapshotInstructions  off 3238208  size 8492032   <- Dart code
_kDartVmSnapshotData              off      832  size  18256   kind 3
_kDartIsolateSnapshotData         off    19136  size 3089728   kind 3
```

## B. Permissions / components (REA `inspect_android_package`)

```
INTERNET  ACCESS_NETWORK_STATE  ACCESS_WIFI_STATE  WAKE_LOCK  VIBRATE
FOREGROUND_SERVICE  POST_NOTIFICATIONS
READ/WRITE_EXTERNAL_STORAGE (maxSdk 29)
com.android.vending.BILLING        com.android.vending.CHECK_LICENSE
com.google.android.gms.permission.AD_ID  ACCESS_ADSERVICES_{AD_ID,ATTRIBUTION,TOPICS}
com.google.android.c2dm.permission.RECEIVE  READ_GSERVICES
com.amazon.privacypass.ATTEST
com.famspace.app.android.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION (signature)
```

Single Flutter activity (`com.famspace.app.android.MainActivity`) with deep
links for `famspace://`, `https://famspace.us/s/`, `/spaces/`, `/aiVideo/`,
`/home`, `/game`.  Firebase (auth, messaging, crashlytics, sessions), ad
mediation (AppLovin MAX, Google, Pangle, Mintegral, Mbridge, Vungle, Unity,
Facebook Audience Network, InMobi, ByteDance, TikTok), Tencent COS SDK,
`flutter_soloud`, `media_kit`/`mpv` (player), `flutter_local_notifications`,
`jni` (Dart↔Java bridge), `mmkv` (local KV), Room, DataStore, WorkManager.

The manifest `<queries>` block explicitly probes for
`com.topjohnwu.magisk`, `eu.chainfire.supersu`, `com.noshufou.android.su`,
`com.koushikdutta.superuser`, `com.thirdparty.superuser` — the app expects
rooted devices to exist in its user base.

## C. Currency / premium (Blutter object pool)

Enum `CurrencyType { gems, coins }`.  Profile model keys, in pool order:

```
email nickname avatar provider providerUid createdAt
vipLv vipEnd sizeCap sizeCurr gems coins appAccountToken
```

Wallet UI strings (Arabic, directly referenced from `Myl.dart`):

```
"افتح هذا الفيديو مقابل " + " عملة" + " جوهرة"     -> "open this video for N coins/gems"
"تحتاج إلى " + " جوهرة · لديك "                    -> "you need X gems · you have Y"
" أفتح ... / شاهد الإعلانات ("                    -> rewarded ad / daily views
"تم تفعيل الاشتراك المميز"                        -> "Premium activated"
"تم التحقق من عملية الشراء وتحديث رصيد الجواهر."  -> "purchase verified, gem balance updated"
```

Premium feature strings (from `assets/images/…`): `premium_no_ads.svg`,
`premium_download.svg`, `premium_storage.svg`, `player_unlock_premium.png`,
`benefit_incognito.svg`, `benefit_all_download.svg`, `benefit_download.svg`
→ Premium unlocks ad-free playback, unlimited downloads and Incognito
playback (server endpoint `/api/v1/space/incognito/enable`).

## D. REST surface (47 routes, Blutter `pp.txt`)

```
/api/v1/me/profile                    wallet + VIP state
/api/v1/space/favorite/unlock          unlock one item with coins   <- the gate
/api/v1/space/file/list                items of a space
/api/v1/file/detail  /api/v1/file/list item detail (itemId, playUrl, canView, …)
/api/v1/reward/ad/claim                 rewarded ad -> coins
/api/v1/game/attempt/{create,info,settle}  match-3 mini game
/api/v1/game/progress  /api/v1/game/catalog
/api/v1/iap/google/verify              gem purchase  {purchaseToken, signedTransactionInfo, addedGems, balance, idempotent}
/api/v1/iap/apple/verify
/api/v1/sub/gp/verify                  subscription {receipt, currency, payFrom}
/api/v1/sub/apple/verify
/api/v1/wallet/flow/list               wallet history
/api/v1/space/{join,leave,create,delete,profile,list,update-perm}
/api/v1/space/incognito/{enable,disable}   premium feature
/api/v1/space/file/favorite
/api/v1/ai/video/{task/create,task/info,task/list,preset,template/*}
/api/v1/upload/{create-item,session}   COS upload
/api/v1/auth/{login/firebase,login/email,register/email,email/send-code,logout,password/reset}
/api/v1/user/{delete,nickname}   /api/v1/device/{sign-in,install,get-app-cou,deeplink/report}
/api/v1/config/{app,mode,mediation}    cloud config (contains "baseUrl")
/api/v1/push/{report-token,badge/clear,test}
/api/v1/recent/{file,file/list,file/delete,space,space/list,space/delete}
/api/v1/share/{info,link,ascribe}   /api/v1/report/content   /api/v1/usage
```

Unlock response contract (pool strings around `0x2c8d0`):

```
"alreadyUnlocked"
"Invalid favorite unlock response"
"Unlocked favorite file is missing"
"spaceId and itemId must not be empty"
"Favorite unlock is already in progress"
"favorite_unlock_coins_success"
```

## E. Hosts and how they are stored

```
https://famspace.disoms.net      27 bytes   service host (next to "useTestApi")
https://www.famspace.us           23 bytes   web host (next to "debugLogEnabled")
https://wt.famspace.us            22 bytes   Chatwoot support widget
http://190.92.202.202:9999        26 bytes   debug/test API (Jenkins build)
```

Config keys in the pool: `useTestApi`, `useDebugBucket`, `bucket`,
`useDebugCountry`, `app_user_country`, `debugLogEnabled`, `baseUrl`,
`debug_mode`, `session_id`, `analytics_cache`, `trc`, `first_open_time`,
`last_version_name`.

Dart stores these as plain ASCII in the read-only heap of `libapp.so`; the
string data area is packed back-to-back (string lengths live in a separate
table), so an **equal-length in-place replacement needs no header fix**.

## E2. The API crypto (solved)

Every payload is encrypted into `responseData`:

```
responseData = base64( ciphertext || index byte )
```

| part | how it was found |
|---|---|
| the **key table** | the Dart object pool of `libapp.so`, `pp+0x9230`: `Map<String, String>(16)` with the keys `a`…`f` and `0`…`9` and sixteen 16-character values.  It sits next to `pp+0x9258` (`"AES/"`) and `pp+0x9260` (`Map<jQ, String>` mapping the cipher-mode enum to `CBC`, `CTR`, `ECB`, `OFB-64`, `CFB-64`, `GCM`, `OFB-64/GCTR`, `SIC`) |
| the **framing** | every captured answer is `N × 16 + 1` bytes long; the trailing byte is the index into the table, the rest is the ciphertext |
| the **mode** | AES-128-**CBC**, and the **IV is the key itself** |
| the **padding** | the plaintext is the JSON followed by zero bytes up to the block boundary |

How the framing was pinned down, in order:

1. the key table gave sixteen candidate keys;
2. CBC makes the *second* block independent of the IV
   (`P2 = D(C2) XOR C1`), so the key could be confirmed from the second
   block alone — for `ai/task/list` the tail `l":0}` came out of the key
   `cZ1xV3bN5mK7lJ9h` (index `e`);
3. with the key known, the IV fell out of the algebra
   (`IV = D(C1) XOR P1`): the profile starts with `{"appAccountToken":"76`,
   and the recovered IV was the key;
4. two captured answers still did not decrypt — the reason was a **single
   mistyped character** in the base64 that had been copied out of the log by
   hand.  Both decrypt once the typo is corrected.

The numeric fields of the profile are read by the Dart model with

```
ldur  w4, [x3, #off]      ; the raw 32 bit value
sbfiz x0, w4, #1, #0x1f
cmp   w4, x0, asr #1
```

which is the Smi/Mint check — values below 2^29 stay small integers, larger
ones become 64 bit integers.  The server already sends `uid: 5342202629` and
`sizeCap: 2147483648` through the same path, so the rewritten
`coins: 999999999` is in the same class of value.

`vipEnd` is read as an **integer** (the model formats it as a date), so it is
rewritten to `2147483647` (2038-01-19) and not to a date string — a string
there would break the model's type cast.

## F. Where the analysis stands

* The wallet, the VIP level and the per-item `playUrl` are all produced by the
  server; the purchase paths carry Google-signed data
  (`signedTransactionInfo`), so they cannot be forged from the client.
* The unlock decision that the user sees ("you need X gems, you have Y") is
  computed in Dart (`Myl.dart`) from the server-supplied wallet, and the
  unlock itself is a server call to `/api/v1/space/favorite/unlock`.
* Consequently the only lever that produces a *working* unlock is to change
  what the client receives: the patched APK redirects the service hosts to a
  local proxy that rewrites the answers (see README).
* With the crypto solved the proxy decrypts every answer, rewrites the
  wallet inside it and encrypts it again — the client believes it is a
  member.  The server still decides whether a paid space really opens.

### Tooling used

```
REA 6.10.1 + jadx-headless-mcp 0.7.1 (sha256 verified) -> package, manifest,
     components, Dart AOT profile, identifier inventory
Blutter (github.com/worawit/blutter, Dart 3.10.9 build) -> object pool,
     per-library disassembly, pp.txt / objs.txt
ubiquitous: Blutter's asm dump does not contain the API service layer
     (classes eA/jA/Wz of libraries Vul/Zul/Nul have no dumped method bodies),
     so the endpoint strings are read from the object pool instead.
```
