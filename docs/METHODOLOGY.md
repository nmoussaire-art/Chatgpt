# Quantitative methodology

How BatteryCast Quant turns a stream of battery readings into a probability.

Every constant named here is stated with the reasoning behind it. Where a value is a modelling
choice rather than a physical fact, it says so.

---

## 1. The measurement problem

Battery telemetry on Android is worse data than it first appears.

| Problem | Consequence if ignored |
|---|---|
| Battery level is reported in whole percentage points | A rate from two readings 20 minutes apart is uncertain by ±3 %/h |
| `WorkManager` defers background work under Doze | Samples are irregularly spaced, sometimes by hours |
| `CURRENT_NOW` sign convention is inverted on some OEM kernels | The forecast predicts a rising battery while it drains |
| A few kernels report milliamps through the microamp property | Every mAh-based rate is out by a factor of 1000 |
| Fuel gauges recalibrate, sometimes moving the level by 10 points instantly | A single reading destroys a least-squares fit |
| Reboots reset `elapsedRealtime` and invalidate counter deltas | Differencing across the boundary yields nonsense |
| Time-zone and daylight-saving changes move the wall clock | An hour of "drain" appears or disappears |

The design responds to each of these explicitly rather than hoping they average out.

---

## 2. Observation and validation

### 2.1 What is read

From `ACTION_BATTERY_CHANGED`: level, scale, status, plug type, health, voltage, temperature.
From `BatteryManager.getLongProperty`: charge counter (µAh), instantaneous current (µA), average
current (µA), energy counter (nWh).
From the system services: screen interactivity, power-save mode, thermal status, active network
transport, Bluetooth radio state.
Optionally, with usage access: aggregate foreground time and the dominant broad app *category*.

Each observation also stores **two clocks**: `System.currentTimeMillis()` for labelling, and
`SystemClock.elapsedRealtime()` for arithmetic. Every rate in the app is computed from the second
one, which is why a daylight-saving change cannot distort a drain estimate.

### 2.2 Physical plausibility bounds

These are not tuning parameters. They are the limits outside which a reading cannot be describing a
phone battery, so the only honest response is to discard the field.

| Field | Accepted range | Reasoning |
|---|---|---|
| Voltage | 2 000–5 500 mV | A Li-ion cell below ~2.5 V is shut down; above ~5 V it is not a single cell |
| Temperature | −30 to +80 °C | Beyond this the sensor is not describing a battery |
| Charge counter | 50 mAh – 30 Ah | Brackets every handset pack with room to spare |
| Current | 1 mA – 15 A | Below 1 mA a running handset is not drawing anything real |
| Energy | 0.1 – 200 Wh | A 4 000 mAh pack at 3.85 V is about 15 Wh |

Voltage is additionally *normalised*: a value in the microvolt range is divided by 1 000, and a
whole-volt value is multiplied by 1 000, because both are unambiguous by magnitude.

Temperature deliberately is **not** rescaled. A reading of `32` is an equally valid 3.2 °C and
32 °C, and guessing between them would fabricate data.

An exact **zero** current is treated as *unsupported*, not as an idle device: a great many kernels
signal "not implemented" with zero, and a running phone genuinely drawing 0 mA is not a state an
app can observe.

### 2.3 Learned sensor capabilities

Two device properties are inferred from readings rather than assumed from the API level.

**Current sign convention.** Android documents `CURRENT_NOW` as negative while discharging; a
meaningful number of OEM kernels invert it. The app correlates the observed sign with the charging
state — positive-while-charging and negative-while-discharging both support the documented
convention — and requires at least 5 signed observations with ≥75 % agreement before trusting it.
Until then, current readings are marked `CURRENT_SIGN_UNCALIBRATED` and are not used for rates.
Guessing here would invert the entire forecast on an inverted device.

**Unit.** Some kernels report milliamps through the microamp property. This is detectable purely by
magnitude: 0.4 mA is not a plausible draw for a running handset, so a cluster of readings in the
1–15 000 range is milliamps and a cluster in the 1 000–15 000 000 range is microamps. Five
observations with 80 % agreement settle it.

**Field support** is graded `SUPPORTED` / `LOW_PRECISION` / `UNSUPPORTED` / `UNKNOWN` from the
proportion of readings in which a field was usable, with a minimum of 4 samples before any verdict
and 6 before declaring a field unsupported.

### 2.4 Quality flags

