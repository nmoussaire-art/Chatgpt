# Confirmation: no sample data in the production build

The central promise of BatteryCast Quant is that every number it shows was measured on the device
showing it. This document states exactly what that means and how it is enforced, so the claim can
be checked rather than taken on trust.

---

## The claim

The production APK contains:

- **no demo mode**
- **no sample battery history**
- **no hardcoded battery predictions**
- **no pre-populated observations**
- **no randomly generated usage data**
- **no fake charging sessions**
- **no simulated calendar events**
- **no fallback that silently replaces missing data with sample data**

The database ships **empty**. The first row appears when the device produces its first real
reading. If the device produces nothing, the app says so.

---

## How it is enforced

Four independent mechanisms, deliberately overlapping, so that removing any one does not open a
gap.

### 1. `verifyNoSampleData` — Gradle, runs on every `assemble` and `check`

Parses every Kotlin file in `src/main` and fails the build on:

- any `class` / `object` / `interface` whose name contains `Fake`, `Mock`, `Demo`, `Sample`,
  `Stub`, `Dummy` or `Seed`;
- any function named like a sample-data generator (`generateDemo…`, `generateSample…`,
  `seedSample…`, `fakeObservation…`, `dummyObservation…`);
- any constant named `…DEMO_…`, `…SAMPLE_…` or `…FAKE_…`;
- any use of `kotlin.random.Random`, `java.util.Random` or `Math.random` **outside** the four
  packages that simulate the future (`forecasting/sim`, `forecasting/scenario`,
  `forecasting/planner`, `forecasting/uncertainty`).

That last rule is the important one. Randomness is legitimate in a Monte Carlo simulator, which
models uncertainty about what *will* happen. It is never legitimate for producing an observation of
what *did* happen. Confining it to four packages makes the distinction structural.

Comments are stripped before matching, so prose about the rules cannot trip the gate. Conversely,
the gate is strict enough that it once caught a constant named `SAMPLE_INTERVAL_MS` — meaning
"sampling interval" — which was renamed to `SAMPLING_INTERVAL_MS` rather than allowlisted.

### 2. `verifyProductionTelemetryBinding` — Gradle, runs on every `assemble` and `check`

Parses the production dependency-injection module and fails the build unless
`BatteryTelemetrySource` is bound to `AndroidBatteryTelemetrySource` and nothing else. It also
fails if **no** binding is found, so the check cannot be defeated by deleting the module.

### 3. `verifyNoNetworkPermission` — Gradle, runs on every `assemble` and `check`

Fails if the manifest grants `INTERNET`. Not strictly about sample data, but it belongs to the same
guarantee: an app that cannot reach a network cannot be fed data from one either.

### 4. `ProductionPurityTest` — JVM unit test

Asserts the same properties from the test source set, so they hold under an IDE test run as well as
a Gradle build, plus four more that the Gradle tasks do not cover:

- **exactly one** production implementation of `BatteryTelemetrySource` exists;
- **no production source references the test fixtures** (`ObservationFixtures`, `SnapshotFixtures`,
  or the `fixtures` package at all);
- the database is **never** built with `createFromAsset`, `createFromFile` or `addCallback` — each
  of which could put rows into the database that the device never produced;
- **no analytics, advertising or crash-reporting dependency** is declared in the build file.

### 5. `ProductionTelemetryBindingTest` — instrumentation test

The other three inspect *source*. This one inspects the *object*: it resolves the real Hilt graph on
a real device and asserts that the injected `BatteryTelemetrySource` is an instance of
`AndroidBatteryTelemetrySource`, and that its type name contains none of the test-double words.

Together these close the loop. There is no build configuration, no product flavour, and no
injection point through which a fake telemetry provider could reach the shipped app.

---

## Source separation

| Source set | May contain | Packaged into the APK |
|---|---|---|
| `src/main` | Real Android implementations, real Room storage, real models, real live-state handling | Yes |
| `src/test` | Deterministic fixtures, mocked interfaces, fixed seeds, model validation | **No** |
| `src/androidTest` | Emulator battery scenarios, UI tests, permission-flow tests | Only into the separate test APK |

`ObservationFixtures` and `SnapshotFixtures` exist and are used heavily — by 220 unit tests. They
live in `src/test/java/com/batterycast/quant/fixtures/`, which the Android Gradle Plugin compiles
into the unit-test classpath only. `ProductionPurityTest` additionally asserts that no production
file so much as mentions them.

---

## What the app does instead of showing sample data

This is the substantive half of the guarantee. Refusing to fabricate is only useful if the app
remains usable.

| Situation | What a lesser app might do | What BatteryCast does |
|---|---|---|
| No history at all | Show a plausible demo forecast | *"BatteryCast is collecting live battery behaviour. A preliminary forecast will become available after enough change has been observed."* — with a progress indicator and no chart |
| Only live current available | Present it as a forecast | Show it, labelled **Preliminary live estimate**, with the reasons uncertainty is high |
| Device reports no current | Substitute 0 mA | Store `null` with `CURRENT_UNSUPPORTED`, fall back to percentage-based estimation, and say so on the What Changed screen |
| Charge counter absent | Assume a typical capacity | Do not estimate capacity at all; use percentage regression |
| Never seen this phone charge | Estimate from a typical charger | *"BatteryCast has not yet observed this phone charging… Plug in once and the planner will work from the real charging speed."* |
| Never seen navigation usage | Use a typical navigation figure | Substitute the closest **observed** regime, label the card *"Estimated from the closest behaviour observed"*, and widen the interval by 15 % |
| Power saving never used | Assume a typical saving | Report the scenario as **unavailable** until both saver-on and saver-off have been observed |
| A forecast's target time passed with no nearby reading | Score it against an interpolation | Discard it, so neither the accuracy report nor the residual pool is corrupted |
| Fewer than 20 scored forecasts | Show an accuracy chart anyway | Say that accuracy figures appear after 20, because fewer would be describing noise |
| Asked how accurate it is | *"98 % accurate"* | Median error by horizon, bias, interval coverage and probability calibration — and explicitly no headline accuracy percentage |

---

## Verifying it yourself

```bash
# The three gates, with their reports.
./gradlew :app:verifyProductionPurity
cat app/build/reports/production-purity/*.txt

# The unit-test equivalent, plus everything else.
./gradlew :app:test --tests '*ProductionPurityTest'

# On a device, the runtime check.
./gradlew :app:connectedAndroidTest --tests '*ProductionTelemetryBindingTest'

# And the artefact itself.
aapt2 dump permissions app/build/outputs/apk/release/app-release.apk
```

A more direct check, if you prefer not to trust the tooling: install the APK, open the app, and see
that it shows you nothing but a collecting state. Then watch it fill in from your own battery.
