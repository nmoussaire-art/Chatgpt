# Device-specific limitations

Android's battery API surface is advisory. A property can be declared, return a value, and still be
meaningless. This document lists what actually varies between devices, what BatteryCast does about
each case, and what you can expect on hardware at each end of the range.

---

## The core problem

`BatteryManager.getLongProperty()` is implemented by the kernel's power supply driver, and vendors
implement it inconsistently. A property may:

- return a plausible value in the documented unit — the happy case;
- return **0** for "not implemented";
- return `Long.MIN_VALUE` or `Integer.MIN_VALUE` as a sentinel;
- return a value in the **wrong unit** (milliamps through the microamp property);
- return a value with the **wrong sign** (positive while discharging);
- throw;
- work only while charging, or only while discharging.

BatteryCast treats every one of these as *unmeasured* rather than as a measurement, records the
reason on the observation, and degrades to the next-best estimator. Nothing is ever replaced with a
substitute value.

---

## Per-property behaviour

### `BATTERY_PROPERTY_CHARGE_COUNTER` (µAh)

**Best case.** Most Pixel, Samsung and recent Xiaomi devices report this correctly. It is by far
the most valuable field the app can get: its resolution is three orders of magnitude finer than a
percentage point, so drain rates come out sharp within minutes rather than hours.

**Failure modes.**

| Behaviour | Detection | Response |
|---|---|---|
| Always 0 | Value is exactly zero | Marked unsupported; percentage-based estimation used |
| Reported in mAh (≈3200 rather than 3200000) | Magnitude below the 50 mAh plausibility floor | Rejected — converting would be a guess |
| Resets or rescales after a fuel-gauge recalibration | Counter moves against the percentage | `CHARGE_COUNTER_RESET`; the segment is broken there |
| Frozen at a constant value | Contributes no slope | Regression returns no estimate; app reports no measurable change |

**Consequence when absent.** Drain estimation falls back to whole-percentage regression. Usable,
but a forecast that would have been sharp in 20 minutes takes an hour or two, and the prediction
intervals are correspondingly wider. The app states this on the What Changed screen:
*"This device reports battery percentage only."*

### `BATTERY_PROPERTY_CURRENT_NOW` (µA)

**Sign convention.** Documented as negative while discharging. A meaningful number of OEM kernels —
historically several Samsung, Huawei and LG models — invert it. BatteryCast never trusts the
documented sign: it correlates the observed sign against the charging state and requires 5 signed
observations at 75 % agreement before using current for anything. On a device that plugs in rarely,
this can take a day or more, during which current readings are marked
`CURRENT_SIGN_UNCALIBRATED` and simply not used.

**Unit.** Some kernels report milliamps. Detected by magnitude — 0.4 mA is not a plausible draw for
a running handset — after 5 observations at 80 % agreement.

**Noise.** Even where correct, instantaneous current swings by an order of magnitude between screen
refreshes. It is used only for the *preliminary* estimate and never rated above moderate confidence.

**Consequence when absent.** The preliminary forecast available in the first minutes after
installation is not available; the app stays in its collecting state until real percentage change
has been observed. This is the single biggest difference in first-run experience between devices.

### `BATTERY_PROPERTY_CURRENT_AVERAGE` (µA)

Less widely implemented than `CURRENT_NOW`, and on several devices it returns the same value.
Recorded when plausible, but the model does not depend on it.

### `BATTERY_PROPERTY_ENERGY_COUNTER` (nWh)

Rare. Most devices return `Long.MIN_VALUE`. Where present it gives watt-hours per hour directly —
that is, mean power in watts — which is voltage-aware and therefore slightly better than a
charge-based rate. Absence costs nothing beyond the loss of the live-power readout.

### `EXTRA_VOLTAGE`

Almost universally present. Some devices report microvolts and a few report whole volts; both are
unambiguous by magnitude and are converted. Anything outside 2 000–5 500 mV after conversion is
rejected.

### `EXTRA_TEMPERATURE` (deci-Celsius)

Widely present. Two known problems:

- Some devices report the **CPU or skin** temperature rather than the battery's. There is no way
  for an app to tell, so BatteryCast uses it as a *relative* signal — a rise of 3 °C matters, the
  absolute value is not asserted.
- A reading like `32` is an equally valid 3.2 °C and 32 °C. BatteryCast **does not guess**; it
  takes the value at face value as deci-Celsius. On a device that reports whole Celsius the thermal
  bucket will read "cool" permanently, which degrades the thermal multiplier to a no-op rather than
  producing a wrong one.

