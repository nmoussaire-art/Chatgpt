# Weekly US Equity Seasonality Report
## Trade week: Friday 2026-09-04 close → Friday 2026-09-11 close

**Report generated:** 2026-09-06 (Sunday) · **Data retrieved:** 2026-09-06 17:40 UTC
**Entry reference closes (2026-09-04):** SPX 7,718.60 · NDX 29,544.16 · SPY 770.19 · QQQ 718.96 · VIX 14.53

**Calendar adjustment:** Monday 2026-09-07 is Labor Day (US market holiday). The held week
contains **4 sessions** (Tue 9/8, Wed 9/9, Thu 9/10, Fri 9/11). Entry and exit are both normal
full sessions; no adjustment to the entry or exit date is required.

---

# Weekly verdict

| Market | Bias | Probability of gain | Expected return | Confidence | Preferred position |
|---|---|---|---|---|---|
| Nasdaq-100 / QQQ | **Neutral** | ~54% | +0.1% (CI spans zero) | **Low** | **No trade** |
| S&P 500 / SPY | **Neutral** | ~54% | +0.1% (CI spans zero) | **Low** | **No trade** |

**Bottom line:** the two defensible ways of defining "this week" — as *the Labor Day week* (event-based)
and as *the Sep 8–14 week* (calendar-based) — produce **opposite** signals, and neither is statistically
distinguishable from the unconditional weekly baseline. The regime and analogue evidence leans mildly
negative; the calendar-window evidence leans mildly positive. They cancel. There is no edge here worth
paying spread for.

Reference base rates: SPX unconditional weekly win rate **56.9%** (n=2,696, 1975–2026), mean **+0.20%**;
NDX **56.0%** (n=1,578, 1996–2026), mean **+0.30%**. **Any seasonal claim this week must beat those
numbers to matter — none does.**

---

# 1. Strongest applicable seasonal signals

Ranked by how much they should move the decision, not by how striking the headline looks.

### Signal 1 — "Labor Day week" weakness · **Weak or unstable pattern**
- **Definition:** enter at the close of the last session before Labor Day; exit at the close of the
  final session of the Labor Day week (Friday, 4 sessions later).
- **Period / n:** 1975–2025, **n=51** (SPX); 1996–2025, n=30 (NDX).

| Series | n | Win% | Mean | Median | SD | Worst | Best | Bootstrap CI (mean) |
|---|---|---|---|---|---|---|---|---|
| SPX (index price) | 51 | 51.0% | −0.04% | +0.08% | 1.91% | −4.25% | +3.65% | [−0.56%, +0.47%] |
| NDX (index price) | 30 | 53.3% | −0.05% | +0.60% | 3.70% | −7.89% | +7.05% | [−1.35%, +1.24%] |
| SPY (price only) | 33 | 51.5% | −0.01% | +0.07% | 2.18% | −4.76% | +4.03% | [−0.74%, +0.71%] |
| QQQ (price only) | 27 | 48.1% | −0.70% | −0.19% | 3.24% | −7.44% | +4.04% | [−1.92%, +0.47%] |
| SPY (total return) | 9 | 33.3% | −0.57% | −0.93% | 2.32% | −4.14% | +3.66% | [−1.93%, +0.92%] |
| QQQ (total return) | 9 | 33.3% | −1.11% | −1.31% | 3.16% | −5.79% | +4.04% | [−3.02%, +0.86%] |

**Why it is not tradable:**
- The full-sample mean is **−0.04%** — economically zero. Permutation p = **0.44** (SPX).
- It is entirely a *recent* phenomenon: 1975–1999 **+0.33%** (56.0% win), 2000–2025 **−0.40%** (46.2% win),
  2016–2025 **−0.80%** (30.0% win, n=10). An effect absent in the first half of the sample and present
  in the second is a regime artifact or noise, not a stable seasonal.
- **It fails perturbation.** Moving the entry one session earlier flips the mean to **+0.08%**;
  holding two weeks gives **+0.05%**. A real effect should not invert when the rule is nudged by a day.
