# Manual and instrumented testing

BatteryCast has no demo mode, so exercising it means giving it real battery behaviour to observe —
either from a physical device or from an emulator's battery controls, which the platform surfaces
as genuine `ACTION_BATTERY_CHANGED` broadcasts and real `BatteryManager` property values.

---

## Running the automated suites

### Unit tests — no device needed

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:test
```

Around 220 tests, all on the JVM, every random draw seeded. Reports at
`app/build/reports/tests/testDebugUnitTest/index.html`.

### Everything the build can verify without a device

```bash
./gradlew :app:check
```

Unit tests plus the three production-purity gates (`verifyNoSampleData`,
`verifyProductionTelemetryBinding`, `verifyNoNetworkPermission`).

### Instrumentation and Compose UI tests — device or emulator required

```bash
./gradlew :app:connectedAndroidTest
```

Reports at `app/build/reports/androidTests/connected/index.html`.

These suites read the **real** battery of whatever they run on. Assertions are physical
plausibility bounds rather than fixed values, because the values depend on the device's actual
state — which is the point.

| Test class | Verifies |
|---|---|
| `ProductionTelemetryBindingTest` | Hilt resolves `BatteryTelemetrySource` to the real Android implementation, on a real device |
| `LiveBatteryTelemetryTest` | Live sampling, plausibility of every optional field, storage round trip preserving nulls, capability learning, duplicate suppression |
| `ObservationPersistenceTest` | The database ships empty; unmeasured fields read back as null, not zero; pruning and deletion |
| `PermissionAndOfflineTest` | The installed package holds no network permission; the forecast works with every optional permission denied |
| `DashboardComposeTest` | The dashboard asks the central question; every destination opens; the accuracy screen never claims a headline accuracy percentage |

> **Not executed in the environment this project was built in.** That machine has no KVM, so no
> emulator can start, and no device was attached. The suites compile; they have not been run. Run
> them anywhere with a device or a working emulator.

---

## Setting up an emulator

```bash
sdkmanager "system-images;android-34;google_apis;x86_64"
avdmanager create avd -n batterycast -k "system-images;android-34;google_apis;x86_64"
emulator -avd batterycast &
adb wait-for-device
```

Requires hardware virtualisation (KVM on Linux, HAXM/Hypervisor.Framework elsewhere).

---

## Driving the battery from the command line

The emulator console accepts battery commands that produce real broadcasts, so the app sees them
exactly as it would see a real battery changing.

```bash
adb -e emu power status discharging
adb -e emu power capacity 80
adb -e emu power ac off
```

A useful sequence for exercising the forecast quickly:

```bash
# 1. Unplug and start high.
adb -e emu power ac off
adb -e emu power status discharging
adb -e emu power capacity 90

# 2. Walk it down. Each step is a real battery-changed broadcast.
for pct in 88 86 84 82 80 78 76 74 72 70; do
  adb -e emu power capacity $pct
  sleep 120
done

# 3. Plug in and charge, to teach the charging model.
adb -e emu power ac on
adb -e emu power status charging
for pct in 72 76 80 84 88 92 95 97; do
  adb -e emu power capacity $pct
  sleep 120
done
```

`adb shell dumpsys battery` also works on both emulators and rooted devices:

```bash
adb shell dumpsys battery set level 45
adb shell dumpsys battery set status 3     # 2=charging, 3=discharging, 5=full
adb shell dumpsys battery unplug
adb shell dumpsys battery reset            # hand control back to the real battery
```

---

## Forcing background work

The 15-minute periodic worker can be triggered on demand rather than waited for:

```bash
# Force the app into an idle state so deferred work is eligible.
adb shell dumpsys deviceidle force-idle
adb shell cmd jobscheduler run -f com.batterycast.quant.debug 999

