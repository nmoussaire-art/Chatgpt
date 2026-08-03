# Installing BatteryCast Quant

Ready-to-install Android packages are in [`dist/`](dist/). Nothing needs to be
compiled — download an APK and install it.

| File | Size | Use this one when |
| --- | --- | --- |
| `dist/BatteryCastQuant-1.0.0-release.apk` | 1.3 MB | Normal install. Optimised and shrunk with R8. |
| `dist/BatteryCastQuant-1.0.0-debug.apk` | 18 MB | Fallback. No code shrinking, so it rules out any R8-related problem. |

Both packages are the same app and the same version (`1.0.0`, versionCode `1`).
They are signed with **different keys**, so Android treats them as conflicting:
install one or the other, and uninstall the first if you want to switch.

Requirements: Android 8.0 (API 26) or newer.

## Install from the phone

1. Open this repository on the phone, go to `dist/`, tap the APK, then tap the
   download (⬇) button. Tapping the file name shows a preview page rather than
   downloading the package.
2. Open the downloaded file from the notification shade or from **Files →
   Downloads**.
3. Android will ask to allow installs from that app (browser or file manager).
   Grant it, then go back and confirm the install.
4. Play Protect may show "Unsafe app blocked" or ask to scan. This is the
   standard warning for any app not installed from the Play Store — choose
   **Install anyway**.

## Install over USB from a computer

With `adb` available and USB debugging enabled on the phone:

```bash
adb install -r dist/BatteryCastQuant-1.0.0-release.apk
```

If that reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, a build signed with a
different key is already installed. Remove it first:

```bash
adb uninstall com.batterycast.quant
```

## Verifying the download

```bash
sha256sum -c dist/SHA256SUMS.txt
```

## Permissions the app asks for

The app has **no `INTERNET` permission**, so it cannot send data off the device.
The measurement pipeline reads battery telemetry locally and stores it in a
local Room database.

Two permissions are not granted by the normal install prompt and must be
enabled by hand if you want the features that depend on them:

- **Usage access** (`PACKAGE_USAGE_STATS`) — Settings → Apps → Special app
  access → Usage access → BatteryCast Quant. Feeds foreground-usage context
  into the drain model.
- **Calendar** (`READ_CALENDAR`) — used by the charge planner to pick up event
  times as charge targets.

Notifications, battery telemetry sampling, and the forecast itself work without
either of them.

## Signing keys

The release APK is signed with a self-signed key generated for this build. That
key is deliberately **not** committed, because this repository is public. The
practical consequence: a future rebuild signed with a new key cannot upgrade
this install in place — it needs an uninstall first. If you want a stable
upgrade path, generate a keystore once, keep it private, and reuse it:

```bash
keytool -genkeypair -v -keystore batterycast-release.jks \
  -alias batterycast -keyalg RSA -keysize 4096 -validity 10950
```

The debug APK is signed with the standard Android debug key, so debug builds
always upgrade each other cleanly.

## Rebuilding from source

The full project is in [`BatteryCastQuant/`](BatteryCastQuant/). With JDK 17 and
an Android SDK that has platform 35 and build-tools 35.0.0:

```bash
cd BatteryCastQuant
./gradlew :app:assembleDebug        # installable debug APK
./gradlew :app:assembleRelease      # unsigned release APK, sign it yourself
./gradlew :forecasting:test :app:testDebugUnitTest :app:lintDebug
```
