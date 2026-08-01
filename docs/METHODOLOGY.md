# Quantitative methodology

How OnTime Quant turns a routing estimate into a departure decision, and why each choice
was made. Every section names the file that implements it.

Nothing here is machine learning in the neural sense, and the app does not claim to be
"AI-powered". It is an ensemble of small, interpretable statistical models: hierarchical
Bayesian shrinkage, robust scale estimation, an empirical bootstrap, a compound-Poisson
tail, and Monte Carlo simulation.

---

## 1. The arrival-time equation

```
Arrival(t) = t
           + Preparation
           + Travel( t + Preparation )
           + Parking
           + Walking

Deadline   = EventStart − EntryBuffer
```

*Implementation:* `MonteCarlo.simulate` in `forecasting/.../MonteCarlo.kt`.

Two details matter more than they look.

**The routing baseline is evaluated at `t + Preparation`, not at `t`.** You are not on the
road while you are still finding your keys. During a building morning peak, a seven-minute
preparation delay puts you into materially worse traffic than the routing estimate for
your nominal departure minute. Ignoring this is a systematic under-estimate, and
`MonteCarloTest.the routing baseline is evaluated after preparation not at departure`
pins the behaviour.

**The entry buffer sits on the deadline, not on the arrival.** The brief writes it into
the arrival sum; the two are algebraically identical,

```
P(A + b ≤ S)  ≡  P(A ≤ S − b)
```

and putting it on the deadline keeps `Arrival` meaning "when I reach the door", which is
the quantity the percentiles describe. The buffer is still shown as its own row in the
component breakdown. An optional `entryBufferLogSigma` allows it to be uncertain; the
default is deterministic.

---

## 2. Routing baseline and interpolation

*Implementation:* `TravelBaseline` in `MonteCarlo.kt`.

The routing provider is queried every **5 minutes** across the departure window — the same
resolution as the solver's coarse pass, which keeps a two-hour window inside 25 requests.

Between query points, duration is interpolated **linearly**. Outside the queried range the
nearest endpoint is **held flat**, never extrapolated: projecting a traffic trend past the
last measurement is exactly the kind of invented precision this product exists to avoid.
When a draw lands outside the measured range, `outsideQueriedRange` is true and an
independent widening term is applied instead:

```
multiplier ×= exp( 0.4 · γ_extrap · z ),   γ_extrap = 0.25
```

---

## 3. Bias correction

*Implementation:* `TravelObservation`, `FittedTravelModel.bias` in `TravelModel.kt`.

Error is modelled **multiplicatively**:

```
r_i = log( actual_i / predicted_i )
```

Why log-ratio rather than a difference in minutes: a five-minute miss on a ten-minute trip
and on a sixty-minute trip are completely different errors, and travel-time error is known
to scale with trip length. Log space also guarantees the corrected duration stays positive.

Recency is handled by an **exponentially weighted accumulator** with a 12-observation
half-life (`EwmaAccumulator` in `Stats.kt`). Crucially it tracks a proper effective sample
size rather than a raw count:

```
n_eff = (Σ w_i)² / Σ w_i²
```

so recency-discounted history correctly counts for less. With a 12-observation half-life
`n_eff` saturates near 35, which is the intended behaviour — thirty-year-old commutes
should not vote.

### 3.1 The hierarchy

Five levels, from most general to most specific:

| Level | Key | Prior strength κ |
| --- | --- | --- |
| System prior | — | `logBias = +0.03` |
| Your overall history | `global` | 4 |
| This origin/destination pair | `pair:<o>-><d>` | 5 |
| This route | `route:<journeyId>` | 6 |
| This route at this time of day | `route:… \| bucket:… \| day:…` | 8 |

The system prior of **+3%** encodes that traffic-aware routing measures segment traversal,
while real door-to-door road time also contains pulling out of a parking space, the last
hundred metres and the queue at the destination approach. It is deliberately small and is
overwritten quickly by personal data.

---

## 4. Bayesian shrinkage

*Implementation:* `Shrinkage.kt`, `FittedTravelModel.deviationEvidence`.

For a normal likelihood with a normal prior of variance `σ²/κ`, the posterior mean is

```
              n_eff · x̄  +  κ · μ₀
   μ_post  =  ─────────────────────
                    n_eff + κ
```

and the fraction of the estimate genuinely attributable to personal data is

```
   w = n_eff / (n_eff + κ)
```

