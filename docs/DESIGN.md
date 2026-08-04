# The interface

Version 1.1 keeps v1's information architecture — five destinations, the same screens, the same
numbers — and rebuilds the surface it is drawn on. Nothing in the model, the telemetry layer or the
background pipeline changed; every figure on screen still comes from this device's own battery.

An earlier attempt at a full redesign ("v2") reorganised the whole app and is not in this codebase.
It was rejected on the look, and it shipped a launch crash. What follows is the layout and visual
work applied to the original interface instead.

---

## Colour and surfaces

| Role | Dark | Light |
|---|---|---|
| Canvas | `#0B0D12` | `#F6F7F9` |
| Panel | `#141721` | `#FFFFFF` |
| Panel stroke | `rgba(255,255,255,0.08)` | `rgba(0,0,0,0.08)` |
| Inactive track | `#202634` | `#E3E7ED` |
| Safe · probability > 70 % | `#00E699` | `#00996B` |
| Warning · 30–70 % | `#FFB800` | `#B37A00` |
| Critical · < 30 % | `#FF4560` | `#D92D4B` |
| Primary text | `#FFFFFF` | `#0B0D12` |
| Secondary text | `#8F96A3` | `#5A616D` |

Elevation is one step of value plus a 1px stroke, never a shadow. On an OLED canvas a drop shadow
has nothing to fall on and only muddies the edge, whereas a hairline at 8 % white reads as a crisp
boundary at any screen brightness. Corner radius is 16dp throughout.

**Three status hues, each with exactly one job.** v1's real colour failure was not the palette but
the assignment: mint meant "link", "chart", "positive" and "slider" at once, while amber meant
"preliminary", "headline number", "every progress bar" *and* "caution" — so the app read as a
warning even when the message was "you're fine".

The direct consequence is that **helper links carry no status colour**. "Why" and "Open charge
planner" are secondary text with an underline. Green in this app means *your battery is fine*;
spending it on navigation chrome dilutes it everywhere else.

The probability thresholds (70 % / 30 %) are shared by the colour function and by the wording, so a
bar and the sentence beside it can never disagree.

---

## Layout defects fixed

| Defect | Fix |
|---|---|
| "Chances" wrapping onto two lines and breaking the bar's alignment on every screen | Renamed to **Insights**; every label now renders `maxLines = 1, softWrap = false` with 4dp vertical padding, so the wrap is structurally impossible |
| Target chips truncating — "Midnig…" | The strip moved out of its card onto the canvas, runs the full width of the display, and each chip stacks its name over its time instead of joining them on one line |
| No affordance that the chip strip scrolls | A gradient scrim on each end, painted only on the side that can actually be scrolled towards |
| "chance of staying above 10% until in 2 hours (5:21 PM)" | "**Chance of remaining above 10% until 5:21 PM**" — one clause, one time, no parenthetical |
| "Ac" / "Usb" | Proper charger names, in the UI layer, so the telemetry model stays a pure record of what Android reported |

Numbers that update in place — every percentage, rate and clock time — are set with OpenType
tabular figures (`tnum`), so a live figure stepping from 43 % to 44 % no longer nudges everything
beside it.

---

## The hero

```
┌──────────────────────────────────────────┐
│  LIVE ESTIMATE               ⚡ CHARGING  │
│                                          │
│                  ╭────╮                  │
│                 │  74%  │                │
│                 │ CHANCE │                │
│                  ╰────╯                  │
│                                          │
│  Chance of remaining above 10% until     │
│  5:21 PM                                 │
│ ─────────────────────────────────────── │
│    43%       -14.8%/h        12/15       │
│ BATTERY NOW  CURRENT DRAIN   LEARNING    │
└──────────────────────────────────────────┘
```

A 260° arc with the gap at the bottom, coloured by the same thresholds as the number inside it, so
the ring answers *how worried should I be* before the figure has been read at all. The headline
dropped from 72sp to 42sp: it now sits inside the ring rather than competing with it.

The three-column footer replaces a stack of label/value rows. The learning figure is real on both
sides — the numerator is the effective sample weight the model actually carries, and the
denominator is the constant `ForecastEngine` tests against to promote the forecast to the next
tier. It is not a progress bar invented to look busy.

---

## The chart

- **Monotone cubic Béziers** in place of a polyline over five-minute samples. Tangents come from
  Fritsch–Carlson, which makes the curve shape-preserving: where the samples are monotone the curve
  is too, so smoothing can never invent a bulge above a percentage the simulation did not produce.
  A plain Catmull-Rom spline would, and on a battery chart that is a lie with a straight face.
- **Layered fans at 30 % / 15 % / 5 %.** The 80 % band is on by default because it carries the
  message alone; 50 % and 90 % are chips, because three nested translucent fills on a dark surface
  merge into one indistinct triangle no matter how carefully the alphas are chosen.
- **A gradient beneath the median**, fading to nothing at the baseline.
- **No vertical gridlines anywhere.** Horizontal dashed guides at 25 % increments only, at 5 %
  white. On a time axis a vertical lattice adds structure the eye has to read past, and the x
  positions that matter — now, the target, the scrub point — are all marked explicitly.
- **A crosshair scrubber** with a read-out giving the exact time, the median and the 10–90 %
  interval, pinned to the top of the plot so the value is never under the finger reading it, with a
  haptic tick on each move.
- v1 declared a draw-in animation and then passed a constant, so it never ran. It runs now.

---

## Lists that became bars

**Threshold crossings** are now bars on a shared timeline: the tick is the median crossing time and
the wash around it is the 10th-to-90th-percentile interval. Stacked, they make obvious the one thing
a column of timestamps cannot show — that each successive level is predicted *less* precisely than
the last.

**Reserve levels** and **scenario comparisons** are probability bars, because the comparison between
rows is the entire point of those sections and a column of percentages makes the reader do that
comparison in their head. Scenario rows carry a glyph — 🎮 🧭 📶 🎬 — which is enough to find the row
you want without reading any of them.

---

## Controls

Material's slider draws a dotted tick for every step, which on an eighteen-step control is a row of
dots that reads as texture rather than information and competes with the value being set. It is
replaced by a solid 6dp capsule rail, a bright filled active portion, and a 20dp round thumb that
swells and picks up a halo while dragging — so the touch target confirms itself under a finger that
is covering it.

---

## What guards this

`ComponentSmokeTest` composes and measures every custom component on the JVM under Robolectric,
including at the values that break layout arithmetic: fraction 0 and 1, inverted and zero-width
intervals, a collapsed slider range, a flat curve where every secant is zero, a two-point curve, and
a chart 48dp wide.

This exists because of a real defect. A modifier given an illegal constant compiles cleanly and
throws only when composed, so the first sign of it was the app opening and closing on the device.
`ComposeLayoutSafetyTest` additionally scans the sources for negative values passed to `padding`,
`size`, `width`, `height`, `spacedBy` and `weight` — the modifiers that validate with `require(...)`
rather than at the type level.

---

## Not done

**The home-screen widget.** For a battery-forecast app this is arguably the primary surface, and
Glance would make it roughly 200 lines. It is the clearest next piece of work.

**A shipped typeface.** The system font is used throughout. A variable face would allow finer
optical sizing between metrics and labels, at the cost of shipping a font file; the type scale does
that work through size and weight instead.