### `PowerManager.getCurrentThermalStatus()` — API 29+

Absent below API 29, and on some devices present but permanently `THERMAL_STATUS_NONE`. Where it is
unavailable the app falls back to bucketing by battery temperature. Where it is present but stuck,
the thermal multiplier stays near 1.0, which is the correct behaviour for a signal carrying no
information.

### `BATTERY_PROPERTY_CHARGING_POLICY` — API 34+

**Not available to this app on any device.** The constant exists in AOSP from API 34 but is
annotated `@SystemApi`, so it is absent from the public SDK and unreadable by an ordinary
application.

BatteryCast could reach for the hidden constant. It does not, because presenting whatever came back
as a measurement is exactly what this app is built not to do. The field is reported as unsupported
in Settings, and the charging model relies on the charging **rates** it can actually observe — which
captures adaptive charging behaviour anyway, since adaptive charging is visible as a slower observed
rate.

The plumbing is in place should the API ever become public.

### Battery scale

Almost always 100. A few older devices report 255, giving a percentage granularity of about 0.39
points. The app reads the real scale, records the granularity, flags the observation
`COARSE_PERCENT_SCALE` where the step exceeds 1 point, and uses the granularity in its uncertainty
arithmetic rather than assuming whole points.

### Bluetooth radio state

From API 31, `BluetoothAdapter.isEnabled` requires `BLUETOOTH_CONNECT`. BatteryCast does **not**
request that permission — a radio on/off flag is not worth a runtime permission prompt — so on
API 31+ the Bluetooth state is always `UNKNOWN` and the model treats it as an unobserved dimension.

---

## Background execution

### Doze and app standby

WorkManager's 15-minute period is a **target, not a guarantee**. Under Doze, or in a restricted app
standby bucket, a periodic worker may not run for hours. This is correct platform behaviour and the
app is designed around it:

- every observation carries its own timestamp, so irregular spacing is not a problem;
- gaps longer than 2 hours break the segment rather than being differenced across;
- the dashboard states how fresh its newest reading actually is, and marks it stale past 45 minutes.

BatteryCast does **not** use exact alarms or a persistent foreground service to work around this.
Both would defeat the purpose of the app.

### Aggressive OEM battery managers

Several manufacturers — Xiaomi, Huawei, OPPO, vivo, OnePlus, Samsung to a lesser degree — kill or
severely restrict background work beyond what stock Android does. On such devices, background
sampling may effectively stop when the app has not been opened recently.

**Effect:** sparser history, a model that updates more slowly, and wider prediction intervals. The
app degrades honestly rather than failing — it simply knows less, and says so through the data
quality figures on the What Changed screen.

**Mitigation:** exempting BatteryCast from battery optimisation in system settings improves sampling
density on those devices. The app does not prompt for this; a battery app demanding a battery
optimisation exemption is a poor trade unless the user actively wants it.

### Force-stop

A force-stop cancels the app's WorkManager jobs. They are re-established on next launch and on
`MY_PACKAGE_REPLACED`. Observations already stored are unaffected, and the in-memory continuity
anchor is restored from the database on the next sample.

---

## What to expect, by device class

| Device class | First useful output | Forecast quality |
|---|---|---|
| Charge counter + current, both correct (most Pixels, recent Samsung) | Preliminary estimate within minutes | Sharp; intervals narrow quickly |
| Charge counter only | 20–40 minutes | Very good; no live current readout |
| Current only, sign learnable | Minutes for preliminary; hours for a settled model | Good once the sign is confirmed |
| Percentage only | ~1 hour of real discharge | Usable; noticeably wider intervals, stated in-app |
| Percentage only, scale 255 | ~1 hour | Slightly better than scale 100, finer granularity |
| Aggressive OEM battery manager | Depends on how often the app is opened | Degrades with sampling density |

---

## Things Android does not expose to any app

Stated plainly, because their absence shapes what the app can honestly claim:

- **Per-app energy consumption.** There is no defensible third-party API. This is why BatteryCast
  never attributes drain to a named app.
- **Hotspot / tethering power draw.** Not exposed. The hotspot scenario uses the heaviest observed
  pattern and says so on the card.
- **Screen brightness contribution.** Not separable from total drain.
- **Cellular signal-strength power cost.** Not exposed as an energy figure.
- **Charging policy / adaptive charging state.** `@SystemApi` only, as above.
- **Battery design capacity.** Not in the public SDK. BatteryCast derives *actual* full-charge
  capacity from the charge counter and state of charge instead, which is more useful anyway because
  it reflects the battery's real, aged condition.