A single observation on a brand-new route therefore moves the estimate by `1/(1+κ)` of the
way. `BiasCorrectionTest.sparse personal data cannot dominate the routing baseline`
asserts that two observations claiming a journey takes twice as long move the estimate to
under +35%, not to +100%.

### 4.1 The nesting problem, and how it is solved

The four levels are **nested**: a trip on this route at this time of day appears in *all
four* groups. Shrinking level by level with each group's full `n_eff` would count one
journey as four independent pieces of evidence. With κ values of 4/5/6/8 and two
observations, the naive chain reaches

```
1 − Π(1 − w_k) = 0.71   ⇒   exp(0.71 × log 2) = 1.64×
```

— a 64% correction from two trips. That is wrong, and an early version of this engine did
exactly that until a unit test caught it.

The fix: the broadest level is estimated normally; every level below it estimates a
**deviation** from its parent, and a deviation is only identifiable to the extent that the
parent contains journeys the child does not. Treating it as a two-sample contrast:

```
   n_dev,k = n_k · (n_{k−1} − n_k) / n_{k−1}
```

This is zero exactly when the child *is* the parent — the case where no between-group
difference can be observed — and peaks when the split is even. `nested levels do not count
the same trip four times` asserts that the four-level result now equals a single shrinkage
step to within 0.02 in log space.

### 4.2 Scale shrinkage

Variances are blended on the variance scale, which is the conjugate operation for a
scaled-inverse-χ² prior (`Shrinkage.scaleToward`):

```
   σ²_post = ( n_eff · σ²_sample + κ · σ²_prior ) / ( n_eff + κ )
```

A sparse sample can therefore widen, but not implausibly narrow, the prior.

---

## 5. Residual uncertainty

*Implementation:* `ResidualSampler` in `TravelModel.kt`, `Stats.kt`.

Travel-time errors are skewed and contain genuine outliers. Mean and standard deviation are
not safe estimators, so the engine uses:

- **median** for centres;
- **MAD × 1.4826** for scale (`Stats.madSigma`), a consistent estimator of σ for Gaussian
  data but insensitive to outliers — `mad sigma is robust to a single extreme outlier`
  asserts that adding a 40× outlier moves it by less than 0.6;
- **winsorisation** at the 2–5% tails before pooling;
- **weighted medians** where observations carry recency weights.

### 5.1 Two sampling regimes

**Empirical (preferred).** Once `n_eff ≥ 8` and at least six residuals exist for the
deepest populated group, the sampler draws from the user's own residual pool with Gaussian
jitter sized by Silverman's rule of thumb:

```
   h = 0.9 · min(MAD(pool), σ̂) · n^(−1/5)
```

The pool is centred and the shrunk location re-applied, so the *shape* is the user's own
while the *level* still respects hierarchical shrinkage. This preserves real skew and fat
tails that a fitted Gaussian would erase.

**Parametric (fallback).** Otherwise a log-normal with the shrunk location and scale.

The engine does **not** assume normality: `assertThat(sparse.sampler.isEmpirical).isFalse()`
and its counterpart pin which regime applies.

### 5.2 The disruption tail

Both regimes add a compound-Poisson right tail:

```
   with probability p:  travel += Exponential(mean = m)
   p = p_base · regime + γ_event · E + 0.05 · W          (capped at 0.45)
   m = max(4 min, 0.45 · loaded_duration)
```

`p_base = 0.06`, scaled by the traffic regime (light 0.6× → severe 2.4×). The mean scales
with the **loaded** duration, not free-flow: the same blocked lane costs far more on a
network already at capacity.

This term is what makes the arrival distribution asymmetric. You can be very late; you
cannot be symmetrically very early. That asymmetry is the entire reason a percentile-based
recommendation beats a mean-based one.

### 5.3 Scale modifiers

```
σ_eff = σ_shrunk
      × regimeMultiplier          (0.85 light → 1.55 severe)
      × (1 + 0.30 · W)            (or 1.06 when weather is unavailable)
      × (1 + 0.45 · E)            (event pressure)
      × stalenessMultiplier       (cached routing only)
σ_eff clamped to [0.07, 0.75]
```

Staleness saturates rather than growing without bound:

```
   1 + γ_cached · (1 − exp(−(age − 5) / 45))
```

---

## 6. Weather

*Implementation:* `WeatherSnapshot.severityIndex`, `TravelModelFitter.fitWeatherCoefficient`.