Every observation carries one headline flag — `HIGH_QUALITY`, `ACCEPTABLE`, `LOW_PRECISION`,
`UNSUPPORTED_FIELD` or `SUSPECTED_OUTLIER` — plus a set of specific machine-readable notes
(`CURRENT_UNSUPPORTED`, `PERCENT_JUMP`, `REBOOT_DETECTED`, `CLOCK_JUMP_DETECTED`,
`SUSPECTED_RECALIBRATION`, `CHARGE_COUNTER_RESET`, and others).

**Reboot versus clock change.** These look identical if you only watch the wall clock. They are
separated by the *monotonic* clock: a reboot is the only thing that makes `elapsedRealtime` go
backwards. A wall-clock jump with monotonic time advancing normally is a time-zone or
daylight-saving change, and the reading stays perfectly usable — only its label moved.

---

## 3. Cleaning and segmentation

Before any model is fitted:

1. **Sort** chronologically; **collapse duplicate timestamps**, keeping the higher-quality reading.
2. **Drop** readings already flagged `SUSPECTED_OUTLIER`, recording why.
3. **Second pass**: a surviving pair can still imply an impossible rate once the reading between
   them has been removed. Anything above **3 %/minute** is rejected — even sustained gaming rarely
   exceeds 1 %/min, so 3 is a jump, a recalibration, or an unobserved reboot.

The result is split into **segments**, which are runs of readings that can legitimately be
differenced. A segment breaks at:

- a change in charging state (the physics changes entirely)
- a reboot, identified by the monotonic clock resetting
- a charge-counter reset, identified by the counter moving against the percentage
- a gap longer than **2 hours**, because what happened in between is genuinely unknown

Nothing is ever interpolated into a gap. A gap stays a gap and the segment ends.

---

## 4. Drain-rate estimation

### 4.1 Robust regression

Rates come from **Theil–Sen** regression: the median of the slopes of all point pairs. It tolerates
up to about 29 % arbitrarily corrupted points, which is roughly what a battery history contains
after a recalibration event or a pair of deferred samples. Ordinary least squares does not survive
a single 40-point jump; Theil–Sen barely notices it.

Pairs closer together than **5 minutes** are excluded, because at that separation the quantised
percentage dominates: two readings 90 seconds apart differing by one point imply 40 %/h.

At least **4 points** are required for a regression. With fewer, the app falls back to an endpoint
difference which is explicitly labelled `ENDPOINT_DIFFERENCE`, marked LOW confidence, and carries a
residual scale of one rounding step over the span — the uncertainty is *stated*, not hidden.

**No measurable change produces no estimate.** A null propagates to "no measurable change yet"
rather than to a rate of zero.

### 4.2 Which series to fit

In order of preference:

| Metric | Requires | Why it is better |
|---|---|---|
| Charge counter (µAh) | A working fuel gauge | Resolution finer than a percentage point by three orders of magnitude |
| Energy counter (nWh) | Energy reporting | Gives watts directly, and is voltage-aware |
| Battery percentage | Always available | Coarse, but universal |
| Instantaneous current | Sign convention settled | Available within seconds of installation; very noisy |

Charge-counter rates are converted to percentage points per hour using the **capacity the gauge
itself implies**: `chargeCounter ÷ (percent ÷ 100)`, taken as a median over readings above 15 %
state of charge (below that the ratio is numerically unstable) and accepted only in the
800–12 000 mAh range. This is a measured property of the actual, aged battery — which is exactly
what a forecast should be based on — rather than a datasheet figure.

### 4.3 Multiple horizons

Rates are estimated over the last 30 minutes, hour, 3 hours and 12 hours, and blended with weights
that combine **recency** (1.0 / 0.8 / 0.5 / 0.25) with **support** (confidence × span × point
count). A thirty-minute window on its own is far too jumpy to forecast eight hours from; a
twelve-hour window alone cannot see that you just started a video call.

**Disagreement between horizons is itself uncertainty.** The MAD across the horizon estimates is
folded into the blended residual scale rather than averaged away, so a phone whose recent behaviour
diverges sharply from its longer-run pattern gets a visibly wider forecast.

---

## 5. Usage regimes

Each interval between consecutive readings is classified into an interpretable regime: standby,
background activity, light / normal / heavy interactive use, media-like, gaming-like,
navigation-like, or one of three charging regimes.

