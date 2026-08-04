# v2 — Predictive Energy

The v1 interface was a quantitative diagnostics dashboard. Everything the model produced was on
screen at once, in identical cards, at identical weight, and the user was left to decode it.

v2 changes the information hierarchy from

> here are all the model outputs

to

> here is what will probably happen, here is what you should do, and here is the evidence when you
> want it.

Nothing was removed from the model. Every percentile, interval, path count and fitted rate is still
there — one disclosure below the answer instead of in front of it.

---

## The identity

| Role | Colour |
|---|---|
| Background | `#07111F` |
| Elevated surface | `#111E30` |
| Secondary surface | `#17263A` |
| Healthy / sufficient | `#5DE4B0` mint |
| Prediction / information | `#78A9FF` blue |
| Caution / tight margin | `#FFC857` amber |
| Unlikely to reach target | `#FF8F8F` coral |
| Text | `#F6F8FC` / `#A8B3C7` / `#718096` |

**Each hue has exactly one job.** v1's real colour failure was not the palette but the assignment:
mint meant "link", "chart", "positive" and "slider" at once, while amber meant "preliminary",
"headline number", "every progress bar" *and* "caution" — so the app read as a warning even when
the message was "you're fine". Reserving amber and coral strictly for risk is what stops that.

A light scheme carries the same identity on paper, with mint and blue darkened enough to hold
contrast. Dynamic colour is deliberately not applied to the brand hues: mint is simultaneously the
ring, the forecast line and the "you're covered" signal, and letting the wallpaper recolour it
would break the association between the number and the chart it came from.

---

## The hero: one glyph, four facts

The signature component is a 270° ring that answers both halves of the question at once.

| Element | Means |
|---|---|
| Bright arc | Battery you have now |
| Translucent arc beyond it | Charge the forecast expects you to spend before your target |
| Faint outer arc | 10th-to-90th-percentile range — **width is the message** |
| Tick | The reserve level, so the arc has something to be measured against |
| Dot | The predicted level at the target |

Reading the halo's width is far quicker than reading a printed interval, and it makes uncertainty
impossible to overlook. The whole ring is drawn from simulated paths; nothing in it is decorative.

Beneath it the screen reads, in order: **Likely 22% · at 5:21 PM · usually between 12% and 35% ·
74% chance of staying above 10% · you should be fine with normal use**. That is the product.

---

## Progressive disclosure

**Consumer layer** — Home, and the top of Forecast:
likely percentage, plain-language range, survival probability, one recommendation, one driver.

**Advanced layer** — behind "View model details":
median, mean, 50/80/90 % intervals, the 2,400-path provenance, fitted drain rate by horizon,
learned baseline, live current and power, measurement quality counts.

The phrase "2,400 simulated battery paths" is good evidence and belongs in the app. It does not
belong under the headline.

---

## Navigation

Four destinations, named for intentions rather than outputs:

```
Home        Forecast        Scenarios        Charge
```

"Curve" was a visualisation, not a goal. "Chances" was part of the forecast, not a destination —
and it wrapped onto two lines at the device's display size, breaking the alignment of the whole bar
on every screen. Every label is now one short word, single-line by construction.

`More` is gone; Settings is a gear in the top-right of Home, and the four detail screens (What
changed, History, Model accuracy, Privacy) hang off it. A menu item should not become a tab.

---

## Three surface levels, and only three

| Level | Use | Treatment |
|---|---|---|
| **Hero** | The one most important thing on a screen | 30dp corners, diagonal gradient, tone-tinted radial glow |
| **Card** | Ordinary content that needs a container | 22dp corners, flat elevated surface |
| **Quiet** | Explanation, provenance, footers | No container at all |

v1 gave a probability, an explanation and a privacy footer the same card. Removing the chrome from
prose is the cheapest way to make the real content look important.

Label/value rows were replaced by **stat tiles** — metric at 24–34sp with an 11.5sp uppercase label
beneath — so a median and a caveat no longer carry identical weight.

---

## The chart

- **One band by default.** The 80 % interval carries the message; 50 % and 90 % are chips the user
  can turn on. Three nested translucent fills on a dark surface merge into one indistinct triangle
  no matter how carefully the alphas are chosen — which is exactly what v1 produced.
- **Gradient beneath the median**, fading to nothing at the baseline.
- **Cubic smoothing.** v1 drew a polyline over five-minute samples and read as jagged. The curve is
  clamped so it cannot overshoot the data: only the rendering is smoothed, never the values.
- **One reserve line**, labelled inline, instead of three anonymous dashed lines.
- **Highlighted target column** rather than a bare marker.
- **Drag to read**, with a haptic tick on each move and the read-out placed *above* the chart so the
  value is never hidden under the finger.
- v1 declared a draw-in animation and then hard-coded it to `1f`, so it never ran. It runs now.

---

## Scenarios

v1 listed every scenario as a bar, so four activities that had all fallen back to the same
substituted rate appeared as four identical 64 % bars — mathematically correct and
indistinguishable from a broken calculation.

v2 shows **one scenario at a time** against the baseline: two rings, an arrow, and a sentence
("Navigation may use about 6% extra battery"). Provenance is stated under the estimate rather than
as an asterisk with a footnote elsewhere. And where several scenarios genuinely cannot be told
apart yet, the app says so once instead of implying a distinction the data does not support.

---

## Charge

v1 was a settings form followed by a row of em-dashes. v2 leads with the decision — **start
charging by 4:36 PM, about 24 minutes** — over a to-scale timeline of now → plug in → event, with
the controls underneath as adjustments rather than prerequisites.

Presets replace free sliders (20/40/60/80 %, Balanced 80 % / Safe 90 % / Very safe 95 %), each with
a haptic tick. `PlugType` now carries proper display names, so "Ac" and "Usb" are gone.

**"Remind me at 4:36 PM"** schedules a one-off WorkManager notification. It is the only alert that
bypasses the threshold-and-cooldown rules that keep the automatic ones quiet — a reminder you asked
for is not an interruption.

---

## Typography and scale

Hero 58sp · page title 32sp · card headline 22sp · metric 34sp · body 16sp · supporting 14sp ·
metadata 11.5sp uppercase. Line heights tightened to 1.2–1.25 on headings, which is why headings no
longer wrap into three loose lines.

The screenshots that drove this redesign were taken at a large system display size. Font scale is
still respected, but capped at 1.3× — beyond that the ring and the bottom bar cannot hold their
layout — and every title carries `maxLines` with ellipsis.

---

## Defects fixed

| From the review | Status |
|---|---|
| "Chances" tab wrapping to two lines | Renamed and constrained; four one-word labels |
| Target chips clipped at the card edge | Row bleeds past the inset and carries its own content padding |
| "until in 2 hours (5:21 p.m.)" | `TargetSelection` now carries both a chip form and a sentence form |
| "at 5:21 p.m.." double period | Removed |
| "Ac" / "Usb" | `PlugType.displayName` |
| Four identical 64 % scenarios | Grouped, with the reason stated |
| Content clipped at the nav bar | Edge-to-edge; insets passed as content padding, translucent bar |
| Zero-floor showing "0%" | "Could reach empty" |
| `animatedReveal` hard-coded to 1f | Wired to a real animation |

---

## What was not done

**The home-screen widget.** For a battery-forecast app this is arguably the primary surface, and
Glance would make it roughly 200 lines. It is the clearest next piece of work.

**Roboto Flex.** The system font is used throughout. A variable typeface would allow finer optical
sizing between metrics and labels, at the cost of shipping a font file; the type scale is doing
that work through size and weight instead.