Providers differ in what they publish, so the engine consumes a single bounded **severity
index** in `[0, 1]` built from precipitation probability, precipitation amount, visibility,
wind and temperature extremes, combined as `0.7 · worst + 0.3 · mean`. A provider supplying
a subset of fields still contributes; a provider supplying none yields exactly zero rather
than a guess.

The sensitivity coefficient is **learned**, not assumed, by ridge regression of the
bias-removed residual on severity:

```
   r_i − b̂  ≈  γ · W_i,      γ ~ N(γ₀, 1/λ),  γ₀ = 0.07,  λ = 10
   γ̂ = ( Σ w_i W_i (r_i − b̂) + λ γ₀ ) / ( Σ w_i W_i² + λ )
```

With no weather-varying history the posterior *is* the prior. When severity barely varies
across the history (range < 0.15), the prior is kept rather than fitting to noise.
`a genuine weather effect is learned from data` and `an absent weather effect keeps the
coefficient near zero` assert both directions.

**When weather is missing**, no mean adjustment is applied and the scale is widened by 6%.
Widen, never invent.

---

## 7. Nearby-event pressure

*Implementation:* `EventPressureModel.kt`.

A **risk** score in `[0, 1]`, explicitly not a delay estimate. For each event:

```
   proximity = max( exp(−d_route / 1200 m),  0.8 · exp(−d_dest / 900 m) )
   timing    = triangular kernel: rising over the 75 min before the start,
               and a sharper spike over the 50 min after the end
   size      = 0.35 + 0.65 · ln(1 + capacity) / ln(1 + 60000)
   e_i       = proximity · timing · size
```

Overlapping events combine with a **noisy-OR**, which saturates rather than summing past 1:

```
   E = 1 − Π (1 − e_i)
```

Distance to the route uses point-to-segment distance under a local equirectangular
projection (`GeoPoint.distanceToPath`), which is accurate enough at city scale.

**How the score is used.** Below a threshold of 0.55 it affects *only* uncertainty and the
disruption probability. Above it, and only proportionally to the excess, it shifts the
central estimate:

```
   Δlog = 0.06 · (E − 0.55) / (1 − 0.55)
```

`event pressure widens uncertainty before it shifts the mean` asserts that a pressure of
0.30 moves the mean by exactly zero while still widening the range.

---

## 8. Preparation, parking and walking

*Implementation:* `DurationModel.kt`.

All three are strictly positive with long right tails, so all three are **log-normal**,
parameterised by median and log-scale. Both parameters are shrunk with the same conjugate
rule, over a two-level hierarchy (this destination → all destinations → prior).

| Component | Prior median | Prior log-σ | Key |
| --- | --- | --- | --- |
| Preparation | 5 min | 0.45 | origin |
| Parking | 4 min | 0.50 | destination |
| Walking | 3 min | 0.35 | destination |

Precedence: a per-destination value the user typed **wins outright**; then a user default;
then learned history; then the prior. Each fitted duration carries its own
`DataProvenance` and a human-readable source label, which is what the details screen renders.

**Preparation is measured, not judged.** The observation is the gap between the instant the
user accepts a recommendation and the instant movement is confirmed. The feature can be
switched off in Settings, in which case the component becomes exactly zero rather than
falling back to a prior. The copy reads *"Your recent trips suggest you usually begin
moving about seven minutes after deciding to leave"* — a timing measurement, phrased as one.

---

## 9. Monte Carlo simulation

*Implementation:* `MonteCarlo.simulate`.

Default **4000 draws** per candidate departure (configurable 2000–20000; the brief's
minimum is 2000). Each draw samples preparation, the interpolated routing baseline at the
resulting road-start minute, the multiplicative shock, the disruption tail, parking,
walking and the optional entry-buffer noise.

**The random generator** is a hand-implemented xoshiro256\*\* (`Rng.kt`) rather than
`java.util.Random`, so that the engine stays pure Kotlin, is byte-for-byte reproducible
across platforms for a given seed, and is fast enough to run tens of thousands of draws off
the main thread. Gaussians come from Box–Muller with a cached spare.

**Common random numbers.** The seed depends only on the *inputs*, never on the candidate
departure time. Every candidate therefore sees the identical sequence of shocks. This is a
standard variance-reduction technique and it is why the departure curve comes out smooth
without any post-hoc manipulation: candidate-to-candidate differences reflect the model,
not simulation noise. `the curve is effectively monotone thanks to common random numbers`
asserts the largest wrong-way step is under four Monte Carlo standard errors.

