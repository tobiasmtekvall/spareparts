# Spare Parts – Android app

Mobile client for the spare-parts inventory server in `../server`. Kotlin, Jetpack Compose, Material 3.

- **Sign in** (v2): everyone signs in with their own account. The server returns a token, the app keeps
  it between restarts and sends it as `X-Api-Key`. There is no free-text "your name" any more: the
  server records the signed-in username on every movement and in the audit log.
- **Scan** (start screen): continuous QR / DataMatrix / Code 128 / EAN… scanning with ML Kit, a **Read label**
  button that runs on-device text recognition on the current frame, a torch toggle and manual code entry.
  One match opens the part, several matches open a picker, and no match offers a manual search.
- **Search**: instant filter over the cached parts (part no, name, model, manufacturer, location, specs), with
  category chips, a low-stock filter and stock badges. Pull down to sync.
- **Low stock**: parts below their minimum, sorted by shortage.
- **Part detail**: stock card with −/+, Take, Return, Receive order and Set count (each with an optional
  reason), editable location, minimum and notes, price and stock value, order info, machine positions,
  specs, documentation links and server files, a share button and movement history. What is shown
  depends on the account's permissions (see below).
- **Offline first**: all parts are cached in `filesDir/parts-cache.json`. Barcode and label lookups run
  locally first (the same rules as the server's `/api/lookup`), and the server is only asked when nothing
  matches locally. Stock changes made offline are applied to the cache straight away, queued in
  `pending-adjustments.json` (each with a UUID), and sent with `POST /api/adjust` as soon as the network is
  back. WorkManager handles this with a network constraint, and the app also tries immediately. A banner
  shows when the app is offline or has changes waiting.
- **Sync**: the app pulls on start, on every return to the foreground, on pull-to-refresh and when the
  network comes back, using `GET /api/me` followed by `GET /api/parts?since=<version>`. Re-reading
  `/api/me` every sync means a role change on the server takes effect without reinstalling.

## Accounts and permissions

Sign-in screen: server address (kept from last time, plain `http://` is fine on a LAN), username,
password. Errors from the server are shown as they come ("Wrong username or password", "This account is
switched off", "Too many attempts - try again in 12 min"); if the server cannot be reached at all the app
says so instead. "Use a device token instead" swaps the username and password for a single token field –
that is the shared token from the web app (Accounts → Device token) for a scanner phone that several
people use.

If the account is flagged `must_change` (a password an administrator typed in), a **Choose a password**
screen blocks the way in: current, new and repeat, minimum 8 characters, sent with `POST /api/me`. A new
password ends every session on the server, so the app signs in again with it immediately and carries on
instead of throwing the person out. The same screen is reachable from Settings later.

The server enforces permissions; the app simply does not draw controls the account may not use:

| Missing permission | What disappears |
|---|---|
| `adjust` | the −/+ stepper and Take / Return / Receive / Set count. A **Read only** chip is shown instead |
| `edit_light` | location, notes and minimum become plain text (no edit pencil) |
| `edit`, `delete`, `files` | the app has no create, delete or upload controls, so nothing changes |

Roles map to permissions on the server: `viewer` = view, `staff` = view + adjust + edit_light + files,
`manager` = that plus edit, import and delete, `admin` = everything including accounts.

**Sessions.** The token is stored with DataStore and survives restarts. Any 401 from a normal call (token
expired, account switched off, password reset) drops the token and returns to the sign-in screen with
"Your session ended - please sign in again". The offline queue is deliberately **not** cleared: queued
stock changes stay on the phone and are sent after the next sign-in. Signing out from Settings warns when
changes are still waiting.

**Settings** shows who is signed in (name, username, role chip and what the role means), the server
address, "Change password", "Sign out", last sync time, cached parts, data version, pending changes,
"Sync now" and the theme switch.

## Build

Requirements: JDK 17+ (21 works), Android SDK with `platforms;android-35` and `build-tools;35.0.0`.

```bash
cd android
echo "sdk.dir=/path/to/android-sdk" > local.properties   # or set ANDROID_HOME
./gradlew assembleRelease        # -> app/build/outputs/apk/release/app-<abi>-release.apk (arm64 ≈ 12 MB)
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-<abi>-debug.apk
./gradlew testDebugUnitTest      # JVM unit tests (matching, queue, JSON, sign-in, permissions)
```

The release build is minified and runs noticeably smoother than the debug build. It is signed with the
local debug key, so it can be sideloaded as-is. To sign with your own key, replace
`signingConfig = signingConfigs.getByName("debug")` in `app/build.gradle.kts`.

Most of the APK size comes from the bundled ML Kit models, which work fully offline. The build makes one
APK per CPU type (`arm64-v8a` fits almost every phone from the last eight years, `armeabi-v7a` fits older
32-bit phones, `x86_64` is for the emulator) plus a `universal` APK that runs everywhere.

Optional live test against a running server (it makes stock changes and then undoes them, so the net
change is zero, but the changes show up in the movement log):

```bash
python3 ../server/app.py &                       # port 8765
./gradlew testDebugUnitTest -Pserver=http://localhost:8765 -Puser=admin -Ppass=...
```

`-Puser` / `-Ppass` (or `INVENTORY_USER` / `INVENTORY_PASSWORD`) pick the account; it must be one that
may book stock. The test signs in, checks that a call without a token and a call with a bogus token are
both refused with 401, then compares local and server lookups for every part number, model and MPN. It
also exercises adjust, the batch endpoint, the error for negative stock, movements (including that they
are attributed to the signed-in account), the low-stock list and PATCH.

The offline tests use MockWebServer for the sign-in call, the `X-Api-Key` header, the 401 that ends a
session without touching the queue, and the pure `perms` → UI mapping in `domain/Permissions.kt`.

## Install

```bash
adb install -r ../dist/SpareParts-arm64-v2.apk
```

You can also copy the APK to the phone and open it. You need to allow "install unknown apps" for the file
manager or browser you open it with.

## Configure

1. Start the server on a PC on the same network. It prints a line like
   `Network: http://192.168.1.20:8765  (use this in the Android app)`.
2. Create an account for each person in the web app (Accounts), or use an existing one.
3. On the phone, the app opens on the sign-in screen. Fill in the address above (`http://` may be left
   out), your username and your password, and tap **Sign in**. The first sync starts straight away.
4. For a shared scanner phone, make a device account in the web app, copy its token and use
   **Use a device token instead** on the sign-in screen.

Upgrading from v1: the old free-text name and API key fields are gone. The app asks for a sign-in once
after the update; anything queued offline is kept and sent afterwards.

The app allows plain HTTP (see `res/xml/network_security_config.xml`) because the server usually runs
on the LAN without TLS.

## Code map

| Path | What |
|---|---|
| `domain/PartMatcher.kt` | Offline lookup and search. Pure Kotlin, unit tested. |
| `data/ApiClient.kt` | OkHttp + kotlinx.serialization client for the server API (no Android dependencies). |
| `data/Repository.kt` | Cache, optimistic stock changes, offline queue, sync. |
| `data/SettingsStore.kt` | DataStore: server URL, last sync, data version, theme, and the stored session. |
| `data/Session.kt` | Account, token and `SessionManager` (sign-in, sign-out, 401 handling). Pure Kotlin. |
| `data/LocalStore.kt` | Crash-safe JSON files and `PendingQueue`, the offline queue. |
| `domain/Permissions.kt` | `perms` → what the UI offers. Pure Kotlin, unit tested. |
| `ui/auth` | Sign-in and "choose a password" screens. |
| `work/SyncWorker.kt` | WorkManager job that flushes the queue when the network is back. |
| `ui/scan` | CameraX + ML Kit scanner. |
| `ui/search`, `ui/detail`, `ui/settings` | The other screens. |

## Known limitations

- Editing location, minimum or notes needs a connection. Only stock changes are queued offline.
- Signing in needs a connection; there is no offline sign-in. A token already on the phone keeps working
  offline, with the cached parts and the queue.
- Queued changes are sent under the account that is signed in when they finally go out, and the server
  refuses them if that account may not book stock.
- If a queued change is rejected when it is sent (for example because someone else already took the last
  one), the app drops it and shows a message.
- Movement history, server files and server-side lookup are only available online.
- Text recognition uses the Latin-script model.
