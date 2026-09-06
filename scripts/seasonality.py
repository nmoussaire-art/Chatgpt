#!/usr/bin/env python3
"""Weekly seasonality toolkit: session calendar, weekly returns, setup statistics.

All returns are CLOSE-TO-CLOSE. A "week trade" is:
    entry  = close of the last trading session of week W
    exit   = close of the last trading session of week W+1
    return = exit / entry - 1
Weeks are ISO weeks; holidays are handled implicitly because the calendar is
built from actual sessions present in the price files.
"""
import bisect, csv, datetime as dt, math, os, random

DATA = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data")


# ---------------------------------------------------------------- data loading
def load(fname, col=1):
    rows = list(csv.reader(open(os.path.join(DATA, fname))))[1:]
    return {dt.date.fromisoformat(r[0]): float(r[col]) for r in rows}


class Series:
    """A daily close series with helpers for session and ISO-week navigation."""

    def __init__(self, name, prices, kind):
        self.name = name
        self.kind = kind                      # 'index price' or 'ETF total return'
        self.px = prices
        self.days = sorted(prices)
        self.start, self.end = self.days[0], self.days[-1]
        # last session of each ISO week
        self.week_last = {}
        for d in self.days:
            self.week_last[d.isocalendar()[:2]] = d

    # -- session helpers
    def prev_session(self, d):
        """Last session strictly before d."""
        i = bisect.bisect_left(self.days, d)
        return self.days[i - 1] if i > 0 else None

    def last_session_in(self, lo, hi):
        """Last session in [lo, hi], or None."""
        i = bisect.bisect_right(self.days, hi) - 1
        return self.days[i] if i >= 0 and self.days[i] >= lo else None

    def ret(self, a, b):
        return self.px[b] / self.px[a] - 1.0

    # -- weekly series
    def weekly_returns(self):
        """[(entry_date, exit_date, ret)] for every consecutive ISO-week pair."""
        keys = sorted(self.week_last)
        out = []
        for k1, k2 in zip(keys, keys[1:]):
            d1, d2 = self.week_last[k1], self.week_last[k2]
            if 3 <= (d2 - d1).days <= 11:     # guard against missing weeks
                out.append((d1, d2, self.ret(d1, d2)))
        return out


def load_all():
    return {
        "SPX": Series("SPX", load("spx_index.csv"), "index price"),
        "NDX": Series("NDX", load("ndx_index.csv"), "index price"),
        "SPY_px": Series("SPY (price)", load("spy_price.csv"), "ETF price only"),
        "QQQ_px": Series("QQQ (price)", load("qqq_price.csv"), "ETF price only"),
        "SPY_adj": Series("SPY (adj)", load("spy_adj.csv", 2), "ETF total return"),
        "QQQ_adj": Series("QQQ (adj)", load("qqq_adj.csv", 2), "ETF total return"),
        "VIX": Series("VIX", load("vix_index.csv", 4), "index level"),
    }


# ---------------------------------------------------------------- US holidays
def nth_weekday(year, month, weekday, n):
    d = dt.date(year, month, 1)
    d += dt.timedelta((weekday - d.weekday()) % 7)
    return d + dt.timedelta(7 * (n - 1))


def last_weekday(year, month, weekday):
    d = dt.date(year, month, 1) + dt.timedelta(31)
    d = d.replace(day=1) - dt.timedelta(1)
    return d - dt.timedelta((d.weekday() - weekday) % 7)


def labor_day(year):
    return nth_weekday(year, 9, 0, 1)          # first Monday of September


def triple_witching(year, month):
    return nth_weekday(year, month, 4, 3)      # third Friday


# ---------------------------------------------------------------- statistics
def bootstrap_ci(x, stat=lambda s: sum(s) / len(s), reps=20000, alpha=0.05, seed=7):
    rnd = random.Random(seed)
    n = len(x)
    vals = sorted(stat([x[rnd.randrange(n)] for _ in range(n)]) for _ in range(reps))
    lo = vals[int(alpha / 2 * reps)]
    hi = vals[int((1 - alpha / 2) * reps) - 1]
    return lo, hi


