# Architecture

## Data flow

1. `AndroidBatteryTelemetrySource` reads supported Android system fields and returns nullable, validated `BatteryObservation` values.
2. `BatteryRepository` persists observations in Room and attaches later actual outcomes to due forecast records.
3. `ObservationCleaner` removes impossible core observations, nulls invalid optional sensor fields, de-duplicates timestamps, detects recalibration jumps, and marks degraded quality.
4. `BatterySegmenter` separates charging/discharging sessions and creates boundaries for reboots, long gaps, and state changes.
5. `DrainEstimator` calculates robust percentage, µAh, and mWh rates across multiple horizons and similar historical states.
6. `BatteryForecastEngine` performs empirical Monte Carlo simulation and returns target distributions, survival probability, forecast curves, and threshold-time distributions.
7. `ChargingModel` learns charger- and battery-level-specific piecewise rates; `ChargePlanner` searches for the latest start that satisfies the requested confidence.
8. Compose screens consume `StateFlow` values from `MainViewModel`.

## Real-data boundary

- Production Hilt binding: `AndroidBatteryTelemetrySource -> BatteryTelemetrySource`.
- No fake implementation exists under any `src/main` directory.
- `verifyProductionDataBoundary` runs before Android `preBuild`.
- `tools/verify_production.py` performs the same boundary check without an Android SDK.
- Fixed seeds are accepted only by constructor injection for deterministic tests; production DI supplies no seed.

## Background design

- Periodic WorkManager request: approximately every 15 minutes with flex; no exact-time promise.
- Immediate one-shot work: app open, battery/power events, power-save changes, manual forecast refresh, reboot/time changes.
- Precision service: user initiated, foreground, 10-second sampling, 10–20 minute hard limit, explicit stop action.
- No permanent foreground service and no wake lock.

## Forecast interpretation

- Survival means `P(battery at target > selected reserve)`.
- Target intervals are empirical path quantiles, not normal-distribution confidence intervals.
- Threshold timestamps are unconditional path quantiles and remain unavailable when too few paths reach a threshold within the 48-hour threshold horizon.
- Heavy-use scenarios are prevented from producing a more optimistic result than the live baseline.
- Missing scenario history uses the closest observed personal regime and wider uncertainty, never a universal hardcoded drain rate.
