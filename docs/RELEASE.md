# Signed release-build procedure

The release build is configured so that signing material never enters version control and no debug
key can be silently substituted for a release one.

---

## 1. Create a keystore

Once, and keep it somewhere safe and backed up. Losing it means you can never update the app.

```bash
keytool -genkeypair -v \
  -keystore batterycast-release.jks \
  -alias batterycast \
  -keyalg RSA -keysize 4096 -validity 10000
```

Store the file **outside the repository**. `*.jks`, `*.keystore` and `keystore.properties` are all
git-ignored, but the safest place is somewhere the repository cannot reach at all.

---

## 2. Provide the credentials

Either method works; the build checks the properties file first, then the environment.

### Option A — `keystore.properties` (local development)

Create it in the repository root. It is git-ignored.

```properties
storeFile=/absolute/path/to/batterycast-release.jks
storePassword=…
keyAlias=batterycast
keyPassword=…
```

### Option B — environment variables (CI)

```bash
export BATTERYCAST_STORE_FILE=/absolute/path/to/batterycast-release.jks
export BATTERYCAST_STORE_PASSWORD=…
export BATTERYCAST_KEY_ALIAS=batterycast
export BATTERYCAST_KEY_PASSWORD=…
```

**If neither is present, the release build still succeeds and produces an unsigned APK.** It does
not fall back to the debug key. An unsigned artefact is obviously unsigned; a debug-signed one
pretending to be a release is not.

---

## 3. Build

```bash
export ANDROID_HOME=/path/to/android-sdk

# Full verification first: unit tests plus all three production-purity gates.
./gradlew :app:check

./gradlew :app:assembleRelease        # APK
./gradlew :app:bundleRelease          # AAB, for Play
```

Outputs:

```
app/build/outputs/apk/release/app-release.apk        (signed, if credentials were provided)
app/build/outputs/apk/release/app-release-unsigned.apk  (otherwise)
app/build/outputs/bundle/release/app-release.aab
```

The release build enables R8 with resource shrinking. The APK is around **1.5 MB**.

---

## 4. Verify before publishing

Three checks, all of which should be part of your release routine.

### The signature

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs --verbose \
  app/build/outputs/apk/release/app-release.apk
```

Expect v2, v3 and v4 signatures and no v1. v1 (JAR signing) is disabled deliberately: `minSdk` is
26, so every supported device understands v2 and above.

### The permissions

The offline guarantee is the app's central privacy claim, so verify it on the actual artefact
rather than trusting the source:

```bash
$ANDROID_HOME/build-tools/35.0.0/aapt2 dump permissions \
  app/build/outputs/apk/release/app-release.apk
```

Expected, and nothing else:

```
android.permission.RECEIVE_BOOT_COMPLETED
android.permission.ACCESS_NETWORK_STATE
android.permission.POST_NOTIFICATIONS
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_SPECIAL_USE
android.permission.READ_CALENDAR
android.permission.PACKAGE_USAGE_STATS
android.permission.WAKE_LOCK                    ← declared by WorkManager, never acquired by this app
<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION  ← added by androidx.core
```

**`android.permission.INTERNET` must not appear.** If it does, a dependency has introduced it and
the manifest's removal directive has been broken; `verifyNoNetworkPermission` would normally have
failed the build first.

### The production-purity report

```bash
cat app/build/reports/production-purity/*.txt
```

All three lines should read `PASS`.

---

## 5. Play Console notes

**Foreground service type.** The app declares `specialUse` for the precision session, which
requires a declaration in Play Console. The justification is the one in the manifest property:

> Time-boxed high-cadence battery measurement session started by the user.

Points worth making in the declaration: the service never starts on its own, shows an ongoing
notification for its entire life, is hard-capped at 20 minutes, stops itself, and is
`START_NOT_STICKY`.

**`PACKAGE_USAGE_STATS`.** Declared but optional and granted only from system settings. The Data
Safety form should state that usage data is processed **on-device only**, is **not** transmitted,
and that only aggregate foreground time and a broad app category are retained — never package names.

**Data Safety form.** The honest answers are: no data collected, no data shared, no data
transmitted off the device. The absence of `INTERNET` supports every one of them.

---

## 6. Versioning

Version code and name live in `app/build.gradle.kts`:

```kotlin
versionCode = 1
versionName = "1.0.0"
```

Debug builds carry the `.debug` application-id suffix and a `-debug` version-name suffix, so a debug
and a release build can be installed side by side on one device — useful when comparing a change
against the previous behaviour on the same battery.