# Return to normal.
adb shell dumpsys deviceidle unforce
```

To check what is scheduled:

```bash
adb shell dumpsys jobscheduler | grep -A 20 batterycast
```

---

## A walkthrough that exercises everything

Roughly 90 minutes on an emulator, or a normal day on a real phone.

**1. Cold start.** Install and open. The dashboard should show *"BatteryCast is collecting live
battery behaviour"* with a progress indicator, and **no forecast**. This is the state to check most
carefully: there must be no chart, no probability, and no placeholder numbers.

**2. Preliminary estimate.** On a device with a working current sensor, within a few minutes the
maturity chip should change to **Preliminary live estimate** and a forecast should appear. Confirm
the word "preliminary" is visible — it is what distinguishes this from a settled forecast.

On a percentage-only device this step is skipped; the app stays in its collecting state until real
discharge has been observed. That is correct behaviour.

**3. Basic forecast.** After roughly an hour of real discharge — or after walking the emulator down
20 points with the loop above — the chip should read **Live forecast**, the survival probability
should be a real number, and the curve should show three nested prediction bands widening to the
right.

**4. Threshold times.** The "Expected to reach" card should list 20 %, 10 % and 5 % with times, and
those times must be in increasing order. If a level is not reached within the window, it should say
so rather than extrapolating.

**5. Charging.** Plug in. The dashboard chip should flip to **Charging**, the curve should turn
upwards, and after one complete charging session the Charge planner should stop saying "charger not
yet observed" and start producing a latest-safe-start time.

**6. Charge planner.** Set a target a few hours out, a battery level of 60 %, and 90 % confidence.
Confirm the recommended start time, the charging duration, the expected and conservative outcomes,
and the achieved confidence. Then raise the confidence to 98 % and confirm the recommended start
moves **earlier**, never later.

**7. Scenarios.** Open the Scenario laboratory. Early on, most cards should say *"Estimated from
the closest behaviour observed"* or *"Not enough observed behaviour yet"* — this is the honest
state, and it is worth verifying that nothing shows a confident number it has not earned. Power
saving should read *unavailable* until the phone has been observed both with and without it.

**8. Power saving.** Enable battery saver, leave it for a while, disable it, leave it again. The
power-saving scenario should become available with a measured multiplier, and the What Changed
screen should report the toggle.

**9. What changed.** Use the phone heavily for twenty minutes, then check the screen. It should
report the drain difference against the learned baseline **with the numbers behind it**, and the
measurement-quality card should show real counts.

**10. History.** Charging sessions should be shaded, screen-on periods marked, and gaps left as
gaps rather than joined by a straight line. Force a gap with
`adb shell dumpsys deviceidle force-idle` for a while and confirm the line breaks.

**11. Accuracy.** Nothing here until 20 forecasts have been scored, which takes real elapsed time.
Before that it should say so plainly rather than showing an empty chart.

**12. Reboot.** Reboot the device. Confirm on next open that the history is intact, the segment
broke at the reboot rather than being differenced across it, and periodic work has been
re-established (`adb shell dumpsys jobscheduler | grep batterycast`).

**13. Time-zone change.** Change the device time zone. The forecast must **not** jump: rates come
from monotonic time. Only the labels on the history should move.

**14. Precision session.** Settings → Precision session → 10 min. Confirm the ongoing notification
appears immediately and states that it uses slightly more power, that sampling becomes dense, and
that the service stops itself at the end without any further interaction.

**15. Data controls.** Export the CSV and open it — confirm unmeasured fields are **empty cells,
not zeros**. Then delete all data and confirm the app returns to its collecting state.

---

## Capturing screenshots

```bash
adb exec-out screencap -p > docs/screenshots/01-dashboard.png
```

Or the whole set, once the app is in a state worth capturing:

```bash
mkdir -p docs/screenshots
for name in dashboard curve chances planner scenarios whatchanged history accuracy settings; do
  read -p "Navigate to $name, then press enter…"
  adb exec-out screencap -p > "docs/screenshots/$name.png"
done
```

Capture them on a device with a few days of real history. Screenshots taken on a fresh install show
the collecting state, which is honest but not very informative about the app.

---

## Checking the app's own battery cost

The app should be close to invisible in the battery stats. To confirm:

```bash
adb shell dumpsys batterystats --reset
# …use the phone normally for a few hours…
adb shell dumpsys batterystats | grep -A 5 com.batterycast.quant
```

Expected: a handful of job executions per hour and effectively no wake-lock time. If BatteryCast
appears meaningfully in the system battery screen, something is wrong — please report it.