- The 33%-win headline for SPY/QQQ total return rests on **n=9**. That is folklore, not evidence.

### Signal 2 — September weakness is **back-loaded**, and this week is in the good half · **Promising but limited evidence**
- **Definition:** SPX daily returns 1975–2025 grouped by day-of-month within September.

| Window | Sessions | Avg daily | Win% | Avg cumulative per year |
|---|---|---|---|---|
| Sep 1–7 | 204 | −0.019% | 49.5% | −0.07% |
| **Sep 8–14** | **251** | **+0.060%** | **59.0%** | **+0.29%** |
| Sep 15–21 | 255 | −0.078% | 47.8% | −0.39% |
| Sep 22–28 | 255 | −0.053% | 47.5% | −0.26% |
| Sep 29–30 | 74 | −0.234% | 47.3% | −0.34% |

- **All four sessions of the 2026 trade week (Sep 8–11) fall in the single best window.**
- Confirmed at weekly frequency: SPX weeks *exiting* Sep 22–28 win only **31.4%** with mean **−0.79%**
  (n=51, CI [−1.36%, −0.20%] — excludes zero). That is the real September seasonal, and it is
  **three weeks away**, not this week.
- **Caveat:** the Sep 8–14 *weekly* setup itself is only +0.23% / 56.9% win (SPX, n=51) — identical to the
  56.9% unconditional baseline, permutation p = **0.91**. The window is *not weak*; it is not *strong* either.

### Signal 3 — Holiday-shortened weeks are usually good; this one historically is not · **Interesting historical tendency**
- **Definition:** weekly trades where the held week contains 4 sessions instead of 5.

| Series | 4-session weeks | 5-session weeks |
|---|---|---|
| SPX | n=446, 59.0% win, **+0.386%** | n=2,247, 56.5% win, +0.163% |
| NDX | n=102, 62.7% win, **+0.539%** | n=1,476, 55.6% win, +0.284% |

- The general holiday effect is well documented and reasonably supported here (n=446).
- **But the Labor-Day-shortened week is the exception:** SPX 51.0% win / −0.04% versus +0.386% for the
  average shortened week — a relative drag of ≈0.43pp. This is the most honest framing of the
  Labor Day effect: not negative in absolute terms, but the weakest of the shortened weeks.
- Sample for the specific claim is still only n=51, and see Signal 1's perturbation failure.

