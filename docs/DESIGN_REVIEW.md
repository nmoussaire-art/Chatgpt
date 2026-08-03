# Design review — from "credible" to "beautiful"

Based on the twelve screenshots from a real device (Samsung, dark theme, large display size).

The honest summary: **the app currently looks like a very well-written settings screen.** Every
screen follows the same shape — title, subtitle, then N identical dark-grey rounded cards full of
label-on-the-left / value-on-the-right rows. It reads as trustworthy, which was the brief, but
nothing on any screen is *the thing you look at*. Best-in-class apps always have one.

There are also six genuine layout defects visible in the screenshots that no amount of styling will
fix. Those come first.

---

## Part A — Defects to fix regardless

These are bugs, not taste.

### A1. The "Chances" tab label wraps to two lines

Visible in **all twelve screenshots**. It renders as `Chance` / `s`, which pushes the `%` icon
upward and breaks the alignment of the entire navigation bar. On a device at large display size
this is the first thing anyone notices.

`BatteryCastNavHost.kt:63`

Fix: rename to a short word (**"Odds"** or **"Risk"**) and constrain the label:

```kotlin
label = { Text(destination.label, maxLines = 1, softWrap = false) }
```

Every tab label should be ≤6 characters. `Home / Curve / Odds / Charge / More` — and "Charge"
should probably become "Plan", since the tab is the planner, not a charging readout.

### A2. Target-time chips are clipped at the right edge

`Bedtime · 11:00 p.m.` then `Midnig`— sliced off with no visual affordance that the row scrolls.

`DashboardScreen.kt:370` — the `LazyRow` sits inside a card with 20dp padding and inherits no
content padding of its own, so the second chip is cut by the card bounds rather than bleeding past
them.

Fix: let the row run edge-to-edge inside the card and give it its own content padding, so a
partially visible chip looks deliberate:

```kotlin
LazyRow(
    modifier = Modifier.padding(horizontal = (-20).dp),   // cancel the card's inset
    contentPadding = PaddingValues(horizontal = 20.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
)
```

### A3. "until in 2 hours (5:21 p.m.)"

`DashboardScreen.kt:184`. The target label is interpolated straight into a sentence that already
contains "until". Quick-target labels need two forms: a chip form ("In 2 hours") and a sentence
form ("5:21 p.m."). The sentence should read:

> chance of staying above 10% until **5:21 p.m.**

with "in 2 hours" as quieter secondary text, not inside the clause.

### A4. "Chance of still being above each level at 5:21 p.m.**..**"

`ProbabilityScreen.kt:86` appends `"."` to a string that already ends in `p.m.` — double period.
Same class of bug likely lurks anywhere `clockTime` ends a sentence.

### A5. "Ac" and "Usb"

`ChargePlannerScreen.kt:148` and `HistoryViewModel.kt:104` do
`.lowercase().replaceFirstChar { uppercase }`, which turns `AC` into `Ac` and `USB` into `Usb`.
Acronyms need an explicit display-name map on `PlugType`: `AC`, `USB`, `Wireless`, `Dock`.

### A6. Four scenarios all showing exactly 64%

"1 hour of video", "30 minutes of gaming", "45 minutes of navigation" and "1 hour of hotspot" all
read **64%** with identical bars. That is *correct* — none of those regimes has been observed yet,
so all four fall back to the same substituted rate — but on screen it looks like a broken
calculation, which is worse than saying nothing.

Fix: when several scenarios resolve to the same substituted regime, collapse them into one row:

> **Heavy use of any kind** · 64%
> Video, gaming, navigation and hotspot all fall back to the same observed behaviour until
> BatteryCast has seen them separately.

### A7 (related, cosmetic but damaging). Content is clipped at the nav bar, not scrolling under it

`BatteryCastNavHost.kt:96` applies `Modifier.padding(innerPadding)` to the `NavHost`, so scrolling
content stops dead at the navigation bar. Modern Android scrolls content *under* a translucent bar.

Fix: stop padding the NavHost; hand `innerPadding` to each screen's `LazyColumn` as
`contentPadding`, and give the `NavigationBar` a translucent container plus a top hairline. This is
a small change with a disproportionate effect on how finished the app feels.

---

## Part B — The three changes that do most of the work

If only three things get done, do these.

### B1. Replace the big text percentage with a survival arc