**Bands are device-relative.** "Heavy use" is the upper quartile of *this phone's own* observed
screen-on drain rates, not an absolute figure. Using absolute thresholds would mean deciding in
advance what heavy use costs on hardware we have never seen. Below **12 intervals** the quantiles
are not describing a distribution, so the bands stay null and the classifier falls back to broader
categories — screen off is "standby", screen on is "normal interactive use", both of which are
true and fully supported by the evidence.

**A specific activity is never named without corroboration.** "Navigation-like" requires all three
of: usage access granted, a maps-category app dominating the foreground, *and* drain in the
device's top decile. Without all three the interval stays "heavy use". The app would rather be
vague than assert something about the user's activity it cannot support.

Intervals shorter than **5 minutes** are discarded entirely — one rounding step dominates the rate.

---

## 6. The learned model

### 6.1 Exponentially weighted cells

A model cell holds a running mean and variance for one metric in one device state, plus an
effective sample count. Updates use **West's weighted incremental algorithm**, which gives the same
answer as recomputing the weighted mean and variance over all decayed observations, in constant
memory:

```
decayed = weight × 0.5^(Δt / halfLife)
α       = w / (decayed + w)
mean'   = mean + α(x − mean)
var'    = (1 − α)(var + α(x − mean)²)
weight' = decayed + w
```

**Half-life: 7 days.** A phone's battery behaviour both drifts (a new app, a colder month, an older
battery) and repeats (the same evenings, the same commute). A plain average is blind to the first;
a one-hour window is blind to the second. Decaying weight rather than discarding old data captures
both: a cell unvisited for a fortnight still remembers its mean, but carries little enough weight
that a handful of fresh observations can move it.

**Observation weight** is the interval's duration in hours, capped at **2.0**. Without the cap, one
eight-hour overnight interval would count as much as sixteen half-hour daytime ones and quietly
become the model.

### 6.2 State hierarchy

Each observed state expands into six increasingly specific keys:

```
0  global
1  regime
2  regime + power-save
3  regime + power-save + thermal
4  regime + power-save + thermal + network
5  regime + power-save + thermal + network + day-type + time-of-day
```

Every one of them is updated on every interval, so the general levels stay well populated while the
specific ones fill in slowly.

### 6.3 Bayesian shrinkage

The problem this solves is concrete: "heavy use, power saving off, warm, on mobile data, Tuesday
evening" is the state the phone is actually in, and it may also be a state seen three times. Using
its mean alone would let three intervals dictate an eight-hour forecast; ignoring it throws away
the most relevant evidence there is.

So the estimate is built from the general down. At each level:

```
w        = n / (n + k)
estimate = w × cell.mean + (1 − w) × parentEstimate
```

**Prior strength k = 5 effective observations.** A cell needs five before it outweighs its parent —
low enough to personalise within a day of normal use, high enough that one unusual evening cannot
take over. The transition is gradual by construction; there is no threshold at which the model
"switches on".

Variance combines by the **law of total variance**:

```
var = w·cell.var + (1−w)·parent.var + w(1−w)(cell.mean − parent.mean)²
```

The third term matters: *disagreement between levels widens the interval* rather than being
averaged away. A specific state that says something very different from the general one produces a
less certain forecast, which is correct.

### 6.4 Blending with live measurement

The learned model is combined with the current live estimate by the same precision-weighted rule,
where the live estimate is worth 6 / 3 / 1 effective observations depending on whether its
confidence is good, moderate or low. This is the bridge that makes a forecast possible on the first
day: with no model, the live estimate is all there is; with three weeks of model, it is one more
piece of evidence rather than the whole story.

### 6.5 Discharge curve, thermal and power-save

Each is learned as a **multiplier against the interval's own baseline rate**, so it describes the
effect independently of how hard the phone happened to be working.

- **Discharge curve**: one multiplier per 10-point band of battery percentage, shrunk towards 1.0
  with prior strength 8 and clamped to [0.25, 4.0]. Li-ion discharge is not linear, and the shape
  varies by device; this learns the shape this device actually has.
- **Thermal**: one multiplier per thermal bucket (cool / warm / hot), from the platform's thermal
  status where available and from battery temperature where it is not.
- **Power saving**: the ratio of the saver-on to saver-off means, and **only produced when both
  sides have at least 3 effective observations**. Manufacturers implement battery saver very
  differently — 10 % on some devices, 40 % on others — so an assumed figure would be a guess dressed
  as a forecast. Until both sides exist, the power-saving scenario reports itself as unavailable.

### 6.6 Regime transitions