def streaks(signs):
    best_w = best_l = cur_w = cur_l = 0
    for s in signs:
        if s > 0:
            cur_w += 1; cur_l = 0
        elif s < 0:
            cur_l += 1; cur_w = 0
        else:
            cur_w = cur_l = 0
        best_w = max(best_w, cur_w); best_l = max(best_l, cur_l)
    return best_w, best_l


def tstat(x):
    n = len(x)
    if n < 2:
        return float("nan"), float("nan")
    m = sum(x) / n
    sd = math.sqrt(sum((v - m) ** 2 for v in x) / (n - 1))
    if sd == 0:
        return float("nan"), float("nan")
    t = m / (sd / math.sqrt(n))
    # two-sided p via normal approximation to the t distribution (n is small; report as approximate)
    p = 2 * (1 - 0.5 * (1 + math.erf(abs(t) / math.sqrt(2))))
    return t, p


def permutation_p(sample, universe, reps=20000, seed=11):
    """P(|mean of a random same-size draw from universe| >= |observed mean|)."""
    rnd = random.Random(seed)
    n, obs = len(sample), sum(sample) / len(sample)
    base = sum(universe) / len(universe)
    hits = 0
    for _ in range(reps):
        m = sum(universe[rnd.randrange(len(universe))] for _ in range(n)) / n
        if abs(m - base) >= abs(obs - base):
            hits += 1
    return (hits + 1) / (reps + 1)


def describe(x, universe=None, cost_bps=0.0):
    """Full stat block for a list of returns."""
    n = len(x)
    if n == 0:
        return None
    s = sorted(x)
    m = sum(x) / n
    med = s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2
    sd = math.sqrt(sum((v - m) ** 2 for v in x) / (n - 1)) if n > 1 else float("nan")
    pos = sum(1 for v in x if v > 0)
    neg = sum(1 for v in x if v < 0)
    up = [v for v in x if v > 0]
    dn = [v for v in x if v < 0]
    t, p = tstat(x)
    lo, hi = bootstrap_ci(x) if n >= 4 else (float("nan"), float("nan"))
    w, l = streaks([1 if v > 0 else (-1 if v < 0 else 0) for v in x])
    c = cost_bps / 10000.0
    d = {
        "n": n, "mean": m, "median": med, "sd": sd,
        "pos": pos, "neg": neg, "win": pos / n, "lose": neg / n,
        "min": s[0], "max": s[-1],
        "ci_lo": lo, "ci_hi": hi,
        "p_gt1": sum(1 for v in x if v > 0.01) / n,
        "p_lt1": sum(1 for v in x if v < -0.01) / n,
        "avg_up": sum(up) / len(up) if up else float("nan"),
        "avg_dn": sum(dn) / len(dn) if dn else float("nan"),
        "t": t, "p": p,
        "sharpe": m / sd if sd and not math.isnan(sd) else float("nan"),
        "max_win_streak": w, "max_loss_streak": l,
        "mean_long_net": m - c, "mean_short_net": -m - c,
        "p10": s[max(0, int(0.10 * n) - 1)], "p90": s[min(n - 1, int(0.90 * n))],
    }
    if universe:
        d["perm_p"] = permutation_p(x, universe)
    return d


def fmt(d, pct=True):
    k = 100 if pct else 1
    return (f"n={d['n']:3d}  win={d['win']*100:5.1f}%  mean={d['mean']*k:+6.2f}%  "
            f"med={d['median']*k:+6.2f}%  sd={d['sd']*k:5.2f}%  "
            f"[{d['ci_lo']*k:+6.2f}%,{d['ci_hi']*k:+6.2f}%]  "
            f"min={d['min']*k:+7.2f}%  max={d['max']*k:+6.2f}%")