Outputs per candidate: the 5th, 10th, 25th, 50th, 75th, 80th, 90th and 95th percentiles of
arrival; P(on time); P(>5 min late); P(>10 min late); expected lateness *conditional on
being late*; interval width; a risk classification; and the mean of each component.

Guarantees asserted by tests: probabilities in `[0, 1]`; percentiles ordered;
`P(>5 late) ≥ P(>10 late)`; `P(>5 late) ≤ 1 − P(on time)`; component means reconstruct the
mean total to within one second.

---

## 10. The latest-safe-departure solver

*Implementation:* `DepartureSolver.kt`.

Solves

```
   t* = max { t ∈ W : P( Arrival(t) ≤ Deadline ) ≥ c }
```

in three passes, cheapest first:

1. **Coarse sweep** at 5-minute resolution across the window — matching the routing grid.
2. **Bracket** the crossing: the last coarse candidate meeting `c`, and the first that does
   not.
3. **Refine** at 1-minute resolution *inside that bracket only*.

The window itself opens at `Deadline − 2.4 × typical total` (clamped to now) and closes at
`Deadline − 0.55 × typical total`, capped at 120 minutes, aligned to whole minutes. It
deliberately extends past the answer so the curve shows the full cost of waiting rather
than stopping where the recommendation lands.

**When no departure in the window meets the target**, the solver returns `feasible = false`
with the best-effort earliest candidate and its true probability, and the UI says *"Even
leaving now, the chance of arriving by the deadline is about 62%, below your 90% target."*
It never fabricates a recommendation.

**Monotonicity is verified, not imposed.** The solver reports the largest wrong-way step it
observed. Isotonic regression (pool-adjacent-violators, `Stats.isotonicDecreasing`) is
applied **only to the displayed line**, and **only when** that violation exceeds three Monte
Carlo standard errors — i.e. only when the wiggle is larger than simulation noise can
explain. When it happens, `DisplayCurve.smoothingApplied` is true, the chart says so in
plain text, and the probabilities used for the recommendation remain the raw ones. On the
demo scenario smoothing does not trigger.

---

## 11. Personalisation levels

*Implementation:* `FittedTravelModel.personalization`.

Based on effective sample size and data quality, not a raw trip count:

| Level | Condition |
| --- | --- |
| **Preliminary** | `n_eff < 3` and global history `< 6` |
| **Learning** | `n_eff ≥ 3` or global `n_eff ≥ 6` |
| **Partially personalized** | `n_eff ≥ 8` **and** ≥ 5 residuals available for scale |
| **Personalized** | `n_eff ≥ 18` **and** ≥ 5 residuals available for scale |

The scale requirement matters: a route with plenty of observations but no usable spread
estimate can produce a confident central estimate with an untrustworthy interval, and the
label must not overstate that.

Day-one copy: *"Preliminary forecast based on current routing conditions and conservative
uncertainty assumptions."* Personalised copy: *"Based on 24 completed trips on this route
(24 at this time of day). Your personal correction of +13% and your own spread of outcomes
drive this forecast."*

---

## 12. Walk-forward backtesting

*Implementation:* `Backtester.run` in `Backtest.kt`.

For each completed trip, in chronological order:

1. Fit the model on **strictly earlier** trips only.
2. Produce the arrival distribution that would have been available before departure.
3. Score the realised outcome against it.
4. **Only then** append the trip to the model state.

The ordering is enforced structurally — the fit happens before the append, in one loop —
so there is no way to leak. `training data grows strictly monotonically and never sees the
future` asserts `records[i].trainingSize == i`, and `reordering the input does not change
the walk-forward result` asserts the pass is order-independent at the input boundary.

Trips excluded from learning are still **scored** (they are real outcomes) but are not fed
back, matching exactly what the live app does.

Only the *travel duration* distribution is scored here. Preparation, parking and walking
are scored by their own models; mixing them would confound routing accuracy with readiness
behaviour.

---

## 13. Calibration and accuracy reporting

*Implementation:* `CalibrationAnalyser` in `Backtest.kt`; `ui/accuracy/Accuracy.kt`.

