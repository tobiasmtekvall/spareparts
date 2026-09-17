# Spare Parts – Android app

Mobile client for the spare-parts inventory server in `../server`. Kotlin, Jetpack Compose, Material 3.

- **Scan** (start screen): continuous QR / DataMatrix / Code 128 / EAN… scanning with ML Kit, a **Read label**
  button that runs on-device text recognition on the current frame, a torch toggle and manual code entry.
  One match opens the part, several matches open a picker, and no match offers a manual search.
- **Search**: instant filter over the cached parts (part no, name, model, manufacturer, location, specs), with
  category chips, a low-stock filter and stock badges. Pull down to sync.
- **Low stock**: parts below their minimum, sorted by shortage.
- **Part detail**: stock card with −/+, Take, Return, Receive order and Set count (each with an optional
  reason), editable location, minimum and notes, price and stock value, order info, machine positions,
  specs, documentation links and server files, a share button and movement history.
- **Offline first**: all parts are cached in `filesDir/parts-cache.json`. Barcode and label lookups run
  locally first (the same rules as the server's `/api/lookup`), and the server is only asked when nothing
  matches locally. Stock changes made offline are applied to the cache straight away, queued in
  `pending-adjustments.json` (each with a UUID), and sent with `POST /api/adjust` as soon as the network is
  back. WorkManager handles this with a network constraint, and the app also tries immediately. A banner
  shows when the app is offline or has changes waiting.
- **Sync**: the app pulls on start, on every return to the foreground, on pull-to-refresh and when the
  network comes back, using `GET /api/parts?since=<version>`.

## Build

Requirements: JDK 17+ (21 works), Android SDK with `platforms;android-35` and `build-tools;35.0.0`.

```bash
cd android
echo "sdk.dir=/path/to/android-sdk" > local.properties   # or set ANDROID_HOME
./gradlew assembleRelease        # -> app/build/outputs/apk/release/app-<abi>-release.apk (arm64 ≈ 12 MB)
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-<abi>-debug.apk
./gradlew testDebugUnitTest      # JVM unit tests (matching logic, queue, JSON parsing)
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
./gradlew testDebugUnitTest -Pserver=http://localhost:8765
```

This test compares local and server lookups for every part number, model and MPN. It also exercises
adjust, the batch endpoint, the error for negative stock, movements, the low-stock list and PATCH.

## Install

```bash
adb install -r ../apk/SpareParts-arm64.apk
```

You can also copy the APK to the phone and open it. You need to allow "install unknown apps" for the file
manager or browser you open it with.

## Configure

1. Start the server on a PC on the same network. It prints a line like
   `Network: http://192.168.1.20:8765  (use this in the Android app)`.
2. In the app, open **Settings** and fill in:
   - **Server URL**: the address above. You can leave out `http://`.
   - **API key**: only needed if `api_key` is set in `config.json`. It is sent as `X-Api-Key`.
   - **Your name**: sent as `X-User` and saved with every stock change.
3. Tap **Test connection**, then **Save**. Saving starts a full sync.

The app allows plain HTTP (see `res/xml/network_security_config.xml`) because the server usually runs
on the LAN without TLS.

## Code map

| Path | What |
|---|---|
| `domain/PartMatcher.kt` | Offline lookup and search. Pure Kotlin, unit tested. |
| `data/ApiClient.kt` | OkHttp + kotlinx.serialization client for the server API (no Android dependencies). |
| `data/Repository.kt` | Cache, optimistic stock changes, offline queue, sync. |
| `data/SettingsStore.kt` | DataStore: server URL, key, user, last sync, data version, theme. |
| `work/SyncWorker.kt` | WorkManager job that flushes the queue when the network is back. |
| `ui/scan` | CameraX + ML Kit scanner. |
| `ui/search`, `ui/detail`, `ui/settings` | The other screens. |

## Known limitations

- Editing location, minimum or notes needs a connection. Only stock changes are queued offline.
- If a queued change is rejected when it is sent (for example because someone else already took the last
  one), the app drops it and shows a message.
- Movement history, server files and server-side lookup are only available online.
- Text recognition uses the Latin-script model.
