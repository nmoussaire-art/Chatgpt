# Build status

## Built and verified

The Android build was carried out with JDK 17, Android SDK platform 35 and
build-tools 35.0.0, using the bundled Gradle wrapper (Gradle 8.11.1).

- `:app:assembleDebug` — succeeded. Output: `app-debug.apk` (18 MB).
- `:app:assembleRelease` — succeeded, including R8 minification and resource
  shrinking. Output: 1.3 MB, zipaligned and signed with a self-signed key
  (APK Signature Scheme v2 and v3; v1 is unnecessary at `minSdk 26`).
- `:forecasting:test` — 22 tests, 0 failures.
- `:app:testDebugUnitTest` — 2 tests, 0 failures.
- `:app:lintDebug` and `:app:lintVitalRelease` — 0 errors, with
  `abortOnError = true`.
- `verifyProductionDataBoundary` — passed as part of `preBuild`.
- Built artifact confirmed to declare no `android.permission.INTERNET`.
- R8 output confirmed to retain the Room and Hilt generated classes
  (`BatteryDatabase_Impl`, `BatteryObservationWorker`, `BatteryCastApplication`,
  `PrecisionMeasurementService`, `MainActivity`).

Both APKs are published under `dist/` at the repository root; see `INSTALL.md`.

## Source fixes required to reach a green build

The sources as originally packaged did not compile under Gradle. Five defects
were fixed:

1. Five cross-module smart casts on nullable `public` properties
   (`thermalStatus` twice, `temperatureDeciC`, `latestSafeStart`,
   `recommendedDurationMinutes`, plus `medianAtTarget` in a test). These compile
   when `core-model` and `forecasting` are treated as a single compilation unit,
   but Kotlin rejects them once the modules are compiled separately, which is
   what Gradle does. Rewritten as elvis defaults or local values; behaviour is
   unchanged.
2. `BatteryManager.BATTERY_PROPERTY_CHARGING_POLICY` does not exist in the
   public SDK (checked against both `android-35` and `android-36`). The read was
   removed and `chargingPolicy` is recorded as unknown. The field is stored and
   exported but never consumed by the forecasting model, so no quantitative
   output changes.
3. Two `Slider` calls passed the value range positionally into the `modifier`
   parameter. Changed to the named `valueRange` argument.
4. `PACKAGE_USAGE_STATS` tripped lint's `ProtectedPermissions` check. It is an
   appop permission the user grants from system settings, so the check is
   suppressed at that declaration.
5. The manifest did not remove `androidx.work.WorkManagerInitializer`, even
   though `BatteryCastApplication` implements `Configuration.Provider` to supply
   the Hilt `WorkerFactory`. Left as-is, WorkManager would have initialised on
   startup with the default factory and injected workers would have failed at
   runtime. The default initializer is now removed via the startup provider.

## Not performed

The app was not launched on a device or emulator. This build environment has no
hardware virtualisation (`/dev/kvm` is absent), so no runtime, UI, or
instrumentation testing was possible. `:app:connectedAndroidTest` has not been
run.