Transitions between adjacent intervals are counted with a **10-day half-life** (habits shift faster
than drain rates), and only between genuinely adjacent intervals — a gap means the transition was
not observed, and counting it would invent a behaviour change.

The chain is smoothed with a diagonal-heavy Dirichlet prior: **4.0** pseudo-counts on staying put,
**0.25** on each alternative. With no data at all this means "whatever the phone is doing now, it
will probably keep doing for a while and might change", which is the honest prior for phone usage —
it arrives in blocks, not in independent five-minute draws.

---

## 7. Uncertainty

### 7.1 Why not a normal distribution

Battery forecast errors are skewed — a phone can drain much faster than expected far more easily
than it can drain much slower than zero — and heavy-tailed, because one unexpected video call moves
an hour's drain by more than any Gaussian allows. Assuming normality produces intervals that look
reassuringly narrow and are wrong about twice as often as they claim.

### 7.2 The residual model

The app keeps its own past errors, expressed as percentage points per hour, and:

- **Median** gives the bias. A persistent non-zero value is reported on the accuracy screen as a
  lean, not silently corrected away.
- **MAD × 1.4826** gives a robust scale with a 50 % breakdown point.
- **Exponentially weighted volatility** with a **3-day half-life** gives recent erraticness, so a
  phone whose behaviour changed yesterday gets wide intervals today and narrow ones again once it
  settles.
- With **≥25 residuals**, the simulator **bootstraps** — resampling actual past errors, which
  reproduces the real skew and tails exactly. Below that it falls back to a **Student-t with 4
  degrees of freedom** scaled by the robust spread: still heavy-tailed, and honest about being a
  fallback.

---

## 8. Monte Carlo simulation

**2 400 paths**, **5-minute steps**, up to a **24-hour horizon**. 2 400 paths give a Monte Carlo
standard error near 1.1 percentage points on a probability around 0.5, comfortably finer than the
whole-percentage resolution the app displays.

**The seed is fixed.** The randomness models uncertainty about the future, not measurement — the
observations are all real. Fixing the seed means the same evidence always yields the same forecast,
so the headline number does not flicker when the screen is reopened, and every test is exact.

Each path carries four distinct sources of randomness, corresponding to four genuinely different
unknowns:

| # | Source | Drawn | Represents |
|---|---|---|---|
| 1 | Usage regime | Markov chain, re-sampled every 15 simulated minutes | What the phone will be doing |
| 2 | Rate per regime | Once per path, from the shrunk estimate's standard error × 1.35 | How fast each regime drains *on this phone* |
| 3 | Step noise | Every step, bootstrapped from real residuals, scaled by √Δt | Step-to-step variation |
| 4 | Thermal state | Once per path, mixing the current bucket with the cooler one (70/30) | Whether the phone stays warm |

Plus a **persistent per-path bias**, log-normal with unit mean, whose σ is derived from the observed
relative spread and the evidence count and clamped to [0.08, 0.55]. This is the mechanism by which
sparse data produces visibly wider intervals rather than a confident-looking wrong answer.

The **1.35 parameter inflation** on the standard error exists because consecutive intervals in one
evening are correlated, so the nominal standard error — which assumes independent samples —
understates the real uncertainty of the mean.

Battery percentage is clamped to [0, 100] at every step, and a path that reaches zero stays there.

### 8.1 What is read off the paths

- Expected, median, and the 5th, 10th, 25th, 75th, 90th and 95th percentiles at every step
- **P(battery > reserve at time T)** — literally the fraction of paths still above the reserve, not
  a value read from a fitted curve
- First-crossing times for 20 %, 10 %, 5 % and 1 %, with their own median and 10th/90th percentiles
- Probability of reaching each threshold at all within the horizon

A median crossing time is reported **only if at least half the paths actually reached that level**;
otherwise the answer is "not reached within the window", which is the truth.

---

## 9. Charge planning

The question is: *given that I need P % at time T with confidence c, what is the latest I can plug
in?*

Starting later means less charge delivered before the event, so the success probability is
**monotonically non-increasing in the start time** — which makes a bisection over start times
exactly correct rather than an approximation. Twelve halvings resolve a 24-hour window to under 30
seconds, far finer than the minute the app displays.

The search runs at 600 paths per evaluation because it only needs to bracket the crossing; the
recommended plan is then re-simulated at the full 2 400 so every number shown comes from a
full-quality run.

**If this device has never been observed charging, no plan is produced.** A charging estimate
without an observed charging rate would be an invention, and the app says so instead:
*"Plug in once and the planner will work from the real charging speed."*