### Signal 4 — Entering after two consecutive up weeks · **Likely statistical coincidence (as a *seasonal* claim)**
- **Definition:** the seasonal setup, filtered to entries preceded by two positive weeks (**today's state**).

| Cut | n | Win% | Mean | Bootstrap CI |
|---|---|---|---|---|
| SPX Labor Day week, after 2 up weeks | 18 | 27.8% | −0.78% | [−1.40%, −0.19%] |
| SPX Sep 8–14 week, after 2 up weeks | 14 | 28.6% | −0.60% | [−1.15%, −0.02%] |
| NDX Labor Day week, after 2 up weeks | 10 | 30.0% | −1.27% | [−2.82%, −0.02%] |
| NDX Sep 8–14 week, after 2 up weeks | 10 | 20.0% | −1.07% | [−2.54%, −0.04%] |

This looks compelling and **it is the main reason not to be long**. It is nonetheless **not a seasonal edge**:

- **It is not September-specific.** The two-up-weeks drag is a year-round short-term reversal effect,
  and September is where it is *weakest*:

| SPX | After 2 up weeks | Otherwise | Spread |
|---|---|---|---|
| All months | n=836, 53.8%, +0.065% | n=1,858, 58.3%, +0.260% | **−0.196%** |
| September only | n=67, 44.8%, −0.149% | n=153, 55.6%, −0.126% | **−0.023%** |
| All other months | n=769, 54.6%, +0.083% | n=1,705, 58.5%, +0.295% | −0.212% |

  The September "interaction" is an illusion created by slicing; the underlying effect is generic and
  worth about **−0.20% relative**, not −0.78%.
- **Multiple testing:** 6 regime flags × 2 setups × 2 indices = **24 cuts**; ~1.2 are expected to exclude
  zero by chance. Four did — but all four are the *same* flag on **correlated** data
  (corr(SPX, NDX) on these weeks = **0.89**). That is roughly one finding, not four.
- Permutation p for the exact rule = **0.063**; Bonferroni-adjusted across 24 cuts → **≈1.00**.
- To its credit, it *does* survive an out-of-sample split (discovery 1975–1999: −0.65%, 22.2% win, n=9;
  validation 2000–2025: −0.90%, 33.3% win, n=9). That consistency is why it is listed at all — but
  n=9 per half, and a generic explanation already accounts for it.

### Signal 5 — Week before September triple witching · **Weak or unstable pattern**
- **Definition:** the week ending the Friday *before* September opex week (opex = Fri 2026-09-18).

| Series | n | Win% | Mean | Median | Permutation p |
|---|---|---|---|---|---|
| SPX | 51 | 56.9% | +0.23% | +0.53% | 0.91 |
| NDX | 30 | 56.7% | +0.65% | +0.66% | 0.57 |

- Mildly positive, indistinguishable from baseline. For contrast, the September **opex week itself**
  is SPX +0.07% / NDX −0.15%, and the week after Labor Day + 2 (opex week) runs 50.0% win / −0.28%.
- Useful mainly as a *negative* finding: there is no pre-opex drag to trade against this week.

---

# 2. Historical analogues

**Configuration match — the latest possible Labor Day.** Labor Day 2026 falls on **September 7**, the
latest date it can occur (it happens when September 1 is a Tuesday). This matters because it pushes the
entire trade week into the Sep 8–14 window rather than straddling the weaker Sep 1–7 stretch.

SPX, Labor Day week grouped by Labor Day's calendar date, 1975–2025:

| Labor Day date | n | Win% | Mean | Median |
|---|---|---|---|---|
| Sep 1 | 8 | 62.5% | +0.20% | +0.27% |
| Sep 2 | 7 | 42.9% | −0.68% | −0.21% |
| Sep 3 | 7 | 28.6% | −1.01% | −1.39% |
| Sep 4 | 7 | 28.6% | −0.20% | −0.92% |
| Sep 5 | 8 | 50.0% | +0.23% | +0.15% |
| Sep 6 | 7 | 57.1% | −0.24% | +0.08% |
| **Sep 7 (2026)** | **7** | **85.7%** | **+1.33%** | **+1.67%** |

The seven Sep-7 years: 1981 +1.28%, 1987 +1.67%, 1992 +0.60%, 1998 +3.61%, 2009 +2.59%, 2015 +2.07%,
2020 −2.51%. NDX where available (4 years): 1998 +7.05%, 2009 +2.89%, 2015 +3.31%, 2020 −4.60%.

**Do not trade this.** n=7, permutation p = **0.169**, bootstrap CI [−0.12%, +2.50%] includes zero, and
the grouping was chosen *after* seeing that the Labor Day effect varies by date — seven buckets of seven
observations will always produce one impressive-looking bucket. It is a genuine ex-ante-identifiable
configuration and it is the single best argument against being short, but it is not evidence of an edge.

**State match — trend, momentum and volatility.** Ranking every Sep 8–14 week from 1990–2025 by
similarity to today (distance from 200-dma +8.1%, prior week +0.09%, week before +0.49%, VIX at the
5th percentile):

| Year | Entry → Exit | SPX | NDX | vs 200dma | wk−1 | wk−2 | VIX %ile |
|---|---|---|---|---|---|---|---|
| 2014 | 09-05 → 09-12 | −1.10% | −0.51% | +6.6% | +0.22% | +0.75% | 18% |
| 2025 | 09-05 → 09-12 | +1.59% | +1.86% | +8.6% | +0.33% | −0.10% | 17% |
| 1993 | 09-03 → 09-10 | +0.08% | n/a | +3.8% | +0.17% | +0.96% | 4% |
| 1999 | 09-03 → 09-10 | −0.41% | +1.12% | +5.0% | +0.67% | +0.87% | 6% |
| 1995 | 09-01 → 09-08 | +1.57% | n/a | +11.1% | +0.67% | +0.16% | 7% |
| 2000 | 09-01 → 09-08 | −1.73% | −6.98% | +5.5% | +0.95% | +0.99% | 4% |
| 2016 | 09-02 → 09-09 | −2.39% | −2.44% | +6.1% | +0.50% | −0.68% | 4% |
| 1992 | 09-04 → 09-11 | +0.60% | n/a | +2.0% | +0.54% | −0.00% | 3% |
| 2017 | 09-01 → 09-08 | −0.61% | −1.24% | +4.8% | +1.37% | +0.72% | 10% |
| 2021 | 09-03 → 09-10 | −1.69% | −1.36% | +11.6% | +0.58% | +1.52% | 10% |

Top-10 analogues: SPX **40% win, mean −0.41%**; NDX (7 available) **29% win, mean −1.36%**.

These are shown in full and unfiltered, including the winners (2025, 1995, 1992) and the losers
(2000, 2016, 2021). This is a hand-ranked neighbourhood of n=10 — illustrative of the mild negative
tilt from the momentum/volatility state, **not an independent test**.

---

# 3. Bull case versus bear case

**Bull case**
- All four sessions sit in **Sep 8–14**, historically September's only positive stretch
  (+0.060% average daily, 59.0% win, n=251 sessions). September's documented weakness is concentrated
  in Sep 15–30 and does not begin until the following week.
- The **latest-possible-Labor-Day** configuration has produced 6 up years in 7 (mean +1.33%) — weak
  evidence, but it points up, not down.
- Holiday-shortened weeks in general run **+0.386%** with a 59.0% win rate (SPX, n=446).
- Trend is unambiguously constructive: SPX **+8.1%** above its 200-dma, NDX **+9.3%**; both above the
  50-dma; SPX within **1.0%** of its 52-week high. Weeks with SPX above the 200-dma historically win
  57.9% (n=1,951).
- No Fed meeting, no opex, no ex-dividend inside the week. The FOMC (Sep 15–16, with projections)
  and triple witching (Sep 18) are both in the *following* week.

**Bear case**
- The entry follows **two consecutive up weeks** into a low-volatility, extended tape. Every cut of
  this filter is negative (SPX Labor Day week 27.8% win / −0.78%; NDX Sep 8–14 20.0% win / −1.07%),
  and the closest state-matched analogues are 40% win / −0.41% (SPX) and 29% / −1.36% (NDX).
- **VIX at 14.53 sits in the 5th percentile of the trailing year** (1-year median 17.21). In the Sep 8–14
  window, entries with VIX in the bottom third returned **−0.14%** (SPX) and **−0.29%** (NDX), versus
  **+1.14%** and **+1.47%** when VIX was higher. Cheap protection, thin cushion.
- The Labor Day week is the weakest of all holiday-shortened weeks, and the last decade has been
  genuinely poor (SPX 30% win, −0.80%, n=10; NDX 30% win, −1.27%, n=10).
- **August CPI prints Friday 2026-09-11 at 08:30 ET — on the exit day itself.** The exit price is set
  hours after a top-tier macro release, with the FOMC's projection meeting four days later. This raises
  exit-day variance without any directional prior.
- NDX/QQQ carry the wider dispersion: weekly SD **2.77% vs 2.14%** in the Sep 8–14 window and
  **3.70% vs 1.91%** on the Labor Day framing. On the Labor Day framing NDX's 10th percentile is
  **−5.89%** versus −2.42% for SPX; within the Sep 8–14 window the two downside tails are comparable
  (−2.44% vs −2.39%) and the difference is mostly upside (90th pct +4.05% vs +2.68%).

---

# 4. Combined assessment

**The signals contradict each other, and the contradiction is the finding.**

1. **The two framings of "this week" disagree by construction.** Defined by the *event* (Labor Day week),
   SPX gives 51.0% win / −0.04%. Defined by the *calendar* (exit in ISO week 37), the same notion of
   "the week after Labor Day" gives **60.8% win / +0.33%** (n=51); exit in ISO week 36 gives
   42.3% / −0.52%. Which answer you get depends on an arbitrary definitional choice. When a pattern's
   sign is a function of how you index the calendar, it is not a property of the market.

2. **Nothing survives correction.** Permutation p-values for the applicable setups: Labor Day week 0.44
   (SPX), 0.56 (NDX); Sep 8–14 window 0.91 (SPX), 0.57 (NDX); pre-opex week 0.91 (SPX), 0.57 (NDX).
   The one cut that approaches significance (two-up-weeks, p=0.063) is fully explained by a generic,
   non-seasonal reversal effect and dies under Bonferroni (≈1.00).

3. **I have not averaged overlapping signals.** Signals 1, 3 and 4 describe the *same 51 weeks* sliced
   three ways, and SPX/NDX correlate at 0.89 on these dates. Treating them as independent confirmation
   would manufacture false confidence. Weighted honestly, the seasonal evidence is: one long-history
   result at zero (Labor Day week, n=51), one long-history result at baseline (Sep 8–14, n=51), and one
   genuine but non-seasonal reversal drag worth ≈−0.20%.

4. **Net expectation.** The seasonal framings blend to roughly **+0.10% (SPX) / +0.30% (NDX)** with a
   53.9% / 55.0% win rate — *below* the 56.9% / 56.0% unconditional base rate. The momentum and
   volatility state subtracts perhaps 0.2pp more. The result is an expectation indistinguishable from
   zero and a probability of gain around **54%**, which is *worse* than simply being long a random week.

5. **The genuinely robust September finding is not actionable today.** SPX weeks exiting **Sep 22–28**
   win 31.4% with mean −0.79% (n=51, CI excludes zero) — the strongest calendar result in this study.
   That setup arrives in three weeks. Trading a non-edge now is the fastest way to be out of position,
   or out of conviction, when it does.

**Conclusion: neutral, low confidence, no trade in either index.**

---

# 5. Trade framework

| Item | Nasdaq-100 / QQQ | S&P 500 / SPY |
|---|---|---|
| **Direction** | **No trade** | **No trade** |
| Intended entry | Fri 2026-09-04 close (NDX 29,544.16 / QQQ 718.96) | Fri 2026-09-04 close (SPX 7,718.60 / SPY 770.19) |
| Intended exit | Fri 2026-09-11 close | Fri 2026-09-11 close |
| Historical expected return | +0.1% to +0.3%, CI spans zero | +0.1%, CI spans zero |
| Probability of profit | ~54% (vs 56.0% unconditional) | ~54% (vs 56.9% unconditional) |
| P(gain > 1%) / P(loss > 1%) | 47% / 20% (Sep 8–14, n=30) | 37% / 29% (Sep 8–14, n=51) |
| Reasonable adverse-move range | Median worst mark −0.69%; 25th pct −1.50%; worst −6.98% | Median worst mark −0.78%; 25th pct −1.43%; worst −7.91% |
| Weekly dispersion (SD) | 2.77% | 2.14% |

**Why no position:** the best directional estimate is ~54% — my brief is explicit that a probability
slightly above 50% is not a reason to trade, and here it is *below* the unconditional base rate. After a
conservative **4 bp** round-trip cost, the long side nets **+0.20%** (SPX Sep 8–14) and the short side
**−0.28%**; on the Labor Day framing the long nets −0.08% and the short +0.00%. At n=51 with a 1.9–2.1%
weekly SD, a mean of roughly **±0.55%** is required for 95% significance. Nothing applicable reaches half
of that. There is no side of this trade that pays.

**Conditions that would change the assessment:**
- *Toward a long:* a sharp down week into the entry — the setup's returns are markedly better after a
  negative prior week (SPX +0.46% / 63.6% win vs +0.06% / 51.7% after an up week; NDX +2.19% / 83.3% vs
  −0.38% / 38.9%) and after a sub-50-dma entry (NDX +2.62% / 80.0% win). None of that describes today.
- *Toward a short:* a VIX break above the 1-year median (17.2) with SPX losing the 50-dma, which would
  align the momentum, volatility and back-half-of-September signals in the same direction — most likely
  from the week of Sep 21 onward, not now.
- *Invalidation of the "no trade" itself:* a CPI print on 9/11 large enough to reprice the Sep 15–16
  FOMC. That is event risk on the exit day, and it is unhedged directional exposure, not seasonality.
  It is a reason to stay flat, not a reason to pick a side.

**The edge is not strong enough to justify a position in either index this week.**

---

# 6. Seasonality fact of the week

**When Labor Day falls on September 7 — the latest date possible — the S&P 500 has risen in 6 of the
last 7 occurrences (85.7%), averaging +1.33%, versus 28.6% and −1.01% when Labor Day falls on
September 3.**

The mechanism is plausible rather than mystical: a Sep-7 Labor Day pushes the entire holiday week into
the Sep 8–14 window, historically September's only positive stretch, and away from the Sep 15–30 decline
that accounts for essentially all of the month's bad reputation.

**Context, so this is not mistaken for a strategy:** n=**7** (1981, 1987, 1992, 1998, 2009, 2015, 2020).
The bootstrap confidence interval is [−0.12%, +2.50%] and includes zero; permutation p = 0.169. The one
loss, 2020, was −2.51%. Seven observations spread over 45 years, in a bucket selected after inspecting
all seven possible Labor Day dates, is a coincidence-shaped result. It is offered as a genuinely
interesting calendar quirk and an argument against shorting — **not** as a reason to buy.

---

# 7. Data and methodology

**Data sources**
| Series | Symbol | Source | Coverage | Sessions |
|---|---|---|---|---|
| S&P 500 index close | SPX | Cboe (`cdn.cboe.com/api/global/us_indices/daily_prices/SPX_History.csv`) | 1975-01-02 → 2026-09-04 | 13,028 |
| Nasdaq-100 index close | NDX | Nasdaq (`api.nasdaq.com/api/quote/NDX/chart`) | 1996-06-06 → 2026-09-04 | 7,790 |
| SPY close (price only) | SPY | Nasdaq chart API | 1993-01-29 → 2026-09-04 | 8,458 |
| QQQ close (price only) | QQQ | Nasdaq chart API | 1999-03-10 → 2026-09-04 | 6,916 |
| SPY adjusted close | SPY | stockanalysis.com history API | 2016-09-07 → 2026-09-04 | 2,513 |
| QQQ adjusted close | QQQ | stockanalysis.com history API | 2016-09-07 → 2026-09-04 | 2,513 |
| VIX close | VIX | Cboe (`VIX_History.csv`) | 1990-01-02 → 2026-09-04 | 9,266 |

**Retrieval timestamp:** 2026-09-06 17:40 UTC. Event dates verified against primary sources:
BLS CPI release schedule (August CPI → **Fri 2026-09-11, 08:30 ET**), BLS Employment Situation schedule
(August payrolls → Fri 2026-09-04, the entry day), Federal Reserve FOMC calendar
(**Sep 15–16, 2026**, with Summary of Economic Projections).

**Adjustments**
- All returns are **close-to-close**. No intraday, open-to-close, or dividend-timing adjustment beyond
  what is stated.
- **Index price returns (SPX, NDX) and ETF total returns (SPY, QQQ adjusted) are reported separately and
  never mixed.** ETF price-only series are labelled "(price only)" throughout.
- **Verified for this specific week:** SPY and QQQ go ex-dividend on the *third* Friday of September
  (2016–2025: 9/16, 9/15, 9/21, 9/20, 9/18, 9/17, 9/16, 9/15, 9/20, 9/19), i.e. **2026-09-18**, which is
  *after* this trade exits. Measured dividend contribution inside every historical Sep 8–14 trade week is
  **0.000%**. Price and total returns are therefore directly comparable for this week — this will not
  hold for the following week's report.

**Validation performed**
- Cross-checked SPY closes between two independent sources (Nasdaq vs stockanalysis) over 2,513
  overlapping sessions: mean relative difference **0.00000089**, max 0.002.
- Verified the session calendar contains no weekend dates and exactly one gap >5 calendar days —
  the 2001-09-11 → 2001-09-17 exchange closure.
- Confirmed 2026-09-07 (Labor Day) is absent from the data and 2026-09-11 is a normal session.

**Methodology**
- Weekly trade = close of the last session of week W → close of the last session of week W+1, using ISO
  weeks. Holidays are handled implicitly because the calendar is built from actual sessions present in
  the price files. Consecutive-week pairs are required to be 3–11 calendar days apart.
- The Labor Day setup uses the **actual event date** (first Monday of September), not a week number, per
  the moving-date requirement. ISO-week results are reported separately and explicitly, and they disagree.
- Confidence intervals: **percentile bootstrap of the mean, 20,000 resamples**, seeded for reproducibility.
- Significance: two-sided t-statistic (normal approximation — samples are small, so treat as approximate)
  **and** a permutation test drawing same-size samples from the full weekly universe, 20,000–40,000 reps.
  The permutation test is the one to trust.
- Discovery/validation split (1975–1999 vs 2000–2025) reported for the primary rules; results from
  multiple sensible start dates shown rather than a single flattering one.
- Perturbation tests: entry shifted 1 and 2 sessions earlier, holding period extended to 2 weeks,
  event-based versus calendar-based definitions, and neighbouring-week and neighbouring-window controls
  (plus August/October controls for the Sep 8–14 window).

**Transaction-cost assumptions**
Round trip of **4 bp** (SPY/QQQ retail spread plus slippage; ES/NQ futures would be nearer 1–2 bp).
Applied to means as `long_net = mean − 0.0004`, `short_net = −mean − 0.0004`. No financing, borrow,
tax, or market-impact modelling. Costs are small relative to the noise here and are not what makes this
a no-trade — the absent edge is.

**Limitations and missing data**
- **NDX history begins 1996-06-06** in the accessible source; the index itself launched in 1985. NDX
  results therefore rest on ~30 years, and NDX Labor-Day-week subperiod cuts fall to n=10–15.
- **Verified dividend-adjusted ETF data covers only 2016-09 → 2026-09** (10 years, n=9 for this setup).
  Longer SPY/QQQ series are price-only. Every ETF total-return figure in this report with n=9 is
  reported with that sample size attached and should carry no weight on its own.
- **CPI/FOMC-week seasonality was not tested quantitatively.** I do not have a verified historical series
  of CPI release dates going back through the sample, and I will not estimate one from a mid-month
  heuristic. The 2026-09-11 CPI release is therefore treated as a stated, source-verified event risk on
  the exit day, not as a backtested factor.
- Adverse-move figures use **daily closes only**. True intraday drawdowns are larger; the −0.78% median
  worst mark for SPX understates what an intraday stop would have experienced.
- Multiple-testing exposure is real: roughly 24 regime cuts plus ~10 setup definitions were examined.
  P-values are reported unadjusted *and* with a Bonferroni note where it matters. The negative conclusion
  of this report is robust to that exposure; any positive claim in it would not have been.
- Index price returns exclude dividends and so understate long-run total returns. This does not affect
  the relative comparisons, which are all computed within a single series.

**Reproducibility.** `scripts/fetch_data.py` re-downloads every series into `data/`;
`scripts/seasonality.py` holds the shared calendar/statistics toolkit; the per-section scripts
(`context`, `labor_day`, `robust`, `analogues`, `window`, `regimes`, `validate`, `divcheck`, `final`,
`sessions`) regenerate every number quoted above. Full output is in
`results/2026-09-11_analysis_log.txt`.
