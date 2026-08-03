# BatteryCast Quant

A predictive battery survival assistant for Android. It answers the question a battery percentage
cannot:

> **Will my phone last?**

Not "you have 46%". Rather: *there is an 84% chance you stay above 10% until 10:00 PM, the median
outcome is 17%, the conservative one is 7%, and the reason it moved is that drain over the last
hour has been 28% above your normal pattern for this state.*

Everything it says is computed on the device, from that device's own measured battery behaviour.
There is no server, no account, no API key, and no network permission.

---

## Contents

- [What it does](#what-it-does)
- [The honesty rule](#the-honesty-rule)
- [Build and run](#build-and-run)
- [Architecture](#architecture)
- [Testing](#testing)
- [Documentation](#documentation)
- [Deliberate deviations](#deliberate-deviations)
- [Status of each deliverable](#status-of-each-deliverable)

---

## What it does

| Screen | Question it answers |
|---|---|
| **Home** | Will my phone last until my target? What will I have then? |
| **Curve** | What does the whole forecast look like, with its uncertainty? |
| **Chances** | How likely is it at each hour, and under different usage? |
| **Charge planner** | What is the latest safe time to start charging? |
| **Scenario laboratory** | What would 45 minutes of navigation cost me? |
| **What changed** | Why did the forecast move? |
| **History** | What actually happened, and how did past forecasts turn out? |
| **Model accuracy** | How wrong has this app typically been? |
| **Permissions and privacy** | What is collected, what leaves the phone, how do I delete it? |

The forecasting engine underneath is real:

- **Robust rate estimation** — Theil–Sen regression over several look-back windows, run on the
  fuel gauge's charge counter where the device has one and on battery percentage where it does not.
- **Hierarchical Bayesian shrinkage** — a state as specific as "heavy use, warm, on mobile data,
  Tuesday evening" is blended towards more general behaviour in proportion to how much evidence it
  actually has, so three unusual intervals cannot take over an eight-hour forecast.
- **Exponentially weighted learning** with a seven-day half-life, so recent behaviour dominates
  without discarding last week's pattern.
- **Monte Carlo simulation** — 2,400 paths per forecast, each carrying uncertainty about future
  usage (a Markov chain over regimes learned from this device), about the drain rate itself, about
  step-to-step variation (bootstrapped from real past forecast errors), and about temperature.
- **Piecewise charging model** by charger type and state of charge, so the taper above 80% comes
  out of the data rather than from an assumed curve.
- **Calibration tracking** — every forecast is filed when made and scored when its target time
  passes, and those errors feed back into the uncertainty model.

Full detail in [`docs/METHODOLOGY.md`](docs/METHODOLOGY.md).

---

## The honesty rule

The single rule the whole codebase is organised around:

> **Never show a number the device did not produce.**

Concretely:

- The APK ships with an **empty database**. No sample history, no seeded model, no demo mode.
- An unsupported sensor reads back as **null with a recorded reason**, never as zero. A device that
  cannot report current does not get a fabricated 0 mA that the model would treat as a measurement.
- Before there is enough evidence, the app says so:
  *"BatteryCast is collecting live battery behaviour. A preliminary forecast will become available
  after enough change has been observed."*
- A preliminary forecast from live instantaneous readings is **labelled preliminary** wherever it
  appears.
- A scenario the device has never exhibited is either **substituted from the closest observed
  behaviour and labelled as such**, or reported as unavailable. It is never filled in with a
  plausible universal figure.
- The app **never claims an app consumed a specific amount of battery**, because Android gives no
  third-party evidence that would support it.
- The accuracy screen publishes **no headline accuracy percentage**, because a probabilistic
  forecaster does not have one.

This is enforced mechanically, not by convention. Three Gradle tasks run on every `check` and every
`assemble`:

| Task | Fails the build if… |
|---|---|
| `verifyNoSampleData` | `src/main` declares any fake/demo/sample/stub type, a sample-data generator, or uses randomness outside the simulation packages |
| `verifyProductionTelemetryBinding` | the production Hilt graph binds `BatteryTelemetrySource` to anything but `AndroidBatteryTelemetrySource` |
| `verifyNoNetworkPermission` | the manifest grants `INTERNET` |

`ProductionPurityTest` asserts the same properties from the unit test source set, and
`ProductionTelemetryBindingTest` resolves the real graph on a device and checks the injected
instance's type. Test fixtures live only in `src/test` and `src/androidTest` and are referenced by
nothing in `src/main` — which is itself one of the assertions.

---

## Build and run

### Requirements

- JDK 17 or newer
- Android SDK with platform **35** and build-tools **35.0.0**
- No network access beyond the initial dependency download

### Build

```bash
# Point the build at your SDK (or create local.properties with sdk.dir=…)
export ANDROID_HOME=/path/to/android-sdk

./gradlew :app:assembleDebug          # debug APK
./gradlew :app:test                   # unit tests (~220)
./gradlew :app:check                  # unit tests + all production-purity gates
./gradlew :app:connectedAndroidTest   # instrumentation tests (needs a device or emulator)
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

### Install and use

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch the app will show its collecting state. That is correct: it has no history yet.
A preliminary forecast appears within minutes on a device with a working current sensor, and a
proper forecast after roughly an hour of real discharge. See
[`docs/MANUAL_TESTING.md`](docs/MANUAL_TESTING.md) for how to exercise this quickly on an emulator.

### Release build

See [`docs/RELEASE.md`](docs/RELEASE.md). Signing material is read from a git-ignored
`keystore.properties` or from environment variables; when neither is present the release build
stays unsigned rather than silently falling back to a debug key.

---

## Architecture

Kotlin, Jetpack Compose, Material 3, MVVM over a clean-architecture layering, Room, Hilt,
Coroutines/Flow, WorkManager, Navigation Compose.

```
com.batterycast.quant
├── core
│   ├── database/     Room entities, DAOs, converters, the database itself
│   ├── datastore/    user preferences and model bookkeeping
│   ├── system/       PowerManager, ConnectivityManager, UsageStats, Calendar wrappers
│   └── ui/           theme, shared components, charts, formatters
├── telemetry
│   ├── BatteryTelemetrySource        the single seam to the device
│   ├── AndroidBatteryTelemetrySource the only production implementation
│   ├── validation/   field validation, unit and sign detection, capability learning
│   ├── repository/   the only writer of observations
│   ├── receiver/     plug, power-save and boot events
│   ├── work/         WorkManager sampling and maintenance
│   ├── precision/    the user-started, self-terminating measurement session
│   └── export/       CSV export
├── forecasting
│   ├── clean/        sorting, de-duplication, outlier rejection, segmentation
│   ├── stats/        median, MAD, quantiles, Theil–Sen
│   ├── drain/        multi-horizon rate estimation
│   ├── regime/       interpretable usage-regime classification
│   ├── ewma/         exponentially weighted cells with time decay
│   ├── shrinkage/    hierarchical Bayesian shrinkage
│   ├── uncertainty/  residual model and bootstrap
│   ├── sim/          Monte Carlo simulator
│   ├── planner/      latest-safe-charge solver
│   ├── scenario/     what-if engine
│   ├── accuracy/     forecast recording, scoring and calibration
│   └── explain/      the "what changed" driver generator
├── feature/          one package per screen, each with its ViewModel
├── notifications/    threshold alerts with hysteresis and cooldown
└── di/               the production dependency graph
```

**Data flow.** `AndroidBatteryTelemetrySource` reads the platform →
`ObservationRepository` validates and stores → `ModelUpdater` folds new intervals into the learned
model → `ForecastEngine` assembles a snapshot → `MonteCarloSimulator` produces the distribution →
`ForecastCoordinator` holds it as the single source of truth for every screen.

---

## Testing

| Suite | Location | Runs on |
|---|---|---|
| Unit (~220 tests) | `src/test` | JVM, no device |
| Instrumentation | `src/androidTest` | device or emulator |
| Compose UI | `src/androidTest/…/ui` | device or emulator |

Unit tests cover drain-rate estimation, segment detection, outlier removal, percentage rounding,
EWMA updates and decay, Bayesian shrinkage, Monte Carlo invariants, percentile ordering,
threshold-crossing times, survival probability, the latest-safe-charge solver, charging taper,
missing sensors, cold-start gating, reboot and clock-change handling, forecast calibration, and the
production dependency-injection verification. Every random draw uses a fixed seed.

Properties asserted rather than examples checked:

- probabilities stay in [0, 1] and battery stays in [0, 100]
- percentiles are correctly ordered at every step
- heavier usage never predicts longer battery life
- charging never lowers the projection
- wider drain uncertainty produces wider intervals
- sparser evidence produces wider intervals
- a later charging start never has a higher success probability
- unsupported sensors never crash and never become zeros

---

## Documentation

| Document | Contents |
|---|---|
| [`docs/METHODOLOGY.md`](docs/METHODOLOGY.md) | The full quantitative method, with every constant justified |
| [`docs/PRIVACY.md`](docs/PRIVACY.md) | Every permission, what it buys, what works without it |
| [`docs/DEVICE_LIMITATIONS.md`](docs/DEVICE_LIMITATIONS.md) | What varies between manufacturers and how the app copes |
| [`docs/RELEASE.md`](docs/RELEASE.md) | Signed release-build procedure |
| [`docs/MANUAL_TESTING.md`](docs/MANUAL_TESTING.md) | Exercising the app against live and emulated battery state |
| [`docs/NO_SAMPLE_DATA.md`](docs/NO_SAMPLE_DATA.md) | The production-data guarantee and how it is enforced |

---

## Deliberate deviations

Two choices depart from the brief, both for engineering reasons rather than convenience:

**Single Gradle module, module-shaped packages.** The suggested thirteen-module split is mirrored
exactly in the package structure (`core.ui`, `core.database`, `core.system`, `telemetry`,
`forecasting`, `feature.*`), with no package depending upwards. Extracting them into real Gradle
modules is mechanical. It is not done here because thirteen modules each running KSP for Hilt and
Room multiplies build time several-fold for a codebase this size, and the dependency discipline the
split exists to enforce is already enforced by the package layout and verified by the purity tests.

**Charts written on Compose Canvas rather than with a chart library.** The central visual is a fan
chart: three nested quantile ribbons, dashed threshold lines, a target marker and a scrub
interaction. Generic charting libraries express that awkwardly, and the result would be a
dependency whose API shape dictated the design of the app's most important screen. The charts live
in `core/ui/chart` and are ordinary Compose composables.

---

## Status of each deliverable

| # | Deliverable | Status |
|---|---|---|
| 1 | Full Kotlin source | Complete |
| 2 | Functional Compose interface | Complete — 9 screens |
| 3 | Live Android battery integration | Complete |
| 4 | On-device quantitative forecasting | Complete |
| 5 | Room database | Complete, 8 tables, schema exported |
| 6 | Background observation | Complete — WorkManager + event receivers |
| 7 | Battery-conscious implementation | Complete — no wake locks acquired, no persistent service |
| 8 | Unit tests | Complete — ~220, all passing |
| 9 | Instrumentation tests | Written and compiling; **not executed here** (see below) |
| 10 | Compose UI tests | Written and compiling; **not executed here** (see below) |
| 11 | README with build instructions | This file |
| 12 | Quantitative methodology docs | `docs/METHODOLOGY.md` |
| 13 | Permission and privacy docs | `docs/PRIVACY.md` |
| 14 | App icon and splash screen | Complete — adaptive icon with themed variant |
| 15 | Screenshots from live battery data | **Not produced here** (see below) |
| 16 | Debug APK | Built — `app/build/outputs/apk/debug/app-debug.apk` |
| 17 | Signed release procedure | `docs/RELEASE.md`; unsigned release APK builds at 1.5 MB |
| 18 | Device-specific limitations | `docs/DEVICE_LIMITATIONS.md` |
| 19 | No sample data in production | `docs/NO_SAMPLE_DATA.md`, enforced by three build gates |

**On items 9, 10 and 15.** The build environment used to produce this project has no KVM, so no
Android emulator can start in it, and no physical device is attached. The instrumentation and
Compose UI tests are written and **compile**, but have not been executed, and no screenshots of the
running app exist. Rather than generate mock-ups and present them as screenshots — which would be
precisely the kind of fabricated artefact this app is built to avoid — they are simply absent.
[`docs/MANUAL_TESTING.md`](docs/MANUAL_TESTING.md) gives the exact commands to run both suites and
capture screenshots on any machine with a device or a working emulator.
