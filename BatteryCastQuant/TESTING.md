# Testing

## Automated test coverage

The forecasting test sources contain deterministic validation for:

- Cleaning, duplicate selection, optional-sensor sanitization, and outlier rejection.
- Charging/discharging segment detection and reboot boundaries.
- Percentage rounding, robust drain rates, µAh/mWh rates, EWMA, percentiles, and Bayesian shrinkage.
- Charging taper and charger-specific observed curves.
- Monte Carlo bounds, ordered quantiles, threshold times, survival probability, uncertainty width, scenario duration, and heavy-use monotonicity.
- Cold start and unsupported sensor behaviour.
- Latest-safe charging calculation and impossible-plan behaviour.
- Accuracy error, bias, 50%/80%/90% coverage, time-to-20% error, and probability calibration.
- Production dependency-injection and no-`INTERNET` verification.

Tests use fixed seeds and fixtures only in `src/test` or `tools`; none are packaged into the APK.

## Commands

With Android SDK and Gradle dependency access:

```bash
./gradlew verifyProductionDataBoundary test
./gradlew :app:lintDebug :app:assembleDebug
```

SDK-independent checks:

```bash
python3 tools/verify_production.py
kotlinc $(find core-model/src/main/kotlin forecasting/src/main/kotlin -name '*.kt') \
  tools/ForecastSmokeTest.kt -include-runtime -d /tmp/batterycast-smoke.jar
java -jar /tmp/batterycast-smoke.jar
```

## Manual device matrix

Test at minimum:

- Device with current and charge-counter support.
- Device without one or both sensors.
- Charging by AC, USB, and wireless where available.
- Charge interruption and high-battery taper.
- Reboot, force-stop, timezone change, and delayed background work.
- Permission denied/granted flows for Usage Access, Calendar, Bluetooth, and notifications.
- Battery-level changes through physical use or emulator battery controls only.