Computed: MAE, median absolute error, RMSE, mean and median bias, 50/80/90% interval
coverage, Brier score, calibration by predicted-probability bucket
(`0–50, 50–70, 70–80, 80–90, 90–95, 95–100`), median interval width (sharpness),
per-route performance, per-time-bucket performance, and recent-versus-long-run error.

**Below 8 scored trips the screen shows a warning instead of headline confidence.**

The app deliberately never displays a single "accuracy percentage". A forecast that says
90% is *supposed* to be wrong one time in ten; scoring it like a classifier would be
meaningless and misleading. What it shows instead:

- *"Typical arrival-time error: 5 min"*
- *"The model tends to underestimate journeys by about 2 minutes. That correction is
  already applied to new forecasts."*
- *"The 80% forecast range contained the actual journey 67% of the time"* — with the
  honest follow-up that the ranges have been slightly too narrow.
- *"Brier score 0.089 — 0 is perfect, 0.25 is a coin flip."*

Sharpness and coverage are always shown **together**, because a model can look sharp purely
by being over-confident.

On the calibration chart, buckets holding fewer than three trips are plotted as faded dots
but are **not joined by the line** — connecting a one-trip bucket would draw a dramatic
zig-zag out of pure noise.

---

## 14. Behaviour with limited data

| Situation | Behaviour |
| --- | --- |
| No history at all | Routing baseline + documented priors; labelled *Preliminary*; wider intervals |
| One or two trips | Shrinkage keeps the correction small; parametric sampler |
| Sparse but weather-varying | Weather coefficient stays at its prior |
| Weather unavailable | No mean adjustment; scale × 1.06; notice shown |
| Events unavailable | Term excluded entirely; notice shown |
| Routing cached | Provenance `CACHED`, real age shown, scale widened with age |
| Routing unavailable and no cache | **No forecast**, with an explanation — not a guess |
| Deadline unreachable | `feasible = false`, best-effort probability, honest copy |

---

## 15. Insight generation

*Implementation:* `InsightEngine.kt`.

Insights require a minimum of five usable trips overall and four per compared group. Every
insight carries the sample size that produced it. There is deliberately **no fallback
insight**: when there is nothing defensible to say, the screen says the history is too thin
and shows nothing else. Generated insight types: day-of-week effect, time-bucket over-run,
route bias, recent streak, weather effect *or its absence*, parking-time drift, readiness,
volatility change, and interval coverage.

---

## 16. Assumptions

1. Driving with parking. Public transport, cycling and walking journeys are not modelled.
2. The routing provider's traffic-aware duration is an unbiased-up-to-a-multiplier estimate
   of road time. The bias model corrects the multiplier; it cannot fix a structurally wrong
   route.
3. Preparation, parking and walking are conditionally independent of travel time given the
   context. Correlation between them (a stressful morning making everything slower) is not
   modelled.
4. Weather affects travel time through a single scalar severity index.
5. Residuals within a group are exchangeable after recency weighting. Serial correlation
   between consecutive days is not modelled.
6. A user's routine is stable enough that a 12-observation half-life is appropriate.

## 17. Limitations

1. The empirical bootstrap can only reproduce tail events the user has already
   experienced; the parametric disruption tail exists to cover the rest, and its parameters
   are priors rather than learned.
2. With very few observations the interval width is driven almost entirely by priors, which
   are intentionally conservative and therefore wide.
3. Event pressure is heuristic and unvalidated against outcomes.
4. Alternative routes are fetched but not modelled — the engine forecasts the primary route.
5. The solver assumes probability is monotonically decreasing in departure time when
   bracketing. Unusual route dynamics (a road opening at 07:00) could in principle break
   this; the full curve is still evaluated and the violation reported, so the failure would
   be visible rather than silent.
6. Calibration on small samples is noisy. A 24-trip history gives coverage estimates with
   a standard error of roughly ±8 percentage points, which the minimum-sample warning
   exists to communicate.

## 18. Possible future work

- Quantile regression on the residual pool, conditioning the whole distribution on
  time-of-day and congestion rather than only its location and scale.
- Modelling correlation between preparation and travel conditions.
- Learning the disruption parameters `p` and `m` from observed tail events.
- Scoring alternative routes and recommending a route as well as a time.
- Conformal prediction to give the intervals a finite-sample coverage guarantee.
- A proper hierarchical model fitted by variational inference, replacing the credibility
  recursion, once there is enough cross-user data to estimate the between-group variances
  rather than assuming κ.