Right now the headline is `74%` set in 72sp bold — large text, but still just text, and it happens
to be amber, which makes the app's single most important number look like a warning.

Replace it with a **240° arc gauge**, roughly 200dp, drawn on Canvas:

- Track: `surfaceContainerHighest`, 14dp stroke, round caps.
- Progress: a sweep gradient across the probability colour, 14dp, animated from 0 on entry.
- Centre: the percentage in ~64sp with tight tracking, the word "chance" beneath it at 13sp.
- Below the arc: `staying above 10% until 5:21 p.m.` on one or two lines.
- A second, thin inner ring showing **current battery level** — so the hero shows both "where you
  are" and "how likely you are to make it", which is the whole product in one glyph.
- Tick marks at the reserve threshold so the arc has something to be measured against.

This is the difference between a dashboard and a *product*. It also gives the app a screenshot
that means something at thumbnail size on a store listing.

### B2. Fix the colour thresholds, and stop using amber for four different things

Currently amber is: the maturity chip, the headline number, every probability bar, and caution. So
the whole app reads amber-on-charcoal — which subconsciously says "warning" on a screen whose
message is "you're fine".

Two fixes:

**Thresholds.** `BatteryCastSemanticColors.forProbability` uses `≥0.80` high, `≥0.55` medium. For a
battery forecast that is far too strict — 74% is a comfortable outcome and should read green. Move
to `≥0.70` high, `≥0.45` medium.

**Assignment.** Reserve amber and clay *exclusively* for risk. The maturity chip
("Preliminary live estimate") should be a neutral outline chip in `onSurfaceVariant`, not amber —
it is information about confidence, not a warning. It currently competes with the headline for
attention and wins.

### B3. Give the chart room and clarity

The fan chart is the most distinctive thing in the app and it is currently rendered as a muddy
teal wedge about 180dp tall.

- **Ribbon colour.** Three overlapping semi-transparent mints composite into murky blue-green. Use
  one hue and let opacity do the work: 90% band at 8%, 80% at 14%, 50% at 24% — and paint them as
  *vertical gradients* fading downward, not flat fills.
- **Median line.** 3dp, round cap, with a soft outer glow (draw the same path twice, once at 8dp
  and 15% alpha). Add a filled dot with a subtle halo at "now".
- **Smoothing.** The path is a polyline over 5-minute steps and reads as visible straight segments.
  Use cubic Bézier through the points (Catmull–Rom → Bézier conversion); the underlying data is
  unchanged, only the rendering.
- **Threshold lines.** The three dashed grey lines at 20/10/5 currently look like chart junk. Drop
  to one dashed line at the user's chosen reserve, labelled inline at the right edge.
- **Height.** 180dp on the home card and 280dp on the Curve screen is too small for something this
  central. 220dp and 340dp, with the axis labels moved inside the plot area to reclaim space.

---

## Part C — The rest of the plan

### C1. Break the card monotony with stat tiles

"Expected battery at 5:21 p.m." is currently three label/value rows of identical weight, so
"Median 22%" and "Conservative 0%" look equally important when one is the answer and the other is
the caveat.

Replace with a 3-up tile row: number large (28sp, semibold, tabular), label tiny beneath (11sp,
uppercase, letter-spaced, `onSurfaceVariant`). Median gets the accent colour; the other two stay
neutral. This is the pattern Oura, Whoop and Gentler Streak all use, and it scans in about a third
of the time.

### C2. Tighten the typography

Line heights are around 1.5×, which is why nearly every heading wraps and the screens feel loose
and wobbly.

- Titles: line height 1.25×, not 1.35×.
- Body: 1.4× is fine, but drop `bodyMedium` letter-spacing from 0.2sp to 0.1sp.
- Numbers: everything numeric should use a tabular-figure style with `-0.5sp` tracking and
  `FontWeight.SemiBold`, so columns align and values read as data rather than prose.
- The hero number wants `-2.5sp` tracking, which it already has — keep that.

### C3. Survive large display sizes

The screenshots are at a large system font scale and it shows: "Expected battery at / 5:21 p.m."
wraps mid-phrase, section titles wrap, chips overflow. The app must not assume default scale.

