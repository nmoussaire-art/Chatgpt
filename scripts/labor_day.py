import sys, datetime as dt, math; sys.path.insert(0,'scripts')
import seasonality as sz
S = sz.load_all()

def labor_day_trade(s, year):
    """entry = last session before Labor Day; exit = last session of Labor Day week (Fri)."""
    ld = sz.labor_day(year)
    entry = s.prev_session(ld)
    exit_ = s.last_session_in(ld, ld + dt.timedelta(4))
    if entry is None or exit_ is None or entry >= exit_: return None
    if (ld - entry).days > 5: return None            # entry must be the Friday before
    return entry, exit_, s.ret(entry, exit_)

def series_trades(s, y0=None, y1=2025):
    out=[]
    for y in range(s.start.year, y1+1):
        if y0 and y < y0: continue
        t = labor_day_trade(s, y)
        if t: out.append((y,)+t)
    return out

print("=== SETUP: LABOR DAY WEEK ===")
print("entry = close of last session before Labor Day (Friday)")
print("exit  = close of last session of the Labor Day week (Friday, 4 sessions later)\n")

for key in ["SPX","NDX","SPY_px","QQQ_px","SPY_adj","QQQ_adj"]:
    s = S[key]; tr = series_trades(s)
    if not tr: continue
    x = [r for *_ , r in tr]
    uni = [r for _,_,r in s.weekly_returns()]
    d = sz.describe(x, universe=uni)
    b = sz.describe(uni)
    print(f"--- {s.name:12s} [{s.kind}]  {tr[0][0]}-{tr[-1][0]}")
    print(f"    setup    {sz.fmt(d)}")
    print(f"    all wks  {sz.fmt(b)}")
    print(f"    P(>+1%)={d['p_gt1']:.0%}  P(<-1%)={d['p_lt1']:.0%}  avg up={d['avg_up']:+.2%}  avg dn={d['avg_dn']:+.2%}")
    print(f"    t={d['t']:+.2f} p={d['p']:.3f}  permutation p={d['perm_p']:.3f}  mean/sd={d['sharpe']:+.3f}")
    print(f"    longest win streak {d['max_win_streak']}, longest loss streak {d['max_loss_streak']}")
    print(f"    10th pct {d['p10']:+.2%}  90th pct {d['p90']:+.2%}")
    print()

print("=== SPX YEAR-BY-YEAR (Labor Day week) ===")
tr = series_trades(S["SPX"])
for i,(y,a,b,r) in enumerate(tr):
    mark = "  <-- neg" if r<0 else ""
    print(f"  {y}  {a} -> {b}  {r:+7.2%}{mark}")
x=[r for *_,r in tr]
print(f"\n  total {len(x)} obs, {sum(1 for v in x if v>0)} up / {sum(1 for v in x if v<0)} down")
