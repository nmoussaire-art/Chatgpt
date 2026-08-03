# BatteryCast Quant

BatteryCast Quant is a privacy-first Android application that predicts whether a phone will survive to a selected time, estimates remaining battery at that time, projects threshold-crossing times, compares usage scenarios, and plans charging using only observations collected from the Android device.

## Production-data guarantee

Production code contains no demo mode, seeded history, pre-populated observations, sample forecast, hardcoded battery outcome, fake telemetry provider, cloud model, analytics SDK, advertising SDK, account system, or `INTERNET` permission.

`src/main` binds `BatteryTelemetrySource` directly to `AndroidBatteryTelemetrySource`. Monte Carlo randomness propagates uncertainty learned from live observations; it does not generate device telemetry. Fixed random seeds and deterministic fixtures exist only under test/tool sources.

When evidence is insufficient, the app returns an honest collecting or preliminary state instead of substituting invented data.

## What is implemented

- Live telemetry from `ACTION_BATTERY_CHANGED`, `BatteryManager`, `PowerManager`, `ConnectivityManager`, optional `UsageStatsManager`, and optional Calendar Provider access.
- Validation and nullable storage for unsupported current, charge-counter, energy, voltage, temperature, health, status, charging-policy, thermal, Bluetooth, network, screen, and usage fields.
- Room database for observations and forecast-vs-actual evaluation.
- Opportunistic WorkManager sampling plus event-triggered captures.
- User-started 10–20 minute foreground precision session that stops automatically.
- Robust observation cleaning, reboot/reset awareness, charging/discharging segmentation, percentage-rounding handling, Theil–Sen regression, EWMA, Bayesian shrinkage, MAD/empirical uncertainty, battery-level nonlinearity, and piecewise charging taper.
- At least 2,000 Monte Carlo paths per production forecast or charge plan.
- Home dashboard, survival curve, hourly probability view, charge planner, scenario laboratory with custom mixed usage, battery history, explanation view, model accuracy, permissions/privacy, export, and deletion controls.
- Threshold notifications, persistent unusual-drain notification, and saved charge-plan reminder with rate limiting.
- Exact 50%, 80%, and 90% interval-coverage evaluation, forecast bias, median error, time-to-20% error, and probability calibration error.

## Modules

- `app` — Android system integrations, Room, Hilt, WorkManager, Compose UI, notifications, and permissions.
- `core-model` — platform-neutral domain models.
- `forecasting` — cleaning, segmentation, drain/charging models, Monte Carlo engine, scenarios, planner, and accuracy calculator.

## Build in Android Studio

1. Open the `BatteryCastQuant` root folder.
2. Let Android Studio install the requested Android SDK and sync Gradle dependencies.
3. Run the verification and tests:

```bash
./gradlew verifyProductionDataBoundary test
```

4. Build a debug APK:

```bash
./gradlew :app:assembleDebug
```

The APK will be generated under:

```text
app/build/outputs/apk/debug/app-debug.apk
```

For a release build, configure your own signing material outside the repository, then run `:app:assembleRelease`.

## First-run behaviour

Immediately after installation, BatteryCast captures a real device reading. If supported current and charge-counter sensors provide defensible information, it may show a clearly labelled preliminary live estimate. Otherwise, it states that it is collecting behaviour until real battery change has been observed.

Personalized state, day/time, scenario, charger, nonlinear-discharge, and accuracy views unlock only as supporting observations accumulate.

## Device testing

- Prefer a physical Android device.
- Emulator tests must use the emulator's battery controls; no production simulation provider exists.
- Usage access, Calendar, Bluetooth state, and notifications are optional.
- WorkManager timing is opportunistic and may be deferred by Android.
- Precision sessions sample more frequently for a limited period and stop after 10–20 minutes.

## Privacy

All observations, calendar targets, models, forecasts, and accuracy outcomes remain on the device. Database and preferences are excluded from backup and device transfer. CSV export occurs only through a user-selected local document destination.

See `PRIVACY.md`, `ARCHITECTURE.md`, `TESTING.md`, and `BUILD_STATUS.md` for details.