Charging itself is modelled piecewise by state-of-charge band (0–20, 20–50, 50–80, 80–90, 90–100)
crossed with charger type and thermal bucket. Looking the rate up by the *current* band is what
makes the taper emerge from the data: the same charger fills 20→50 quickly and 90→100 slowly, and
the model represents that without being told a taper curve.

---

## 10. Scenarios

Scenario rates come from the user's own history whenever the regime has been observed. Where it has
not, the app does **not** fall back to a plausible-sounding universal figure. It:

1. substitutes the closest regime it has actually seen, following an ordered fallback ladder
   (gaming → media → heavy → interactive, and so on);
2. says so on the card;
3. widens the drain by **15 %**, because a borrowed rate is a weaker claim;
4. or, when even the general regimes have no support, reports the scenario as **unavailable**
   rather than producing a number.

Two scenarios are special:

- **Hotspot** — Android does not expose tethering power draw to apps at all, so this always uses the
  heaviest observed pattern, with the limitation stated on the card.
- **Airplane mode** — modelled from observed screen-off behaviour, with a note that real airplane
  mode is usually a little better than the estimate.

---

## 11. Cold start

Capabilities are gated on evidence, never on elapsed time:

| Level | Requires | Unlocks |
|---|---|---|
| `COLLECTING` | fewer than 2 usable readings | Nothing. The app says what it is doing. |
| `PRELIMINARY` | a live current estimate, or readings with no measurable change yet | A clearly-labelled preliminary estimate |
| `BASIC` | ≥ 2 percentage points of observed discharge over ≥ 45 minutes | Time-to-empty, survival probability, charge planning |
| `PERSONALISED` | ≥ 24 h span, ≥ 15 effective samples, ≥ 3 distinct regime cells | Regime forecasts, scenarios, accuracy reporting |
| `MATURE` | ≥ 7 days, ≥ 60 effective samples | Day-of-week personalisation |

A phone plugged in overnight accumulates 24 hours of readings and stays at `PRELIMINARY`, because
it has produced no information about discharge. That is the intended behaviour.

---

## 12. Accuracy and calibration

Every forecast with a target at least 20 minutes ahead is filed when it is made. When the target
time passes, it is scored against the nearest real observation within **20 minutes**. A forecast
with no nearby observation is **discarded, never scored against an interpolation** — a made-up
outcome would corrupt both the accuracy report and the residual pool that drives the app's
uncertainty. After 6 hours with no match, it is deleted.

Reported:

- **Median absolute error** by horizon band (under 1 h, 1–3 h, 3–6 h, over 6 h)
- **Bias** — the median signed error, described in words as optimistic or pessimistic
- **Interval coverage** — how often the outcome fell inside each stated interval, against what it
  should have been
- **Probability calibration** — forecasts grouped by the probability they stated, compared with how
  often the event actually happened. A bin needs ≥ 5 forecasts to be shown.

Nothing is reported at all below **20 scored forecasts**, because coverage over a handful of
outcomes is describing noise.

**There is deliberately no headline accuracy percentage.** A probabilistic forecaster does not have
one. "98 % accurate" is not a claim that can be made about a distribution, and the app does not
make it.

---

## 13. Battery cost of the app itself

A battery-forecasting app that measurably drains the battery has defeated itself.

- **One periodic worker every 15 minutes** — WorkManager's shortest period. No constraint requires
  the battery to be above any level, because a forecast is most valuable when the battery is low.
- **Event-driven samples** on plug, unplug, power-save toggle, boot and app open, via `goAsync()`
  broadcast receivers doing one read and one insert.
- **No wake locks are acquired by this app.** `WAKE_LOCK` appears in the merged manifest because
  WorkManager declares it — that is what lets a background worker finish once started — but no
  BatteryCast code requests one.
- **No persistent foreground service.** The only foreground service is the precision session: user
  started, notification-visible for its whole life, hard-capped at 20 minutes, `START_NOT_STICKY`
  so the system cannot silently restart it.
- **`ACTION_BATTERY_CHANGED` is registered only while a screen is open**, never in the manifest —
  receiving it continuously would wake the app for every percentage point around the clock.
- **Duplicate suppression**: two readings within 45 seconds with nothing changed are collapsed.
- **A model watermark** ensures each interval is learned from exactly once, so effective sample
  counts grow with what has been observed rather than with how often the app is opened.
- **28-day retention**, pruned by the daily maintenance worker.