- Every title: `maxLines = 2, overflow = TextOverflow.Ellipsis`.
- The hero percentage: auto-size, or clamp the composition-local font scale for that one element.
- `StatRow`: when the value is long, stack it under the label instead of squeezing both onto one
  line. A simple `SubcomposeLayout` or a width threshold handles it.

### C4. Handle the 0% floor gracefully

"Likely range **0%–35%**", "Conservative estimate **0%**", "Above 0%: 100%" — three places where a
legitimate result reads as a broken one. Because paths clamp at zero, a wide forecast will always
show 0 at the bottom.

Present it as what it means: **"could reach empty"**, in the risk colour, instead of "0%". The
number is honest; the phrasing is what makes it informative rather than alarming.

Similarly `"Reaches 10% — not reached"` with "probability within the window: 49%" underneath is a
double negative. Better: **"49% chance, no median time"**, or simply "about even".

### C5. Motion

There are currently two animations in the whole app. Best-in-class apps use motion to explain
change, not to decorate.

- Arc sweeps from 0 on first composition; the number counts up with it.
- Cards stagger in at 40ms intervals with a 12dp translate and fade.
- Chart draws left-to-right over ~700ms (`animatedReveal` already exists but is hard-coded to 1f —
  it never animates; wire it to an `animateFloatAsState` triggered on data change).
- Target-chip selection: crossfade the hero rather than snapping.
- Haptic tick on chip select and on chart scrub. Cheap, and it makes the app feel expensive.

### C6. Surface hierarchy

Every card is the same `surfaceContainer`. Introduce three levels:

| Role | Surface | Treatment |
|---|---|---|
| Hero | `surfaceContainerHigh` + a radial accent glow at 6% behind the arc | No border, 28dp corners |
| Primary content | `surfaceContainer` | 24dp corners |
| Secondary / explanatory | transparent, on the background, with a hairline divider | No card at all |

The privacy footer, the "On app attribution" note and the measurement-quality block do not need to
be cards. Removing card chrome from explanatory text immediately makes the real content look more
important.

### C7. The "More" screen

Five near-identical cards with a small mint icon floating to the left of a two- or three-line text
block. The icons are lost and the rows are very tall.

Better: a single grouped list — no per-item cards — with a 40dp tinted circle holding each icon,
title at `titleMedium`, description on one line with ellipsis, and a trailing chevron. Halves the
height and looks like a system settings list, which is the right idiom for a menu.

### C8. Charge planner sliders

The two sliders currently show as green dotted tracks with a tall bar thumb — Material 3's default,
which reads oddly here. Give them a value bubble above the thumb, remove the tick marks (they imply
discrete steps that do not matter), and put the current value in the label at `titleMedium` so
"Battery at the event: **40%**" has the number emphasised.

### C9. Two things worth building that the app does not have

- **A home-screen widget.** For a battery-forecast app this is arguably the primary surface: a
  2×2 widget with the arc and "84% until 10 PM" is the thing people would actually keep. Glance
  makes this ~200 lines.
- **A dedicated "arrival" answer.** The README promises "what battery will I have when I arrive
  home?" but there is no screen that answers a *place*-based question. Even without location
  permission, "when I arrive" is just a target time — worth surfacing as a first-class quick target
  rather than leaving it in the README.

### C10. App icon

It was designed without ever seeing it on a launcher. At 48dp the battery outline dominates and the
forecast curve inside it — the distinctive part — disappears. Invert the emphasis: drop the battery
body to a thin hairline or remove it entirely, and make the fan curve the mark. A rising/falling
line inside a soft mint-to-teal gradient square would be more recognisable and more modern.

---

## Suggested order

| Phase | Work | Effort | Visible impact |
|---|---|---|---|
| 1 | A1–A7 defects | ~2h | Removes everything that looks unfinished |
| 2 | B2 colour discipline, C2 typography | ~2h | The whole app stops reading as "warning" |
| 3 | B1 survival arc | ~4h | Transforms the home screen |
| 4 | B3 chart rework | ~4h | Transforms the two chart screens |
| 5 | C1 stat tiles, C6 surface hierarchy, C7 More screen | ~4h | Removes the "settings screen" feel |
| 6 | C5 motion, C8 sliders, C4 phrasing | ~3h | Polish |
| 7 | C9 widget, C10 icon | ~6h | Reach beyond the app itself |

Phases 1 and 2 are roughly four hours and account for most of the gap between "this looks like a
side project" and "this looks shipped".
