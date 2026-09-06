import sys, datetime as dt; sys.path.insert(0,'scripts')
import seasonality as sz
S = sz.load_all()
ENTRY = dt.date(2026, 9, 4)

def ma(s, d, n):
    i = s.days.index(d)
    w = s.days[i-n+1:i+1]
    return sum(s.px[x] for x in w)/len(w)

print("=== MARKET CONTEXT AT ENTRY (2026-09-04 close) ===")
for k in ["SPX","NDX","SPY_px","QQQ_px"]:
    s = S[k]; p = s.px[ENTRY]
    m50, m200 = ma(s,ENTRY,50), ma(s,ENTRY,200)
    # prior week / prior month returns
    keys = sorted(s.week_last); i = keys.index(ENTRY.isocalendar()[:2])
    pw = s.ret(s.week_last[keys[i-1]], ENTRY)
    p2w = s.ret(s.week_last[keys[i-2]], s.week_last[keys[i-1]])
    aug_end = s.last_session_in(dt.date(2026,8,25), dt.date(2026,8,31))
    jul_end = s.last_session_in(dt.date(2026,7,25), dt.date(2026,7,31))
    jun_end = s.last_session_in(dt.date(2026,6,25), dt.date(2026,6,30))
    dec_end = s.last_session_in(dt.date(2025,12,26), dt.date(2025,12,31))
    hi52 = max(s.px[d] for d in s.days if ENTRY - dt.timedelta(365) <= d <= ENTRY)
    # weekly streak
    wr = s.weekly_returns(); signs = [1 if r>0 else -1 for _,_,r in wr]
    st = 1
    while st < len(signs) and signs[-1-st] == signs[-1]: st += 1
    print(f"\n{s.name}: close {p:,.2f}")
    print(f"   vs 50dma {m50:,.2f} ({p/m50-1:+.2%})   vs 200dma {m200:,.2f} ({p/m200-1:+.2%})")
    print(f"   last week {pw:+.2%}   week before {p2w:+.2%}   current weekly streak: {st} {'up' if signs[-1]>0 else 'down'} week(s)")
    print(f"   August 2026 {s.ret(jul_end,aug_end):+.2%}   July {s.ret(jun_end,jul_end):+.2%}   YTD {s.ret(dec_end,ENTRY):+.2%}")
    print(f"   drawdown from 52w high {p/hi52-1:+.2%}")
v = S["VIX"]
print(f"\nVIX close {v.px[ENTRY]:.2f}")
vd = [d for d in v.days if d <= ENTRY][-252:]
lv = sorted(v.px[d] for d in vd)
pct = sum(1 for x in lv if x < v.px[ENTRY]) / len(lv)
print(f"   percentile vs trailing 252 sessions: {pct:.0%}   1y median {lv[len(lv)//2]:.2f}")
print(f"   VIX 20d avg {sum(v.px[d] for d in vd[-20:])/20:.2f}")
# realised vol
s = S["SPX"]; wr = s.weekly_returns()
import math
last26 = [r for _,_,r in wr][-26:]
mu = sum(last26)/26
print(f"   SPX realised weekly vol (last 26w, annualised): {math.sqrt(sum((x-mu)**2 for x in last26)/25)*math.sqrt(52):.1%}")
